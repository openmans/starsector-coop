package coop.ui;

import coop.CoopModPlugin;
import coop.ambient.CoopTitleScreenBridge;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.net.CoopConnectionRole;
import coop.net.CoopInGameInvite;
import coop.net.CoopNetStartupConfig;
import coop.net.CoopPortMapper;
import coop.net.CoopPreGameLobby;
import coop.storm.CoopStormBridge;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Headless-testable state and config controller behind the native in-game CO-OP window
 * (used on both the Title Screen and inside a running campaign).
 */
public final class CoopInGameLauncherSession {

    public enum Tab {
        HOST,
        JOIN
    }

    public static final int DEFAULT_PORT = 7777;
    private static final String[] SECTOR_SIZES = { "normal", "small" };
    private static final String[] STAR_AGES = { "mixed", "young", "average", "old" };

    private static final CoopInGameLauncherSession SHARED = new CoopInGameLauncherSession();

    public static CoopInGameLauncherSession shared() {
        return SHARED;
    }

    private Tab activeTab = Tab.HOST;
    private String playerName = "";
    private final CoopPreGameLobby preGameLobby = new CoopPreGameLobby();
    private String lastSyncedGuestLobbySeed = "";
    private boolean launchedFromPreGameLobby;
    private boolean pendingAutoEnterGame;
    private String pendingLoadSavePath = "";
    private boolean pendingRestoreGuestFromSharedSave;

    // Host fields
    private String hostAddress = "";
    private String hostPort = String.valueOf(DEFAULT_PORT);
    private String hostPassword = "";
    private String hostSeed = "";
    private boolean portMappingAuto = true;
    private String sectorSize = "normal";
    private String sectorAge = "mixed";
    private String hostCampaignId = "";

    // Join fields
    private String guestInvite = "";
    private String guestHost = "";
    private String guestPort = String.valueOf(DEFAULT_PORT);
    private String guestPassword = "";
    private String guestSeed = "";
    private String guestSectorSize = "";
    private String guestSectorAge = "";
    private String guestCampaignId = "";

    // Armed mode reflected on the green Title Screen button
    private CoopConnectionRole armedRole = CoopConnectionRole.NONE;
    private String statusText = "";
    private Color statusColor = new Color(165, 225, 195);
    private boolean initialized;
    private CoopPortMapper lobbyPortMapper;
    private boolean lobbyPortMapperReported;
    private BiFunction<Integer, Boolean, CoopPortMapper> lobbyPortMapperFactory;

    public CoopInGameLauncherSession() {
    }

    /** Populates fields once from current {@link CoopOptionsStore} / startup config. */
    public void ensureInitialized() {
        CoopUiLang.initFromPreferences();
        if (initialized) {
            return;
        }
        initialized = true;
        syncFromStore(CoopOptionsStore.system());
        if (hostSeed.isEmpty()) {
            hostSeed = CoopInGameInvite.generateSeed();
        }
        // The host screen always starts at DEFAULT_PORT (7777) and with no password. A remembered
        // port and a remembered "password is set" flag both surfaced as settings the player never
        // chose, and a generated password nobody asked for is a secret waiting to leak into a log.
        // A password is typed for that session only; Regenerate makes one on demand.
        if (hostAddress.isEmpty()) {
            hostAddress = CoopTitleScreenBridge.detectLocalLanAddress();
        }
        if (playerName.isEmpty()) {
            playerName = CoopTitleScreenBridge.loadPlayerName();
        }
        if (guestHost.isEmpty()) {
            String savedHost = CoopTitleScreenBridge.loadLastJoinHost();
            if (!savedHost.isEmpty()) {
                guestHost = savedHost;
                String savedPort = CoopTitleScreenBridge.loadLastJoinPort();
                if (!savedPort.isEmpty()) {
                    guestPort = savedPort;
                }
            }
        }
    }

    /** Reloads current config values from the given store (also used directly by unit tests). */
    public void syncFromStore(CoopOptionsStore store) {
        if (store == null) {
            return;
        }
        try {
            CoopNetStartupConfig cfg = CoopNetStartupConfig.from(store);
            if (cfg.isPresent()) {
                armedRole = cfg.role();
                if (cfg.role() == CoopConnectionRole.HOST) {
                    activeTab = Tab.HOST;
                    // A port only comes from the launch itself (the launcher passed -Dcoop.hostPort).
                    // The settings store keeps the port the last session used, and reading it back
                    // made the host screen come up on whatever was tried months ago instead of 7777.
                    String launchPort = System.getProperty(CoopOptionsRegistry.HOST_PORT);
                    hostPort = launchPort != null && !launchPort.trim().isEmpty()
                            ? String.valueOf(cfg.port())
                            : String.valueOf(DEFAULT_PORT);
                    hostPassword = cfg.password();
                    portMappingAuto = cfg.portMappingEnabled();
                } else if (cfg.role() == CoopConnectionRole.GUEST) {
                    activeTab = Tab.JOIN;
                    guestHost = cfg.host();
                    guestPort = String.valueOf(cfg.port());
                    guestPassword = cfg.password();
                }
            } else {
                armedRole = CoopConnectionRole.NONE;
            }
            String seed = store.rawOneShot(CoopOptionsRegistry.NEW_GAME_SEED);
            if (seed != null && !seed.trim().isEmpty()) {
                if (armedRole == CoopConnectionRole.GUEST) {
                    guestSeed = seed.trim();
                } else {
                    hostSeed = seed.trim();
                }
            }
            String size = store.rawOneShot(CoopOptionsRegistry.SECTOR_SIZE);
            if (size != null && !size.trim().isEmpty()) {
                sectorSize = normalizeSize(size);
            }
            String age = store.rawOneShot(CoopOptionsRegistry.SECTOR_AGE);
            if (age != null && !age.trim().isEmpty()) {
                sectorAge = normalizeAge(age);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopInGameLauncherSession.class, "Could not sync in-game launcher state", ex);
        }
    }

