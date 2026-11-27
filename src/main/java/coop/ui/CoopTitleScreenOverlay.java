package coop.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import coop.ambient.CoopTitleScreenBridge;
import coop.config.CoopOptionsStore;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;

/**
 * Native Starsector Title Screen green {@code CO-OP} button and Host / Join configuration modal.
 *
 * <p>Driven on the LWJGL main thread via {@code CoopBattleStatusCombatPlugin.advance(...)} while
 * {@code Global.getCurrentState() == GameState.TITLE}. Automatically attaches once per
 * {@code TitleScreenState.screenPanel} instance, hides whenever a vanilla modal dialog
 * (Load Game, New Game, Settings, Codex, Missions) is open, and cleans up cleanly on state exit.
 */
public final class CoopTitleScreenOverlay {

    // Emerald-green Starsector palette for the CO-OP button & modal
    static final Color GREEN_BRIGHT = new Color(110, 255, 165);
    static final Color GREEN_BORDER = new Color(72, 224, 130);
    static final Color GREEN_BG = new Color(20, 82, 46);
    static final Color GREEN_TAB_ACTIVE_BG = new Color(28, 108, 60);
    static final Color TAB_INACTIVE_TEXT = new Color(155, 200, 185);
    static final Color TAB_INACTIVE_BG = new Color(16, 34, 48);
    static final Color ACTION_TEXT = new Color(195, 240, 255);
    static final Color ACTION_BG = new Color(20, 56, 76);
    static final Color DANGER_TEXT = new Color(255, 165, 155);
    static final Color DANGER_BG = new Color(82, 28, 28);
    static final Color LABEL_MUTED = new Color(150, 195, 212);
    static final Color FIELD_BG = new Color(9, 19, 29);

    private static final float MODAL_W = 600f;
    private static final float MODAL_H = 524f;
    private static final float DOCK_W = 328f;
    private static final float DOCK_H = 256f;
    private static final float DOCK_MARGIN = 18f;

    // Button IDs
    private static final String ID_DOCK_CONNECT = "coop.dock.connect";
    private static final String ID_DOCK_OPEN = "coop.dock.open";
    private static final String ID_DOCK_LOAD = "coop.dock.load";
    private static final String ID_CLOSE = "coop.close";
    private static final String ID_LANG = "coop.lang";
    private static final String ID_TAB_HOST = "coop.tab.host";
    private static final String ID_TAB_JOIN = "coop.tab.join";
    private static final String ID_LOOKUP_IP = "coop.host.lookupIp";
    private static final String ID_GEN_PW = "coop.host.genPw";
    private static final String ID_GEN_SEED = "coop.host.genSeed";
    private static final String ID_SIZE = "coop.host.size";
    private static final String ID_AGE = "coop.host.age";
    private static final String ID_UPNP = "coop.host.upnp";
    private static final String ID_DEDICATED = "coop.host.dedicated";
    private static final String ID_APPLY_HOST = "coop.host.apply";
    private static final String ID_COPY_INVITE = "coop.host.copy";
    private static final String ID_PASTE_INVITE = "coop.join.paste";
    private static final String ID_APPLY_JOIN = "coop.join.apply";
    private static final String ID_DISABLE = "coop.disable";
    private static final String ID_LOBBY_READY = "coop.lobby.ready";
    private static final String ID_LOBBY_START = "coop.lobby.start";
    private static final String ID_LOBBY_LEAVE = "coop.lobby.leave";

    private static UIPanelAPI attachedScreen;
    private static CustomPanelAPI dockPanel;
    private static boolean dockRebuildPending;
    private static long dockLastRevision = -1L;
    private static boolean dockLastRussian;
    private static boolean openModalOnNextConnectError;
    private static boolean pendingOpenLoadGame;
    private static boolean launchingIntoGame;
    private static boolean dedicatedAutoStartAttempted;
    private static CustomPanelAPI modalPanel;
    private static boolean modalOpen;
    private static boolean rebuildPending;
    private static volatile String pendingPublicIp;
    private static boolean lookupInFlight;
    private static boolean loggedError;

    // Active form text fields (read before rebuild / apply)
    private static TextFieldAPI playerNameField;
    private static TextFieldAPI hostAddrField;
    private static TextFieldAPI hostPortField;
    private static TextFieldAPI hostPwField;
    private static TextFieldAPI hostSeedField;
    private static TextFieldAPI guestInviteField;
    private static TextFieldAPI guestHostField;
    private static TextFieldAPI guestPortField;
    private static TextFieldAPI guestPwField;

    private CoopTitleScreenOverlay() {
    }

    /** Called every frame on the LWJGL thread from {@code CoopBattleStatusCombatPlugin}. */
    public static void onTitleFrame() {
        try {
            if (Global.getCurrentState() != GameState.TITLE) {
                coop.CoopModPlugin.markCampaignFrameEntered();
                CoopInGameLauncherSession.shared().stopPreGameLobbyOnStateExit();
                detachAll();
                return;
            }
            if (!launchingIntoGame) {
                coop.CoopModPlugin.shutdownActiveSessionOnTitleScreen();
            }
            UIPanelAPI screen = CoopTitleScreenBridge.currentTitleScreenPanel();
            if (screen == null) {
                return;
            }
            CoopInGameLauncherSession session = CoopInGameLauncherSession.shared();
            session.ensureInitialized();
            if (!launchingIntoGame && !dedicatedAutoStartAttempted
                    && coop.net.CoopNetStartupConfig.dedicatedHostFromSystemProperties()
                    && !CoopTitleScreenBridge.isTitleScreenShowingDialog()) {
                dedicatedAutoStartAttempted = true;
                session.setDedicatedHost(true);
                // Preserve -D launches without overwriting normal singleplayer/client prefs in saves/common
                if (coop.net.CoopNetStartupConfig.fromSystemProperties().role()
                        != coop.net.CoopConnectionRole.HOST) {
                    session.applyHostConfig(CoopOptionsStore.system());
                }
                String latestSave = CoopTitleScreenBridge.findLatestHostSaveDirPath();
                if (!latestSave.isEmpty()) {
                    CoopLog.info(CoopTitleScreenOverlay.class,
                            "Coop Dedicated Host auto-loading latest save: " + latestSave);
                    session.scheduleDedicatedAutoLoad(latestSave);
                }
            }

            // Advance live pre-game lobby networking on the main frame thread
            if (session.tickPreGameLobby(CoopOptionsStore.system())) {
                dockRebuildPending = true;
                if (modalOpen) {
                    rebuildPending = true;
                }
                if (openModalOnNextConnectError) {
                    coop.net.CoopPreGameLobby.State lst = session.preGameLobby().state();
                    if (lst == coop.net.CoopPreGameLobby.State.ERROR) {
                        openModalOnNextConnectError = false;
                        session.setActiveTab(CoopInGameLauncherSession.Tab.JOIN);
                        modalOpen = true;
                        rebuildPending = true;
                    } else if (lst == coop.net.CoopPreGameLobby.State.IN_LOBBY
                            || lst == coop.net.CoopPreGameLobby.State.LAUNCHING) {
                        openModalOnNextConnectError = false;
                    }
                }
            }

            // Apply async IP lookup result safely on the LWJGL main thread
            String foundIp = pendingPublicIp;
            if (foundIp != null) {
                pendingPublicIp = null;
                lookupInFlight = false;
                if (!foundIp.isEmpty()) {
                    session.setHostAddress(foundIp);
                    session.setStatus(
                            session.tr("Detected IP: " + foundIp, "Определён IP: " + foundIp),
                            GREEN_BRIGHT);
                } else {
                    session.setStatus(
                            session.tr("Could not detect public IP; enter manually.",
                                    "Не удалось определить IP; введите адрес вручную."),
                            new Color(255, 185, 80));
                }
                if (modalOpen) {
                    rebuildPending = true;
                }
            }

            if (screen != attachedScreen) {
                detachAll();
                attachedScreen = screen;
            }
            if (launchingIntoGame) {
                removeModal();
                removeDock();
                return;
            }

            boolean vanillaDialogOpen = CoopTitleScreenBridge.isTitleScreenShowingDialog();
            if (vanillaDialogOpen) {
                removeModal();
                removeDock();
                return;
            }

            // NOTE: Never call enterGameFromTitleScreen() or openLoadGameDialog() here!
            // onTitleFrame() runs inside CoopBattleStatusCombatPlugin.advance() -> CombatEngine.advanceInner().
            // Calling TitleScreenState.menuItemSelected(Continue) there invokes CampaignEngine.resetInstance() ->
            // CombatEngine.destroyInstance(), nulling collisionChecker on the CombatEngine frame still on the stack!
            // Instead, DockPlugin.advance() / ModalPlugin.advance() run inside TitleScreenState.screenPanel.advance()
            // BEFORE CombatEngine.advance() and safely invoke flushPendingTitleActions().

            if (modalOpen) {
                removeDock();
                if (modalPanel == null || rebuildPending) {
                    rebuildPending = false;
                    attachModal(screen, session);
                }
            } else {
                if (modalPanel != null) {
                    removeModal();
                }
                long rev = session.preGameLobby().revision();
                boolean ru = CoopUiLang.isRussian();
                if (dockPanel == null || dockRebuildPending || rev != dockLastRevision || ru != dockLastRussian) {
                    dockRebuildPending = false;
                    dockLastRevision = rev;
                    dockLastRussian = ru;
                    attachDock(screen, session);
                }
            }
        } catch (Throwable ex) {
            if (!loggedError) {
                loggedError = true;
                CoopLog.warn(CoopTitleScreenOverlay.class, "Title screen CO-OP overlay error", ex);
            }
        }
    }

