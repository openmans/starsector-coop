package coop.launcher;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * Bilingual EN / RU dictionary and live Swing tree translator for the Starsector Coop launcher.
 *
 * <p>English strings stay the canonical keys so existing tests and headless defaults remain
 * deterministic in {@link Lang#EN}, while real runs default to the OS locale (RU on Russian
 * Windows, EN otherwise) and persist the player's explicit toggle via {@link Preferences}.
 */
final class CoopLauncherI18n {

    enum Lang {
        EN("EN"),
        RU("RU");

        final String code;

        Lang(String code) {
            this.code = code;
        }

        Lang other() {
            return this == EN ? RU : EN;
        }
    }

    private static final String PREF_KEY = "launcher.lang";
    private static final String PROP_EN_TEXT = "coop.i18n.en";
    private static final String PROP_EN_PLACEHOLDER = "coop.i18n.ph";
    private static final String PROP_EN_TITLE = "coop.i18n.title";

    private static final Map<String, String> EN_TO_RU = new LinkedHashMap<>();

    static {
        // Header & mode switcher
        put("Check updates", "Обновления");
        put("Checking...", "Проверка...");
        put("Up to date", "Актуально");
        put("Installing...", "Установка...");
        put("Settings", "Настройки");
        put("Logs", "Логи");
        put("Install / Update", "Установка мода");
        put("Host a game", "Запустить сервер");
        put("Join a game", "Подключиться");

        // Update banner
        put("Update available", "Доступно обновление");
        put("Update now", "Обновить");
        put("Later", "Позже");

        // Install / Update mode
        put("Starsector executable", "Файл игры (starsector.exe)");
        put("Select starsector.exe...", "Выберите starsector.exe...");
        put("Browse .exe", "Выбрать .exe");
        put("Also install The Wingman Project (optional: manual co-op piloting)",
                "Также установить The Wingman Project (опционально: ручное пилотирование)");
        put("Also update The Wingman Project (installed — optional manual co-op piloting)",
                "Также обновить The Wingman Project (установлен — ручное пилотирование)");
        put("Fix vmparams & mods", "Починить vmparams и моды");
        put("Check GitHub", "Проверить GitHub");
        put("All checks", "Все проверки");
        put("Select starsector.exe to install or update Starsector Coop from GitHub.",
                "Выберите starsector.exe для установки или обновления Starsector Coop с GitHub.");

        // Host mode
        put("Campaign", "Кампания");
        put("New campaign", "Новая кампания");
        put("Customize", "Настроить");
        put("Details", "Подробнее");
        put("Campaign settings", "Настройки кампании");
        put("Sector seed", "Сид сектора");
        put("Generate a seed", "Сгенерировать сид");
        put("Generate seed", "Новый сид");
        put("Sector size", "Размер сектора");
        put("Star age", "Возраст звёзд");
        put("Connection settings", "Настройки подключения");
        put("Address your partner connects to", "Адрес для подключения напарника");
        put("Public, LAN or VPN address", "Публичный, LAN или VPN адрес");
        put("Look up", "Определить");
        put("Looking up…", "Поиск…");
        put("Port", "Порт");
        put("Password", "Пароль");
        put("No password", "Без пароля");
        put("Full invite", "Полная ссылка-инвайт");
        put("An address and valid seed are needed", "Нужны адрес и корректный сид");
        put("Use a LAN or VPN address when that is how you connect. The invite carries the address, password and campaign settings.",
                "Укажите LAN или VPN адрес, если подключаетесь через них. Инвайт содержит адрес, пароль и параметры кампании.");
        put("Invite your partner", "Пригласить напарника");
        put("Copy invite", "Копировать");
        put("Copied", "Скопировано");
        put("No address selected", "Адрес не выбран");
        put("Copy invite to look up your address, or edit the connection.",
                "Нажмите «Копировать» или укажите адрес вручную.");
        put("Looking up your address…", "Определение адреса…");
        put("Set a valid seed in Customize.", "Укажите корректный сид в «Настроить».");
        put("Set a valid port in Edit connection.", "Укажите корректный порт в настройках сети.");
        put("Check the address in Edit connection.", "Проверьте адрес в настройках сети.");
        put("Invite copied · Send it to your partner", "Инвайт скопирован · Отправьте напарнику");
        put("Settings changed · Copy a fresh invite", "Настройки изменены · Скопируйте новый инвайт");
        put("Ready to share", "Готово к отправке");
        put("Clipboard unavailable · Copy the full invite in Edit connection.",
                "Буфер недоступен · Скопируйте инвайт в настройках сети.");
        put("Address lookup failed · Enter an address in Edit connection.",
                "Не удалось определить адрес · Введите его в настройках сети.");
        put("Start a New Game with the shared seed.", "Начните «Новую игру» с общим сидом.");

        // Guest mode
        put("Invite from your host", "Инвайт от хоста");
        put("Paste the coop:// invite from your host", "Вставьте ссылку coop:// от хоста");
        put("Paste invite", "Вставить");
        put("Paste an invite, or enter the connection manually.",
                "Вставьте инвайт или укажите подключение вручную.");
        put("Host connection", "Подключение к хосту");
        put("Host address", "Адрес хоста");
        put("Name, IPv4 or IPv6", "Домен, IPv4 или IPv6");
        put("None", "Нет");
        put("From the invite or your host", "Из инвайта или от хоста");
        put("Sector size (from invite)", "Размер сектора (из инвайта)");
        put("Star age (from invite)", "Возраст звёзд (из инвайта)");
        put("Your session", "Ваша сессия");
        put("Your host's campaign and connection will appear here.",
                "Здесь появятся параметры кампании и адрес хоста.");

        // Connection card
        put("Connection not checked", "Соединение не проверено");
        put("Check before your partner tries to join.", "Проверьте до подключения напарника.");
        put("Test the host's launcher before starting the game.",
                "Проверьте связь с лаунчером хоста перед запуском.");
        put("Check connection", "Проверить связь");
        put("Check again", "Проверить снова");
        put("Checking…", "Проверка…");
        put("Connection details", "Подробности связи");
        put("Host reachable", "Хост доступен");
        put("Router prepared", "Порт подготовлен");

        // Footer & launch button
        put("Checking installation", "Проверка установки");
        put("Install ready", "Готово к запуску");
        put("no install", "игра не выбрана");
        put("Launch Starsector", "Запустить Starsector");
        put("Game running", "Игра запущена");
        put("Select game .exe", "Выбрать .exe");
        put("Opens Starsector's launcher. Then press Play.",
                "Откроет лаунчер Starsector. Затем нажмите Play.");
        put("Press Play in Starsector's launcher, then start or load your campaign.",
                "Нажмите Play в лаунчере Starsector, затем начните или загрузите кампанию.");
        put("Close Starsector before installing or updating the mod.",
                "Закройте Starsector перед установкой или обновлением мода.");
        put("Downloading and configuring Starsector Coop from GitHub...",
                "Скачивание и настройка Starsector Coop с GitHub...");
        put("Pick starsector.exe to download and install Starsector Coop.",
                "Выберите starsector.exe для скачивания и установки Starsector Coop.");
        put("Downloads the latest GitHub release, patches vmparams and enables the mod.",
                "Скачает последний релиз с GitHub, пропатчит vmparams и включит мод.");
        put("Starsector is already running from this launcher.",
                "Starsector уже запущен из этого лаунчера.");
        put("Choose your Starsector folder in Installation details.",
                "Выберите папку Starsector на вкладке установки.");
        put("Resolve the install problems in Details before launching.",
                "Устраните проблемы установки в «Подробнее» перед запуском.");
        put("Finishing the connection check before launching.",
                "Завершается проверка соединения перед запуском.");
        put("The port has to be a number between 1 and 65535.",
                "Порт должен быть числом от 1 до 65535.");
        put("Set a valid sector seed in Campaign settings.",
                "Укажите корректный сид сектора в настройках кампании.");
        put("Paste the invite from your host, or type the host address in.",
                "Вставьте инвайт от хоста или введите адрес хоста вручную.");
        put("Fix the invite, or clear it to use a manual connection.",
                "Исправьте инвайт или очистите поле для ручного подключения.");

        // Dialogs & Settings
        put("Installation", "Установка");
        put("Install", "Установка");
        put("Refresh", "Обновить");
        put("Game .exe", "Файл .exe");
        put("Guide", "Справка");
        put("Show all checks", "Показать все проверки");
        put("Hide passed checks", "Скрыть пройденные");
        put("Open release page", "Открыть релиз");
        put("Install update", "Установить обновление");
        put("Fix", "Починить");
        put("Done", "Готово");
        put("Changes are used when you launch.", "Изменения применятся при запуске игры.");
        put("Session log", "Лог сессии");
        put("This window can stay open while you play.", "Это окно можно держать открытым во время игры.");
        put("Save a bug report", "Сохранить отчёт");
        put("Include newest save", "Включить последнее сохранение");
        put("Open log folder", "Папка логов");
        put("Clear", "Очистить");
        put("General", "Основные");
        put("Developer", "Разработчик");
        put("Port mapping", "Проброс порта");
        put("Link HUD corner", "Угол HUD связи");
        put("Log marker key", "Клавиша метки лога");
        put("Reconnect grace (seconds)", "Ожидание переподключения (сек)");
        put("Developer flags", "Флаги разработчика");
        put("Agent bridge", "Мост агента");
        put("Enabled", "Включён");
        put("Diagnostics", "Диагностика");
        put("Datagram wiretap", "Дамп датаграмм");
        put("Frame profiler", "Профайлер кадров");
        put("Full-fidelity guest system", "Полная симуляция системы гостя");
        put("Disable shared fast-forward", "Отключить общее ускорение");
        put("Disable clock reconciler", "Отключить синхронизацию часов");
        put("Allow game version mismatch", "Разрешить несовпадение версии игры");
    }