    public boolean isRussian() {
        return CoopUiLang.isRussian();
    }

    public void toggleLanguage() {
        CoopUiLang.toggle();
    }

    public void setRussian(boolean russian) {
        CoopUiLang.setRussian(russian);
    }

    public String tr(String en, String ru) {
        return CoopUiLang.tr(en, ru);
    }

    public Tab activeTab() { return activeTab; }
    public void setActiveTab(Tab tab) { if (tab != null) this.activeTab = tab; }

    public String playerName() { return playerName; }
    public void setPlayerName(String v) {
        String clean = trim(v);
        if (!clean.isEmpty() && !clean.equals(playerName)) {
            playerName = clean.length() > 28 ? clean.substring(0, 28) : clean;
            if (isLiveGameRuntime()) {
                CoopTitleScreenBridge.savePlayerName(playerName);
            }
        } else if (clean.isEmpty()) {
            playerName = "";
        }
    }

    private static boolean isLiveGameRuntime() {
        try {
            return com.fs.starfarer.api.Global.getSettings() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyRuntimeSeedSafe(String seed) {
        if (isLiveGameRuntime()) {
            CoopModPlugin.applyRuntimeSeed(seed);
            return;
        }
        String clean = seed == null ? "" : seed.trim();
        if (clean.isEmpty()) {
            System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        } else {
            System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, clean);
        }
    }

    public CoopPreGameLobby preGameLobby() { return preGameLobby; }
    public boolean isPreGameLobbyActive() { return preGameLobby.isActive(); }

    public String hostAddress() { return hostAddress; }
    public void setHostAddress(String v) { hostAddress = trim(v); }

    public String hostPort() { return hostPort; }
    public void setHostPort(String v) { hostPort = trim(v); }

    public String hostPassword() { return hostPassword; }
    public void setHostPassword(String v) {
        hostPassword = v == null ? "" : v.trim();
    }

    public String hostSeed() { return hostSeed; }
    public void setHostSeed(String v) { hostSeed = trim(v); }

    public boolean portMappingAuto() { return portMappingAuto; }
    public void togglePortMapping() { portMappingAuto = !portMappingAuto; }

    public String sectorSize() { return sectorSize; }
    public void cycleSectorSize() {
        sectorSize = "small".equals(sectorSize) ? "normal" : "small";
    }

    public String sectorAge() { return sectorAge; }
    public void cycleSectorAge() {
        for (int i = 0; i < STAR_AGES.length; i++) {
            if (STAR_AGES[i].equals(sectorAge)) {
                sectorAge = STAR_AGES[(i + 1) % STAR_AGES.length];
                return;
            }
        }
        sectorAge = STAR_AGES[0];
    }

    public void setHostCampaignContext(String seed, String size, String age, String campaignId) {
        if (seed != null && !seed.trim().isEmpty()) {
            hostSeed = seed.trim();
        }
        if (size != null && !size.trim().isEmpty()) {
            sectorSize = normalizeSize(size);
        }
        if (age != null && !age.trim().isEmpty()) {
            sectorAge = normalizeAge(age);
        }
        hostCampaignId = trim(campaignId);
    }

    public String guestInvite() { return guestInvite; }
    public void setGuestInvite(String v) { guestInvite = trim(v); }

    public String guestHost() { return guestHost; }
    public void setGuestHost(String v) { guestHost = trim(v); }

    public String guestPort() { return guestPort; }
    public void setGuestPort(String v) { guestPort = trim(v); }

    public String guestPassword() { return guestPassword; }
    public void setGuestPassword(String v) { guestPassword = v == null ? "" : v.trim(); }

    public String guestSeed() { return guestSeed; }
    public String guestSectorSize() { return guestSectorSize; }
    public String guestSectorAge() { return guestSectorAge; }
    public String guestCampaignId() { return guestCampaignId; }

    public CoopConnectionRole armedRole() { return armedRole; }
    public String statusText() { return statusText; }
    public Color statusColor() { return statusColor; }

    public void setStatus(String text, Color color) {
        this.statusText = text == null ? "" : text;
        this.statusColor = color == null ? Color.WHITE : color;
    }

    public void regenerateSeed() {
        hostSeed = CoopInGameInvite.generateSeed();
        setStatus(tr("Generated new seed: " + hostSeed, "Создан новый сид: " + hostSeed),
                new Color(110, 255, 165));
    }

    public void regeneratePassword() {
        hostPassword = CoopInGameInvite.generatePassword();
        setStatus(tr("Generated new password.", "Сгенерирован новый пароль."),
                new Color(110, 255, 165));
    }

    /** Parses a pasted {@code coop://...} invite into all Guest fields. */
    public boolean applyPastedInvite(String rawText) {
        guestInvite = trim(rawText);
        CoopInGameInvite.Parsed parsed = CoopInGameInvite.parse(guestInvite);
        if (!parsed.ok()) {
            setStatus(tr("Invalid invite: " + parsed.error(),
                    "Некорректный инвайт: " + parsed.error()), new Color(255, 120, 110));
            return false;
        }
        CoopInGameInvite.Invite inv = parsed.invite();
        guestHost = inv.host();
        guestPort = String.valueOf(inv.port());
        guestPassword = inv.password();
        guestSeed = inv.seed();
        guestSectorSize = inv.sectorSize();
        guestSectorAge = inv.sectorAge();
        guestCampaignId = inv.campaignId();
        setStatus(tr("Invite parsed: " + guestHost + ":" + guestPort
                        + (guestSeed.isEmpty() ? "" : " · seed " + guestSeed),
                "Инвайт распознан: " + guestHost + ":" + guestPort
                        + (guestSeed.isEmpty() ? "" : " · сид " + guestSeed)),
                new Color(110, 255, 165));
        return true;
    }

    /** Builds the current {@code coop://...} invite for the Host tab, or {@code ""} if incomplete. */
    public String buildHostInvite() {
        int port = parsePort(hostPort);
        if (port < 1) {
            return "";
        }
        String addr = hostAddress.isEmpty() ? CoopTitleScreenBridge.detectLocalLanAddress() : hostAddress;
        if (addr.isEmpty()) {
            addr = "127.0.0.1";
        }
        return CoopInGameInvite.format(addr, port, hostSeed, hostPassword,
                sectorSize, sectorAge, hostCampaignId);
    }

    /** Copies the host invite to the OS clipboard and updates status. */
    public boolean copyHostInviteToClipboard() {
        String invite = buildHostInvite();
        if (invite.isEmpty()) {
            setStatus(tr("Enter a valid port (1..65535) first.",
                    "Сначала укажите корректный порт (1..65535)."), new Color(255, 180, 75));
            return false;
        }
        boolean copied = CoopTitleScreenBridge.writeClipboard(invite);
        if (copied) {
            setStatus(tr("Invite copied to clipboard!", "Инвайт скопирован в буфер обмена!"),
                    new Color(110, 255, 165));
        } else {
            setStatus(tr("Invite: " + invite, "Инвайт: " + invite), new Color(255, 180, 75));
        }
        return copied;
    }

    /**
     * Arms HOST mode in {@link CoopOptionsStore} and live runtime bridges (seed + storm role).
     * Works directly from the Title Screen before clicking New Game or Load Game.
     */
    public boolean applyHostConfig(CoopOptionsStore store) {
        int port = parsePort(hostPort);
        if (port < 1) {
            setStatus(tr("Port must be between 1 and 65535.",
                    "Порт должен быть числом от 1 до 65535."), new Color(255, 120, 110));
            return false;
        }
        String seed = trim(hostSeed);
        if (!seed.isEmpty() && CoopNetStartupConfig.validateNewGameSeed(seed) != null) {
            setStatus(tr("Seed must look like MN-1234567890...",
                    "Сид должен быть вида MN-1234567890..."), new Color(255, 120, 110));
            return false;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CoopOptionsRegistry.HOST_PORT, String.valueOf(port));
        values.put(CoopOptionsRegistry.CONNECT_HOST, null);
        values.put(CoopOptionsRegistry.CONNECT_PORT, null);
        values.put(CoopOptionsRegistry.PASSWORD, hostPassword.isEmpty() ? null : hostPassword);
        values.put(CoopOptionsRegistry.PORT_MAPPING, portMappingAuto ? "auto" : "off");
        values.put(CoopOptionsRegistry.NEW_GAME_SEED, seed.isEmpty() ? null : seed);
        values.put(CoopOptionsRegistry.SECTOR_SIZE, sectorSize);
        values.put(CoopOptionsRegistry.SECTOR_AGE, sectorAge);
        values.put(CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID, null);

        clearConflictingSystemProperties();
        if (store != null) {
            store.writeSessionLaunchSettings(values);
            store.reload();
        }
        applyRuntimeSeedSafe(seed);
        CoopStormBridge.configureGuest(false);
        armedRole = CoopConnectionRole.HOST;

        String invite = buildHostInvite();
        boolean copied = !invite.isEmpty() && CoopTitleScreenBridge.writeClipboard(invite);
        setStatus(copied
                        ? tr("HOST armed (port " + port + ") & invite copied! Press New Game or Load Game.",
                                "ХОСТ готов (порт " + port + "), инвайт скопирован! Нажмите «Новая игра» или «Загрузить».")
                        : tr("HOST armed (port " + port + ")! Press New Game or Load Game.",
                                "ХОСТ готов (порт " + port + ")! Нажмите «Новая игра» или «Загрузить»."),
                new Color(110, 255, 165));
        return true;
    }

    /**
     * Arms GUEST mode in {@link CoopOptionsStore} and live runtime bridges (seed + storm role).
     * Automatically parses {@link #guestInvite} if present.
     */
    public boolean applyGuestConfig(CoopOptionsStore store) {
        if (!guestInvite.isEmpty() && guestInvite.toLowerCase(Locale.ROOT).startsWith(CoopInGameInvite.SCHEME)) {
            if (!applyPastedInvite(guestInvite)) {
                return false;
            }
        }
        String host = trim(guestHost);
        int port = parsePort(guestPort);
        if (host.isEmpty()) {
            setStatus(tr("Paste a coop:// invite or enter the host IP address.",
                    "Вставьте ссылку coop:// или укажите IP-адрес хоста."), new Color(255, 120, 110));
            return false;
        }
        if (port < 1) {
            setStatus(tr("Port must be between 1 and 65535.",
                    "Порт должен быть числом от 1 до 65535."), new Color(255, 120, 110));
            return false;
        }
        String seed = trim(guestSeed);
        if (!seed.isEmpty() && CoopNetStartupConfig.validateNewGameSeed(seed) != null) {
            seed = "";
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CoopOptionsRegistry.HOST_PORT, null);
        values.put(CoopOptionsRegistry.CONNECT_HOST, host);
        values.put(CoopOptionsRegistry.CONNECT_PORT, String.valueOf(port));
        values.put(CoopOptionsRegistry.PASSWORD, guestPassword.isEmpty() ? null : guestPassword);
        values.put(CoopOptionsRegistry.NEW_GAME_SEED, seed.isEmpty() ? null : seed);
        values.put(CoopOptionsRegistry.SECTOR_SIZE, guestSectorSize.isEmpty() ? null : guestSectorSize);
        values.put(CoopOptionsRegistry.SECTOR_AGE, guestSectorAge.isEmpty() ? null : guestSectorAge);
        values.put(CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID,
                guestCampaignId.isEmpty() ? null : guestCampaignId);

        clearConflictingSystemProperties();
        if (store != null) {
            store.writeSessionLaunchSettings(values);
            store.reload();
        }
        applyRuntimeSeedSafe(seed);
        CoopStormBridge.configureGuest(true);
        if (store == CoopOptionsStore.system() && isLiveGameRuntime()) {
            CoopTitleScreenBridge.saveLastJoinSettings(host, String.valueOf(port));
        }
        armedRole = CoopConnectionRole.GUEST;

        setStatus(tr("JOIN armed (" + host + ":" + port + ")! Press New Game or Load Game to connect.",
                        "ПОДКЛЮЧЕНИЕ готово (" + host + ":" + port + ")! Нажмите «Новая игра» или «Загрузить»."),
                new Color(110, 255, 165));
        return true;
    }

    /** Arms HOST mode and opens the live Title Screen pre-game lobby on the configured port. */
    public boolean openHostLobby(CoopOptionsStore store) {
        if (isLiveGameRuntime()) {
            CoopModPlugin.forceShutdownActiveSession();
        }
        if (!applyHostConfig(store)) {
            return false;
        }
        int port = parsePort(hostPort);
        String name = playerName.isEmpty() ? tr("Host", "Хост") : playerName;
        boolean started = preGameLobby.startHost(port, hostPassword, name,
                hostSeed, sectorSize, sectorAge, System.currentTimeMillis());
        if (!started) {
            setStatus(tr("Could not open lobby on port " + port + ": " + preGameLobby.lastError(),
                    "Не удалось открыть лобби на порту " + port + ": " + preGameLobby.lastError()),
                    new Color(255, 120, 110));
            return false;
        }
        startLobbyPortMapper(port);
        String invite = buildHostInvite();
        boolean copied = !invite.isEmpty() && isLiveGameRuntime() && CoopTitleScreenBridge.writeClipboard(invite);
        if (portMappingAuto && lobbyPortMapper != null) {
            setStatus(copied
                            ? tr("Lobby open (port " + port + "), mapping UPnP... Invite copied.",
                                    "Лобби открыто (порт " + port + "), настройка UPnP... Инвайт скопирован.")
                            : tr("Lobby open (port " + port + "), mapping UPnP... Waiting for players.",
                                    "Лобби открыто (порт " + port + "), настройка UPnP... Ожидание игроков."),
                    new Color(110, 255, 165));
        } else {
            setStatus(copied
                            ? tr("Lobby open (port " + port + ") & invite copied! Waiting for players...",
                                    "Лобби открыто (порт " + port + "), инвайт скопирован! Ожидание игроков...")
                            : tr("Lobby open (port " + port + ")! Share your invite and wait for players.",
                                    "Лобби открыто (порт " + port + ")! Отправьте инвайт друзьям."),
                    new Color(110, 255, 165));
        }
        return true;
    }

    /** Arms GUEST mode and connects to the host's live Title Screen pre-game lobby. */
    public boolean joinHostLobby(CoopOptionsStore store) {
        if (!applyGuestConfig(store)) {
            return false;
        }
        String host = trim(guestHost);
        int port = parsePort(guestPort);
        String name = playerName.isEmpty() ? tr("Guest", "Гость") : playerName;
        lastSyncedGuestLobbySeed = guestSeed;
        boolean started = preGameLobby.startGuest(host, port, guestPassword, name, System.currentTimeMillis());
        if (!started) {
            setStatus(tr("Could not connect to lobby " + host + ":" + port + ": " + preGameLobby.lastError(),
                    "Не удалось подключиться к лобби " + host + ":" + port + ": " + preGameLobby.lastError()),
                    new Color(255, 120, 110));
            return false;
        }
        setStatus(tr("Connecting to lobby " + host + ":" + port + "...",
                "Подключение к лобби " + host + ":" + port + "..."),
                new Color(150, 225, 255));
        return true;
    }

    /** True when previous Guest connection parameters (host + valid port) are available. */
    public boolean hasSavedJoinSettings() {
        return !trim(guestHost).isEmpty() && parsePort(guestPort) > 0;
    }

    /** Short display target for the compact bottom-left Lobby dock widget. */
    public String savedJoinTargetLabel() {
        if (!hasSavedJoinSettings()) {
            return "";
        }
        return trim(guestHost) + ":" + parsePort(guestPort);
    }

    /**
     * One-click CONNECT from the compact bottom-left Lobby widget:
     * <ul>
     *   <li>When armed as HOST (launched in Host mode from launcher or configured as Host):
     *       immediately opens the Host Lobby on the configured port.</li>
     *   <li>Otherwise: attempts to join as Guest using previously saved settings; returns
     *       {@code false} if no settings exist or connect fails so the Join modal opens with error.</li>
     * </ul>
     */
    public boolean quickConnectLastSettings(CoopOptionsStore store) {
        if (armedRole == CoopConnectionRole.HOST) {
            activeTab = Tab.HOST;
            return openHostLobby(store);
        }
        activeTab = Tab.JOIN;
        if (!hasSavedJoinSettings()) {
            setStatus(tr(
                    "No saved connection settings yet. Enter host IP or paste coop:// invite below.",
                    "Нет сохранённых настроек подключения. Укажите IP хоста или вставьте инвайт ниже."),
                    new Color(255, 165, 90));
            return false;
        }
        return joinHostLobby(store);
    }

    /** Ticks the live Title Screen pre-game lobby. Returns {@code true} when UI should rebuild. */
    public boolean tickPreGameLobby(CoopOptionsStore store) {
        if (!preGameLobby.isActive()) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean mapperChanged = tickLobbyPortMapper(now);
        boolean changed = preGameLobby.tick(now);
        if (!changed) {
            return mapperChanged;
        }
        CoopPreGameLobby.State st = preGameLobby.state();
        if (st == CoopPreGameLobby.State.ERROR) {
            String err = preGameLobby.lastError();
            String lower = err == null ? "" : err.toLowerCase(Locale.ROOT);
            if ("WRONG_PASSWORD".equals(err)) {
                setStatus(tr("Wrong lobby password! Check password and try again.",
                        "Неверный пароль лобби! Проверьте пароль и попробуйте снова."),
                        new Color(255, 120, 110));
            } else if (lower.contains("refused")) {
                setStatus(tr("Could not connect: host lobby is not open (" + savedJoinTargetLabel() + ").",
                        "Не удалось подключиться: лобби хоста не открыто (" + savedJoinTargetLabel() + ")."),
                        new Color(255, 120, 110));
            } else if (lower.contains("timed out")) {
                setStatus(tr("Connection timed out (" + savedJoinTargetLabel() + "). Check IP/port.",
                        "Тайм-аут подключения (" + savedJoinTargetLabel() + "). Проверьте IP и порт."),
                        new Color(255, 120, 110));
            } else {
                setStatus(tr("Lobby disconnected: " + err, "Отключено от лобби: " + err),
                        new Color(255, 165, 90));
            }
            return true;
        }
        if (!preGameLobby.isHost() && st == CoopPreGameLobby.State.IN_LOBBY) {
            syncGuestSettingsFromLobby(store);
            setStatus(tr("Connected to host lobby! Press READY when ready to launch.",
                    "Вы в лобби хоста! Нажмите «ГОТОВ», когда будете готовы начать."),
                    new Color(110, 255, 165));
            return true;
        }
        if (!preGameLobby.isHost()) {
            byte[] completedZip = preGameLobby.consumeCompletedSaveZip();
            if (completedZip != null && completedZip.length > 0) {
                boolean isOwn = preGameLobby.consumeCompletedSaveIsOwn();
                // Saves downloaded directly from the host's live lobby/dedicated server may lack
                // the host's in-memory campaignId if the host hasn't saved to disk since player 1
                // joined; arm the one-shot consent so seed lock adopts it on entry.
                System.setProperty(CoopOptionsRegistry.ADOPT_CAMPAIGN_ID, "true");
                String extractedDir = isLiveGameRuntime()
                        ? CoopTitleScreenBridge.extractPlayerSaveZip(playerName, completedZip) : "";
                if (!extractedDir.isEmpty()) {
                    pendingLoadSavePath = extractedDir;
                    // Only restore from host's embedded snapshot when loading host's fallback save,
                    // never when loading the guest's own personal save from host vault!
                    pendingRestoreGuestFromSharedSave = !isOwn;
                }
            }
        }
        if (preGameLobby.isTransferringSave()) {
            int pct = preGameLobby.saveTransferPercent();
            setStatus(preGameLobby.isHost()
                            ? tr("Sending shared save to partner: " + pct + "%",
                                    "Отправка общего сейва напарнику: " + pct + "%")
                            : tr("Downloading host's shared save: " + pct + "%",
                                    "Скачивание сейва хоста: " + pct + "%"),
                    new Color(130, 225, 255));
            return true;
        }
        if (preGameLobby.isLaunchTriggered()) {
            launchedFromPreGameLobby = true;
            pendingAutoEnterGame = true;
            if (!preGameLobby.isHost()) {
                syncGuestSettingsFromLobby(store);
                setStatus(tr("HOST IS STARTING! Entering the game...",
                        "ХОСТ ЗАПУСКАЕТ СЕССИЮ! Входим в игру..."),
                        new Color(110, 255, 165));
            } else {
                setStatus(tr("SESSION STARTING! Entering the game...",
                        "ЗАПУСК СЕССИИ! Входим в игру..."),
                        new Color(110, 255, 165));
            }
            return true;
        }
        return true;
    }

    private void syncGuestSettingsFromLobby(CoopOptionsStore store) {
        String remoteSeed = trim(preGameLobby.worldSeed());
        String remoteSize = trim(preGameLobby.worldSize());
        String remoteAge = trim(preGameLobby.worldAge());
        boolean differs = (!remoteSeed.isEmpty() && !remoteSeed.equals(lastSyncedGuestLobbySeed))
                || (!remoteSize.isEmpty() && !remoteSize.equals(guestSectorSize))
                || (!remoteAge.isEmpty() && !remoteAge.equals(guestSectorAge));
        if (!differs) {
            return;
        }
        if (!remoteSeed.isEmpty()) {
            guestSeed = remoteSeed;
            lastSyncedGuestLobbySeed = remoteSeed;
        }
        if (!remoteSize.isEmpty()) {
            guestSectorSize = remoteSize;
        }
        if (!remoteAge.isEmpty()) {
            guestSectorAge = remoteAge;
        }
        // Persist synced host world parameters quietly so Guest's New Game matches Host automatically.
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CoopOptionsRegistry.NEW_GAME_SEED, guestSeed.isEmpty() ? null : guestSeed);
        values.put(CoopOptionsRegistry.SECTOR_SIZE, guestSectorSize.isEmpty() ? null : guestSectorSize);
        values.put(CoopOptionsRegistry.SECTOR_AGE, guestSectorAge.isEmpty() ? null : guestSectorAge);
        if (store != null) {
            store.writeSessionLaunchSettings(values);
            store.reload();
        }
        CoopModPlugin.applyRuntimeSeed(guestSeed);
    }