    private static void detachAll() {
        removeModal();
        removeDock();
        attachedScreen = null;
        modalOpen = false;
        rebuildPending = false;
        dockRebuildPending = false;
        dockLastRevision = -1L;
        openModalOnNextConnectError = false;
        pendingOpenLoadGame = false;
        launchingIntoGame = false;
    }

    /**
     * Executes deferred Title Screen actions (Continue/New Game auto-enter and Load Save dialog)
     * from {@code screenPanel.advance(dt)} — outside {@code CombatEngine.advanceInner()}, so
     * {@code CampaignEngine.resetInstance()} -> {@code CombatEngine.destroyInstance()} never wipes
     * an actively executing {@code CombatEngine} frame.
     */
    private static void flushPendingTitleActions() {
        try {
            if (Global.getCurrentState() != GameState.TITLE) {
                return;
            }
            if (CoopTitleScreenBridge.isTitleScreenShowingDialog()) {
                return;
            }
            if (pendingOpenLoadGame) {
                pendingOpenLoadGame = false;
                openModalOnNextConnectError = false;
                modalOpen = false;
                removeModal();
                removeDock();
                CoopTitleScreenBridge.openLoadGameDialog();
                return;
            }
            CoopInGameLauncherSession session = CoopInGameLauncherSession.shared();
            if (session.consumePendingAutoEnterGame()) {
                openModalOnNextConnectError = false;
                modalOpen = false;
                removeModal();
                removeDock();
                String savePath = session.consumePendingLoadSavePath();
                boolean entered = !savePath.isEmpty()
                        ? CoopTitleScreenBridge.loadSaveFromTitleScreen(savePath)
                        : CoopTitleScreenBridge.enterGameFromTitleScreen();
                if (entered) {
                    launchingIntoGame = true;
                } else {
                    dockRebuildPending = true;
                }
            }
        } catch (Throwable ex) {
            CoopLog.warn(CoopTitleScreenOverlay.class, "Title screen deferred action failed", ex);
        }
    }

    private static void removeDock() {
        if (attachedScreen != null && dockPanel != null) {
            try {
                attachedScreen.removeComponent(dockPanel);
            } catch (Throwable ignored) {
            }
        }
        dockPanel = null;
    }

    private static void removeModal() {
        if (attachedScreen != null && modalPanel != null) {
            try {
                attachedScreen.removeComponent(modalPanel);
            } catch (Throwable ignored) {
            }
        }
        modalPanel = null;
        clearFieldRefs();
    }

    private static void clearFieldRefs() {
        playerNameField = null;
        hostAddrField = null;
        hostPortField = null;
        hostPwField = null;
        hostSeedField = null;
        guestInviteField = null;
        guestHostField = null;
        guestPortField = null;
        guestPwField = null;
    }

