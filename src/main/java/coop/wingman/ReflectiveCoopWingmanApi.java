package coop.wingman;

import com.fs.starfarer.api.Global;

import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Production {@link CoopWingmanApi}: reaches {@code wingman.api.WingmanAPI} through
 * {@link MethodHandles} so the coop mod has no compile-time dependency on The Wingman Project and
 * stays strictly inside Starsector's script sandbox (which forbids {@code java.lang.reflect.Method}
 * at runtime).
 *
 * <p>Every call fails closed: a missing Wingman, a renamed API, or a sandbox refusal all behave
 * exactly as if Wingman were not installed, and each failure is logged once per cause rather
 * than once per frame.
 *
 * <p>Thread-safety: the game thread only, like the Wingman API itself (its javadoc says
 * "meant for the game thread").
 */
public final class ReflectiveCoopWingmanApi implements CoopWingmanApi {

    private static final String API_CLASS = "wingman.api.WingmanAPI";
    private static final String WINGMAN_MOD_ID = "wingman_project";

    private final BooleanSupplier modEnabledCheck;
    private final String apiClassName;
    private final Class<?> preloadedApiClass;

    private boolean resolveAttempted;
    private MethodHandle isJoinedHandle;
    private MethodHandle localNameHandle;
    private MethodHandle openHostHandle;
    private boolean openHostReturnsBoolean;
    private MethodHandle pumpHandle;
    private MethodHandle playersHandle;
    private MethodHandle playerNameHandle;
    private MethodHandle reserveShipHandle;
    private MethodHandle joinHandle;
    private boolean joinReturnsBoolean;
    private MethodHandle disconnectHandle;
    private boolean disconnectReturnsBoolean;
    private MethodHandle closeHostHandle;
    private boolean closeHostReturnsBoolean;
    private boolean apiAvailable;

    private final Set<String> loggedFailures = new HashSet<>();

    public ReflectiveCoopWingmanApi() {
        this(API_CLASS, null, ReflectiveCoopWingmanApi::wingmanModEnabled);
    }

    /** Test seam: bind against a synthetic static API class without loading the game engine. */
    ReflectiveCoopWingmanApi(Class<?> apiClass, BooleanSupplier modEnabledCheck) {
        this(apiClass == null ? API_CLASS : apiClass.getName(), apiClass, modEnabledCheck);
    }

    private ReflectiveCoopWingmanApi(String apiClassName, Class<?> preloadedApiClass,
                                     BooleanSupplier modEnabledCheck) {
        this.apiClassName = apiClassName;
        this.preloadedApiClass = preloadedApiClass;
        this.modEnabledCheck = modEnabledCheck;
    }

