package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless-testable state, JSON codec, same-system proximity filter, and numeric highlight
 * extractor for Baldur's Gate 3-style live interactive dialog, trade, and Comm Directory spectating.
 */
public final class CoopDialogSpectatorState {

    /**
     * Highlights numeric values with optional currency/day/unit/percent suffixes in RU and EN
     * (e.g. "12 500¢", "15 дней", "240 ед.", "30 days", "50,000 credits", "+25%", "420").
     */
    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile(
            "[+-]?\\d[\\d\\s,.]*\\d(?:\\s*(?:¢|%|credits?\\b|CR\\b|кредит(?:ов|а)?\\b|дн(?:ей|я|и)\\b|день\\b|days?\\b|цикл(?:ов|а)?\\b|cycles?\\b|ед\\.?|units?\\b|шт\\.?|т\\b))?"
                    + "|[+-]?\\d+(?:\\s*(?:¢|%|credits?\\b|CR\\b|кредит(?:ов|а)?\\b|дн(?:ей|я|и)\\b|день\\b|days?\\b|цикл(?:ов|а)?\\b|cycles?\\b|ед\\.?|units?\\b|шт\\.?|т\\b))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    public record OptionView(String text, boolean enabled) {
        public OptionView {
            text = text == null ? "" : text;
        }
    }

    public record TradeItemView(
            String commodityId,
            String name,
            int qty,
            int buyPrice,
            int sellPrice,
            String submarket) {
        public TradeItemView {
            commodityId = commodityId == null ? "" : commodityId;
            name = name == null ? "" : name;
            submarket = submarket == null ? "" : submarket;
        }
    }

    public record CommEntryView(
            String name,
            String post,
            String faction,
            String portraitSprite) {
        public CommEntryView {
            name = name == null ? "" : name;
            post = post == null ? "" : post;
            faction = faction == null ? "" : faction;
            portraitSprite = portraitSprite == null ? "" : portraitSprite;
        }
    }

    public record SyncPayload(
            String dialogId,
            String speakerId,
            String speakerName,
            String locationId,
            String viewMode,
            boolean open,
            String targetName,
            String subHeader,
            List<String> paragraphs,
            List<OptionView> options,
            List<TradeItemView> tradeItems,
            List<CommEntryView> commEntries,
            int hoveredOptionIndex,
            float cursorNormX,
            float cursorNormY,
            int votedOptionIndex,
            String voterName) {

        public SyncPayload {
            dialogId = dialogId == null ? "" : dialogId;
            speakerId = speakerId == null ? "" : speakerId;
            speakerName = speakerName == null || speakerName.isBlank() ? "Partner" : speakerName.trim();
            locationId = locationId == null ? "" : locationId.trim();
            viewMode = viewMode == null || viewMode.isBlank() ? "DIALOG" : viewMode.trim();
            targetName = targetName == null ? "" : targetName.trim();
            subHeader = subHeader == null ? "" : subHeader.trim();
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            options = options == null ? List.of() : List.copyOf(options);
            tradeItems = tradeItems == null ? List.of() : List.copyOf(tradeItems);
            commEntries = commEntries == null ? List.of() : List.copyOf(commEntries);
            cursorNormX = clamp01(cursorNormX);
            cursorNormY = clamp01(cursorNormY);
            voterName = voterName == null ? "" : voterName.trim();
        }

        /** Structural key excluding cursor movement so the spectator panel only rebuilds on content changes. */
        public String structuralKey() {
            StringBuilder sb = new StringBuilder();
            sb.append(dialogId).append('|').append(locationId).append('|').append(viewMode)
                    .append('|').append(speakerName).append('|').append(targetName)
                    .append('|').append(subHeader)
                    .append('|').append(hoveredOptionIndex).append('|').append(votedOptionIndex)
                    .append('|').append(voterName).append('|');
            for (String p : paragraphs) {
                sb.append(p).append('\u001e');
            }
            sb.append('\u001d');
            for (OptionView o : options) {
                sb.append(o.text()).append(':').append(o.enabled() ? '1' : '0').append('\u001f');
            }
            sb.append('\u001c');
            for (TradeItemView t : tradeItems) {
                sb.append(t.commodityId()).append(':').append(t.qty()).append(':')
                        .append(t.buyPrice()).append(':').append(t.sellPrice()).append('\u001f');
            }
            sb.append('\u001b');
            for (CommEntryView c : commEntries) {
                sb.append(c.name()).append(':').append(c.post()).append('\u001f');
            }
            return sb.toString();
        }
    }

    public record VotePayload(
            String dialogId,
            String voterName,
            int optionIndex,
            String optionText) {
        public VotePayload {
            dialogId = dialogId == null ? "" : dialogId;
            voterName = voterName == null || voterName.isBlank() ? "Partner" : voterName.trim();
            optionText = optionText == null ? "" : optionText.trim();
        }
    }

    private static final long VOTE_BANNER_TTL_MILLIS = 12_000L;

    private volatile SyncPayload remoteDialog;
    private volatile String dismissedDialogId = "";
    private volatile int localVotedIndex = -1;
    private volatile String localVotedDialogId = "";

