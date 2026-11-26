package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.Locale;

/**
 * One-line notices in the campaign message feed (Phase 20.6), filtered by {@code coop.feedVerbosity}
 * ({@code all} | {@code important} | {@code minimal}).
 */
public final class CoopFeed {

    /** Message priority used to filter campaign feed lines against {@code coop.feedVerbosity}. */
    public enum Severity {
        INFO,
        IMPORTANT,
        CRITICAL
    }

    private CoopFeed() {
    }

    /** Evaluates whether a line of {@code severity} should appear under {@code verbosity}. */
    public static boolean shouldDisplay(Severity severity, String verbosity) {
        Severity sev = severity == null ? Severity.INFO : severity;
        String mode = verbosity == null ? "all" : verbosity.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "minimal" -> sev == Severity.CRITICAL;
            case "important" -> sev != Severity.INFO;
            default -> true;
        };
    }

    /** Posts one message, classifying severity automatically from color/content. */
    public static void post(String text, Color color) {
        Severity inferred = inferSeverity(text, color);
        post(text, color, inferred);
    }

    /** Posts one message if permitted by the local {@code coop.feedVerbosity} preference. */
    public static void post(String text, Color color, Severity severity) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String verbosity = "all";
        try {
            verbosity = CoopOptionsStore.system().string(CoopOptionsRegistry.FEED_VERBOSITY);
        } catch (RuntimeException | LinkageError ignored) {
            // default to all
        }
        if (!shouldDisplay(severity, verbosity)) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            if (color == null) {
                ui.addMessage(text);
            } else {
                ui.addMessage(text, color);
            }
        } catch (Throwable ex) {
            CoopLog.warn(CoopFeed.class, "Coop could not post a campaign feed message", ex);
        }
    }

    static Severity inferSeverity(String text, Color color) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("lost") || lower.contains("disconnect") || lower.contains("refused")
                || lower.contains("too large") || lower.contains("ended") || lower.contains("desync")) {
            return Severity.CRITICAL;
        }
        if (color != null || lower.contains("battle") || lower.contains("credits")
                || lower.contains("bounty") || lower.contains("colony") || lower.contains("chat")) {
            return Severity.IMPORTANT;
        }
        return Severity.INFO;
    }
}
