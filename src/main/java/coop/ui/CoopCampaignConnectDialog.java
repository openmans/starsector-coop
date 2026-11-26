package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.CoopModPlugin;
import coop.config.CoopOptionsStore;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.newgame.CoopWorldSettings;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * In-campaign native Host / Join dialog (opened via {@code F7} or {@code Intel -> Coop Session}).
 *
 * <p>Reuses {@link CoopTitleScreenOverlay}'s Host / Join layout inside a Starsector
 * {@link InteractionDialogAPI} so players can start hosting, copy an invite for the live sector,
 * join a host with matching seed, or disconnect without leaving the campaign.
 */
public final class CoopCampaignConnectDialog implements InteractionDialogPlugin {

    private static final float PANEL_W = 580f;
    private static final float PANEL_H = 490f;

    private InteractionDialogAPI dialog;
    private boolean rebuildPending;
    private boolean dismissed;

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideTextPanel();
            dialog.getOptionPanel().clearOptions();
        } catch (Throwable ignored) {
        }
        CoopInGameLauncherSession s = CoopInGameLauncherSession.shared();
        s.ensureInitialized();
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            s.setHostCampaignContext(
                    sector.getSeedString(),
                    CoopWorldSettings.currentSectorSize(),
                    CoopWorldSettings.currentSectorAge(),
                    "");
        }
        rebuildPanel();
    }

    @Override
    public void advance(float amount) {
        if (dismissed) {
            return;
        }
        String ip = CoopTitleScreenOverlay.pollPendingPublicIp();
        if (ip != null) {
            CoopInGameLauncherSession s = CoopInGameLauncherSession.shared();
            if (!ip.isEmpty()) {
                s.setHostAddress(ip);
                s.setStatus(s.tr("Detected IP: " + ip, "Определён IP: " + ip),
                        CoopTitleScreenOverlay.GREEN_BRIGHT);
            } else {
                s.setStatus(s.tr("Could not detect public IP.", "Не удалось определить IP."),
                        new Color(255, 185, 80));
            }
            rebuildPending = true;
        }
        if (rebuildPending) {
            rebuildPending = false;
            rebuildPanel();
        }
    }

    private void rebuildPanel() {
        if (dialog == null || dismissed) {
            return;
        }
        try {
            CoopInGameLauncherSession s = CoopInGameLauncherSession.shared();
            CustomPanelAPI panel = dialog.getVisualPanel().showCustomPanel(
                    PANEL_W, PANEL_H, new PanelPlugin());
            TooltipMakerAPI ui = panel.createUIElement(PANEL_W, PANEL_H, false);
            CoopTitleScreenOverlay.buildModalContents(ui, s, PANEL_W, true, liveBanner(s), null);
            panel.addUIElement(ui).inTL(0f, 0f);
        } catch (Throwable ex) {
            CoopLog.warn(CoopCampaignConnectDialog.class, "Could not render in-campaign CO-OP dialog", ex);
        }
    }

    private static String liveBanner(CoopInGameLauncherSession s) {
        CoopNetPump pump = CoopModPlugin.activeNetPump();
        if (pump == null) {
            return s.tr("Mode: Singleplayer (not connected)", "Режим: одиночная игра (нет подключения)");
        }
        CoopConnectionRole role = pump.currentRole();
        if (role == CoopConnectionRole.HOST) {
            return s.tr("Active: HOSTING live session", "Активно: ХОСТ (сервер запущен)");
        }
        if (role == CoopConnectionRole.GUEST) {
            return s.tr("Active: CONNECTED as guest", "Активно: ПОДКЛЮЧЁН как гость");
        }
        return s.tr("Mode: Singleplayer — start Host or Join below",
                "Режим: одиночная игра — запустите сервер или подключитесь ниже");
    }

    private void dismissDialog() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private final class PanelPlugin implements CustomUIPanelPlugin {
        private float px;
        private float py;
        private float pw = PANEL_W;
        private float ph = PANEL_H;

        @Override
        public void positionChanged(PositionAPI pos) {
            if (pos != null) {
                px = pos.getX();
                py = pos.getY();
                pw = pos.getWidth();
                ph = pos.getHeight();
            }
        }

        @Override
        public void renderBelow(float alphaMult) {
            CoopTitleScreenOverlay.drawRect(px, py, pw, ph, 0.043f, 0.086f, 0.133f, 0.96f * alphaMult);
            CoopTitleScreenOverlay.drawBorder(px, py, pw, ph, 0.28f, 0.88f, 0.51f, 0.95f * alphaMult);
        }

        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {}

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (events == null) return;
            for (InputEventAPI ev : events) {
                if (!ev.isConsumed() && ev.isKeyDownEvent() && ev.getEventValue() == Keyboard.KEY_ESCAPE) {
                    ev.consume();
                    dismissDialog();
                    return;
                }
            }
        }

        @Override
        public void buttonPressed(Object buttonId) {
            boolean rebuild = CoopTitleScreenOverlay.handleButtonAction(
                    buttonId,
                    CoopInGameLauncherSession.shared(),
                    CoopCampaignConnectDialog.this::dismissDialog,
                    new LiveCampaignActions());
            if (rebuild) {
                rebuildPending = true;
            }
        }
    }

    private final class LiveCampaignActions implements CoopTitleScreenOverlay.CampaignActions {
        @Override
        public void onHost(CoopInGameLauncherSession s) {
            int port = CoopInGameLauncherSession.parsePort(s.hostPort());
            if (port < 1) {
                s.setStatus(s.tr("Port must be 1..65535.", "Порт должен быть от 1 до 65535."),
                        new Color(255, 120, 110));
                return;
            }
            s.applyHostConfig(CoopOptionsStore.system());
            CoopNetPump pump = CoopModPlugin.activeNetPump();
            if (pump == null) {
                return;
            }
            String err = pump.startHostRuntime(port, s.hostPassword(), s.portMappingAuto());
            if (err != null) {
                s.setStatus(s.tr("Could not start host: " + err, "Не удалось запустить сервер: " + err),
                        new Color(255, 120, 110));
                return;
            }
            s.copyHostInviteToClipboard();
            s.setStatus(s.tr("Server LIVE on port " + port + "! Invite copied to clipboard.",
                            "Сервер ЗАПУЩЕН на порту " + port + "! Инвайт скопирован в буфер."),
                    CoopTitleScreenOverlay.GREEN_BRIGHT);
        }

        @Override
        public void onJoin(CoopInGameLauncherSession s) {
            if (!s.applyGuestConfig(CoopOptionsStore.system())) {
                return;
            }
            SectorAPI sector = Global.getSector();
            String currentSeed = sector == null || sector.getSeedString() == null ? "" : sector.getSeedString().trim();
            String targetSeed = s.guestSeed();
            if (!targetSeed.isEmpty() && !currentSeed.isEmpty() && !targetSeed.equalsIgnoreCase(currentSeed)) {
                s.setStatus(s.tr("Invite seed differs from this save! Return to Main Menu and press New Game.",
                                "Сид хоста отличается от этого сейва! Выйдите в главное меню и нажмите «Новая игра»."),
                        new Color(255, 195, 80));
                return;
            }
            CoopNetPump pump = CoopModPlugin.activeNetPump();
            if (pump == null) {
                return;
            }
            int port = CoopInGameLauncherSession.parsePort(s.guestPort());
            String err = pump.startGuestRuntime(s.guestHost(), port, s.guestPassword());
            if (err != null) {
                s.setStatus(s.tr("Could not connect: " + err, "Ошибка подключения: " + err),
                        new Color(255, 120, 110));
                return;
            }
            s.setStatus(s.tr("Connecting to " + s.guestHost() + ":" + port + "...",
                            "Подключение к " + s.guestHost() + ":" + port + "..."),
                    CoopTitleScreenOverlay.GREEN_BRIGHT);
        }

        @Override
        public void onStop(CoopInGameLauncherSession s) {
            CoopNetPump pump = CoopModPlugin.activeNetPump();
            if (pump != null) {
                pump.stopRuntimeSession();
            }
            s.disableCoop(CoopOptionsStore.system());
            s.setStatus(s.tr("CO-OP session stopped.", "Кооп-сессия остановлена."),
                    new Color(180, 200, 210));
        }
    }

    @Override public void optionSelected(String optionText, Object optionData) {}
    @Override public void optionMousedOver(String optionText, Object optionData) {}
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
