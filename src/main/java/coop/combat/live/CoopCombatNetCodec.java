package coop.combat.live;

import coop.campaign.CoopDelimited;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Compact, sandbox-safe (pure {@link ByteBuffer} + Base64 / delimited text, zero {@code java.io.*})
 * wire codec for native real-time co-op combat synchronization.
 *
 * <p>High-frequency streams ({@code COMBAT_INPUT}, {@code COMBAT_STATE}, {@code COMBAT_MISSILE_STATE},
 * {@code COMBAT_BEAMS}) use tight binary packing with chunking under the 1,200-byte UDP datagram
 * budget; low-frequency structural & reliable records ({@code COMBAT_INIT}, {@code COMBAT_SPAWN},
 * {@code COMBAT_PROJ_SPAWN}, {@code COMBAT_PROJ_HIT}, {@code COMBAT_EVENT}) use deterministic
 * delimited encoding, and {@code COMBAT_ARMOR_DELTA} encodes sparse changed armor cells in 3 bytes
 * per cell.
 */
public final class CoopCombatNetCodec {

    /** Conservative char ceiling per Base64 chunk so UDP envelope + header always stays <= 1200 B. */
    public static final int DEFAULT_UDP_BODY_LIMIT = 860;

    // ---- PilotInput command bitmask constants ----
    public static final int CMD_ACCEL = 1 << 0;
    public static final int CMD_BACK = 1 << 1;
    public static final int CMD_DECEL = 1 << 2;
    public static final int CMD_TURN_LEFT = 1 << 3;
    public static final int CMD_TURN_RIGHT = 1 << 4;
    public static final int CMD_STRAFE_LEFT = 1 << 5;
    public static final int CMD_STRAFE_RIGHT = 1 << 6;
    public static final int CMD_FIRE = 1 << 7;
    public static final int CMD_USE_SELECTED = 1 << 8;
    public static final int CMD_SHIELD_TOGGLE = 1 << 9;
    public static final int CMD_USE_SYSTEM = 1 << 10;
    public static final int CMD_VENT = 1 << 11;
    public static final int CMD_HOLD_FIRE = 1 << 12;
    public static final int CMD_PULL_FIGHTERS = 1 << 13;
    public static final int CMD_SHIELD_DESIRED_ON = 1 << 14;
    public static final int CMD_SHIELD_HAS_DESIRED = 1 << 15;

    // ---- ShipStateSample flags ----
    public static final int FLAG_SHIELD_ON = 1 << 0;
    public static final int FLAG_OVERLOADED = 1 << 1;
    public static final int FLAG_VENTING = 1 << 2;
    public static final int FLAG_PHASED = 1 << 3;
    public static final int FLAG_HULK = 1 << 4;
    public static final int FLAG_FIGHTER = 1 << 5;
    public static final int FLAG_DEAD = 1 << 6;
    public static final int FLAG_RETREATED = 1 << 7;

    // ---- CombatEvent kinds ----
    public static final String EVT_SHIP_DEAD = "SHIP_DEAD";
    public static final String EVT_SHIP_HULK = "SHIP_HULK";
    public static final String EVT_SHIP_RETREATED = "SHIP_RETREATED";
    public static final String EVT_SHIP_REMOVED = "SHIP_REMOVED";
    public static final String EVT_EMP_ARC = "EMP_ARC";
    public static final String EVT_EXPLOSION = "EXPLOSION";
    public static final String EVT_OBJ_OWNER = "OBJ_OWNER";
    public static final String EVT_PILOT_SWITCH = "PILOT_SWITCH";
    public static final String EVT_PAUSE = "PAUSE";
    public static final String EVT_COMBAT_END = "COMBAT_END";

    private CoopCombatNetCodec() {
    }

    // ---- DTO records ----------------------------------------------------------------------------

    public record ObjectiveInit(String id, String type, float x, float y, int owner) {
        public ObjectiveInit {
            id = safe(id);
            type = safe(type);
        }
    }

    public record CombatInit(String battleId, float mapWidth, float mapHeight,
                             String authorityName, String partnerPlayerId,
                             int initialPilotNetId, List<ObjectiveInit> objectives) {
        public CombatInit {
            battleId = safe(battleId);
            authorityName = safe(authorityName);
            partnerPlayerId = safe(partnerPlayerId);
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
        }
    }

