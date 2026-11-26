package coop.campaign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-authoritative Contacts &amp; Contact-Mission replication and first-come claim arbitration
 * (Item 4).
 *
 * <p>Starsector's {@code ContactIntel} and {@code HubMission} objects are created locally on the
 * host; this authority captures a clean deterministic snapshot of contacts and their active
 * missions, encodes/decodes them for {@code CONTACT_SET}, and arbitrates concurrent claims so two
 * players cannot accept the same contact mission twice.
 */
public final class CoopContactAuthority {

    public enum ClaimResult {
        ACCEPTED,
        ALREADY_CLAIMED,
        NOT_FOUND
    }

    public record ContactMission(String missionId, String title, long rewardCredits,
                                 String claimedByPlayerId) {
        public ContactMission {
            missionId = clean(missionId);
            title = clean(title);
            rewardCredits = Math.max(0L, rewardCredits);
            claimedByPlayerId = clean(claimedByPlayerId);
        }

        public boolean isClaimed() {
            return !claimedByPlayerId.isEmpty();
        }
    }

    public record ContactRecord(String contactId, String name, String factionId,
                                String importance, String marketId,
                                List<ContactMission> missions) {
        public ContactRecord {
            contactId = clean(contactId);
            name = clean(name);
            factionId = clean(factionId);
            importance = clean(importance);
            marketId = clean(marketId);
            missions = missions == null ? List.of() : List.copyOf(missions);
        }
    }

    private final Map<String, ContactRecord> contacts = new LinkedHashMap<>();

    public synchronized void replaceAll(List<ContactRecord> incoming) {
        contacts.clear();
        if (incoming != null) {
            for (ContactRecord rec : incoming) {
                if (rec != null && !rec.contactId().isEmpty()) {
                    contacts.put(rec.contactId(), rec);
                }
            }
        }
    }

    public synchronized List<ContactRecord> snapshot() {
        return List.copyOf(contacts.values());
    }

    /** First-come mission claim arbitration across host and all guests. */
    public synchronized ClaimResult claimMission(String contactId, String missionId, String playerId) {
        String cid = clean(contactId);
        String mid = clean(missionId);
        String pid = clean(playerId);
        ContactRecord existing = contacts.get(cid);
        if (existing == null || mid.isEmpty() || pid.isEmpty()) {
            return ClaimResult.NOT_FOUND;
        }
        List<ContactMission> updated = new ArrayList<>(existing.missions().size());
        boolean found = false;
        for (ContactMission m : existing.missions()) {
            if (m.missionId().equals(mid)) {
                found = true;
                if (m.isClaimed()) {
                    return m.claimedByPlayerId().equals(pid)
                            ? ClaimResult.ACCEPTED : ClaimResult.ALREADY_CLAIMED;
                }
                updated.add(new ContactMission(m.missionId(), m.title(), m.rewardCredits(), pid));
            } else {
                updated.add(m);
            }
        }
        if (!found) {
            return ClaimResult.NOT_FOUND;
        }
        contacts.put(cid, new ContactRecord(existing.contactId(), existing.name(),
                existing.factionId(), existing.importance(), existing.marketId(), updated));
        return ClaimResult.ACCEPTED;
    }

    public synchronized void clear() {
        contacts.clear();
    }

    /** Compact wire encoding for {@code CONTACT_SET}. */
    public static String encode(List<ContactRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(records.size() * 64);
        for (ContactRecord c : records) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("C|")
                    .append(CoopDelimited.field(c.contactId())).append('|')
                    .append(CoopDelimited.field(c.name())).append('|')
                    .append(CoopDelimited.field(c.factionId())).append('|')
                    .append(CoopDelimited.field(c.importance())).append('|')
                    .append(CoopDelimited.field(c.marketId()));
            for (ContactMission m : c.missions()) {
                sb.append("\nM|")
                        .append(CoopDelimited.field(c.contactId())).append('|')
                        .append(CoopDelimited.field(m.missionId())).append('|')
                        .append(CoopDelimited.field(m.title())).append('|')
                        .append(m.rewardCredits()).append('|')
                        .append(CoopDelimited.field(m.claimedByPlayerId()));
            }
        }
        return sb.toString();
    }

    /** Decodes a {@code CONTACT_SET} payload back into immutable {@link ContactRecord}s. */
    public static List<ContactRecord> decode(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Map<String, ContactBuilder> builders = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            if (f.size() < 6) {
                continue;
            }
            if ("C".equals(f.get(0))) {
                String id = f.get(1);
                builders.put(id, new ContactBuilder(
                        id, f.get(2), f.get(3), f.get(4), f.get(5)));
            } else if ("M".equals(f.get(0))) {
                String cid = f.get(1);
                ContactBuilder b = builders.get(cid);
                if (b != null) {
                    long reward = parseLong(f.get(4));
                    b.missions.add(new ContactMission(
                            f.get(2), f.get(3), reward, f.get(5)));
                }
            }
        }
        List<ContactRecord> out = new ArrayList<>(builders.size());
        for (ContactBuilder b : builders.values()) {
            out.add(b.build());
        }
        return Collections.unmodifiableList(out);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class ContactBuilder {
        final String id;
        final String name;
        final String factionId;
        final String importance;
        final String marketId;
        final List<ContactMission> missions = new ArrayList<>();

        ContactBuilder(String id, String name, String factionId, String importance, String marketId) {
            this.id = Objects.requireNonNull(id);
            this.name = name;
            this.factionId = factionId;
            this.importance = importance;
            this.marketId = marketId;
        }

        ContactRecord build() {
            return new ContactRecord(id, name, factionId, importance, marketId, missions);
        }
    }
}