    public void toggleLobbyReady() {
        preGameLobby.toggleLocalReady();
    }

    /** True when the current campaign launch came directly from our Title Screen Pre-Game Lobby. */
    public boolean wasLaunchedFromPreGameLobby() {
        return launchedFromPreGameLobby;
    }

    public void clearLaunchedFromPreGameLobby() {
        launchedFromPreGameLobby = false;
    }

    /** One-shot latch consumed on the Title Screen to enter Continue / New Game immediately. */
    public boolean consumePendingAutoEnterGame() {
        if (!pendingAutoEnterGame) {
            return false;
        }
        pendingAutoEnterGame = false;
        return true;
    }

    public String consumePendingLoadSavePath() {
        String p = pendingLoadSavePath;
        pendingLoadSavePath = "";
        return p == null ? "" : p;
    }

    public boolean consumePendingRestoreGuestFromSharedSave() {
        if (!pendingRestoreGuestFromSharedSave) {
            return false;
        }
        pendingRestoreGuestFromSharedSave = false;
        return true;
    }

    public boolean startGameFromLobby() {
        if (!preGameLobby.isHost() || preGameLobby.state() != CoopPreGameLobby.State.HOSTING) {
            return false;
        }
        if (!preGameLobby.allReady()) {
            setStatus(tr("Waiting for all players to press READY ("
                            + preGameLobby.readyCount() + "/" + preGameLobby.totalCount() + ").",
                    "Ждём готовности всех участников ("
                            + preGameLobby.readyCount() + "/" + preGameLobby.totalCount() + ")."),
                    new Color(255, 195, 95));
            return false;
        }
        pendingLoadSavePath = "";
        pendingRestoreGuestFromSharedSave = false;
        if (isLiveGameRuntime() && preGameLobby.totalCount() > 1) {
            String hostSavePath = CoopTitleScreenBridge.findLatestHostSaveDirPath();
            byte[][] hostFallbackZip = new byte[1][];
            boolean transferring = preGameLobby.triggerHostStartWithPlayerSaves(guestName -> {
                byte[] personalZip = CoopTitleScreenBridge.loadPlayerSaveZip(guestName);
                if (personalZip != null && personalZip.length > 0) {
                    return new CoopPreGameLobby.SavePayload(
                            "save_coop_" + CoopTitleScreenBridge.sanitizePlayerVaultKey(guestName),
                            personalZip, true);
                }
                if (!hostSavePath.isEmpty()) {
                    if (hostFallbackZip[0] == null) {
                        hostFallbackZip[0] = CoopTitleScreenBridge.packageSaveDirectoryAsZip(hostSavePath);
                    }
                    if (hostFallbackZip[0] != null && hostFallbackZip[0].length > 0) {
                        return new CoopPreGameLobby.SavePayload(
                                CoopTitleScreenBridge.SHARED_SAVE_DIR_NAME, hostFallbackZip[0], false);
                    }
                }
                return null;
            }, System.currentTimeMillis());
            if (transferring) {
                pendingLoadSavePath = hostSavePath;
                setStatus(tr("Sending saves to partner: 0%",
                        "Отправка сейвов игрокам: 0%"),
                        new Color(130, 225, 255));
                return true;
            }
            if (!hostSavePath.isEmpty()) {
                pendingLoadSavePath = hostSavePath;
            }
        }
        preGameLobby.triggerHostStart();
        launchedFromPreGameLobby = true;
        pendingAutoEnterGame = true;
        setStatus(tr("SESSION STARTING! Entering the game...",
                "ЗАПУСК СЕССИИ! Входим в игру..."),
                new Color(110, 255, 165));
        return true;
    }