    public record SlotWeapon(String slotId, String weaponId) {
        public SlotWeapon {
            slotId = safe(slotId);
            weaponId = safe(weaponId);
        }
    }

    public record ShipSpawn(String battleId, int netId, int owner, String ownerPlayerId,
                            String fleetMemberId, String hullId, String variantId, String shipName,
                            int fluxVents, int fluxCaps,
                            List<SlotWeapon> weapons, List<String> hullMods,
                            List<String> sMods, List<String> wings,
                            float x, float y, float facing, float vx, float vy,
                            float hp, float maxHp, float cr,
                            boolean fighter, boolean module, int parentNetId) {
        public ShipSpawn {
            battleId = safe(battleId);
            ownerPlayerId = safe(ownerPlayerId);
            fleetMemberId = safe(fleetMemberId);
            hullId = safe(hullId);
            variantId = safe(variantId);
            shipName = safe(shipName);
            weapons = weapons == null ? List.of() : List.copyOf(weapons);
            hullMods = hullMods == null ? List.of() : List.copyOf(hullMods);
            sMods = sMods == null ? List.of() : List.copyOf(sMods);
            wings = wings == null ? List.of() : List.copyOf(wings);
        }
    }

    public record PilotInput(String battleId, int seq, int shipNetId, int commandMask,
                             float mouseX, float mouseY, int selectedGroup, int autofireMask) {
        public PilotInput {
            battleId = safe(battleId);
        }
    }

    public record ShipStateSample(int netId, float x, float y, float vx, float vy,
                                  float facing, float angVel, float hp, float maxHp, float cr,
                                  float currFlux, float hardFlux, float overloadRem,
                                  int flags, float shieldFacing, float shieldArc,
                                  int systemStateOrdinal, float systemEffect,
                                  int systemAmmo, float systemCooldown,
                                  int[] turretAnglesDeg, int[] weaponAmmo,
                                  int disabledWeaponMask, int autofireMask) {
        public ShipStateSample {
            turretAnglesDeg = turretAnglesDeg == null ? new int[0] : turretAnglesDeg.clone();
            weaponAmmo = weaponAmmo == null ? new int[0] : weaponAmmo.clone();
        }
    }

    public record ShipStateBatch(String battleId, int tickSeq, List<ShipStateSample> ships) {
        public ShipStateBatch {
            battleId = safe(battleId);
            ships = ships == null ? List.of() : List.copyOf(ships);
        }
    }

    public record ArmorCell(int cx, int cy, int valueByte) {
    }