    /**
     * Always-open compact bottom-left Lobby & Participants dock on the Title Screen with a
     * one-click {@code CONNECT} button using previously saved settings (and auto-opening the full
     * Join settings modal with the exact error banner if connecting fails).
     */
    private static void attachDock(UIPanelAPI screen, CoopInGameLauncherSession session) {
        removeDock();
        coop.net.CoopPreGameLobby lobby = session.preGameLobby();
        coop.net.CoopPreGameLobby.State lst = lobby.state();

        CustomPanelAPI panel = Global.getSettings().createCustom(DOCK_W, DOCK_H, new DockPlugin());
        TooltipMakerAPI ui = panel.createUIElement(DOCK_W, DOCK_H, false);

        // Header line inside 30px emerald bar
        LabelAPI hdr = ui.addPara(session.tr("CO-OP LOBBY", "CO-OP ЛОББИ"), GREEN_BRIGHT, 0f);
        hdr.getPosition().inTL(10f, 7f);

        String badgeText;
        Color badgeColor;
        if (lobby.isTransferringSave()) {
            badgeText = (lobby.isHost() ? session.tr("SENDING ", "ОТПРАВКА ")
                    : session.tr("DOWNLOADING ", "СКАЧИВАНИЕ ")) + lobby.saveTransferPercent() + "%";
            badgeColor = new Color(130, 225, 255);
        } else if (lobby.isConnected()) {
            badgeText = session.tr("READY ", "ГОТОВЫ ") + lobby.readyCount() + "/" + lobby.totalCount();
            badgeColor = lobby.allReady() ? GREEN_BRIGHT : new Color(255, 214, 102);
        } else if (lst == coop.net.CoopPreGameLobby.State.CONNECTING) {
            badgeText = session.tr("CONNECTING...", "ПОДКЛЮЧЕНИЕ...");
            badgeColor = new Color(130, 225, 255);
        } else if (lobby.isLaunchTriggered()) {
            badgeText = session.tr("LAUNCHING!", "ЗАПУСК!");
            badgeColor = GREEN_BRIGHT;
        } else if (lst == coop.net.CoopPreGameLobby.State.ERROR) {
            badgeText = session.tr("ERROR", "ОШИБКА");
            badgeColor = new Color(255, 130, 115);
        } else {
            badgeText = session.tr("OFFLINE", "ОФФЛАЙН");
            badgeColor = LABEL_MUTED;
        }
        LabelAPI badge = ui.addPara(badgeText, badgeColor, 0f);
        badge.setAlignment(Alignment.RMID);
        badge.getPosition().setSize(150f, 18f);
        badge.getPosition().inTR(10f, 7f);

        float y = 38f;
        float innerW = DOCK_W - 20f;

        if (lobby.isConnected()) {
            LabelAPI pHdr = ui.addPara(
                    session.tr("Participants:", "Участники лобби:"), LABEL_MUTED, 0f);
            pHdr.getPosition().inTL(10f, y);
            y += 20f;

            List<coop.net.CoopPreGameLobby.Member> members = lobby.members();
            int shown = Math.min(4, members.size());
            for (int i = 0; i < shown; i++) {
                coop.net.CoopPreGameLobby.Member m = members.get(i);
                String prefix = m.host() ? "[H] " : " -  ";
                LabelAPI nameLbl = ui.addPara(prefix + m.name(),
                        m.host() ? GREEN_BRIGHT : Color.WHITE, 0f);
                nameLbl.getPosition().setSize(176f, 20f);
                nameLbl.getPosition().inTL(10f, y);

                LabelAPI stateLbl = ui.addPara(
                        m.ready() ? session.tr("READY", "ГОТОВ") : session.tr("WAIT", "ЖДЁТ"),
                        m.ready() ? GREEN_BRIGHT : new Color(255, 205, 100), 0f);
                stateLbl.setAlignment(Alignment.RMID);
                stateLbl.getPosition().setSize(102f, 20f);
                stateLbl.getPosition().inTR(10f, y);
                y += 22f;
            }
            if (members.size() > shown) {
                LabelAPI more = ui.addPara("+" + (members.size() - shown)
                        + session.tr(" more...", " ещё..."), LABEL_MUTED, 0f);
                more.getPosition().inTL(10f, y);
            }
            if (!lobby.worldSeed().isEmpty()) {
                LabelAPI seedLbl = ui.addPara(session.tr("Seed: ", "Сид: ") + lobby.worldSeed(),
                        LABEL_MUTED, 0f);
                seedLbl.getPosition().setSize(innerW, 18f);
                seedLbl.getPosition().inTL(10f, DOCK_H - 94f);
            }
        } else if (lst == coop.net.CoopPreGameLobby.State.CONNECTING) {
            LabelAPI connLbl = ui.addPara(
                    session.tr("Connecting to " + session.savedJoinTargetLabel() + "...",
                            "Подключение к " + session.savedJoinTargetLabel() + "..."),
                    new Color(130, 225, 255), 0f);
            connLbl.getPosition().setSize(innerW, 40f);
            connLbl.getPosition().inTL(10f, y + 10f);
        } else if (lobby.isLaunchTriggered()) {
            LabelAPI launchLbl = ui.addPara(
                    session.tr("World is starting!\nClick New Game or Load Game.",
                            "Мир запускается!\nНажмите «Новая игра» или «Загрузить»."),
                    GREEN_BRIGHT, 0f);
            launchLbl.getPosition().setSize(innerW, 56f);
            launchLbl.getPosition().inTL(10f, y + 10f);
        } else {
            String nick = session.playerName().isEmpty() ? "Player" : session.playerName();
            LabelAPI nickLbl = ui.addPara(session.tr("Player: ", "Ник: ") + nick, Color.WHITE, 0f);
            nickLbl.getPosition().setSize(innerW, 18f);
            nickLbl.getPosition().inTL(10f, y);
            y += 22f;

            boolean isHostArmed = session.armedRole() == coop.net.CoopConnectionRole.HOST;
            if (isHostArmed) {
                LabelAPI modeLbl = ui.addPara(
                        session.tr("Mode: HOST (port " + session.hostPort() + ")",
                                "Режим: ХОСТ (порт " + session.hostPort() + ")"),
                        GREEN_BRIGHT, 0f);
                modeLbl.getPosition().setSize(innerW, 18f);
                modeLbl.getPosition().inTL(10f, y);
            } else {
                String target = session.hasSavedJoinSettings()
                        ? session.savedJoinTargetLabel()
                        : session.tr("not configured", "не задан");
                LabelAPI hostLbl = ui.addPara(
                        session.tr("Last server: ", "Сервер: ") + target,
                        session.hasSavedJoinSettings() ? new Color(150, 230, 255) : LABEL_MUTED, 0f);
                hostLbl.getPosition().setSize(innerW, 18f);
                hostLbl.getPosition().inTL(10f, y);
            }
            y += 24f;

            String defaultHint = isHostArmed
                    ? session.tr("Press CONNECT to open Host Lobby, or Load Game to pick a save.",
                            "Нажмите CONNECT чтобы открыть лобби хоста, или «Загрузка» для выбора сейва.")
                    : session.tr("Press CONNECT to join using your last settings, or Settings to Host/edit.",
                            "Нажмите CONNECT для входа по прошлым настройкам, или «Настройки» для хоста.");
            String hint = !session.statusText().isEmpty() ? session.statusText() : defaultHint;
            Color hintColor = !session.statusText().isEmpty() ? session.statusColor() : LABEL_MUTED;
            LabelAPI hintLbl = ui.addPara(hint, hintColor, 0f);
            hintLbl.getPosition().setSize(innerW, 66f);
            hintLbl.getPosition().inTL(10f, y);
        }

        // Bottom action area inside dock
        if (lobby.isConnected()) {
            float row1Y = DOCK_H - 72f;
            float row2Y = DOCK_H - 36f;
            if (lobby.isHost()) {
                ButtonAPI readyBtn = ui.addButton(
                        lobby.isLocalReady() ? session.tr("Unready", "Не готов") : session.tr("READY", "ГОТОВ"),
                        ID_LOBBY_READY,
                        lobby.isLocalReady() ? new Color(255, 215, 110) : GREEN_BRIGHT,
                        lobby.isLocalReady() ? new Color(82, 58, 20) : GREEN_BG,
                        Alignment.MID, CutStyle.ALL, 126f, 28f, 0f);
                readyBtn.getPosition().inTL(10f, row1Y);

                boolean transferring = lobby.isTransferringSave();
                boolean allReady = lobby.allReady() && !transferring;
                ButtonAPI startBtn = ui.addButton(
                        transferring
                                ? session.tr("SAVE " + lobby.saveTransferPercent() + "%",
                                        "СЕЙВ " + lobby.saveTransferPercent() + "%")
                                : (allReady ? session.tr("START SESSION", "СТАРТ СЕССИИ")
                                : session.tr("Wait (" + lobby.readyCount() + "/" + lobby.totalCount() + ")",
                                        "Ждём (" + lobby.readyCount() + "/" + lobby.totalCount() + ")")),
                        ID_LOBBY_START,
                        allReady ? GREEN_BRIGHT : LABEL_MUTED,
                        allReady ? GREEN_TAB_ACTIVE_BG : TAB_INACTIVE_BG,
                        Alignment.MID, CutStyle.ALL, 176f, 28f, 0f);
                startBtn.setEnabled(allReady);
                startBtn.getPosition().inTL(142f, row1Y);
            } else {
                ButtonAPI readyBtn = ui.addButton(
                        lobby.isLocalReady() ? session.tr("READY (click to cancel)", "ГОТОВ (отменить)")
                                : session.tr("I AM READY", "Я ГОТОВ"),
                        ID_LOBBY_READY,
                        lobby.isLocalReady() ? new Color(255, 215, 110) : GREEN_BRIGHT,
                        lobby.isLocalReady() ? new Color(82, 58, 20) : GREEN_TAB_ACTIVE_BG,
                        Alignment.MID, CutStyle.ALL, innerW, 28f, 0f);
                readyBtn.getPosition().inTL(10f, row1Y);
            }

            ButtonAPI cfgBtn = ui.addButton(session.tr("Settings", "Настройки"), ID_DOCK_OPEN,
                    ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, 146f, 26f, 0f);
            cfgBtn.getPosition().inTL(10f, row2Y);

            ButtonAPI leaveBtn = ui.addButton(session.tr("Leave Lobby", "Выйти из лобби"), ID_LOBBY_LEAVE,
                    DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, 156f, 26f, 0f);
            leaveBtn.getPosition().inTL(162f, row2Y);
        } else if (lst == coop.net.CoopPreGameLobby.State.CONNECTING) {
            float barY = DOCK_H - 40f;
            ButtonAPI cancelBtn = ui.addButton(session.tr("Cancel", "Отмена"), ID_LOBBY_LEAVE,
                    DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, 148f, 30f, 0f);
            cancelBtn.getPosition().inTL(10f, barY);

            ButtonAPI cfgBtn = ui.addButton(session.tr("Settings", "Настройки"), ID_DOCK_OPEN,
                    ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, 154f, 30f, 0f);
            cfgBtn.getPosition().inTL(164f, barY);
        } else {
            boolean isHostArmed = session.armedRole() == coop.net.CoopConnectionRole.HOST;
            if (isHostArmed) {
                float row1Y = DOCK_H - 72f;
                float row2Y = DOCK_H - 36f;
                ButtonAPI connectBtn = ui.addButton("CONNECT", ID_DOCK_CONNECT,
                        GREEN_BRIGHT, GREEN_TAB_ACTIVE_BG, Alignment.MID, CutStyle.ALL, 156f, 28f, 0f);
                connectBtn.getPosition().inTL(10f, row1Y);

                ButtonAPI loadBtn = ui.addButton(session.tr("Load Save", "Загрузка"), ID_DOCK_LOAD,
                        GREEN_BRIGHT, GREEN_BG, Alignment.MID, CutStyle.ALL, 146f, 28f, 0f);
                loadBtn.getPosition().inTL(172f, row1Y);

                ButtonAPI cfgBtn = ui.addButton(session.tr("Settings", "Настройки"), ID_DOCK_OPEN,
                        ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, innerW, 26f, 0f);
                cfgBtn.getPosition().inTL(10f, row2Y);
            } else {
                float barY = DOCK_H - 40f;
                ButtonAPI connectBtn = ui.addButton("CONNECT", ID_DOCK_CONNECT,
                        GREEN_BRIGHT, GREEN_TAB_ACTIVE_BG, Alignment.MID, CutStyle.ALL, 156f, 30f, 0f);
                connectBtn.getPosition().inTL(10f, barY);

                ButtonAPI cfgBtn = ui.addButton(session.tr("Settings", "Настройки"), ID_DOCK_OPEN,
                        ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, 146f, 30f, 0f);
                cfgBtn.getPosition().inTL(172f, barY);
            }
        }

        panel.addUIElement(ui).inTL(0f, 0f);
        screen.addComponent(panel).inBL(DOCK_MARGIN, DOCK_MARGIN);
        screen.bringComponentToTop(panel);
        dockPanel = panel;
    }