    public void leavePreGameLobby() {
        closeLobbyPortMapper(true);
        preGameLobby.stop();
        launchedFromPreGameLobby = false;
        pendingAutoEnterGame = false;
        pendingLoadSavePath = "";
        pendingRestoreGuestFromSharedSave = false;
        setStatus(tr("Left the pre-game lobby.", "Вы вышли из лобби."), new Color(180, 200, 210));
    }

    /** Releases the pre-game lobby port cleanly when leaving the Title Screen into the campaign. */
    public void stopPreGameLobbyOnStateExit() {
        // Keep the router UPnP mapping alive while transitioning into the campaign so CoopNetPump can renew it.
        closeLobbyPortMapper(false);
        preGameLobby.closeChannelsKeepSummary();
    }

    /** Turns off CO-OP auto-start and restores pure singleplayer mode. */
    public void disableCoop(CoopOptionsStore store) {
        closeLobbyPortMapper(true);
        preGameLobby.stop();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CoopOptionsRegistry.HOST_PORT, null);
        values.put(CoopOptionsRegistry.CONNECT_HOST, null);
        values.put(CoopOptionsRegistry.CONNECT_PORT, null);
        values.put(CoopOptionsRegistry.NEW_GAME_SEED, null);
        values.put(CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID, null);

