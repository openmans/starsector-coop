package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Headless-testable state, JSON codec, and plugin filter for Baldur's Gate 3-style
 * live interactive dialog spectating (symmetric for both Host and Guest) with live partner
 * cursor tracking and option voting.
 */
public final class CoopDialogSpectatorState {

    public record OptionView(String text, boolean enabled) {
        public OptionView {
            text = text == null ? "" : text;
        }
    }

    public record SyncPayload(
            String dialogId,
            String speakerId,
            String speakerName,
            boolean open,
            String targetName,
            List<String> paragraphs,
            List<OptionView> options,
            int hoveredOptionIndex,
            float cursorNormX,
            float cursorNormY,
            int votedOptionIndex,
            String voterName) {

        public SyncPayload {
            dialogId = dialogId == null ? "" : dialogId;
            speakerId = speakerId == null ? "" : speakerId;
            speakerName = speakerName == null || speakerName.isBlank() ? "Partner" : speakerName.trim();
            targetName = targetName == null ? "" : targetName.trim();
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            options = options == null ? List.of() : List.copyOf(options);
            cursorNormX = clamp01(cursorNormX);
            cursorNormY = clamp01(cursorNormY);
            voterName = voterName == null ? "" : voterName.trim();
        }

        /** Structural key excluding cursor movement so the spectator panel only rebuilds on text/option changes. */
        public String structuralKey() {
            StringBuilder sb = new StringBuilder();
            sb.append(dialogId).append('|').append(speakerName).append('|').append(targetName)
                    .append('|').append(hoveredOptionIndex).append('|').append(votedOptionIndex)
                    .append('|').append(voterName).append('|');
            for (String p : paragraphs) {
                sb.append(p).append('\u001e');
            }
            sb.append('\u001d');
            for (OptionView o : options) {
                sb.append(o.text()).append(':').append(o.enabled() ? '1' : '0').append('\u001f');
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

    /** True when the remote partner has an open dialog that the spectator has not manually closed. */
    public boolean shouldAutoOpenSpectator() {
        SyncPayload current = remoteDialog;
        return current != null && current.open() && !current.dialogId().equals(dismissedDialogId);
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
            o.put("open", p.open());
            o.put("tgt", p.targetName());
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
            return new SyncPayload(
                    o.optString("id", ""),
                    o.optString("sid", ""),
                    o.optString("sn", "Partner"),
                    o.optBoolean("open", false),
                    o.optString("tgt", ""),
                    Collections.unmodifiableList(paras),
                    Collections.unmodifiableList(opts),
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