    private static void attachModal(UIPanelAPI screen, CoopInGameLauncherSession session) {
        removeModal();
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float cardX = Math.round((screenW - MODAL_W) * 0.5f);
        float cardY = Math.round((screenH - MODAL_H) * 0.5f);

        CustomPanelAPI backdrop = Global.getSettings().createCustom(screenW, screenH,
                new ModalPlugin(cardX, cardY, MODAL_W, MODAL_H));
        TooltipMakerAPI ui = backdrop.createUIElement(MODAL_W, MODAL_H, false);
        buildModalContents(ui, session, MODAL_W, false, null, null);
        backdrop.addUIElement(ui).inBL(cardX, cardY);

        screen.addComponent(backdrop).inBL(0f, 0f);
        screen.bringComponentToTop(backdrop);
        modalPanel = backdrop;
    }

    /**
     * Populates the native Host / Join form into {@code ui}. Shared between the Title Screen
     * modal and the in-campaign {@link CoopCampaignConnectDialog}.
     */
    static void buildModalContents(TooltipMakerAPI ui, CoopInGameLauncherSession s, float width,
                                   boolean inCampaign, String liveSessionBanner, Runnable unused) {
        clearFieldRefs();
        float pad = 16f;
        float innerW = width - pad * 2f;

        // ---- Header row: Title + RU/EN + Close ----
        LabelAPI title = ui.addPara("STARSECTOR CO-OP", GREEN_BRIGHT, 0f);
        title.setHighlight("STARSECTOR CO-OP");
        title.setHighlightColor(GREEN_BRIGHT);
        title.getPosition().inTL(pad, 14f);

        float closeW = 76f;
        float langW = 76f;
        ButtonAPI closeBtn = ui.addButton(s.tr("Close", "Закрыть"), ID_CLOSE,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, closeW, 24f, 0f);
        closeBtn.getPosition().inTR(pad, 11f);
        closeBtn.setShortcut(Keyboard.KEY_ESCAPE, true);

        ButtonAPI langBtn = ui.addButton(CoopUiLang.shortToggleLabel(), ID_LANG,
                GREEN_BRIGHT, GREEN_BG, Alignment.MID, CutStyle.ALL, langW, 24f, 0f);
        langBtn.getPosition().inTR(pad + closeW + 8f, 11f);

        // ---- Pre-game Lobby view (Title Screen only, while hosting or connected to a lobby) ----
        if (!inCampaign && s.isPreGameLobbyActive()) {
            float y = 52f;
            y = buildPreGameLobbyBody(ui, s, pad, innerW, y);
            if (!s.statusText().isEmpty()) {
                LabelAPI st = ui.addPara(s.statusText(), s.statusColor(), 0f);
                st.getPosition().setSize(innerW, 44f);
                st.getPosition().inTL(pad, y + 6f);
            }
            return;
        }

        // ---- Mode Tabs: HOST | JOIN ----
        float tabY = 46f;
        float tabW = (innerW - 8f) * 0.5f;
        boolean isHost = s.activeTab() == CoopInGameLauncherSession.Tab.HOST;

        ButtonAPI hostTab = ui.addButton(s.tr("HOST A GAME", "ЗАПУСТИТЬ СЕРВЕР"), ID_TAB_HOST,
                isHost ? GREEN_BRIGHT : TAB_INACTIVE_TEXT,
                isHost ? GREEN_TAB_ACTIVE_BG : TAB_INACTIVE_BG,
                Alignment.MID, CutStyle.ALL, tabW, 30f, 0f);
        hostTab.getPosition().inTL(pad, tabY);

        ButtonAPI joinTab = ui.addButton(s.tr("JOIN A GAME", "ПОДКЛЮЧИТЬСЯ"), ID_TAB_JOIN,
                !isHost ? GREEN_BRIGHT : TAB_INACTIVE_TEXT,
                !isHost ? GREEN_TAB_ACTIVE_BG : TAB_INACTIVE_BG,
                Alignment.MID, CutStyle.ALL, tabW, 30f, 0f);
        joinTab.getPosition().inTL(pad + tabW + 8f, tabY);

        float y = tabY + 40f;

        if (!inCampaign) {
            float nickLblW = 148f;
            LabelAPI nickLbl = ui.addPara(s.tr("Your lobby nickname:", "Ваш ник в лобби:"), LABEL_MUTED, 0f);
            nickLbl.getPosition().inTL(pad, y + 5f);
            playerNameField = createField(ui, innerW - nickLblW - 8f, s.playerName(), 28);
            playerNameField.getPosition().inTL(pad + nickLblW + 8f, y);
            y += 38f;
        }

        if (liveSessionBanner != null && !liveSessionBanner.isEmpty()) {
            LabelAPI banner = ui.addPara(liveSessionBanner, GREEN_BRIGHT, 0f);
            banner.getPosition().inTL(pad, y);
            y += 24f;
        }

        if (isHost) {
            y = buildHostBody(ui, s, pad, innerW, y, inCampaign);
        } else {
            y = buildJoinBody(ui, s, pad, innerW, y, inCampaign);
        }

        // ---- Status line ----
        if (!s.statusText().isEmpty()) {
            LabelAPI st = ui.addPara(s.statusText(), s.statusColor(), 0f);
            st.getPosition().setSize(innerW, 42f);
            st.getPosition().inTL(pad, y + 6f);
        }
    }