    private CoopLauncherI18n() {
    }

    private static void put(String en, String ru) {
        EN_TO_RU.put(en, ru);
    }

    /** Detects saved preference, falling back to OS locale (RU on Russian Windows, EN otherwise). */
    static Lang loadPreferred() {
        try {
            String saved = Preferences.userNodeForPackage(CoopLauncherI18n.class).get(PREF_KEY, "");
            if ("ru".equalsIgnoreCase(saved)) return Lang.RU;
            if ("en".equalsIgnoreCase(saved)) return Lang.EN;
        } catch (RuntimeException ignored) {
        }
        return "ru".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? Lang.RU : Lang.EN;
    }

    static void savePreferred(Lang lang) {
        if (lang == null) return;
        try {
            Preferences.userNodeForPackage(CoopLauncherI18n.class).put(PREF_KEY, lang.code);
        } catch (RuntimeException ignored) {
        }
    }

    static String t(Lang lang, String en) {
        if (en == null || lang == null || lang == Lang.EN) {
            return en == null ? "" : en;
        }
        return EN_TO_RU.getOrDefault(en, en);
    }

    static String tr(Lang lang, String en, String ru) {
        return lang == Lang.RU ? ru : en;
    }

    /** Translates every known static label, button, tab, placeholder and dialog title in {@code root}. */
    static void applyTree(Component root, Lang lang) {
        if (root == null) return;
        if (root instanceof JDialog dialog) {
            JComponent rp = dialog.getRootPane();
            String enTitle = remember(rp, PROP_EN_TITLE, dialog.getTitle());
            if (enTitle != null) {
                dialog.setTitle(t(lang, enTitle));
            }
        }
        if (root instanceof JComponent jc) {
            if (jc instanceof AbstractButton button) {
                String current = button.getText();
                if (current != null && !current.isEmpty() && !"RU".equals(current) && !"EN".equals(current)) {
                    String en = rememberKnown(jc, PROP_EN_TEXT, current);
                    if (en != null) {
                        button.setText(t(lang, en));
                    }
                }
            } else if (jc instanceof JLabel label) {
                String current = label.getText();
                if (current != null && !current.isEmpty()) {
                    String en = rememberKnown(jc, PROP_EN_TEXT, current);
                    if (en != null) {
                        label.setText(t(lang, en));
                    }
                }
            } else if (jc instanceof JTextArea area) {
                String current = area.getText();
                if (current != null && !current.isEmpty()) {
                    String en = rememberKnown(jc, PROP_EN_TEXT, current);
                    if (en != null) {
                        area.setText(t(lang, en));
                    }
                }
            }
            if (jc instanceof JTextField) {
                Object ph = jc.getClientProperty("JTextField.placeholderText");
                if (ph instanceof String text && !text.isEmpty()) {
                    String enPh = rememberKnown(jc, PROP_EN_PLACEHOLDER, text);
                    if (enPh != null) {
                        jc.putClientProperty("JTextField.placeholderText", t(lang, enPh));
                    }
                }
            }
            if (jc instanceof JTabbedPane tabs) {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    String key = PROP_EN_TEXT + ".tab." + i;
                    String en = rememberKnown(jc, key, tabs.getTitleAt(i));
                    if (en != null) {
                        tabs.setTitleAt(i, t(lang, en));
                    }
                }
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyTree(child, lang);
            }
        }
    }

    private static String remember(JComponent jc, String prop, String current) {
        Object existing = jc.getClientProperty(prop);
        if (existing instanceof String s) return s;
        if (current != null && !current.isEmpty()) {
            jc.putClientProperty(prop, current);
            return current;
        }
        return null;
    }

    private static String rememberKnown(JComponent jc, String prop, String current) {
        Object existing = jc.getClientProperty(prop);
        if (existing instanceof String s) return s;
        if (EN_TO_RU.containsKey(current)) {
            jc.putClientProperty(prop, current);
            return current;
        }
        return null;
    }
}
