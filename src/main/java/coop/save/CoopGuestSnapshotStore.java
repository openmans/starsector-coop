package coop.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the most recent {@link CoopGuestSnapshot} per guest the host received, and writes them into
 * the host save (Phase 16 / Phase 27).
 *
 * <p>Static, because the write happens from {@code CoopModPlugin.beforeGameSave()} — a
 * {@code ModPlugin} callback with no handle on the campaign pump — the same arrangement
 * {@code CoopFullFidelitySystemDriver.beginSave()} uses for the same reason.
 *
 * <p><b>{@link #PERSISTENT_KEY} is write-only in v1, by decision (2026-06-10).</b> Phase 27 adds
 * {@link #PERSISTENT_MAP_KEY} keyed by guest {@code playerId} while keeping {@link #PERSISTENT_KEY}
 * populated with the latest snapshot for full save compatibility.
 */
public final class CoopGuestSnapshotStore {

    /** The sector persistent-data key holding the primary guest's snapshot inside the host save. */
    public static final String PERSISTENT_KEY = "coop.guestFleetSnapshot";
    /** Phase 27: per-guest snapshots map keyed by guest {@code playerId}. */
    public static final String PERSISTENT_MAP_KEY = "coop.guestFleetSnapshots";

    private static volatile CoopGuestSnapshot latest;
    private static final Map<String, CoopGuestSnapshot> byPlayer =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private CoopGuestSnapshotStore() {
    }

    /** Host: record the guest state that arrived on the wire. Latest wins; there is no history. */
    public static void publish(CoopGuestSnapshot snapshot) {
        latest = snapshot;
        if (snapshot != null && snapshot.getPlayerId() != null
                && !snapshot.getPlayerId().trim().isEmpty()) {
            byPlayer.put(snapshot.getPlayerId().trim(), snapshot);
        }
    }

    /** Phase 27: records one guest's snapshot under an explicit {@code playerId}. */
    public static void publish(String playerId, CoopGuestSnapshot snapshot) {
        latest = snapshot;
        if (playerId != null && !playerId.trim().isEmpty()) {
            if (snapshot == null) {
                byPlayer.remove(playerId.trim());
            } else {
                byPlayer.put(playerId.trim(), snapshot);
            }
        }
    }

    /** The snapshot that would be embedded by the next save, or null when none has arrived. */
    public static CoopGuestSnapshot latest() {
        return latest;
    }

    /** Phase 27: the latest snapshot held for one guest player id, or null. */
    public static CoopGuestSnapshot latest(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            return latest;
        }
        return byPlayer.get(playerId.trim());
    }

    /** Phase 27: resolves the best recovery snapshot for {@code playerId}, falling back to latest. */
    public static CoopGuestSnapshot findForRestore(String playerId) {
        CoopGuestSnapshot exact = latest(playerId);
        return exact != null ? exact : latest;
    }

    /** Phase 27: immutable view of all held guest snapshots keyed by player id. */
    public static Map<String, CoopGuestSnapshot> all() {
        synchronized (byPlayer) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(byPlayer));
        }
    }

    /**
     * Drops the held snapshots (game load, session end). Deliberately does <em>not</em> remove the
     * keys from any save: a stale guest snapshot in an old save is exactly the recovery material
     * this exists for, and clearing it would throw away the only copy.
     */
    public static void clear() {
        latest = null;
        byPlayer.clear();
    }

    /**
     * Writes the held snapshot(s) into {@code persistentData} under {@link #PERSISTENT_KEY} and
     * {@link #PERSISTENT_MAP_KEY}. A missing snapshot leaves whatever is already stored alone — an
     * older snapshot beats none at all.
     *
     * @return true when a snapshot was written.
     */
    public static boolean writeInto(Map<String, Object> persistentData) {
        CoopGuestSnapshot snapshot = latest;
        Map<String, CoopGuestSnapshot> allSnapshots = all();
        if (persistentData == null || (snapshot == null && allSnapshots.isEmpty())) {
            return false;
        }
        if (snapshot != null) {
            persistentData.put(PERSISTENT_KEY, snapshot);
        }
        if (!allSnapshots.isEmpty()) {
            persistentData.put(PERSISTENT_MAP_KEY, new LinkedHashMap<>(allSnapshots));
        }
        return true;
    }

    /**
     * Engine-facing form for {@code CoopModPlugin.beforeGameSave()}. Never throws: a snapshot that
     * cannot be embedded must not be able to fail the player's save.
     */
    public static void writeIntoCurrentSector() {
        try {
            CoopGuestSnapshot snapshot = latest;
            if (snapshot == null) {
                return;
            }
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            if (writeInto(sector.getPersistentData())) {
                CoopLog.info(CoopGuestSnapshotStore.class, "Coop embedded the guest snapshot in this"
                        + " save under " + PERSISTENT_KEY + ": " + snapshot.summary());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestSnapshotStore.class,
                    "Failed to embed the coop guest snapshot in the save", ex);
        }
    }
}