    private static float buildPreGameLobbyBody(TooltipMakerAPI ui, CoopInGameLauncherSession s,
                                               float pad, float innerW, float y) {
        coop.net.CoopPreGameLobby lobby = s.preGameLobby();
        boolean isHost = lobby.isHost();
        boolean connecting = lobby.state() == coop.net.CoopPreGameLobby.State.CONNECTING;

        // 1. Lobby title & readiness counter
        String heading = connecting
                ? s.tr("CONNECTING TO HOST LOBBY...", "ПОДКЛЮЧЕНИЕ К ЛОББИ ХОСТА...")
                : s.tr("PRE-GAME LOBBY  ·  READY: " + lobby.readyCount() + " / " + lobby.totalCount(),
                        "ЛОББИ ОЖИДАНИЯ  ·  ГОТОВЫ: " + lobby.readyCount() + " / " + lobby.totalCount());
        LabelAPI headerLbl = ui.addPara(heading, GREEN_BRIGHT, 0f);
        headerLbl.getPosition().inTL(pad, y);
        y += 24f;

        // 2. World parameters summary
        String seedText = lobby.worldSeed().isEmpty() ? "—" : lobby.worldSeed();
        String worldLine = s.tr(
                "Port: " + lobby.boundPort() + "  ·  Seed: " + seedText
                        + "  ·  Size: " + lobby.worldSize() + "  ·  Age: " + lobby.worldAge(),
                "Порт: " + lobby.boundPort() + "  ·  Сид: " + seedText
                        + "  ·  Сектор: " + lobby.worldSize() + "  ·  Звёзды: " + lobby.worldAge());
        LabelAPI worldLbl = ui.addPara(worldLine, LABEL_MUTED, 0f);
        worldLbl.getPosition().setSize(innerW, 20f);
        worldLbl.getPosition().inTL(pad, y);
        y += 26f;

        // 3. Connected players roster
        LabelAPI rosterTitle = ui.addPara(
                s.tr("Players in lobby (" + lobby.totalCount() + "):",
                        "Игроки в лобби (" + lobby.totalCount() + "):"),
                ACTION_TEXT, 0f);
        rosterTitle.getPosition().inTL(pad, y);
        y += 24f;

        List<coop.net.CoopPreGameLobby.Member> members = lobby.members();
        Color amberText = new Color(255, 215, 110);
        Color amberBg = new Color(64, 48, 18);
        int shown = 0;
        for (coop.net.CoopPreGameLobby.Member m : members) {
            if (shown >= 6) {
                break;
            }
            boolean isSelf = (isHost && m.host()) || (!isHost && !m.host() && m.name().equals(s.playerName()));
            String roleTag = m.host() ? s.tr("[HOST] ", "[ХОСТ] ") : s.tr("[GUEST] ", "[ГОСТЬ] ");
            String youTag = isSelf ? s.tr(" (you)", " (вы)") : "";
            String vaultTag = (isHost && !m.host() && CoopTitleScreenBridge.hasPlayerSaveZip(m.name()))
                    ? s.tr(" [Save ✓]", " [Сейв ✓]") : "";
            String badge = m.ready() ? s.tr("READY", "ГОТОВ") : s.tr("NOT READY", "НЕ ГОТОВ");

            float badgeW = 128f;
            float nameW = innerW - badgeW - 8f;
            ButtonAPI namePill = ui.addButton(roleTag + m.name() + youTag + vaultTag, "coop.lobby.member." + shown,
                    m.ready() ? GREEN_BRIGHT : ACTION_TEXT,
                    m.ready() ? new Color(16, 54, 34) : TAB_INACTIVE_BG,
                    Alignment.LMID, CutStyle.ALL, nameW, 28f, 0f);
            namePill.setEnabled(false);
            namePill.getPosition().inTL(pad, y);

            ButtonAPI statePill = ui.addButton(badge, "coop.lobby.state." + shown,
                    m.ready() ? GREEN_BRIGHT : amberText,
                    m.ready() ? GREEN_BG : amberBg,
                    Alignment.MID, CutStyle.ALL, badgeW, 28f, 0f);
            statePill.setEnabled(false);
            statePill.getPosition().inTL(pad + nameW + 8f, y);

            y += 34f;
            shown++;
        }

        if (isHost && members.size() <= 1) {
            LabelAPI waitTip = ui.addPara(
                    s.tr("Waiting for partner(s)... Copy the invite and send it to your friend.",
                            "Ожидание напарника... Скопируйте инвайт и отправьте другу."),
                    LABEL_MUTED, 0f);
            waitTip.getPosition().setSize(innerW, 24f);
            waitTip.getPosition().inTL(pad, y + 4f);
        } else if (!isHost && !connecting) {
            LabelAPI guestTip = ui.addPara(
                    s.tr("Host's sector seed & settings synced automatically. Waiting for host to start...",
                            "Сид и настройки мира синхронизированы с хостом. Ожидание старта от хоста..."),
                    LABEL_MUTED, 0f);
            guestTip.getPosition().setSize(innerW, 24f);
            guestTip.getPosition().inTL(pad, y + 4f);
        }

        // Fixed bottom action bar inside the lobby card
        float barY = 382f;
        boolean localReady = lobby.isLocalReady();
        if (isHost) {
            float readyW = 176f;
            float copyW = 184f;
            float leaveW = innerW - readyW - copyW - 16f;

            ButtonAPI readyBtn = ui.addButton(
                    localReady ? s.tr("READY (click to unset)", "ГОТОВ (снять)")
                            : s.tr("MARK READY", "ГОТОВ"),
                    ID_LOBBY_READY,
                    localReady ? GREEN_BRIGHT : amberText,
                    localReady ? GREEN_TAB_ACTIVE_BG : amberBg,
                    Alignment.MID, CutStyle.ALL, readyW, 32f, 0f);
            readyBtn.getPosition().inTL(pad, barY);

            ButtonAPI copyBtn = ui.addButton(s.tr("Copy Invite", "Копировать инвайт"), ID_COPY_INVITE,
                    ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, copyW, 32f, 0f);
            copyBtn.getPosition().inTL(pad + readyW + 8f, barY);

            ButtonAPI leaveBtn = ui.addButton(s.tr("Close Lobby", "Закрыть лобби"), ID_LOBBY_LEAVE,
                    DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, leaveW, 32f, 0f);
            leaveBtn.getPosition().inTL(pad + readyW + copyW + 16f, barY);

            boolean allReady = lobby.allReady();
            String startLabel = allReady
                    ? (lobby.totalCount() <= 1
                            ? s.tr("START SESSION (SOLO)", "СТАРТ СЕССИИ (СОЛО)")
                            : s.tr("ALL READY — START SESSION", "ВСЕ ГОТОВЫ — СТАРТ СЕССИИ"))
                    : s.tr("WAITING FOR ALL READY (" + lobby.readyCount() + "/" + lobby.totalCount() + ")",
                            "ЖДЁМ ГОТОВНОСТИ ВСЕХ (" + lobby.readyCount() + "/" + lobby.totalCount() + ")");
            ButtonAPI startBtn = ui.addButton(startLabel, ID_LOBBY_START,
                    allReady ? GREEN_BRIGHT : LABEL_MUTED,
                    allReady ? new Color(26, 132, 68) : TAB_INACTIVE_BG,
                    Alignment.MID, CutStyle.ALL, innerW, 36f, 0f);
            startBtn.setEnabled(allReady);
            startBtn.getPosition().inTL(pad, barY + 40f);
            return barY + 82f;
        } else {
            float readyW = innerW - 188f;
            float leaveW = 180f;
            ButtonAPI readyBtn = ui.addButton(
                    localReady ? s.tr("[READY] (click to cancel)", "[ГОТОВ] (отменить)")
                            : s.tr("I AM READY", "Я ГОТОВ"),
                    ID_LOBBY_READY,
                    localReady ? GREEN_BRIGHT : amberText,
                    localReady ? new Color(26, 132, 68) : amberBg,
                    Alignment.MID, CutStyle.ALL, readyW, 38f, 0f);
            readyBtn.setEnabled(!connecting);
            readyBtn.getPosition().inTL(pad, barY + 22f);

            ButtonAPI leaveBtn = ui.addButton(s.tr("Leave Lobby", "Выйти из лобби"), ID_LOBBY_LEAVE,
                    DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, leaveW, 38f, 0f);
            leaveBtn.getPosition().inTL(pad + readyW + 8f, barY + 22f);
            return barY + 72f;
        }
    }