    public record ArmorDelta(String battleId, int netId, int gridW, int gridH, List<ArmorCell> cells) {
        public ArmorDelta {
            battleId = safe(battleId);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record ProjectileSpawn(int projId, int sourceShipNetId, int owner, String weaponId,
                                  boolean missile, float x, float y, float vx, float vy, float facing) {
        public ProjectileSpawn {
            weaponId = safe(weaponId);
        }
    }

    public record ProjectileSpawnBatch(String battleId, List<ProjectileSpawn> spawns) {
        public ProjectileSpawnBatch {
            battleId = safe(battleId);
            spawns = spawns == null ? List.of() : List.copyOf(spawns);
        }
    }

    public record ProjectileHit(int projId, boolean hit, float x, float y, float damage,
                                int damageTypeOrdinal, boolean shieldHit, int targetShipNetId) {
    }

    public record ProjectileHitBatch(String battleId, List<ProjectileHit> hits) {
        public ProjectileHitBatch {
            battleId = safe(battleId);
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    public record MissileSample(int projId, float x, float y, float vx, float vy,
                                float facing, boolean fizzling) {
    }

    public record MissileStateBatch(String battleId, int tickSeq, List<MissileSample> missiles) {
        public MissileStateBatch {
            battleId = safe(battleId);
            missiles = missiles == null ? List.of() : List.copyOf(missiles);
        }
    }

    public record BeamSample(float fromX, float fromY, float toX, float toY,
                             float width, int coreArgb, int fringeArgb, boolean hitting) {
    }

    public record BeamStateBatch(String battleId, int tickSeq, List<BeamSample> beams) {
        public BeamStateBatch {
            battleId = safe(battleId);
            beams = beams == null ? List.of() : List.copyOf(beams);
        }
    }

    public record CombatEvent(String battleId, String kind, int argInt1, int argInt2,
                              float f1, float f2, float f3, float f4, float f5, String text) {
        public CombatEvent {
            battleId = safe(battleId);
            kind = safe(kind);
            text = safe(text);
        }
    }

    // ---- COMBAT_INIT ----------------------------------------------------------------------------

    public static String encodeInit(CombatInit init) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("CI|")
                .append(CoopDelimited.field(init.battleId())).append('|')
                .append(fmtF(init.mapWidth())).append('|')
                .append(fmtF(init.mapHeight())).append('|')
                .append(CoopDelimited.field(init.authorityName())).append('|')
                .append(CoopDelimited.field(init.partnerPlayerId())).append('|')
                .append(init.initialPilotNetId());
        for (ObjectiveInit o : init.objectives()) {
            sb.append("\nO|")
                    .append(CoopDelimited.field(o.id())).append('|')
                    .append(CoopDelimited.field(o.type())).append('|')
                    .append(fmtF(o.x())).append('|')
                    .append(fmtF(o.y())).append('|')
                    .append(o.owner());
        }
        return sb.toString();
    }

    public static CombatInit decodeInit(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String battleId = "";
        float mapW = 14000f;
        float mapH = 14000f;
        String authName = "";
        String partnerId = "";
        int pilotNetId = 0;
        boolean header = false;
        List<ObjectiveInit> objs = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            if ("CI".equals(f.get(0)) && f.size() >= 7) {
                header = true;
                battleId = f.get(1);
                mapW = parseFloat(f.get(2), 14000f);
                mapH = parseFloat(f.get(3), 14000f);
                authName = f.get(4);
                partnerId = f.get(5);
                pilotNetId = parseInt(f.get(6), 0);
            } else if ("O".equals(f.get(0)) && f.size() >= 6) {
                objs.add(new ObjectiveInit(f.get(1), f.get(2),
                        parseFloat(f.get(3), 0f), parseFloat(f.get(4), 0f),
                        parseInt(f.get(5), 100)));
            }
        }
        return header ? new CombatInit(battleId, mapW, mapH, authName, partnerId, pilotNetId, objs) : null;
    }

    // ---- COMBAT_SPAWN ---------------------------------------------------------------------------

    public static String encodeSpawn(ShipSpawn s) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("CS|")
                .append(CoopDelimited.field(s.battleId())).append('|')
                .append(s.netId()).append('|')
                .append(s.owner()).append('|')
                .append(CoopDelimited.field(s.ownerPlayerId())).append('|')
                .append(CoopDelimited.field(s.fleetMemberId())).append('|')
                .append(CoopDelimited.field(s.hullId())).append('|')
                .append(CoopDelimited.field(s.variantId())).append('|')
                .append(CoopDelimited.field(s.shipName())).append('|')
                .append(s.fluxVents()).append('|')
                .append(s.fluxCaps()).append('|')
                .append(fmtF(s.x())).append('|')
                .append(fmtF(s.y())).append('|')
                .append(fmtF(s.facing())).append('|')
                .append(fmtF(s.vx())).append('|')
                .append(fmtF(s.vy())).append('|')
                .append(fmtF(s.hp())).append('|')
                .append(fmtF(s.maxHp())).append('|')
                .append(fmtF(s.cr())).append('|')
                .append(s.fighter() ? '1' : '0').append('|')
                .append(s.module() ? '1' : '0').append('|')
                .append(s.parentNetId());
        for (SlotWeapon w : s.weapons()) {
            sb.append("\nW|").append(CoopDelimited.field(w.slotId())).append('|')
                    .append(CoopDelimited.field(w.weaponId()));
        }
        if (!s.hullMods().isEmpty()) {
            sb.append("\nM|").append(CoopDelimited.field(String.join(",", s.hullMods())));
        }
        if (!s.sMods().isEmpty()) {
            sb.append("\nSM|").append(CoopDelimited.field(String.join(",", s.sMods())));
        }
        if (!s.wings().isEmpty()) {
            sb.append("\nWG|").append(CoopDelimited.field(String.join(",", s.wings())));
        }
        return sb.toString();
    }