    /** Wingman's own mod id; the guard every caller checks first. */
    public static boolean wingmanModEnabled() {
        try {
            return Global.getSettings().getModManager().isModEnabled(WINGMAN_MOD_ID);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private boolean ensureResolved() {
        if (resolveAttempted) {
            return apiAvailable;
        }
        resolveAttempted = true;
        try {
            Class<?> apiClass = preloadedApiClass != null
                    ? preloadedApiClass
                    : Class.forName(apiClassName);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isJoinedHandle = findOptional(lookup, apiClass, "isJoined",
                    MethodType.methodType(boolean.class));
            localNameHandle = findOptional(lookup, apiClass, "localName",
                    MethodType.methodType(String.class));
            openHostHandle = findOptional(lookup, apiClass, "openHost",
                    MethodType.methodType(void.class));
            if (openHostHandle == null) {
                openHostHandle = findOptional(lookup, apiClass, "openHost",
                        MethodType.methodType(boolean.class));
                openHostReturnsBoolean = openHostHandle != null;
            }
            pumpHandle = findOptional(lookup, apiClass, "pump",
                    MethodType.methodType(List.class));
            playersHandle = findOptional(lookup, apiClass, "players",
                    MethodType.methodType(int[].class));
            playerNameHandle = findOptional(lookup, apiClass, "playerName",
                    MethodType.methodType(String.class, int.class));
            reserveShipHandle = findOptional(lookup, apiClass, "reserveShip",
                    MethodType.methodType(void.class, String.class, String.class));
            joinHandle = findOptional(lookup, apiClass, "join",
                    MethodType.methodType(boolean.class, String.class, int.class));
            if (joinHandle != null) {
                joinReturnsBoolean = true;
            } else {
                joinHandle = findOptional(lookup, apiClass, "join",
                        MethodType.methodType(void.class, String.class, int.class));
            }
            disconnectHandle = findOptional(lookup, apiClass, "disconnect",
                    MethodType.methodType(boolean.class));
            if (disconnectHandle != null) {
                disconnectReturnsBoolean = true;
            } else {
                disconnectHandle = findOptional(lookup, apiClass, "disconnect",
                        MethodType.methodType(void.class));
            }
            closeHostHandle = findOptional(lookup, apiClass, "closeHost",
                    MethodType.methodType(boolean.class));
            if (closeHostHandle != null) {
                closeHostReturnsBoolean = true;
            } else {
                closeHostHandle = findOptional(lookup, apiClass, "closeHost",
                        MethodType.methodType(void.class));
            }
            apiAvailable = true;
        } catch (Throwable t) {
            apiAvailable = false;
            logOnce("resolve",
                    "Coop wingman: Wingman API class not found; wingman battles are off", t);
        }
        return apiAvailable;
    }

    private MethodHandle findOptional(MethodHandles.Lookup lookup, Class<?> apiClass,
                                      String name, MethodType type) {
        try {
            return lookup.findStatic(apiClass, name, type);
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            return null;
        }
    }

    private void logOnce(String key, String message, Throwable cause) {
        if (loggedFailures.add(key)) {
            if (cause == null) {
                CoopLog.warn(ReflectiveCoopWingmanApi.class, message);
            } else {
                CoopLog.warn(ReflectiveCoopWingmanApi.class, message, cause);
            }
        }
    }

    @Override
    public boolean wingmanEnabled() {
        // Check mod presence first so installs without Wingman never attempt Class.forName or log.
        if (!modEnabledCheck.getAsBoolean()) {
            return false;
        }
        return ensureResolved();
    }

    @Override
    public boolean isJoined() {
        if (!wingmanEnabled() || isJoinedHandle == null) {
            return false;
        }
        try {
            return (boolean) isJoinedHandle.invoke();
        } catch (Throwable t) {
            logOnce("isJoined", "Coop wingman: WingmanAPI.isJoined failed", t);
            return false;
        }
    }

    @Override
    public String localName() {
        if (!wingmanEnabled() || localNameHandle == null) {
            return "";
        }
        try {
            String value = (String) localNameHandle.invoke();
            return value == null ? "" : value;
        } catch (Throwable t) {
            logOnce("localName", "Coop wingman: WingmanAPI.localName failed", t);
            return "";
        }
    }

    @Override
    public boolean openHost() {
        if (!wingmanEnabled()) {
            return false;
        }
        if (openHostHandle == null) {
            logOnce("openHost.missing",
                    "Coop wingman: WingmanAPI.openHost method not found", null);
            return false;
        }
        try {
            if (openHostReturnsBoolean) {
                return (boolean) openHostHandle.invoke();
            }
            openHostHandle.invoke();
            return true;
        } catch (Throwable t) {
            logOnce("openHost", "Coop wingman: WingmanAPI.openHost failed", t);
            return false;
        }
    }

    @Override
    public String pumpHost() {
        if (!wingmanEnabled() || pumpHandle == null) {
            return "";
        }
        try {
            List<?> lines = (List<?>) pumpHandle.invoke();
            if (lines == null || lines.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Object line : lines) {
                if (line instanceof String s && !s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(s);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            logOnce("pump", "Coop wingman: WingmanAPI.pump failed", t);
            return "";
        }
    }

    @Override
    public List<String> playerNames() {
        List<String> out = new ArrayList<>();
        if (!wingmanEnabled() || playersHandle == null || playerNameHandle == null) {
            return out;
        }
        try {
            int[] ids = (int[]) playersHandle.invoke();
            if (ids == null) {
                return out;
            }
            for (int id : ids) {
                String name = (String) playerNameHandle.invoke(id);
                if (name != null && !name.isEmpty()) {
                    out.add(name);
                }
            }
        } catch (Throwable t) {
            logOnce("players", "Coop wingman: WingmanAPI.players/playerName failed", t);
        }
        return out;
    }

    @Override
    public void reserveShip(String name, String fleetMemberId) {
        if (!wingmanEnabled()) {
            return;
        }
        if (reserveShipHandle == null) {
            logOnce("reserveShip.missing",
                    "Coop wingman: WingmanAPI.reserveShip method not found", null);
            return;
        }
        try {
            reserveShipHandle.invoke(name, fleetMemberId);
        } catch (Throwable t) {
            logOnce("reserveShip", "Coop wingman: WingmanAPI.reserveShip failed", t);
        }
    }

    @Override
    public boolean supportsAutoJoin() {
        return wingmanEnabled() && joinHandle != null;
    }

    @Override
    public boolean join(String host, int port) {
        if (!wingmanEnabled() || joinHandle == null || host == null || host.isBlank() || port <= 0) {
            return false;
        }
        try {
            if (joinReturnsBoolean) {
                return (boolean) joinHandle.invoke(host.trim(), port);
            }
            joinHandle.invoke(host.trim(), port);
            return true;
        } catch (Throwable t) {
            logOnce("join", "Coop wingman: WingmanAPI.join failed", t);
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        if (!wingmanEnabled() || disconnectHandle == null) {
            return false;
        }
        try {
            if (disconnectReturnsBoolean) {
                return (boolean) disconnectHandle.invoke();
            }
            disconnectHandle.invoke();
            return true;
        } catch (Throwable t) {
            logOnce("disconnect", "Coop wingman: WingmanAPI.disconnect failed", t);
            return false;
        }
    }

    @Override
    public boolean closeHost() {
        if (!wingmanEnabled() || closeHostHandle == null) {
            return false;
        }
        try {
            if (closeHostReturnsBoolean) {
                return (boolean) closeHostHandle.invoke();
            }
            closeHostHandle.invoke();
            return true;
        } catch (Throwable t) {
            logOnce("closeHost", "Coop wingman: WingmanAPI.closeHost failed", t);
            return false;
        }
    }
}