    private static float buildHostBody(TooltipMakerAPI ui, CoopInGameLauncherSession s,
                                       float pad, float innerW, float y, boolean inCampaign) {
        // Row 1: Public / LAN address + Detect IP button
        LabelAPI addrLbl = ui.addPara(
                s.tr("Address for invite (public / LAN / VPN IP):", "Адрес для инвайта (публичный / LAN / VPN IP):"),
                LABEL_MUTED, 0f);
        addrLbl.getPosition().inTL(pad, y);
        y += 20f;

        float detectW = 136f;
        hostAddrField = createField(ui, innerW - detectW - 8f, s.hostAddress(), 120);
        hostAddrField.getPosition().inTL(pad, y);

        ButtonAPI detectBtn = ui.addButton(
                lookupInFlight ? s.tr("Detecting...", "Поиск...") : s.tr("Detect IP", "Узнать IP"),
                ID_LOOKUP_IP, ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, detectW, 28f, 0f);
        detectBtn.getPosition().inTL(pad + innerW - detectW, y);
        y += 38f;

        // Row 2: Port + Password + New PW button
        float portW = 110f;
        float genPwW = 92f;
        float pwW = innerW - portW - genPwW - 16f;

        LabelAPI portLbl = ui.addPara(s.tr("Port:", "Порт:"), LABEL_MUTED, 0f);
        portLbl.getPosition().inTL(pad, y);
        LabelAPI pwLbl = ui.addPara(s.tr("Password (optional):", "Пароль (опционально):"), LABEL_MUTED, 0f);
        pwLbl.getPosition().inTL(pad + portW + 8f, y);
        y += 20f;

        hostPortField = createField(ui, portW, s.hostPort(), 6);
        hostPortField.getPosition().inTL(pad, y);

        hostPwField = createField(ui, pwW, s.hostPassword(), 48);
        hostPwField.getPosition().inTL(pad + portW + 8f, y);

        ButtonAPI genPwBtn = ui.addButton(s.tr("Random", "Новый"), ID_GEN_PW,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, genPwW, 28f, 0f);
        genPwBtn.getPosition().inTL(pad + innerW - genPwW, y);
        y += 38f;

        // Row 3: Sector seed + Generate button
        LabelAPI seedLbl = ui.addPara(
                inCampaign
                        ? s.tr("Current sector seed:", "Сид текущего сектора:")
                        : s.tr("New campaign seed (leave as-is or generate):", "Сид новой кампании (или оставьте как есть):"),
                LABEL_MUTED, 0f);
        seedLbl.getPosition().inTL(pad, y);
        y += 20f;

        float genSeedW = 120f;
        hostSeedField = createField(ui, innerW - genSeedW - 8f, s.hostSeed(), 40);
        hostSeedField.getPosition().inTL(pad, y);

        ButtonAPI genSeedBtn = ui.addButton(s.tr("New Seed", "Новый сид"), ID_GEN_SEED,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, genSeedW, 28f, 0f);
        genSeedBtn.setEnabled(!inCampaign);
        genSeedBtn.getPosition().inTL(pad + innerW - genSeedW, y);
        y += 38f;

        // Row 4: World settings & UPnP toggles
        float thirdW = (innerW - 16f) / 3f;
        ButtonAPI sizeBtn = ui.addButton(
                s.tr("Size: ", "Сектор: ") + s.sectorSizeLabel(), ID_SIZE,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, thirdW, 26f, 0f);
        sizeBtn.setEnabled(!inCampaign);
        sizeBtn.getPosition().inTL(pad, y);

        ButtonAPI ageBtn = ui.addButton(
                s.tr("Age: ", "Звёзды: ") + s.starAgeLabel(), ID_AGE,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, thirdW, 26f, 0f);
        ageBtn.setEnabled(!inCampaign);
        ageBtn.getPosition().inTL(pad + thirdW + 8f, y);

        ButtonAPI upnpBtn = ui.addButton(
                "UPnP: " + (s.portMappingAuto() ? s.tr("Auto", "Авто") : s.tr("Off", "Выкл")),
                ID_UPNP,
                s.portMappingAuto() ? GREEN_BRIGHT : TAB_INACTIVE_TEXT,
                s.portMappingAuto() ? GREEN_BG : TAB_INACTIVE_BG,
                Alignment.MID, CutStyle.ALL, thirdW, 26f, 0f);
        upnpBtn.getPosition().inTL(pad + (thirdW + 8f) * 2f, y);
        y += 32f;

        // Row 4b: Dedicated Server toggle (unattended world host, live combat, auto-pause when empty)
        ButtonAPI dedBtn = ui.addButton(
                s.tr("Dedicated Server (live world, 0-player auto-pause): ",
                        "Выделенный сервер (живой мир, авто-пауза без игроков): ")
                        + (s.dedicatedHost() ? s.tr("ON", "ВКЛ") : s.tr("OFF", "ВЫКЛ")),
                ID_DEDICATED,
                s.dedicatedHost() ? GREEN_BRIGHT : TAB_INACTIVE_TEXT,
                s.dedicatedHost() ? GREEN_BG : TAB_INACTIVE_BG,
                Alignment.MID, CutStyle.ALL, innerW, 24f, 0f);
        dedBtn.getPosition().inTL(pad, y);
        y += 32f;

        // Row 5: Primary Host action + Copy Invite + Disable
        float primaryW = 212f;
        float copyW = 176f;
        float offW = innerW - primaryW - copyW - 16f;

        ButtonAPI applyHost = ui.addButton(
                inCampaign ? s.tr("START HOST SERVER", "ЗАПУСТИТЬ СЕРВЕР")
                        : s.tr("OPEN HOST LOBBY", "ОТКРЫТЬ ЛОББИ"),
                ID_APPLY_HOST, GREEN_BRIGHT, GREEN_TAB_ACTIVE_BG,
                Alignment.MID, CutStyle.ALL, primaryW, 34f, 0f);
        applyHost.getPosition().inTL(pad, y);

        ButtonAPI copyBtn = ui.addButton(s.tr("Copy Invite", "Копировать инвайт"), ID_COPY_INVITE,
                ACTION_TEXT, ACTION_BG, Alignment.MID, CutStyle.ALL, copyW, 34f, 0f);
        copyBtn.getPosition().inTL(pad + primaryW + 8f, y);

        ButtonAPI disableBtn = ui.addButton(
                inCampaign ? s.tr("Stop", "Стоп") : s.tr("Disable", "Выкл."),
                ID_DISABLE, DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, offW, 34f, 0f);
        disableBtn.getPosition().inTL(pad + primaryW + copyW + 16f, y);
        return y + 44f;
    }

