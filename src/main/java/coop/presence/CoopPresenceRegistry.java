package coop.presence;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one slot through which coop.jar tells the forked engine classes "there is a second player, and
 * this entity is where they are".
 *
 * <p><b>Classloader contract</b> (identical to {@code coop.rng.CoopRandom}, and the reason this class
 * lives in its own package rather than in {@code coop.fleet}): this class is bundled into
 * {@code jars/coop-forks.jar} and excluded from {@code jars/coop.jar}, so it is loaded by the JVM
 * system/application classloader -- the same one that loads {@code starfarer.api.jar} and the forked
 * {@code com.fs.starfarer.api.impl.campaign.fleets.RouteManager} that reads it. It therefore must NOT
 * reference any class from {@code coop.jar}, which loads in Starsector's child mod classloader and is
 * invisible to the parent. The write side ({@code coop.fleet.CoopGuestPresence}) is in coop.jar and can
 * see this class, because the mod classloader delegates to its parent first.
 *
 * <p>One static slot, defaulting to null. Null means "no second player" and every fork guarded on it
 * behaves exactly as vanilla -- which is what makes solo play, the guest side, and a launch without
 * coop-forks.jar all provably unchanged.
 *
 * <p>The slot holds a live engine entity, so it must be cleared when the session ends; it is
 * re-asserted every tick and released on the first frame the assert stops arriving. It is deliberately
 * <em>not</em> persisted anywhere: nothing here is written to a save.
 *
 * <p>This class also owns the <b>pinned-version guard</b> every presence fork shares
 * ({@link #getForFork(String)}). The guard lives here rather than in any one fork so that a version
 * bump has exactly one constant to change and the forks can never disagree about which build they were
 * taken from.
 */
public final class CoopPresenceRegistry {

    /**
     * The Starsector build every guest-presence fork in {@code coop-forks.jar} was taken from, line for
     * line. If the running game is not this build, {@link #getForFork(String)} returns null forever, so
     * every fork falls back to the vanilla logic it was copied from rather than running a stale copy of
     * that logic with co-op behaviour bolted on.
     */
    public static final String PINNED_VERSION = "0.98.5a-RC9";

    private static volatile SectorEntityToken presence;
    /** Phase 27: all live guest mirrors (host + up to 3 guests). */
    private static volatile List<SectorEntityToken> allPresences = Collections.emptyList();

    /** Tri-state: null = not checked yet, TRUE/FALSE = the pinned-version verdict for this process. */
    private static Boolean versionOk;

    private CoopPresenceRegistry() {
    }

    /**
     * The presence entity for a forked engine class, or null when the fork must behave exactly as
     * vanilla. Null is returned when there is no co-op session, when the guest mirror does not exist
     * yet, when coop.jar never registered anything, when coop-forks.jar is on the classpath without the
     * mod, and when the running game is not {@link #PINNED_VERSION}.
     *
     * @param forkName the simple name of the calling fork, used only in the version-mismatch warning.
     */
    public static SectorEntityToken getForFork(String forkName) {
        SectorEntityToken current = presence;
        if (current == null) {
            return null;
        }
        if (!versionMatches(forkName)) {
            return null;
        }
        return current;
    }

    /** Checked once per process, on the first use of the presence term by any fork. */
    private static synchronized boolean versionMatches(String forkName) {
        if (versionOk != null) {
            return versionOk;
        }

        String version;
        try {
            version = Global.getSettings().getVersionString();
        } catch (Throwable t) {
            version = null;
        }
        versionOk = version != null && version.contains(PINNED_VERSION);
        if (versionOk) {
            Global.getLogger(CoopPresenceRegistry.class).info(
                    "[COOP-FORK] guest-presence forks active (version " + version + "); first user: "
                            + forkName);
        } else {
            Global.getLogger(CoopPresenceRegistry.class).warn(
                    "[COOP-FORK] the guest-presence forks were taken from Starsector " + PINNED_VERSION
                            + " but this game reports '" + version + "'. The co-op guest-presence term is"
                            + " DISABLED for this process (vanilla spawning only, so NPC fleets will not"
                            + " materialise around the guest). Re-fork against the new version. First"
                            + " user: " + forkName);
        }
        return versionOk;
    }

    /**
     * Phase 27: all live guest presence entities for multi-guest forks, or empty when vanilla
     * behaviour applies (solo play, no mirrors, or version mismatch).
     */
    public static List<SectorEntityToken> getAllForFork(String forkName) {
        List<SectorEntityToken> current = allPresences;
        if (current.isEmpty()) {
            SectorEntityToken single = presence;
            if (single == null) {
                return Collections.emptyList();
            }
            current = Collections.singletonList(single);
        }
        if (!versionMatches(forkName)) {
            return Collections.emptyList();
        }
        return current;
    }

    /**
     * Publishes the co-op guest's presence entity. Called every tick with the current guest mirror, so
     * a mirror that is disposed and rebuilt heals on the next tick rather than leaving a dead entity
     * in the slot.
     */
    public static void set(SectorEntityToken entity) {
        presence = entity;
        allPresences = entity == null ? Collections.emptyList() : Collections.singletonList(entity);
    }

    /**
     * Phase 27: publishes all live guest presence entities. Keeps the single-entity {@link #get()}
     * slot pointing at the first live mirror so every existing fork stays 100% compatible.
     */
    public static void setAll(List<? extends SectorEntityToken> entities) {
        if (entities == null || entities.isEmpty()) {
            clear();
            return;
        }
        List<SectorEntityToken> cleaned = new ArrayList<>(entities.size());
        for (SectorEntityToken entity : entities) {
            if (entity != null) {
                cleaned.add(entity);
            }
        }
        if (cleaned.isEmpty()) {
            clear();
            return;
        }
        allPresences = Collections.unmodifiableList(cleaned);
        presence = cleaned.get(0);
    }

    /** Drops all presence entities. After this every fork reading it is back to vanilla behaviour. */
    public static void clear() {
        presence = null;
        allPresences = Collections.emptyList();
    }

    /** The primary co-op guest's presence entity, or null when there is no second player. */
    public static SectorEntityToken get() {
        return presence;
    }

    /** Phase 27: all published guest presence entities (empty when solo). */
    public static List<SectorEntityToken> getAll() {
        return allPresences;
    }
}