        clearConflictingSystemProperties();
        if (store != null) {
            store.writeSessionLaunchSettings(values);
            store.reload();
        }
        applyRuntimeSeedSafe("");
        CoopStormBridge.configureGuest(false);
        armedRole = CoopConnectionRole.NONE;
        setStatus(tr("CO-OP disabled (Singleplayer mode).",
                "Кооп отключён (обычная одиночная игра)."), new Color(180, 200, 210));
    }

    /** Short dynamic label for the green CO-OP button on the Title Screen. */
    public String titleButtonLabel() {
        if (preGameLobby.isActive()) {
            if (preGameLobby.state() == CoopPreGameLobby.State.CONNECTING) {
                return tr("CO-OP · CONNECTING...", "CO-OP · ПОДКЛЮЧЕНИЕ...");
            }
            return tr("CO-OP · LOBBY (" + preGameLobby.readyCount() + "/" + preGameLobby.totalCount() + ")",
                    "CO-OP · ЛОББИ (" + preGameLobby.readyCount() + "/" + preGameLobby.totalCount() + ")");
        }
        if (preGameLobby.isLaunchTriggered()) {
            return tr("CO-OP · ENTER WORLD!", "CO-OP · ВХОД В МИР!");
        }
        if (armedRole == CoopConnectionRole.HOST) {
            return tr("CO-OP · HOST :" + hostPort, "CO-OP · ХОСТ :" + hostPort);
        }
        if (armedRole == CoopConnectionRole.GUEST) {
            return tr("CO-OP · JOIN", "CO-OP · ГОСТЬ");
        }
        return "CO-OP";
    }

    public String sectorSizeLabel() {
        return "small".equals(sectorSize)
                ? tr("Small", "Малый")
                : tr("Normal", "Обычный");
    }

    public String starAgeLabel() {
        return switch (sectorAge) {
            case "young" -> tr("Young", "Молодые");
            case "average" -> tr("Average", "Средние");
            case "old" -> tr("Old", "Старые");
            default -> tr("Mixed", "Смешанные");
        };
    }

    private static void clearConflictingSystemProperties() {
        String[] keys = {
                CoopOptionsRegistry.HOST_PORT,
                CoopOptionsRegistry.CONNECT_HOST,
                CoopOptionsRegistry.CONNECT_PORT,
                CoopOptionsRegistry.PASSWORD,
                CoopOptionsRegistry.PORT_MAPPING,
                CoopOptionsRegistry.SECTOR_SIZE,
                CoopOptionsRegistry.SECTOR_AGE,
                CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID,
                CoopOptionsRegistry.ADOPT_CAMPAIGN_ID
        };
        for (String key : keys) {
            try {
                System.clearProperty(key);
            } catch (SecurityException ignored) {
            }
        }
    }

    public static int parsePort(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        try {
            int p = Integer.parseInt(text.trim());
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String normalizeSize(String raw) {
        return "small".equalsIgnoreCase(trim(raw)) ? "small" : "normal";
    }

    private static String normalizeAge(String raw) {
        String v = trim(raw).toLowerCase(Locale.ROOT);
        for (String allowed : STAR_AGES) {
            if (allowed.equals(v)) {
                return allowed;
            }
        }
        return "mixed";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    void setLobbyPortMapperFactoryForTests(BiFunction<Integer, Boolean, CoopPortMapper> factory) {
        this.lobbyPortMapperFactory = factory;
    }

    CoopPortMapper lobbyPortMapperForTests() {
        return lobbyPortMapper;
    }

    private void startLobbyPortMapper(int port) {
        if (lobbyPortMapper != null) {
            closeLobbyPortMapper(lobbyPortMapper.port() != port);
        }
        lobbyPortMapperReported = false;
        if (lobbyPortMapperFactory != null) {
            lobbyPortMapper = lobbyPortMapperFactory.apply(port, portMappingAuto);
        } else if (isLiveGameRuntime()) {
            lobbyPortMapper = CoopPortMapper.start(port, portMappingAuto, System::currentTimeMillis);
        }
    }

    private void closeLobbyPortMapper(boolean releaseMapping) {
        CoopPortMapper mapper = lobbyPortMapper;
        lobbyPortMapper = null;
        lobbyPortMapperReported = false;
        if (mapper == null) {
            return;
        }
        if (releaseMapping) {
            mapper.shutdown();
        } else {
            mapper.closeWithoutRelease();
        }
    }

    private boolean tickLobbyPortMapper(long now) {
        if (lobbyPortMapper == null || !preGameLobby.isHost()) {
            return false;
        }
        lobbyPortMapper.tick(now);
        CoopPortMapper.Result res = lobbyPortMapper.result();
        if (lobbyPortMapperReported || !res.finished()) {
            return false;
        }
        lobbyPortMapperReported = true;
        return applyLobbyPortMapperResult(res);
    }

    boolean applyLobbyPortMapperResult(CoopPortMapper.Result res) {
        if (res == null) {
            return false;
        }
        if (res.mapped()) {
            CoopLog.info(CoopInGameLauncherSession.class,
                    "Coop Title Screen lobby mapped " + res.externalEndpoint() + " via " + res.tier());
            if (res.cgnat()) {
                setStatus(tr("Lobby open, but CGNAT (" + res.externalAddress()
                                + ") blocks direct joins. Use VPN / IPv6.",
                        "Лобби открыто, но провайдер за CGNAT (" + res.externalAddress()
                                + ") блокирует прямой вход. Нужен VPN / IPv6."),
                        new Color(255, 185, 80));
            } else {
                if (!res.externalAddress().isEmpty()
                        && (hostAddress.isEmpty() || isPrivateIpv4Address(hostAddress))) {
                    hostAddress = res.externalAddress();
                }
                String invite = buildHostInvite();
                boolean copied = !invite.isEmpty() && isLiveGameRuntime()
                        && CoopTitleScreenBridge.writeClipboard(invite);
                setStatus(tr("Lobby open! UPnP mapped " + res.externalEndpoint()
                                + (copied ? " (invite copied)" : "") + ". Waiting for players...",
                        "Лобби открыто! Порт открыт (UPnP): " + res.externalEndpoint()
                                + (copied ? " (инвайт скопирован)" : "") + ". Ожидание игроков..."),
                        new Color(110, 255, 165));
            }
            return true;
        }
        if (!res.failureText().isEmpty()) {
            CoopLog.warn(CoopInGameLauncherSession.class,
                    "Coop Title Screen lobby UPnP failed: " + res.failureText());
            int p = lobbyPortMapper != null ? lobbyPortMapper.port() : parsePort(hostPort);
            setStatus(tr("Lobby open (port " + p + "), UPnP failed: " + res.failureText()
                            + ". Forward port manually or use VPN.",
                    "Лобби открыто (порт " + p + "), UPnP не сработал: " + res.failureText()
                            + ". Пробросьте порт вручную или используйте VPN."),
                    new Color(255, 185, 80));
            return true;
        }
        return false;
    }

    static boolean isPrivateIpv4Address(String address) {
        String s = trim(address);
        if (s.isEmpty() || "localhost".equalsIgnoreCase(s) || "0.0.0.0".equals(s)) {
            return true;
        }
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException ex) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127);
    }
}