    private static float buildJoinBody(TooltipMakerAPI ui, CoopInGameLauncherSession s,
                                       float pad, float innerW, float y, boolean inCampaign) {
        // Row 1: Invite link + Paste button
        LabelAPI invLbl = ui.addPara(
                s.tr("Paste coop:// invite from host:", "Вставьте ссылку-инвайт coop:// от хоста:"),
                LABEL_MUTED, 0f);
        invLbl.getPosition().inTL(pad, y);
        y += 20f;

        float pasteW = 136f;
        guestInviteField = createField(ui, innerW - pasteW - 8f, s.guestInvite(), 256);
        guestInviteField.getPosition().inTL(pad, y);

        ButtonAPI pasteBtn = ui.addButton(s.tr("Paste Invite", "Вставить"), ID_PASTE_INVITE,
                GREEN_BRIGHT, GREEN_BG, Alignment.MID, CutStyle.ALL, pasteW, 28f, 0f);
        pasteBtn.getPosition().inTL(pad + innerW - pasteW, y);
        y += 40f;

        // Row 2: Host IP
        LabelAPI hostLbl = ui.addPara(
                s.tr("Or enter host address manually (IP / hostname):", "Или укажите адрес хоста вручную (IP / домен):"),
                LABEL_MUTED, 0f);
        hostLbl.getPosition().inTL(pad, y);
        y += 20f;

        guestHostField = createField(ui, innerW, s.guestHost(), 120);
        guestHostField.getPosition().inTL(pad, y);
        y += 38f;

        // Row 3: Port + Password
        float portW = 120f;
        float pwW = innerW - portW - 8f;
        LabelAPI portLbl = ui.addPara(s.tr("Port:", "Порт:"), LABEL_MUTED, 0f);
        portLbl.getPosition().inTL(pad, y);
        LabelAPI pwLbl = ui.addPara(s.tr("Password:", "Пароль:"), LABEL_MUTED, 0f);
        pwLbl.getPosition().inTL(pad + portW + 8f, y);
        y += 20f;

        guestPortField = createField(ui, portW, s.guestPort(), 6);
        guestPortField.getPosition().inTL(pad, y);

        guestPwField = createField(ui, pwW, s.guestPassword(), 48);
        guestPwField.getPosition().inTL(pad + portW + 8f, y);
        y += 38f;

        // Row 4: Parsed seed hint
        String seedHint = s.guestSeed().isEmpty()
                ? s.tr("Tip: After enabling Join, click New Game (first time) or Load Game (continue).",
                        "Совет: после нажатия «Подключиться» выберите «Новая игра» или «Загрузить».")
                : s.tr("Invite seed: " + s.guestSeed()
                        + (s.guestSectorSize().isEmpty() ? "" : " · " + s.guestSectorSize())
                        + (s.guestSectorAge().isEmpty() ? "" : " · " + s.guestSectorAge()),
                        "Сид из инвайта: " + s.guestSeed()
                                + (s.guestSectorSize().isEmpty() ? "" : " · " + s.guestSectorSize())
                                + (s.guestSectorAge().isEmpty() ? "" : " · " + s.guestSectorAge()));
        LabelAPI hint = ui.addPara(seedHint, LABEL_MUTED, 0f);
        hint.getPosition().setSize(innerW, 24f);
        hint.getPosition().inTL(pad, y);
        y += 30f;

        // Row 5: Primary Join action + Disable
        float primaryW = 340f;
        float offW = innerW - primaryW - 8f;
        ButtonAPI applyJoin = ui.addButton(
                inCampaign ? s.tr("CONNECT TO HOST", "ПОДКЛЮЧИТЬСЯ К ХОСТУ")
                        : s.tr("JOIN LOBBY", "ВОЙТИ В ЛОББИ"),
                ID_APPLY_JOIN, GREEN_BRIGHT, GREEN_TAB_ACTIVE_BG,
                Alignment.MID, CutStyle.ALL, primaryW, 34f, 0f);
        applyJoin.getPosition().inTL(pad, y);

        ButtonAPI disableBtn = ui.addButton(
                inCampaign ? s.tr("Disconnect", "Отключиться") : s.tr("Disable Coop", "Выкл. кооп"),
                ID_DISABLE, DANGER_TEXT, DANGER_BG, Alignment.MID, CutStyle.ALL, offW, 34f, 0f);
        disableBtn.getPosition().inTL(pad + primaryW + 8f, y);
        return y + 44f;
    }

    private static TextFieldAPI createField(TooltipMakerAPI ui, float width, String initial, int maxChars) {
        TextFieldAPI field = ui.addTextField(width, 28f, Fonts.DEFAULT_SMALL, 0f);
        field.setMaxChars(maxChars);
        field.setHandleCtrlV(true);
        field.setBgColor(FIELD_BG);
        field.setBorderColor(GREEN_BORDER);
        field.setText(initial == null ? "" : initial);
        return field;
    }

    /** Copies current widget values back into {@link CoopInGameLauncherSession}. */
    static void syncFieldsToSession(CoopInGameLauncherSession s) {
        if (playerNameField != null) s.setPlayerName(playerNameField.getText());
        if (hostAddrField != null) s.setHostAddress(hostAddrField.getText());
        if (hostPortField != null) s.setHostPort(hostPortField.getText());
        if (hostPwField != null) s.setHostPassword(hostPwField.getText());
        if (hostSeedField != null) s.setHostSeed(hostSeedField.getText());
        if (guestInviteField != null) s.setGuestInvite(guestInviteField.getText());
        if (guestHostField != null) s.setGuestHost(guestHostField.getText());
        if (guestPortField != null) s.setGuestPort(guestPortField.getText());
        if (guestPwField != null) s.setGuestPassword(guestPwField.getText());
    }

