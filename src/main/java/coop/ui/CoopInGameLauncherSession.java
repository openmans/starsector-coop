package coop.ui;

import coop.CoopModPlugin;
import coop.ambient.CoopTitleScreenBridge;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.net.CoopConnectionRole;
import coop.net.CoopInGameInvite;
import coop.net.CoopNetStartupConfig;
import coop.storm.CoopStormBridge;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    private boolean russian;
    private Tab activeTab = Tab.HOST;

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

    public CoopInGameLauncherSession() {
        this.russian = CoopTitleScreenBridge.loadPreferRussian();
    }

    /** Populates fields once from current {@link CoopOptionsStore} / startup config. */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        syncFromStore(CoopOptionsStore.system());
        if (hostSeed.isEmpty()) {
            hostSeed = CoopInGameInvite.generateSeed();
        }
        if (hostPassword.isEmpty()) {
            hostPassword = CoopInGameInvite.generatePassword();
        }
        if (hostAddress.isEmpty()) {
            hostAddress = CoopTitleScreenBridge.detectLocalLanAddress();
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
                    hostPort = String.valueOf(cfg.port());
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
        return russian;
    }

    public void toggleLanguage() {
        russian = !russian;
        CoopTitleScreenBridge.savePreferRussian(russian);
    }

    public void setRussian(boolean russian) {
        this.russian = russian;
    }

    public String tr(String en, String ru) {
        return russian ? ru : en;
    }

    public Tab activeTab() { return activeTab; }
    public void setActiveTab(Tab tab) { if (tab != null) this.activeTab = tab; }

    public String hostAddress() { return hostAddress; }
    public void setHostAddress(String v) { hostAddress = trim(v); }

    public String hostPort() { return hostPort; }
    public void setHostPort(String v) { hostPort = trim(v); }

    public String hostPassword() { return hostPassword; }
    public void setHostPassword(String v) { hostPassword = v == null ? "" : v.trim(); }

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
        CoopModPlugin.applyRuntimeSeed(seed);
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
        CoopModPlugin.applyRuntimeSeed(seed);
        CoopStormBridge.configureGuest(true);
        armedRole = CoopConnectionRole.GUEST;

        setStatus(tr("JOIN armed (" + host + ":" + port + ")! Press New Game or Load Game to connect.",
                        "ПОДКЛЮЧЕНИЕ готово (" + host + ":" + port + ")! Нажмите «Новая игра» или «Загрузить»."),
                new Color(110, 255, 165));
        return true;
    }

    /** Turns off CO-OP auto-start and restores pure singleplayer mode. */
    public void disableCoop(CoopOptionsStore store) {
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
        CoopModPlugin.applyRuntimeSeed("");
        CoopStormBridge.configureGuest(false);
        armedRole = CoopConnectionRole.NONE;
        setStatus(tr("CO-OP disabled (Singleplayer mode).",
                "Кооп отключён (обычная одиночная игра)."), new Color(180, 200, 210));
    }

    /** Short dynamic label for the green CO-OP button on the Title Screen. */
    public String titleButtonLabel() {
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
                CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID
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
}
