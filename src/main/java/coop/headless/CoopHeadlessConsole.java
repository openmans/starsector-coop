package coop.headless;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopNetPump;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import coop.util.CoopLog;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Console control for the windowless dedicated server ({@code -Dcoop.headless=true}).
 *
 * <p>Dormant unless the system property is set, so the graphical client and an ordinary
 * {@code -Dcoop.dedicatedHost} launch behave exactly as before.
 *
 * <p><b>Commands run on the frame thread, not on the stdin thread.</b> {@code autosave()} and the
 * session dialogs are engine state owned by the thread that advances the campaign; the reader thread
 * only queues lines. {@link #tick(CoopNetPump)} drains a bounded number per frame from wherever the
 * mod already has a per-frame hook: {@code CoopNetPump.advance} in campaign, and the global combat
 * status plugin while the title screen or a battle is up.
 *
 * <p><b>stop = save, then exit.</b> SIGINT/SIGTERM are turned into the same request instead of the
 * JVM's immediate shutdown: a dedicated host that loses its port and its world because someone
 * pressed Ctrl+C is worse than one that takes two seconds longer to die. A second signal, or a
 * shutdown that cannot make progress, falls back to {@code halt}.
 */
public final class CoopHeadlessConsole {

    private static final boolean ENABLED = Boolean.getBoolean("coop.headless");

    /** Lines from System.in, drained by the frame thread. Bounded by the reader. */
    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();

    /** How many commands one frame may run. Matches CoopAgentBridge's budget for the same reason. */
    private static final int MAX_COMMANDS_PER_FRAME = 4;

    /** Periodic status print, so a passive operator still sees the world breathing. */
    private static final long STATUS_INTERVAL_MILLIS = 30_000L;

    /** How long `stop` keeps retrying a deferred save (an open dialog blocks autosave). */
    private static final long STOP_SAVE_BUDGET_MILLIS = 30_000L;

    /** How long a signal-driven stop waits before forcing the JVM down, in case frames stop. */
    private static final long SIGNAL_WATCHDOG_MILLIS = 20_000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean STOP_HANDLED = new AtomicBoolean();

    private static volatile boolean stopRequested;
    private static volatile boolean stopFromSignal;
    private static volatile boolean stdinClosed;
    private static long stopRequestedAtMillis;

    private static long lastStatusAtMillis;
    private static long lastTickNanos;
    private static double tps = 0.0;

    private static long pendingSaveAtMillis;

    private CoopHeadlessConsole() {
    }

    /** True when the process was started by the Null-LWJGL headless launcher. */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Per-frame entry point. Cheap when disabled (one boolean read), and never throws: a console
     * that takes the campaign frame down would be a worse failure than the one it reports.
     */
    public static void tick(CoopNetPump pump) {
        if (!ENABLED) {
            return;
        }
        try {
            start();
            sampleTps();
            if (stopRequested) {
                tickStop(pump);
                return;
            }
            tickPendingSave(pump);
            for (int i = 0; i < MAX_COMMANDS_PER_FRAME; i++) {
                String line = PENDING.poll();
                if (line == null) {
                    break;
                }
                try {
                    handle(pump, line.trim());
                } catch (RuntimeException | LinkageError ex) {
                    System.out.println("[coop] command failed: " + rootMessage(ex));
                    CoopLog.warn(CoopHeadlessConsole.class, "headless console command failed", ex);
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastStatusAtMillis >= STATUS_INTERVAL_MILLIS) {
                lastStatusAtMillis = now;
                System.out.println(statusLine(pump));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopHeadlessConsole.class, "headless console tick failed", ex);
        }
    }

    /** Idempotent: installs the signal handlers and the stdin reader exactly once. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        installSignalHandlers();
        Thread reader = new Thread(CoopHeadlessConsole::readStdin, "coop-headless-stdin");
        reader.setDaemon(true);
        reader.start();
        System.out.println("[coop] headless console ready (status | save | kick <name> | stop)");
    }

    /** Requests the save-then-exit path; safe from any thread and from a signal handler. */
    public static void requestStop(String why) {
        if (!ENABLED) {
            System.exit(0);
        }
        if (stopRequested) {
            // Second Ctrl+C (or a stuck frame loop): do not sit in a watchdog that cannot run.
            System.out.println("[coop] second stop request (" + why + "), halting now");
            System.out.flush();
            Runtime.getRuntime().halt(130);
        }
        System.out.println("[coop] stop requested (" + why + ")");
        stopFromSignal = "signal".equals(why);
        stopRequestedAtMillis = System.currentTimeMillis();
        stopRequested = true;
        if (stopFromSignal) {
            startSignalWatchdog();
        }
    }

    private static void installSignalHandlers() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> requestStop("signal"));
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> requestStop("signal"));
        } catch (Throwable ex) {
            // Not fatal: `stop` on the console still works. Log once at the level the rest of the
            // startup uses.
            CoopLog.warn(CoopHeadlessConsole.class, "headless console could not install signal handlers", ex);
        }
    }

    private static void startSignalWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SIGNAL_WATCHDOG_MILLIS);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!STOP_HANDLED.get()) {
                System.out.println("[coop] stop did not finish within " + (SIGNAL_WATCHDOG_MILLIS / 1000)
                        + "s, halting");
                System.out.flush();
                Runtime.getRuntime().halt(1);
            }
        }, "coop-headless-stop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Raw byte reads rather than BufferedReader: this class ships inside coop.jar, and the engine's
     * script classloader only loads a narrow stream allow-list for mod classes. System.in is an
     * InputStream (allowed) and its own read(byte[]) needs no reader classes at all, so the console
     * keeps working under the same sandbox as the rest of the mod.
     */
    private static void readStdin() {
        try {
            byte[] buffer = new byte[4096];
            StringBuilder line = new StringBuilder();
            int read;
            while ((read = System.in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    char c = (char) (buffer[i] & 0xff);
                    if (c == '\n') {
                        offerLine(line);
                    } else if (c != '\r') {
                        line.append(c);
                    }
                }
            }
            offerLine(line);
            stdinClosed = true;
            System.out.println("[coop] stdin closed; console commands disabled (server keeps running)");
        } catch (Throwable ex) {
            stdinClosed = true;
            CoopLog.warn(CoopHeadlessConsole.class, "headless console stdin reader failed", ex);
        }
    }

    private static void offerLine(StringBuilder line) {
        String text = line.toString().trim();
        line.setLength(0);
        if (!text.isEmpty()) {
            PENDING.add(text);
        }
    }

    private static void sampleTps() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            double dt = (now - lastTickNanos) / 1_000_000_000.0;
            if (dt > 0.0) {
                double instant = 1.0 / dt;
                tps = tps <= 0.0 ? instant : (tps * 0.9 + instant * 0.1);
            }
        }
        lastTickNanos = now;
    }

    private static void tickStop(CoopNetPump pump) {
        if (STOP_HANDLED.get()) {
            return;
        }
        String result = pump == null ? "not in campaign" : pump.headlessSaveNow();
        boolean deferred = result.startsWith("deferred");
        if (deferred && System.currentTimeMillis() - stopRequestedAtMillis < STOP_SAVE_BUDGET_MILLIS) {
            if (pendingSaveAtMillis == 0L) {
                pendingSaveAtMillis = System.currentTimeMillis() + 500L;
                System.out.println("[coop] " + result + "; retrying until the screen closes");
            }
            return;
        }
        if (!STOP_HANDLED.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[coop] stopping; save=" + result);
        System.out.flush();
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[coop] exit");
            System.out.flush();
            System.exit(0);
        }, "coop-headless-exit");
        exit.setDaemon(false);
        exit.start();
    }

    private static void tickPendingSave(CoopNetPump pump) {
        if (pendingSaveAtMillis == 0L || System.currentTimeMillis() < pendingSaveAtMillis) {
            return;
        }
        String result = pump == null ? "not in campaign" : pump.headlessSaveNow();
        if (result.startsWith("deferred")) {
            pendingSaveAtMillis = System.currentTimeMillis() + 1000L;
            return;
        }
        pendingSaveAtMillis = 0L;
        System.out.println("[coop] save: " + result);
    }

    private static void handle(CoopNetPump pump, String line) {
        String[] parts = line.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (verb) {
            case "help", "?":
                System.out.println("[coop] commands: status | save | kick <name> | stop");
                break;
            case "status":
                System.out.println(statusLine(pump));
                break;
            case "save": {
                String result = pump == null ? "not in campaign" : pump.headlessSaveNow();
                if (result.startsWith("deferred")) {
                    pendingSaveAtMillis = System.currentTimeMillis() + 500L;
                    System.out.println("[coop] " + result + "; will retry");
                } else {
                    System.out.println("[coop] save: " + result);
                }
                break;
            }
            case "kick": {
                if (arg.isEmpty()) {
                    System.out.println("[coop] usage: kick <guest name or id>");
                    break;
                }
                System.out.println("[coop] kick: " + (pump == null ? "not in campaign" : pump.kickGuestByName(arg)));
                break;
            }
            case "stop":
                requestStop("console");
                break;
            default:
                System.out.println("[coop] unknown command '" + verb + "'; try help");
        }
    }

    /** One-line snapshot: role, players, game day and the pause holder. */
    static String statusLine(CoopNetPump pump) {
        StringBuilder sb = new StringBuilder("[coop] status:");
        if (pump == null) {
            sb.append(" state=title/no-pump");
        } else {
            sb.append(" role=").append(pump.currentRole());
            CoopSessionState state = pump.sessionStateForBridge();
            if (state != null) {
                List<CoopSessionState.PeerSession> guests = state.guests();
                sb.append(" players=").append(guests.size());
                if (!guests.isEmpty()) {
                    sb.append(" (");
                    for (int i = 0; i < guests.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(guests.get(i).name());
                    }
                    sb.append(')');
                }
            }
            CoopSharedPauseCoordinator pause = pump.pauseCoordinatorForBridge();
            if (pause != null) {
                String holder;
                if (pause.reconnectHold()) {
                    holder = "RECONNECT";
                } else if (pause.dedicatedEmptyHold()) {
                    holder = "EMPTY_HOLD";
                } else if (pause.effectivePaused()) {
                    holder = "PAUSED";
                } else {
                    holder = "LIVE_1X";
                }
                sb.append(" pause=").append(holder);
            }
        }
        String day = gameDay();
        if (day != null) {
            sb.append(" day=").append(day);
        }
        if (tps > 0.0) {
            sb.append(String.format(" tps=%.1f", tps));
        }
        return sb.toString();
    }

    private static String gameDay() {
        try {
            if (Global.getCurrentState() != GameState.CAMPAIGN) {
                return null;
            }
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return null;
            }
            CampaignClockAPI clock = sector.getClock();
            if (clock == null) {
                return null;
            }
            return clock.getCycle() + "." + clock.getDay();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }
}