    // Speaker-side incoming partner vote state
    private volatile String activeVoteDialogId = "";
    private volatile int incomingVotedIndex = -1;
    private volatile String incomingVoterName = "";
    private volatile String incomingVotedOptionText = "";
    private volatile long incomingVoteExpiresAtMillis;

    /**
     * Returns {@code true} only for real Starsector gameplay dialogs (stations, bars, quests,
     * storyline, fleets, ruins, mod dialogs) — never our own internal CO-OP dialogs.
     */
    public static boolean isSpectatablePlugin(InteractionDialogPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        String className = plugin.getClass().getName();
        return !className.startsWith("coop.ui.");
    }

    /** Formats the centered top header requested by the player. */
    public static String headerTitle(String speakerName) {
        String clean = speakerName == null || speakerName.isBlank()
                ? CoopUiLang.tr("Partner", "Напарник") : speakerName.trim();
        return CoopUiLang.tr("Dialog of \"" + clean + "\"",
                "Диалог игрока «" + clean + "»");
    }

    /**
     * Extracts numeric highlights (credits, days, units, percentages, plain counts) from dialog text
     * for {@code TooltipMakerAPI.addPara(..., highlightColor, highlights)}.
     */
    public static List<String> extractNumberHighlights(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = HIGHLIGHT_PATTERN.matcher(text);
        while (m.find() && found.size() < 24) {
            String token = m.group().trim();
            if (!token.isEmpty()) {
                found.add(token);
            }
        }
        return new ArrayList<>(found);
    }

    public synchronized void applyRemoteSync(SyncPayload payload) {
        if (payload == null) {
            return;
        }
        if (!payload.open()) {
            if (remoteDialog != null && (payload.dialogId().isEmpty()
                    || payload.dialogId().equals(remoteDialog.dialogId()))) {
                remoteDialog = null;
            }
            return;
        }
        boolean newDialog = remoteDialog == null || !payload.dialogId().equals(remoteDialog.dialogId());
        if (newDialog) {
            localVotedIndex = -1;
            localVotedDialogId = payload.dialogId();
        }
        remoteDialog = payload;
    }

    public SyncPayload activeRemoteDialog() {
        return remoteDialog;
    }

    /**
     * True ONLY when the remote partner has an open dialog inside the exact same non-hyperspace
     * star system as {@code localSystemId}, and the spectator has not manually dismissed it.
     */
    public boolean shouldAutoOpenSpectator(String localSystemId) {
        SyncPayload current = remoteDialog;
        if (current == null || !current.open()) {
            return false;
        }
        if (current.dialogId().equals(dismissedDialogId)) {
            return false;
        }
        if (localSystemId == null || localSystemId.isBlank() || current.locationId().isEmpty()) {
            return false;
        }
        return localSystemId.trim().equals(current.locationId());
    }

    public synchronized void markDismissedByUser(String dialogId) {
        dismissedDialogId = dialogId == null ? "" : dialogId;
    }

    public synchronized void recordLocalVote(String dialogId, int optionIndex) {
        localVotedDialogId = dialogId == null ? "" : dialogId;
        localVotedIndex = optionIndex;
    }

    public int localVotedIndexFor(String dialogId) {
        return Objects.equals(localVotedDialogId, dialogId) ? localVotedIndex : -1;
    }

    public synchronized void recordIncomingVote(VotePayload vote, long nowMillis) {
        if (vote == null) {
            return;
        }
        activeVoteDialogId = vote.dialogId();
        incomingVotedIndex = vote.optionIndex();
        incomingVoterName = vote.voterName();
        incomingVotedOptionText = vote.optionText();
        incomingVoteExpiresAtMillis = nowMillis + VOTE_BANNER_TTL_MILLIS;
    }

    public synchronized void clearIncomingVote() {
        activeVoteDialogId = "";
        incomingVotedIndex = -1;
        incomingVoterName = "";
        incomingVotedOptionText = "";
        incomingVoteExpiresAtMillis = 0L;
    }

    public int incomingVotedIndexFor(String dialogId) {
        return Objects.equals(activeVoteDialogId, dialogId) ? incomingVotedIndex : -1;
    }

    public String incomingVoterNameFor(String dialogId) {
        return Objects.equals(activeVoteDialogId, dialogId) ? incomingVoterName : "";
    }

    /** Live vote notice for the speaker's HUD, or {@code ""} when none is active. */
    public String speakerVoteBanner(long nowMillis) {
        if (incomingVotedIndex < 0 || nowMillis > incomingVoteExpiresAtMillis) {
            return "";
        }
        String opt = incomingVotedOptionText.isEmpty()
                ? ("#" + (incomingVotedIndex + 1)) : incomingVotedOptionText;
        return CoopUiLang.tr(
                "[Vote] " + incomingVoterName + " suggests: \"" + opt + "\"",
                "[Голос] " + incomingVoterName + " советует: «" + opt + "»");
    }

    public synchronized void reset() {
        remoteDialog = null;
        dismissedDialogId = "";
        localVotedIndex = -1;
        localVotedDialogId = "";
        clearIncomingVote();
    }

