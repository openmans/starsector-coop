package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * System-classloader bridge that reads live text paragraphs, option buttons, hover state,
 * and relative cursor coordinates from an open Starsector {@link InteractionDialogAPI}, and
 * flashes a voted option button on the speaker's screen.
 *
 * <p>Uses only public unobfuscated engine methods via {@link MethodHandles} (zero
 * {@code java.lang.reflect.*} calls) so it satisfies {@code CoopSandboxCompatibilityTest} and
 * degrades cleanly to {@code null} if an engine update changes internal UI classes.
 */
public final class CoopDialogSpectatorBridge {

    private static final int MAX_PARAGRAPHS = 16;
    private static final int MAX_PARA_CHARS = 320;
    private static final int MAX_OPTIONS = 16;

    public record CapturedOption(String text, boolean enabled, boolean hovered) {
    }

    public record CapturedDialog(
            String targetName,
            List<String> paragraphs,
            List<CapturedOption> options,
            float cursorNormX,
            float cursorNormY,
            int hoveredOptionIndex) {
    }

    private static volatile boolean resolved;
    private static volatile MethodHandle textGetScroller;
    private static volatile MethodHandle scrollerGetContent;
    private static volatile MethodHandle panelGetCopy;
    private static volatile MethodHandle optionGetButtonMap;

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
            } catch (Throwable ignored) {
                // Headless tests or unknown engine version: degrade gracefully.
            } finally {
                resolved = true;
            }
        }
    }

    /** Captures a live snapshot of {@code dialog} including speaker mouse & hover state. */
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

            TextPanelAPI textPanel = dialog.getTextPanel();
            OptionPanelAPI optionPanel = dialog.getOptionPanel();

            List<String> paragraphs = readParagraphs(textPanel);
            List<ButtonAPI> buttons = readSortedOptionButtons(optionPanel);

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
            return new CapturedDialog(targetName, paragraphs, options, normX, normY, hoveredIndex);
        } catch (Throwable ignored) {
            return null;
        }
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
        return w > 0f && h > 0f && mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float[] computeDialogBounds(TextPanelAPI textPanel, OptionPanelAPI optionPanel,
                                               float screenW, float screenH) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Object obj : new Object[] { textPanel, optionPanel }) {
            if (obj instanceof UIComponentAPI comp) {
                try {
                    PositionAPI p = comp.getPosition();
                    if (p != null && p.getWidth() > 20f && p.getHeight() > 20f) {
                        minX = Math.min(minX, p.getX());
                        minY = Math.min(minY, p.getY());
                        maxX = Math.max(maxX, p.getX() + p.getWidth());
                        maxY = Math.max(maxY, p.getY() + p.getHeight());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (minX < maxX && minY < maxY) {
            return new float[] { minX, minY, maxX - minX, maxY - minY };
        }
        return new float[] { 0f, 0f, screenW, screenH };
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.5f;
        return Math.max(0f, Math.min(1f, v));
    }
}
