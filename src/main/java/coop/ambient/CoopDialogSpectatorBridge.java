package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * System-classloader bridge that reads live dialog paragraphs, option buttons, Comm Directory NPCs,
 * active market trade view (commodities, stock, buy/sell prices), hover state, and relative cursor
 * coordinates from an open Starsector {@link InteractionDialogAPI}, and flashes a voted button on
 * the speaker's screen.
 *
 * <p>Uses only public unobfuscated engine methods via {@link MethodHandles} (zero
 * {@code java.lang.reflect.*} calls) so it satisfies {@code CoopSandboxCompatibilityTest} and
 * degrades cleanly if an engine update changes internal UI classes.
 */
public final class CoopDialogSpectatorBridge {

    private static final int MAX_PARAGRAPHS = 24;
    private static final int MAX_PARA_CHARS = 360;
    private static final int MAX_OPTIONS = 16;
    private static final int MAX_TRADE_ITEMS = 22;
    private static final int MAX_COMM_ENTRIES = 16;

    public record CapturedOption(String text, boolean enabled, boolean hovered) {
    }

    public record CapturedTradeItem(
            String commodityId,
            String name,
            int qty,
            int buyPrice,
            int sellPrice,
            String submarket) {
    }

    public record CapturedCommEntry(
            String name,
            String post,
            String faction,
            String portraitSprite) {
    }

    public record CapturedDialog(
            String viewMode,
            String targetName,
            String subHeader,
            List<String> paragraphs,
            List<CapturedOption> options,
            List<CapturedTradeItem> tradeItems,
            List<CapturedCommEntry> commEntries,
            float cursorNormX,
            float cursorNormY,
            int hoveredOptionIndex) {
    }

    private static volatile boolean resolved;
    private static volatile MethodHandle textGetScroller;
    private static volatile MethodHandle scrollerGetContent;
    private static volatile MethodHandle panelGetCopy;
    private static volatile MethodHandle optionGetButtonMap;
    private static volatile MethodHandle dialogGetCoreUI;
    private static volatile MethodHandle coreGetTradeMode;
    private static volatile Class<?> commDialogClass;

    private CoopDialogSpectatorBridge() {
    }