    public static ShipSpawn decodeSpawn(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        boolean header = false;
        String battleId = "", ownerPlayerId = "", memberId = "", hullId = "", variantId = "", shipName = "";
        int netId = 0, owner = 0, vents = 0, caps = 0, parentNetId = 0;
        float x = 0f, y = 0f, facing = 0f, vx = 0f, vy = 0f, hp = 100f, maxHp = 100f, cr = 0.7f;
        boolean fighter = false, module = false;
        List<SlotWeapon> weapons = new ArrayList<>();
        List<String> hullMods = new ArrayList<>();
        List<String> sMods = new ArrayList<>();
        List<String> wings = new ArrayList<>();

        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            String tag = f.get(0);
            if ("CS".equals(tag) && f.size() >= 22) {
                header = true;
                battleId = f.get(1);
                netId = parseInt(f.get(2), 0);
                owner = parseInt(f.get(3), 0);
                ownerPlayerId = f.get(4);
                memberId = f.get(5);
                hullId = f.get(6);
                variantId = f.get(7);
                shipName = f.get(8);
                vents = parseInt(f.get(9), 0);
                caps = parseInt(f.get(10), 0);
                x = parseFloat(f.get(11), 0f);
                y = parseFloat(f.get(12), 0f);
                facing = parseFloat(f.get(13), 0f);
                vx = parseFloat(f.get(14), 0f);
                vy = parseFloat(f.get(15), 0f);
                hp = parseFloat(f.get(16), 100f);
                maxHp = parseFloat(f.get(17), 100f);
                cr = parseFloat(f.get(18), 0.7f);
                fighter = "1".equals(f.get(19));
                module = "1".equals(f.get(20));
                parentNetId = parseInt(f.get(21), 0);
            } else if ("W".equals(tag) && f.size() >= 3) {
                weapons.add(new SlotWeapon(f.get(1), f.get(2)));
            } else if ("M".equals(tag) && f.size() >= 2) {
                splitCsv(f.get(1), hullMods);
            } else if ("SM".equals(tag) && f.size() >= 2) {
                splitCsv(f.get(1), sMods);
            } else if ("WG".equals(tag) && f.size() >= 2) {
                splitCsv(f.get(1), wings);
            }
        }
        return header ? new ShipSpawn(battleId, netId, owner, ownerPlayerId, memberId, hullId,
                variantId, shipName, vents, caps, weapons, hullMods, sMods, wings,
                x, y, facing, vx, vy, hp, maxHp, cr, fighter, module, parentNetId) : null;
    }

    // ---- COMBAT_INPUT (compact binary + Base64, ~44 chars) --------------------------------------

    public static String encodeInput(PilotInput in) {
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(in.seq());
        buf.putInt(in.shipNetId());
        buf.putInt(in.commandMask());
        buf.putFloat(in.mouseX());
        buf.putFloat(in.mouseY());
        buf.put((byte) (in.selectedGroup() & 0xFF));
        buf.putShort((short) (in.autofireMask() & 0xFFFF));
        buf.put((byte) 0);
        return CoopDelimited.field(in.battleId()) + "|"
                + Base64.getEncoder().encodeToString(buf.array());
    }

    public static PilotInput decodeInput(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 2) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(f.get(1).trim());
            if (raw.length < 23) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int seq = buf.getInt();
            int shipNetId = buf.getInt();
            int mask = buf.getInt();
            float mx = buf.getFloat();
            float my = buf.getFloat();
            int group = buf.get() & 0xFF;
            if (group == 255) {
                group = -1;
            }
            int autoMask = buf.getShort() & 0xFFFF;
            return new PilotInput(f.get(0), seq, shipNetId, mask, mx, my, group, autoMask);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- COMBAT_STATE (chunked compact binary + Base64) -----------------------------------------

    /**
     * Encodes ship states into one or more UDP-safe payloads, each strictly under
     * {@code maxBodyChars}.
     */
    public static List<String> encodeShipStateChunks(String battleId, int tickSeq,
                                                     List<ShipStateSample> samples,
                                                     int maxBodyChars) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(200, maxBodyChars);
        String prefix = CoopDelimited.field(battleId) + "|" + tickSeq + "|";
        int maxRawBytes = Math.max(96, ((limit - prefix.length()) / 4) * 3 - 4);
        List<String> out = new ArrayList<>();

