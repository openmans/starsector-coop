package coop.combat;

import coop.campaign.CoopDelimited;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Snapshot of a coop battle as seen by the <em>engaging</em> client, plus its codec (Phase 14 +
 * Phase 22 tactical-map extension).
 *
 * <p>Format lines:
 * <ul>
 *   <li>{@code S|shipId|hullId|name|enemy|hull|flux|state[|x|y|facing|cr|ally|assignment|retreating]}</li>
 *   <li>{@code K|killFeedEntry}</li>
 *   <li>{@code O|id|type|name|owner|x|y} (Phase 22 battle objectives)</li>
 *   <li>{@code B|width|height|paused} (Phase 22 map bounds and tactical pause state)</li>
 * </ul>
 */
public record CoopBattleStatus(String battleId, long statusSeq, long elapsedMillis,
                               List<ShipRecord> ships, List<String> killFeed,
                               List<ObjectiveRecord> objectives, MapBounds bounds) {

    /** Per-ship liveness as the spectator panel / tactical map renders it. */
    public enum ShipState {
        ALIVE,
        DISABLED,
        DESTROYED
    }

    /**
     * One ship in the battle. {@code enemy} is relative to the engaging player (owner 0 = own side).
     * Phase 22 adds world position, facing, CR, ally flag, active assignment and retreat state.
     */
    public record ShipRecord(String shipId, String hullId, String name, boolean enemy,
                             float hullFraction, float fluxLevel, ShipState state,
                             float x, float y, float facing, float cr,
                             boolean ally, String assignment, boolean retreating) {
        public ShipRecord {
            shipId = CoopDelimited.normalize(shipId);
            hullId = CoopDelimited.normalize(hullId);
            name = CoopDelimited.normalize(name);
            state = state == null ? ShipState.ALIVE : state;
            hullFraction = clamp(hullFraction);
            fluxLevel = clamp(fluxLevel);
            cr = clamp(cr);
            assignment = CoopDelimited.normalize(assignment);
        }

        /** Phase 14 compatibility constructor (no tactical coordinates). */
        public ShipRecord(String shipId, String hullId, String name, boolean enemy,
                          float hullFraction, float fluxLevel, ShipState state) {
            this(shipId, hullId, name, enemy, hullFraction, fluxLevel, state,
                    0f, 0f, 0f, 1f, false, "", false);
        }
    }

    /** Phase 22 battle objective (nav buoy, sensor array, comm relay). */
    public record ObjectiveRecord(String id, String type, String name, int owner, float x, float y) {
        public ObjectiveRecord {
            id = CoopDelimited.normalize(id);
            type = CoopDelimited.normalize(type);
            name = CoopDelimited.normalize(name);
        }
    }

    /** Phase 22 combat map bounds and tactical-pause flag. */
    public record MapBounds(float width, float height, boolean paused) {
        public static final MapBounds DEFAULT = new MapBounds(14_000f, 14_000f, false);

        public MapBounds {
            width = width <= 0f ? 14_000f : width;
            height = height <= 0f ? 14_000f : height;
        }
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final String RECORD_SEPARATOR = "\n";
    private static final String SHIP_TOKEN = "S";
    private static final String KILL_TOKEN = "K";
    private static final String OBJECTIVE_TOKEN = "O";
    private static final String BOUNDS_TOKEN = "B";
    private static final int SHIP_FIELD_COUNT = 8;

    public CoopBattleStatus {
        battleId = CoopDelimited.normalize(battleId);
        ships = ships == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(ships));
        killFeed = killFeed == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(killFeed));
        objectives = objectives == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(objectives));
        bounds = bounds == null ? MapBounds.DEFAULT : bounds;
    }

    /** Phase 14 compatibility constructor (no objectives or bounds). */
    public CoopBattleStatus(String battleId, long statusSeq, long elapsedMillis,
                            List<ShipRecord> ships, List<String> killFeed) {
        this(battleId, statusSeq, elapsedMillis, ships, killFeed, List.of(), MapBounds.DEFAULT);
    }

    public static boolean isNewer(String incomingBattleId, long incomingSeq, CoopBattleStatus current) {
        if (current == null) {
            return true;
        }
        String incoming = CoopDelimited.normalize(incomingBattleId);
        if (!incoming.equals(current.battleId())) {
            return true;
        }
        return incomingSeq > current.statusSeq();
    }

    public boolean isNewerThan(CoopBattleStatus current) {
        return isNewer(battleId, statusSeq, current);
    }

    public List<ShipRecord> ownShips() {
        return sideShips(false);
    }

    public List<ShipRecord> enemyShips() {
        return sideShips(true);
    }

    public List<ShipRecord> alliedShips() {
        List<ShipRecord> out = new ArrayList<>();
        for (ShipRecord ship : ships) {
            if (!ship.enemy() && ship.ally()) {
                out.add(ship);
            }
        }
        return out;
    }

    private List<ShipRecord> sideShips(boolean enemy) {
        List<ShipRecord> out = new ArrayList<>();
        for (ShipRecord ship : ships) {
            if (ship.enemy() == enemy) {
                out.add(ship);
            }
        }
        return out;
    }

    public String encodeBody() {
        boolean includeBounds = !MapBounds.DEFAULT.equals(bounds);
        if (ships.isEmpty() && killFeed.isEmpty() && objectives.isEmpty() && !includeBounds) {
            return "";
        }
        List<String> lines = new ArrayList<>(ships.size() + killFeed.size() + objectives.size() + (includeBounds ? 1 : 0));
        if (includeBounds) {
            lines.add(BOUNDS_TOKEN
                    + FIELD_SEPARATOR + bounds.width()
                    + FIELD_SEPARATOR + bounds.height()
                    + FIELD_SEPARATOR + (bounds.paused() ? "1" : "0"));
        }
        for (ObjectiveRecord obj : objectives) {
            lines.add(OBJECTIVE_TOKEN
                    + FIELD_SEPARATOR + CoopDelimited.field(obj.id())
                    + FIELD_SEPARATOR + CoopDelimited.field(obj.type())
                    + FIELD_SEPARATOR + CoopDelimited.field(obj.name())
                    + FIELD_SEPARATOR + obj.owner()
                    + FIELD_SEPARATOR + obj.x()
                    + FIELD_SEPARATOR + obj.y());
        }
        for (ShipRecord ship : ships) {
            lines.add(SHIP_TOKEN
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.shipId())
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.hullId())
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.name())
                    + FIELD_SEPARATOR + (ship.enemy() ? "1" : "0")
                    + FIELD_SEPARATOR + ship.hullFraction()
                    + FIELD_SEPARATOR + ship.fluxLevel()
                    + FIELD_SEPARATOR + ship.state().name()
                    + FIELD_SEPARATOR + ship.x()
                    + FIELD_SEPARATOR + ship.y()
                    + FIELD_SEPARATOR + ship.facing()
                    + FIELD_SEPARATOR + ship.cr()
                    + FIELD_SEPARATOR + (ship.ally() ? "1" : "0")
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.assignment())
                    + FIELD_SEPARATOR + (ship.retreating() ? "1" : "0"));
        }
        for (String kill : killFeed) {
            lines.add(KILL_TOKEN + FIELD_SEPARATOR + CoopDelimited.field(CoopDelimited.normalize(kill)));
        }
        return String.join(RECORD_SEPARATOR, lines);
    }

    public static CoopBattleStatus decode(String battleId, long statusSeq, long elapsedMillis, String body) {
        Objects.requireNonNull(body, "body");
        List<ShipRecord> ships = new ArrayList<>();
        List<String> killFeed = new ArrayList<>();
        List<ObjectiveRecord> objectives = new ArrayList<>();
        MapBounds bounds = MapBounds.DEFAULT;
        if (!body.isEmpty()) {
            for (String line : body.split(RECORD_SEPARATOR, -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> fields = CoopDelimited.split(line);
                String token = fields.get(0);
                if (SHIP_TOKEN.equals(token)) {
                    if (fields.size() < SHIP_FIELD_COUNT) {
                        throw new IllegalArgumentException("Expected at least " + SHIP_FIELD_COUNT
                                + " battle-status ship fields, got " + fields.size());
                    }
                    float x = fields.size() > 8 ? parseFloat(fields.get(8)) : 0f;
                    float y = fields.size() > 9 ? parseFloat(fields.get(9)) : 0f;
                    float facing = fields.size() > 10 ? parseFloat(fields.get(10)) : 0f;
                    float cr = fields.size() > 11 ? parseFloat(fields.get(11)) : 1f;
                    boolean ally = fields.size() > 12 && "1".equals(fields.get(12));
                    String assignment = fields.size() > 13 ? fields.get(13) : "";
                    boolean retreating = fields.size() > 14 && "1".equals(fields.get(14));
                    ships.add(new ShipRecord(
                            fields.get(1),
                            fields.get(2),
                            fields.get(3),
                            "1".equals(fields.get(4)),
                            parseFloat(fields.get(5)),
                            parseFloat(fields.get(6)),
                            parseState(fields.get(7)),
                            x, y, facing, cr, ally, assignment, retreating));
                } else if (KILL_TOKEN.equals(token)) {
                    killFeed.add(fields.size() > 1 ? fields.get(1) : "");
                } else if (OBJECTIVE_TOKEN.equals(token) && fields.size() >= 7) {
                    objectives.add(new ObjectiveRecord(
                            fields.get(1),
                            fields.get(2),
                            fields.get(3),
                            parseInt(fields.get(4)),
                            parseFloat(fields.get(5)),
                            parseFloat(fields.get(6))));
                } else if (BOUNDS_TOKEN.equals(token) && fields.size() >= 4) {
                    bounds = new MapBounds(
                            parseFloat(fields.get(1)),
                            parseFloat(fields.get(2)),
                            "1".equals(fields.get(3)));
                }
            }
        }
        return new CoopBattleStatus(battleId, statusSeq, elapsedMillis, ships, killFeed, objectives, bounds);
    }

    private static ShipState parseState(String raw) {
        try {
            return ShipState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return ShipState.ALIVE;
        }
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException ex) {
            return 0f;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || value < 0f) {
            return 0f;
        }
        return value > 1f ? 1f : value;
    }
}