    // ---- JSON wire codec ----

    public static String encodeSync(SyncPayload p) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", p.dialogId());
            o.put("sid", p.speakerId());
            o.put("sn", p.speakerName());
            o.put("loc", p.locationId());
            o.put("vm", p.viewMode());
            o.put("open", p.open());
            o.put("tgt", p.targetName());
            o.put("sh", p.subHeader());
            o.put("hv", p.hoveredOptionIndex());
            o.put("mx", Math.round(p.cursorNormX() * 1000f));
            o.put("my", Math.round(p.cursorNormY() * 1000f));
            o.put("vi", p.votedOptionIndex());
            o.put("vn", p.voterName());

            JSONArray paras = new JSONArray();
            for (String line : p.paragraphs()) {
                paras.put(line);
            }
            o.put("p", paras);

            JSONArray opts = new JSONArray();
            for (OptionView opt : p.options()) {
                JSONObject item = new JSONObject();
                item.put("t", opt.text());
                item.put("e", opt.enabled());
                opts.put(item);
            }
            o.put("o", opts);

            if (!p.tradeItems().isEmpty()) {
                JSONArray tr = new JSONArray();
                for (TradeItemView t : p.tradeItems()) {
                    JSONObject item = new JSONObject();
                    item.put("c", t.commodityId());
                    item.put("n", t.name());
                    item.put("q", t.qty());
                    item.put("b", t.buyPrice());
                    item.put("s", t.sellPrice());
                    item.put("m", t.submarket());
                    tr.put(item);
                }
                o.put("tr", tr);
            }

            if (!p.commEntries().isEmpty()) {
                JSONArray cm = new JSONArray();
                for (CommEntryView c : p.commEntries()) {
                    JSONObject item = new JSONObject();
                    item.put("n", c.name());
                    item.put("p", c.post());
                    item.put("f", c.faction());
                    item.put("i", c.portraitSprite());
                    cm.put(item);
                }
                o.put("cm", cm);
            }
            return o.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    public static SyncPayload decodeSync(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(body);
            List<String> paras = new ArrayList<>();
            JSONArray pa = o.optJSONArray("p");
            if (pa != null) {
                for (int i = 0; i < pa.length(); i++) {
                    String s = pa.optString(i, "");
                    if (!s.isEmpty()) {
                        paras.add(s);
                    }
                }
            }
            List<OptionView> opts = new ArrayList<>();
            JSONArray oa = o.optJSONArray("o");
            if (oa != null) {
                for (int i = 0; i < oa.length(); i++) {
                    JSONObject item = oa.optJSONObject(i);
                    if (item != null) {
                        opts.add(new OptionView(item.optString("t", ""), item.optBoolean("e", true)));
                    }
                }
            }
            List<TradeItemView> tradeItems = new ArrayList<>();
            JSONArray ta = o.optJSONArray("tr");
            if (ta != null) {
                for (int i = 0; i < ta.length(); i++) {
                    JSONObject item = ta.optJSONObject(i);
                    if (item != null) {
                        tradeItems.add(new TradeItemView(
                                item.optString("c", ""),
                                item.optString("n", ""),
                                item.optInt("q", 0),
                                item.optInt("b", 0),
                                item.optInt("s", 0),
                                item.optString("m", "")));
                    }
                }
            }
            List<CommEntryView> commEntries = new ArrayList<>();
            JSONArray ca = o.optJSONArray("cm");
            if (ca != null) {
                for (int i = 0; i < ca.length(); i++) {
                    JSONObject item = ca.optJSONObject(i);
                    if (item != null) {
                        commEntries.add(new CommEntryView(
                                item.optString("n", ""),
                                item.optString("p", ""),
                                item.optString("f", ""),
                                item.optString("i", "")));
                    }
                }
            }
            return new SyncPayload(
                    o.optString("id", ""),
                    o.optString("sid", ""),
                    o.optString("sn", "Partner"),
                    o.optString("loc", ""),
                    o.optString("vm", "DIALOG"),
                    o.optBoolean("open", false),
                    o.optString("tgt", ""),
                    o.optString("sh", ""),
                    Collections.unmodifiableList(paras),
                    Collections.unmodifiableList(opts),
                    Collections.unmodifiableList(tradeItems),
                    Collections.unmodifiableList(commEntries),
                    o.optInt("hv", -1),
                    o.optInt("mx", 500) / 1000f,
                    o.optInt("my", 500) / 1000f,
                    o.optInt("vi", -1),
                    o.optString("vn", ""));
        } catch (Exception ex) {
            return null;
        }
    }

    public static String encodeVote(VotePayload v) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", v.dialogId());
            o.put("vn", v.voterName());
            o.put("idx", v.optionIndex());
            o.put("txt", v.optionText());
            return o.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    public static VotePayload decodeVote(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(body);
            return new VotePayload(
                    o.optString("id", ""),
                    o.optString("vn", "Partner"),
                    o.optInt("idx", -1),
                    o.optString("txt", ""));
        } catch (Exception ex) {
            return null;
        }
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.5f;
        return Math.max(0f, Math.min(1f, v));
    }
}