        List<ShipStateSample> batch = new ArrayList<>();
        int batchBytes = 2; // short count
        for (ShipStateSample s : samples) {
            int entryBytes = shipSampleByteSize(s);
            if (!batch.isEmpty() && batchBytes + entryBytes > maxRawBytes) {
                out.add(prefix + encodeShipBatchBinary(batch, batchBytes));
                batch.clear();
                batchBytes = 2;
            }
            batch.add(s);
            batchBytes += entryBytes;
        }
        if (!batch.isEmpty()) {
            out.add(prefix + encodeShipBatchBinary(batch, batchBytes));
        }
        return out;
    }

    private static final int SHIP_STATE_FIXED_BYTES = 56;

    private static int shipSampleByteSize(ShipStateSample s) {
        int tCount = Math.min(32, s.turretAnglesDeg().length);
        // Fixed 56 bytes + tCount * (1 byte angle + 2 bytes ammo)
        return SHIP_STATE_FIXED_BYTES + tCount * 3;
    }

    private static String encodeShipBatchBinary(List<ShipStateSample> batch, int totalBytes) {
        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) batch.size());
        for (ShipStateSample s : batch) {
            buf.putInt(s.netId());
            buf.putFloat(s.x());
            buf.putFloat(s.y());
            buf.putShort(clampShort(Math.round(s.vx() * 4f)));
            buf.putShort(clampShort(Math.round(s.vy() * 4f)));
            buf.putShort(packAngle16(s.facing()));
            buf.putShort(clampShort(Math.round(s.angVel() * 16f)));
            buf.putFloat(s.hp());
            buf.putFloat(s.maxHp());
            buf.put((byte) clampByte(Math.round(s.cr() * 255f)));
            buf.put((byte) (s.flags() & 0xFF));
            buf.putFloat(s.currFlux());
            buf.putFloat(s.hardFlux());
            buf.putShort(clampShort(Math.round(s.overloadRem() * 100f)));
            buf.putShort(packAngle16(s.shieldFacing()));
            buf.putShort(packAngle16(s.shieldArc()));
            buf.put((byte) (s.systemStateOrdinal() & 0xFF));
            buf.put((byte) clampByte(Math.round(s.systemEffect() * 255f)));
            buf.putShort(clampShort(s.systemAmmo()));
            buf.putShort(clampShort(Math.round(s.systemCooldown() * 100f)));
            buf.putInt(s.disabledWeaponMask());
            buf.put((byte) (s.autofireMask() & 0xFF));
            int tCount = Math.min(32, s.turretAnglesDeg().length);
            buf.put((byte) (tCount & 0x3F));
            for (int i = 0; i < tCount; i++) {
                buf.put((byte) packAngle8(s.turretAnglesDeg()[i]));
            }
            int[] ammoArr = s.weaponAmmo();
            for (int i = 0; i < tCount; i++) {
                int ammo = i < ammoArr.length ? ammoArr[i] : -1;
                buf.putShort(clampShort(ammo));
            }
        }
        byte[] bytes = new byte[buf.position()];
        buf.flip();
        buf.get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static ShipStateBatch decodeShipState(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 3) {
            return null;
        }
        String battleId = f.get(0);
        int tickSeq = parseInt(f.get(1), 0);
        try {
            byte[] raw = Base64.getDecoder().decode(f.get(2).trim());
            if (raw.length < 2) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.getShort() & 0xFFFF;
            List<ShipStateSample> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (buf.remaining() < SHIP_STATE_FIXED_BYTES) {
                    break;
                }
                int netId = buf.getInt();
                float x = buf.getFloat();
                float y = buf.getFloat();
                float vx = buf.getShort() / 4f;
                float vy = buf.getShort() / 4f;
                float facing = unpackAngle16(buf.getShort());
                float angVel = buf.getShort() / 16f;
                float hp = buf.getFloat();
                float maxHp = buf.getFloat();
                float cr = (buf.get() & 0xFF) / 255f;
                int flags = buf.get() & 0xFF;
                float currFlux = buf.getFloat();
                float hardFlux = buf.getFloat();
                float overloadRem = Math.max(0f, buf.getShort() / 100f);
                float shieldFacing = unpackAngle16(buf.getShort());
                float shieldArc = unpackAngle16(buf.getShort());
                int sysState = buf.get() & 0xFF;
                float sysEffect = (buf.get() & 0xFF) / 255f;
                int sysAmmo = buf.getShort();
                float sysCd = Math.max(0f, buf.getShort() / 100f);
                int disabledMask = buf.getInt();
                int autofireMask = buf.get() & 0xFF;
                int tCount = buf.get() & 0x3F;
                if (buf.remaining() < tCount * 3) {
                    break;
                }
                int[] turrets = new int[tCount];
                for (int t = 0; t < tCount; t++) {
                    turrets[t] = Math.round(unpackAngle8(buf.get() & 0xFF));
                }
                int[] ammo = new int[tCount];
                for (int t = 0; t < tCount; t++) {
                    ammo[t] = buf.getShort();
                }
                list.add(new ShipStateSample(netId, x, y, vx, vy, facing, angVel, hp, maxHp, cr,
                        currFlux, hardFlux, overloadRem, flags, shieldFacing, shieldArc,
                        sysState, sysEffect, sysAmmo, sysCd, turrets, ammo, disabledMask, autofireMask));
            }
            return new ShipStateBatch(battleId, tickSeq, list);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- COMBAT_ARMOR_DELTA (3 bytes per dirty cell!) -------------------------------------------

    public static String encodeArmorDelta(ArmorDelta delta) {
        List<ArmorCell> cells = delta.cells();
        ByteBuffer buf = ByteBuffer.allocate(8 + cells.size() * 3).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(delta.netId());
        buf.put((byte) (delta.gridW() & 0xFF));
        buf.put((byte) (delta.gridH() & 0xFF));
        buf.putShort((short) cells.size());
        for (ArmorCell c : cells) {
            buf.put((byte) (c.cx() & 0xFF));
            buf.put((byte) (c.cy() & 0xFF));
            buf.put((byte) (c.valueByte() & 0xFF));
        }
        return CoopDelimited.field(delta.battleId()) + "|"
                + Base64.getEncoder().encodeToString(buf.array());
    }

    public static ArmorDelta decodeArmorDelta(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 2) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(f.get(1).trim());
            if (raw.length < 8) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int netId = buf.getInt();
            int gw = buf.get() & 0xFF;
            int gh = buf.get() & 0xFF;
            int count = buf.getShort() & 0xFFFF;
            List<ArmorCell> cells = new ArrayList<>(Math.min(count, buf.remaining() / 3));
            for (int i = 0; i < count && buf.remaining() >= 3; i++) {
                cells.add(new ArmorCell(buf.get() & 0xFF, buf.get() & 0xFF, buf.get() & 0xFF));
            }
            return new ArmorDelta(f.get(0), netId, gw, gh, cells);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- COMBAT_PROJ_SPAWN & COMBAT_PROJ_HIT ----------------------------------------------------

    public static String encodeProjSpawns(ProjectileSpawnBatch batch) {
        StringBuilder sb = new StringBuilder(64 + batch.spawns().size() * 48);
        sb.append("PS|").append(CoopDelimited.field(batch.battleId()));
        for (ProjectileSpawn p : batch.spawns()) {
            sb.append('\n')
                    .append(p.projId()).append('|')
                    .append(p.sourceShipNetId()).append('|')
                    .append(p.owner()).append('|')
                    .append(CoopDelimited.field(p.weaponId())).append('|')
                    .append(p.missile() ? '1' : '0').append('|')
                    .append(fmtF(p.x())).append('|')
                    .append(fmtF(p.y())).append('|')
                    .append(fmtF(p.vx())).append('|')
                    .append(fmtF(p.vy())).append('|')
                    .append(fmtF(p.facing()));
        }
        return sb.toString();
    }

    public static ProjectileSpawnBatch decodeProjSpawns(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] lines = body.split("\n");
        if (lines.length == 0) {
            return null;
        }
        List<String> hdr = CoopDelimited.split(lines[0]);
        if (hdr.size() < 2 || !"PS".equals(hdr.get(0))) {
            return null;
        }
        List<ProjectileSpawn> list = new ArrayList<>(Math.max(0, lines.length - 1));
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(lines[i]);
            if (f.size() >= 10) {
                list.add(new ProjectileSpawn(
                        parseInt(f.get(0), 0),
                        parseInt(f.get(1), 0),
                        parseInt(f.get(2), 0),
                        f.get(3),
                        "1".equals(f.get(4)),
                        parseFloat(f.get(5), 0f),
                        parseFloat(f.get(6), 0f),
                        parseFloat(f.get(7), 0f),
                        parseFloat(f.get(8), 0f),
                        parseFloat(f.get(9), 0f)));
            }
        }
        return new ProjectileSpawnBatch(hdr.get(1), list);
    }

    public static String encodeProjHits(ProjectileHitBatch batch) {
        StringBuilder sb = new StringBuilder(64 + batch.hits().size() * 40);
        sb.append("PH|").append(CoopDelimited.field(batch.battleId()));
        for (ProjectileHit h : batch.hits()) {
            sb.append('\n')
                    .append(h.projId()).append('|')
                    .append(h.hit() ? '1' : '0').append('|')
                    .append(fmtF(h.x())).append('|')
                    .append(fmtF(h.y())).append('|')
                    .append(fmtF(h.damage())).append('|')
                    .append(h.damageTypeOrdinal()).append('|')
                    .append(h.shieldHit() ? '1' : '0').append('|')
                    .append(h.targetShipNetId());
        }
        return sb.toString();
    }

    public static ProjectileHitBatch decodeProjHits(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] lines = body.split("\n");
        if (lines.length == 0) {
            return null;
        }
        List<String> hdr = CoopDelimited.split(lines[0]);
        if (hdr.size() < 2 || !"PH".equals(hdr.get(0))) {
            return null;
        }
        List<ProjectileHit> list = new ArrayList<>(Math.max(0, lines.length - 1));
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(lines[i]);
            if (f.size() >= 8) {
                list.add(new ProjectileHit(
                        parseInt(f.get(0), 0),
                        "1".equals(f.get(1)),
                        parseFloat(f.get(2), 0f),
                        parseFloat(f.get(3), 0f),
                        parseFloat(f.get(4), 0f),
                        parseInt(f.get(5), 0),
                        "1".equals(f.get(6)),
                        parseInt(f.get(7), 0)));
            }
        }
        return new ProjectileHitBatch(hdr.get(1), list);
    }

    // ---- COMBAT_MISSILE_STATE (chunked binary + Base64, 19 bytes/missile) -----------------------

    public static List<String> encodeMissileStateChunks(String battleId, int tickSeq,
                                                        List<MissileSample> missiles,
                                                        int maxBodyChars) {
        if (missiles == null || missiles.isEmpty()) {
            return List.of();
        }
        String prefix = CoopDelimited.field(battleId) + "|" + tickSeq + "|";
        int maxRaw = Math.max(64, ((Math.max(200, maxBodyChars) - prefix.length()) / 4) * 3 - 4);
        int perChunk = Math.max(1, (maxRaw - 2) / 19);
        List<String> out = new ArrayList<>();
        for (int start = 0; start < missiles.size(); start += perChunk) {
            int end = Math.min(missiles.size(), start + perChunk);
            int count = end - start;
            ByteBuffer buf = ByteBuffer.allocate(2 + count * 19).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) count);
            for (int i = start; i < end; i++) {
                MissileSample m = missiles.get(i);
                buf.putInt(m.projId());
                buf.putFloat(m.x());
                buf.putFloat(m.y());
                buf.putShort(clampShort(Math.round(m.vx() * 4f)));
                buf.putShort(clampShort(Math.round(m.vy() * 4f)));
                buf.putShort(packAngle16(m.facing()));
                buf.put((byte) (m.fizzling() ? 1 : 0));
            }
            out.add(prefix + Base64.getEncoder().encodeToString(buf.array()));
        }
        return out;
    }

    public static MissileStateBatch decodeMissileState(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 3) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(f.get(2).trim());
            if (raw.length < 2) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.getShort() & 0xFFFF;
            List<MissileSample> list = new ArrayList<>(Math.min(count, buf.remaining() / 19));
            for (int i = 0; i < count && buf.remaining() >= 19; i++) {
                list.add(new MissileSample(
                        buf.getInt(),
                        buf.getFloat(),
                        buf.getFloat(),
                        buf.getShort() / 4f,
                        buf.getShort() / 4f,
                        unpackAngle16(buf.getShort()),
                        buf.get() != 0));
            }
            return new MissileStateBatch(f.get(0), parseInt(f.get(1), 0), list);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- COMBAT_BEAMS (chunked binary + Base64, 29 bytes/beam) ----------------------------------

    public static List<String> encodeBeamChunks(String battleId, int tickSeq,
                                                List<BeamSample> beams, int maxBodyChars) {
        String prefix = CoopDelimited.field(battleId) + "|" + tickSeq + "|";
        if (beams == null || beams.isEmpty()) {
            // Send an explicit 0-beam packet so the client clears finished beams immediately
            ByteBuffer empty = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
            empty.putShort((short) 0);
            return List.of(prefix + Base64.getEncoder().encodeToString(empty.array()));
        }
        int maxRaw = Math.max(64, ((Math.max(200, maxBodyChars) - prefix.length()) / 4) * 3 - 4);
        int perChunk = Math.max(1, (maxRaw - 2) / 29);
        List<String> out = new ArrayList<>();
        for (int start = 0; start < beams.size(); start += perChunk) {
            int end = Math.min(beams.size(), start + perChunk);
            int count = end - start;
            ByteBuffer buf = ByteBuffer.allocate(2 + count * 29).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) count);
            for (int i = start; i < end; i++) {
                BeamSample b = beams.get(i);
                buf.putFloat(b.fromX());
                buf.putFloat(b.fromY());
                buf.putFloat(b.toX());
                buf.putFloat(b.toY());
                buf.putFloat(b.width());
                buf.putInt(b.coreArgb());
                buf.putInt(b.fringeArgb());
                buf.put((byte) (b.hitting() ? 1 : 0));
            }
            out.add(prefix + Base64.getEncoder().encodeToString(buf.array()));
        }
        return out;
    }

    public static BeamStateBatch decodeBeams(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 3) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(f.get(2).trim());
            if (raw.length < 2) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.getShort() & 0xFFFF;
            List<BeamSample> list = new ArrayList<>(Math.min(count, buf.remaining() / 29));
            for (int i = 0; i < count && buf.remaining() >= 29; i++) {
                list.add(new BeamSample(
                        buf.getFloat(), buf.getFloat(),
                        buf.getFloat(), buf.getFloat(),
                        buf.getFloat(), buf.getInt(), buf.getInt(),
                        buf.get() != 0));
            }
            return new BeamStateBatch(f.get(0), parseInt(f.get(1), 0), list);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- COMBAT_EVENT ---------------------------------------------------------------------------

    public static String encodeEvent(CombatEvent e) {
        return "CE|" + CoopDelimited.field(e.battleId()) + '|'
                + CoopDelimited.field(e.kind()) + '|'
                + e.argInt1() + '|' + e.argInt2() + '|'
                + fmtF(e.f1()) + '|' + fmtF(e.f2()) + '|'
                + fmtF(e.f3()) + '|' + fmtF(e.f4()) + '|'
                + fmtF(e.f5()) + '|' + CoopDelimited.field(e.text());
    }

    public static CombatEvent decodeEvent(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 11 || !"CE".equals(f.get(0))) {
            return null;
        }
        return new CombatEvent(
                f.get(1), f.get(2),
                parseInt(f.get(3), 0), parseInt(f.get(4), 0),
                parseFloat(f.get(5), 0f), parseFloat(f.get(6), 0f),
                parseFloat(f.get(7), 0f), parseFloat(f.get(8), 0f),
                parseFloat(f.get(9), 0f), f.get(10));
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static short packAngle16(float deg) {
        float norm = ((deg % 360f) + 360f) % 360f;
        return (short) Math.round((norm / 360f) * 65535f);
    }

    private static float unpackAngle16(short packed) {
        return ((packed & 0xFFFF) / 65535f) * 360f;
    }

    private static int packAngle8(float deg) {
        float norm = ((deg % 360f) + 360f) % 360f;
        return Math.round((norm / 360f) * 255f) & 0xFF;
    }

    private static float unpackAngle8(int packed) {
        return ((packed & 0xFF) / 255f) * 360f;
    }

    private static short clampShort(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String fmtF(float v) {
        if (!Float.isFinite(v)) {
            return "0";
        }
        float rounded = Math.round(v * 10f) / 10f;
        if (rounded == (long) rounded) {
            return Long.toString((long) rounded);
        }
        return Float.toString(rounded);
    }

    private static float parseFloat(String s, float fallback) {
        try {
            float v = Float.parseFloat(s.trim());
            return Float.isFinite(v) ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void splitCsv(String raw, List<String> out) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** Convenience helper: normalize an unmodifiable list copy safely. */
    public static <T> List<T> unmodifiableCopy(List<T> items) {
        return items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
    }
}