    /**
     * Handles any modal button press. Returns {@code true} if the modal should rebuild,
     * or closes the modal via {@code onClose}.
     */
    static boolean handleButtonAction(Object buttonId, CoopInGameLauncherSession s,
                                      Runnable onClose, CampaignActions campaignActions) {
        if (!(buttonId instanceof String id)) {
            return false;
        }
        syncFieldsToSession(s);
        switch (id) {
            case ID_CLOSE -> {
                if (onClose != null) onClose.run();
                return false;
            }
            case ID_LANG -> {
                s.toggleLanguage();
                return true;
            }
            case ID_TAB_HOST -> {
                s.setActiveTab(CoopInGameLauncherSession.Tab.HOST);
                return true;
            }
            case ID_TAB_JOIN -> {
                s.setActiveTab(CoopInGameLauncherSession.Tab.JOIN);
                return true;
            }
            case ID_LOOKUP_IP -> {
                if (!lookupInFlight) {
                    lookupInFlight = true;
                    s.setStatus(s.tr("Detecting public IP...", "Определяем публичный IP..."), LABEL_MUTED);
                    CoopTitleScreenBridge.lookupPublicIpAsync(ip -> pendingPublicIp = ip == null ? "" : ip);
                }
                return true;
            }
            case ID_GEN_PW -> {
                s.regeneratePassword();
                return true;
            }
            case ID_GEN_SEED -> {
                s.regenerateSeed();
                return true;
            }
            case ID_SIZE -> {
                s.cycleSectorSize();
                return true;
            }
            case ID_AGE -> {
                s.cycleSectorAge();
                return true;
            }
            case ID_UPNP -> {
                s.togglePortMapping();
                return true;
            }
            case ID_DEDICATED -> {
                s.toggleDedicatedHost();
                return true;
            }
            case ID_COPY_INVITE -> {
                s.copyHostInviteToClipboard();
                return true;
            }
            case ID_PASTE_INVITE -> {
                String clip = CoopTitleScreenBridge.readClipboard();
                if (clip.trim().isEmpty()) {
                    s.setStatus(s.tr("Clipboard is empty.", "Буфер обмена пуст."), new Color(255, 180, 75));
                } else {
                    s.applyPastedInvite(clip);
                }
                return true;
            }
            case ID_APPLY_HOST -> {
                if (campaignActions != null) {
                    campaignActions.onHost(s);
                } else {
                    s.openHostLobby(CoopOptionsStore.system());
                }
                return true;
            }
            case ID_APPLY_JOIN -> {
                if (campaignActions != null) {
                    campaignActions.onJoin(s);
                } else {
                    s.joinHostLobby(CoopOptionsStore.system());
                }
                return true;
            }
            case ID_LOBBY_READY -> {
                s.toggleLobbyReady();
                return true;
            }
            case ID_LOBBY_START -> {
                s.startGameFromLobby();
                return true;
            }
            case ID_LOBBY_LEAVE -> {
                s.leavePreGameLobby();
                return true;
            }
            case ID_DISABLE -> {
                if (campaignActions != null) {
                    campaignActions.onStop(s);
                } else {
                    s.disableCoop(CoopOptionsStore.system());
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Optional live campaign callbacks when the form runs inside an active campaign. */
    interface CampaignActions {
        void onHost(CoopInGameLauncherSession s);
        void onJoin(CoopInGameLauncherSession s);
        void onStop(CoopInGameLauncherSession s);
    }

    static String pollPendingPublicIp() {
        String ip = pendingPublicIp;
        if (ip != null) {
            pendingPublicIp = null;
            lookupInFlight = false;
        }
        return ip;
    }

    // ---- Title Screen bottom-left compact Lobby & Roster dock plugin ----

    private static final class DockPlugin implements CustomUIPanelPlugin {
        private float px = DOCK_MARGIN;
        private float py = DOCK_MARGIN;
        private float pw = DOCK_W;
        private float ph = DOCK_H;

        @Override
        public void positionChanged(PositionAPI position) {
            if (position != null) {
                px = position.getX();
                py = position.getY();
                pw = position.getWidth();
                ph = position.getHeight();
            }
        }

        @Override
        public void renderBelow(float alphaMult) {
            drawRect(px, py, pw, ph, 0.043f, 0.086f, 0.133f, 0.94f * alphaMult);
            drawRect(px, py + ph - 30f, pw, 30f, 0.06f, 0.18f, 0.14f, 0.96f * alphaMult);
            drawBorder(px, py, pw, ph, 0.28f, 0.88f, 0.51f, 0.92f * alphaMult);
        }

        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {
            flushPendingTitleActions();
        }
        @Override public void processInput(List<InputEventAPI> events) {}

        @Override
        public void buttonPressed(Object buttonId) {
            CoopInGameLauncherSession s = CoopInGameLauncherSession.shared();
            if (ID_DOCK_CONNECT.equals(buttonId)) {
                boolean wasHost = s.armedRole() == coop.net.CoopConnectionRole.HOST;
                boolean started = s.quickConnectLastSettings(CoopOptionsStore.system());
                if (!started) {
                    openModalOnNextConnectError = false;
                    s.setActiveTab(wasHost
                            ? CoopInGameLauncherSession.Tab.HOST
                            : CoopInGameLauncherSession.Tab.JOIN);
                    modalOpen = true;
                    rebuildPending = true;
                } else {
                    openModalOnNextConnectError = !wasHost;
                    dockRebuildPending = true;
                }
                return;
            }
            if (ID_DOCK_LOAD.equals(buttonId)) {
                openModalOnNextConnectError = false;
                pendingOpenLoadGame = true;
                return;
            }
            if (ID_DOCK_OPEN.equals(buttonId)) {
                openModalOnNextConnectError = false;
                modalOpen = true;
                rebuildPending = true;
                return;
            }
            if (ID_LOBBY_READY.equals(buttonId)) {
                s.toggleLobbyReady();
                dockRebuildPending = true;
            } else if (ID_LOBBY_START.equals(buttonId)) {
                s.startGameFromLobby();
                dockRebuildPending = true;
            } else if (ID_LOBBY_LEAVE.equals(buttonId)) {
                openModalOnNextConnectError = false;
                s.leavePreGameLobby();
                dockRebuildPending = true;
            }
        }
    }

    // ---- Title Screen modal plugin ----

    private static final class ModalPlugin implements CustomUIPanelPlugin {
        private final float cardX;
        private final float cardY;
        private final float cardW;
        private final float cardH;

        ModalPlugin(float cardX, float cardY, float cardW, float cardH) {
            this.cardX = cardX;
            this.cardY = cardY;
            this.cardW = cardW;
            this.cardH = cardH;
        }

        @Override public void positionChanged(PositionAPI position) {}

        @Override
        public void renderBelow(float alphaMult) {
            float screenW = Global.getSettings().getScreenWidth();
            float screenH = Global.getSettings().getScreenHeight();
            drawRect(0f, 0f, screenW, screenH, 0.02f, 0.05f, 0.08f, 0.72f * alphaMult);
            drawRect(cardX, cardY, cardW, cardH, 0.043f, 0.086f, 0.133f, 0.96f * alphaMult);
            drawBorder(cardX, cardY, cardW, cardH, 0.28f, 0.88f, 0.51f, 0.95f * alphaMult);
        }

        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {
            flushPendingTitleActions();
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (events == null) {
                return;
            }
            for (InputEventAPI ev : events) {
                if (ev.isConsumed()) {
                    continue;
                }
                if (ev.isKeyDownEvent() && ev.getEventValue() == Keyboard.KEY_ESCAPE) {
                    ev.consume();
                    modalOpen = false;
                    return;
                }
                if (ev.isMouseEvent()) {
                    float mx = ev.getX();
                    float my = ev.getY();
                    boolean inside = mx >= cardX && mx <= cardX + cardW
                            && my >= cardY && my <= cardY + cardH;
                    if (!inside) {
                        ev.consume();
                    }
                }
            }
        }

        @Override
        public void buttonPressed(Object buttonId) {
            boolean rebuild = handleButtonAction(buttonId, CoopInGameLauncherSession.shared(),
                    () -> modalOpen = false, null);
            if (rebuild) {
                rebuildPending = true;
            }
        }
    }

    static void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    static void drawBorder(float x, float y, float w, float h, float r, float g, float b, float a) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