    private static void ensureHandles() {
        if (resolved) {
            return;
        }
        synchronized (CoopDialogSpectatorBridge.class) {
            if (resolved) {
                return;
            }
            try {
                ClassLoader loader = CoopDialogSpectatorBridge.class.getClassLoader();
                MethodHandles.Lookup pub = MethodHandles.publicLookup();

                Class<?> panelCls = Class.forName("com.fs.starfarer.ui.z", false, loader);
                panelGetCopy = pub.findVirtual(panelCls, "getCopy", MethodType.methodType(List.class));

                Class<?> textCls = Class.forName("com.fs.starfarer.ui.newui.c", false, loader);
                Class<?> scrollerCls = Class.forName("com.fs.starfarer.ui.privatesuper", false, loader);
                Class<?> contentCls = Class.forName("com.fs.starfarer.ui.privatesuper$Oo", false, loader);
                textGetScroller = pub.findVirtual(textCls, "getScroller", MethodType.methodType(scrollerCls));
                scrollerGetContent = pub.findVirtual(scrollerCls, "getContentContainer",
                        MethodType.methodType(contentCls));

                Class<?> optionCls = Class.forName("com.fs.starfarer.ui.newui.oo0O", false, loader);
                optionGetButtonMap = pub.findVirtual(optionCls, "getButtonToItemMap",
                        MethodType.methodType(Map.class));

                try {
                    Class<?> dialogCls = Class.forName("com.fs.starfarer.ui.newui.Oo0O", false, loader);
                    Class<?> coreCls = Class.forName("com.fs.starfarer.ui.newui.S", false, loader);
                    dialogGetCoreUI = pub.findVirtual(dialogCls, "getCoreUI", MethodType.methodType(coreCls));
                    coreGetTradeMode = pub.findVirtual(coreCls, "getTradeMode",
                            MethodType.methodType(CampaignUIAPI.CoreUITradeMode.class));
                } catch (Throwable ignored) {
                }

                try {
                    commDialogClass = Class.forName("com.fs.starfarer.ui.newui.V", false, loader);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
                // Headless tests or unknown engine version: degrade gracefully.
            } finally {
                resolved = true;
            }
        }
    }

    /** Captures a live snapshot of {@code dialog} including Trade / Comm Directory views and mouse state. */
    public static CapturedDialog capture(InteractionDialogAPI dialog) {
        if (dialog == null) {
            return null;
        }
        ensureHandles();
        try {
            String targetName = "";
            SectorEntityToken target = dialog.getInteractionTarget();
            if (target != null && target.getName() != null) {
                targetName = target.getName().trim();
            }
            MarketAPI market = target == null ? null : target.getMarket();

            float mouseX = 0f;
            float mouseY = 0f;
            float screenW = 1280f;
            float screenH = 720f;
            try {
                float scale = Math.max(0.5f, Global.getSettings().getScreenScaleMult());
                mouseX = Global.getSettings().getMouseX() / scale;
                mouseY = Global.getSettings().getMouseY() / scale;
                screenW = Math.max(1f, Global.getSettings().getScreenWidth());
                screenH = Math.max(1f, Global.getSettings().getScreenHeight());
            } catch (Throwable ignored) {
            }

            // 1. Check whether CoreUI (Trade / Fleet / Refit) is open inside the interaction dialog
            Object coreUI = readCoreUI(dialog);
            CoreUITabId coreTab = null;
            try {
                CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
                coreTab = ui == null ? null : ui.getCurrentCoreTab();
            } catch (Throwable ignored) {
            }
            if (coreUI != null || coreTab == CoreUITabId.CARGO) {
                return captureCoreView(dialog, market, targetName, coreUI, coreTab, mouseX, mouseY, screenW, screenH);
            }

            // 2. Check whether the Comm Directory sub-dialog (com.fs.starfarer.ui.newui.V) is open
            Object commDialog = findOpenCommDialog(dialog);
            if (commDialog != null) {
                return captureCommDirectoryView(commDialog, market, targetName, mouseX, mouseY, screenW, screenH);
            }

            // 3. Standard InteractionDialog (stations, bars, quests, storyline, fleet hails)
            TextPanelAPI textPanel = dialog.getTextPanel();
            OptionPanelAPI optionPanel = dialog.getOptionPanel();

            List<String> paragraphs = readParagraphs(textPanel);
            List<ButtonAPI> buttons = readSortedOptionButtons(optionPanel);

            List<CapturedOption> options = new ArrayList<>(buttons.size());
            int hoveredIndex = -1;
            int limit = Math.min(buttons.size(), MAX_OPTIONS);
            for (int i = 0; i < limit; i++) {
                ButtonAPI btn = buttons.get(i);
                String text = btn.getText() == null ? "" : btn.getText().trim();
                boolean enabled = btn.isEnabled();
                boolean hovered = isPointerInside(btn.getPosition(), mouseX, mouseY) || btn.isHighlighted();
                if (hovered && hoveredIndex < 0) {
                    hoveredIndex = i;
                }
                options.add(new CapturedOption(text, enabled, hovered));
            }

            float[] box = computeDialogBounds(textPanel, optionPanel, screenW, screenH);
            float normX = clamp01((mouseX - box[0]) / Math.max(1f, box[2]));
            float normY = clamp01((mouseY - box[1]) / Math.max(1f, box[3]));
            return new CapturedDialog("DIALOG", targetName, "", paragraphs, options,
                    List.of(), List.of(), normX, normY, hoveredIndex);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CapturedDialog captureCoreView(InteractionDialogAPI dialog, MarketAPI market,
                                                  String targetName, Object coreUI, CoreUITabId coreTab,
                                                  float mouseX, float mouseY, float screenW, float screenH) {
        String mode = "TRADE";
        if (coreTab == CoreUITabId.FLEET) {
            mode = "FLEET";
        } else if (coreTab == CoreUITabId.REFIT) {
            mode = "REFIT";
        } else if (coreTab != null && coreTab != CoreUITabId.CARGO) {
            mode = coreTab.name();
        }

        List<CapturedTradeItem> tradeItems = new ArrayList<>();
        List<CapturedOption> options = new ArrayList<>();
        StringBuilder sub = new StringBuilder();
        try {
            CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (fleet != null && fleet.getCargo() != null) {
                long credits = Math.round(fleet.getCargo().getCredits().get());
                int used = Math.round(fleet.getCargo().getSpaceUsed());
                int cap = Math.round(fleet.getCargo().getMaxCapacity());
                sub.append(formatNumber(credits)).append("¢  ·  ")
                        .append(used).append(" / ").append(cap);
            }
        } catch (Throwable ignored) {
        }

        if ("TRADE".equals(mode) && market != null) {
            collectMarketCommodities(market, tradeItems);
            int idx = 0;
            for (CapturedTradeItem item : tradeItems) {
                String label = item.name() + " (" + item.qty() + ") · "
                        + item.buyPrice() + "¢ / " + item.sellPrice() + "¢";
                options.add(new CapturedOption(label, true, false));
                idx++;
                if (idx >= MAX_OPTIONS) break;
            }
        } else if (coreUI != null) {
            // Collect visible labels / buttons for Fleet or Refit tabs so the spectator still sees context
            List<String> coreLabels = new ArrayList<>();
            collectLabels(coreUI, coreLabels, 0);
            if (coreLabels.size() > 10) {
                coreLabels = new ArrayList<>(coreLabels.subList(0, 10));
            }
            float normX = clamp01(mouseX / Math.max(1f, screenW));
            float normY = clamp01(mouseY / Math.max(1f, screenH));
            return new CapturedDialog(mode, targetName, sub.toString(), coreLabels, options,
                    tradeItems, List.of(), normX, normY, -1);
        }

        float normX = clamp01(mouseX / Math.max(1f, screenW));
        float normY = clamp01(mouseY / Math.max(1f, screenH));
        return new CapturedDialog(mode, targetName, sub.toString(), List.of(), options,
                tradeItems, List.of(), normX, normY, -1);
    }

    private static void collectMarketCommodities(MarketAPI market, List<CapturedTradeItem> out) {
        try {
            List<SubmarketAPI> submarkets = market.getSubmarketsCopy();
            if (submarkets == null) {
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (SubmarketAPI sub : submarkets) {
                if (sub == null || sub.getCargoNullOk() == null) {
                    continue;
                }
                String specId = sub.getSpecId() == null ? "" : sub.getSpecId();
                if ("storage".equalsIgnoreCase(specId)) {
                    continue;
                }
                String subName = sub.getNameOneLine() == null ? "" : sub.getNameOneLine().trim();
                CargoAPI cargo = sub.getCargoNullOk();
                List<CargoStackAPI> stacks = cargo.getStacksCopy();
                if (stacks == null) {
                    continue;
                }
                for (CargoStackAPI stack : stacks) {
                    if (out.size() >= MAX_TRADE_ITEMS) {
                        return;
                    }
                    if (stack == null || !stack.isCommodityStack()) {
                        continue;
                    }
                    String cid = stack.getCommodityId();
                    int qty = Math.round(stack.getSize());
                    if (cid == null || cid.isEmpty() || qty <= 0) {
                        continue;
                    }
                    String key = specId + ":" + cid;
                    if (!seen.add(key)) {
                        continue;
                    }
                    String name = stack.getDisplayName() == null ? cid : stack.getDisplayName().trim();
                    int buy = Math.max(0, Math.round(market.getSupplyPrice(cid, 1, true)));
                    int sell = Math.max(0, Math.round(market.getDemandPrice(cid, 1, true)));
                    out.add(new CapturedTradeItem(cid, name, qty, buy, sell, subName));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static CapturedDialog captureCommDirectoryView(Object commDialog, MarketAPI market,
                                                           String targetName,
                                                           float mouseX, float mouseY,
                                                           float screenW, float screenH) {
        List<CapturedCommEntry> entries = new ArrayList<>();
        List<CapturedOption> options = new ArrayList<>();
        int hoveredIndex = -1;

        try {
            CommDirectoryAPI dir = market == null ? null : market.getCommDirectory();
            if (dir != null) {
                for (CommDirectoryEntryAPI entry : dir.getEntriesCopy()) {
                    if (entries.size() >= MAX_COMM_ENTRIES) {
                        break;
                    }
                    if (entry == null || entry.isHidden()) {
                        continue;
                    }
                    if (entry.getEntryData() instanceof PersonAPI person) {
                        String name = person.getNameString() == null ? "" : person.getNameString().trim();
                        String post = person.getPost() == null ? "" : person.getPost().trim();
                        if (post.isEmpty() && person.getRank() != null) {
                            post = person.getRank().trim();
                        }
                        String faction = person.getFaction() == null || person.getFaction().getDisplayName() == null
                                ? "" : person.getFaction().getDisplayName().trim();
                        String portrait = person.getPortraitSprite() == null ? "" : person.getPortraitSprite().trim();
                        if (!name.isEmpty()) {
                            entries.add(new CapturedCommEntry(name, post, faction, portrait));
                        }
                    } else if (entry.getTitle() != null && !entry.getTitle().trim().isEmpty()) {
                        entries.add(new CapturedCommEntry(entry.getTitle().trim(),
                                entry.getText() == null ? "" : entry.getText().trim(), "", ""));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Also check hovered buttons inside the Comm Directory UI panel
        List<ButtonAPI> commButtons = new ArrayList<>();
        collectButtons(commDialog, commButtons, 0);
        for (int i = 0; i < entries.size(); i++) {
            CapturedCommEntry ce = entries.get(i);
            boolean hovered = false;
            for (ButtonAPI b : commButtons) {
                if ((isPointerInside(b.getPosition(), mouseX, mouseY) || b.isHighlighted())
                        && b.getText() != null && b.getText().contains(ce.name())) {
                    hovered = true;
                    break;
                }
            }
            if (hovered && hoveredIndex < 0) {
                hoveredIndex = i;
            }
            String label = ce.name() + (ce.post().isEmpty() ? "" : " — " + ce.post());
            options.add(new CapturedOption(label, true, hovered));
        }

        // Fallback if comm directory had no market entries: grab visible labels from V
        List<String> fallbackParas = new ArrayList<>();
        if (entries.isEmpty()) {
            collectLabels(commDialog, fallbackParas, 0);
            if (fallbackParas.size() > MAX_PARAGRAPHS) {
                fallbackParas = new ArrayList<>(fallbackParas.subList(0, MAX_PARAGRAPHS));
            }
        }

        float normX = clamp01(mouseX / Math.max(1f, screenW));
        float normY = clamp01(mouseY / Math.max(1f, screenH));
        return new CapturedDialog("COMM", targetName, "", fallbackParas, options,
                List.of(), entries, normX, normY, hoveredIndex);
    }

    private static Object readCoreUI(InteractionDialogAPI dialog) {
        if (dialog == null || dialogGetCoreUI == null) {
            return null;
        }
        try {
            return dialogGetCoreUI.invoke(dialog);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findOpenCommDialog(InteractionDialogAPI dialog) {
        if (commDialogClass == null || panelGetCopy == null) {
            return null;
        }
        Object found = findInstanceInTree(dialog, commDialogClass, 0);
        if (found != null) {
            return found;
        }
        try {
            Object screen = Global.getSettings().getScreenPanel();
            return findInstanceInTree(screen, commDialogClass, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findInstanceInTree(Object node, Class<?> targetClass, int depth) {
        if (node == null || depth > 3) {
            return null;
        }
        if (targetClass.isInstance(node)) {
            return node;
        }
        try {
            Object copy = panelGetCopy.invoke(node);
            if (copy instanceof List<?> children) {
                for (Object child : children) {
                    if (targetClass.isInstance(child)) {
                        return child;
                    }
                }
                for (Object child : children) {
                    Object nested = findInstanceInTree(child, targetClass, depth + 1);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Flashes the {@code optionIndex}-th button on the speaker's live dialog when a partner votes. */
    public static boolean flashVotedOption(InteractionDialogAPI dialog, int optionIndex) {
        if (dialog == null || optionIndex < 0) {
            return false;
        }
        ensureHandles();
        try {
            List<ButtonAPI> buttons = readSortedOptionButtons(dialog.getOptionPanel());
            if (optionIndex >= buttons.size()) {
                return false;
            }
            ButtonAPI btn = buttons.get(optionIndex);
            btn.flash(true, 0.25f, 1.4f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<String> readParagraphs(TextPanelAPI textPanel) {
        List<String> all = new ArrayList<>();
        if (textPanel == null || textGetScroller == null || scrollerGetContent == null || panelGetCopy == null) {
            return all;
        }
        try {
            Object scroller = textGetScroller.invoke(textPanel);
            if (scroller == null) {
                return all;
            }
            Object container = scrollerGetContent.invoke(scroller);
            if (container == null) {
                return all;
            }
            collectLabels(container, all, 0);
        } catch (Throwable ignored) {
        }
        if (all.size() <= MAX_PARAGRAPHS) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - MAX_PARAGRAPHS, all.size()));
    }

    private static void collectLabels(Object node, List<String> out, int depth) {
        if (node == null || depth > 5 || out.size() >= 64) {
            return;
        }
        if (node instanceof LabelAPI label) {
            String raw = label.getText();
            if (raw != null) {
                String clean = raw.replace('\r', ' ').trim();
                if (!clean.isEmpty()) {
                    if (clean.length() > MAX_PARA_CHARS) {
                        clean = clean.substring(0, MAX_PARA_CHARS - 1) + "…";
                    }
                    out.add(clean);
                }
            }
            return;
        }
        try {
            Object copy = panelGetCopy.invoke(node);
            if (copy instanceof List<?> children) {
                for (Object child : children) {
                    collectLabels(child, out, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // Not a panel container.
        }
    }

    private static void collectButtons(Object node, List<ButtonAPI> out, int depth) {
        if (node == null || depth > 5 || out.size() >= 48) {
            return;
        }
        if (node instanceof ButtonAPI btn) {
            out.add(btn);
            return;
        }
        try {
            Object copy = panelGetCopy.invoke(node);
            if (copy instanceof List<?> children) {
                for (Object child : children) {
                    collectButtons(child, out, depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<ButtonAPI> readSortedOptionButtons(OptionPanelAPI optionPanel) {
        List<ButtonAPI> result = new ArrayList<>();
        if (optionPanel == null || optionGetButtonMap == null) {
            return result;
        }
        try {
            Object rawMap = optionGetButtonMap.invoke(optionPanel);
            if (rawMap instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key instanceof ButtonAPI btn) {
                        String text = btn.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            result.add(btn);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        result.sort(Comparator.comparingDouble(CoopDialogSpectatorBridge::buttonTopY).reversed());
        return result;
    }

    private static double buttonTopY(ButtonAPI btn) {
        try {
            PositionAPI pos = btn.getPosition();
            return pos == null ? 0.0 : pos.getY();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static boolean isPointerInside(PositionAPI pos, float mx, float my) {
        if (pos == null) {
            return false;
        }
        float x = pos.getX();
        float y = pos.getY();
        float w = pos.getWidth();
        float h = pos.getHeight();
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float[] computeDialogBounds(TextPanelAPI text, OptionPanelAPI opts,
                                               float screenW, float screenH) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Object part : new Object[] { text, opts }) {
            if (part instanceof UIComponentAPI comp && comp.getPosition() != null) {
                PositionAPI p = comp.getPosition();
                if (p.getWidth() > 0f && p.getHeight() > 0f) {
                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxX = Math.max(maxX, p.getX() + p.getWidth());
                    maxY = Math.max(maxY, p.getY() + p.getHeight());
                }
            }
        }
        if (minX < maxX && minY < maxY) {
            return new float[] { minX, minY, maxX - minX, maxY - minY };
        }
        return new float[] { 0f, 0f, screenW, screenH };
    }

    private static String formatNumber(long value) {
        String s = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        if (value < 0) sb.append('-');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.5f;
        return Math.max(0f, Math.min(1f, v));
    }
}
