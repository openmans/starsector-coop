# План реализации Headless Dedicated Server без OpenGL (`Null-LWJGL` шим)

**Дата:** 2026-11-27  
**Репозиторий:** `starsector-coop` (ветка `port/rc9`, целевая версия движка `0.98.5a-RC9`)  
**Предыдущий этап (`v0.2.19`):** режим `-Dcoop.dedicatedHost=true` уже реализован в `CoopNetPump` / `CoopSharedPauseCoordinator` / `CoopTitleScreenOverlay` / `CoopTitleScreenBridge` (`coop_vault`, живой мир в бою `1x`, авто-пауза без игроков, 4+ слотов, автозагрузка последнего сейва, автосохранение каждые 10 мин).  
**Цель данного плана:** запустить тот же самый реальный `starfarer_obf.jar` (`CampaignEngine`, XStream-сейвы, моды, `coop.jar` + `coop-forks.jar`) **без окна GUI, без GPU и без OpenGL/OpenAL-драйвера** на Windows и Linux (без `Xvfb`).

---

## 1. Архитектурная идея

Переписывать с нуля симуляцию Starsector (экономику, ИИ-флоты, генератор сектора, правила `rules.csv`, квесты, моды) невозможно без постоянного рассинхрона с клиентами. Вместо этого используется проверенный паттерн **classpath-переопределения LWJGL (`Null-LWJGL`)** — по тому же принципу, по которому уже работает `coop-forks.jar`:

1. Собирается отдельный JAR **`jars/coop-headless-lwjgl.jar`** (из отдельного `sourceSets.headless` в `src/headless/java`), который **никогда** не попадает в classpath обычного графического клиента.
2. Скрипты консольного дедика ставят `coop-headless-lwjgl.jar` **строго перед** `coop-forks.jar`, `lwjgl.jar` и `lwjgl_util.jar` в `-classpath`.
3. Все классы создания окна, OpenGL, OpenAL и ввода из `org.lwjgl.*` подменяются облегчёнными headless-заглушками, возвращающими корректные фиктивные хэндлы/константы без нативных DLL/SO (`lwjgl64.dll`, `OpenAL64.dll`, `libGL.so`).
4. Точка входа `coop.headless.CoopHeadlessServerMain` стартует **`com.fs.starfarer.combat.CombatMain`** напрямую (в обход Swing-окна настроек `com.fs.starfarer.StarfarerLauncher`) с флагами `-Dcoop.dedicatedHost=true -Dcoop.liveWorldInCombat=true -Dcoop.allowMidSessionJoin=true`.

---

## 2. Поверхность LWJGL, реально используемая движком

По предварительному анализу `jdeps` для `starfarer_obf.jar` движок обращается к следующим классам `org.lwjgl.*`:

- **Окно и контекст (`org.lwjgl.opengl`):**
  - `Display`, `DisplayMode`, `Drawable`, `PixelFormat`, `Pbuffer`, `GLContext`, `ContextCapabilities`
- **OpenGL & GLU (`org.lwjgl.opengl`, `org.lwjgl.util`):**
  - `GL11`, `org.lwjgl.util.glu.GLU`, `org.lwjgl.util.Display`
  - *(в Фазе 1 дополнительно проверить `fs.common_obf.jar`, `fs.sound_obf.jar`, `starfarer.api.jar` и `coop.jar` на вызовы `GL12`, `GL13`, `GL14`, `GL15`, `GL20`, `GL30`, `EXTFramebufferObject`, `ARB*`)*
- **Звук (`org.lwjgl.openal`):**
  - `AL`, `AL10`, `ALC10`, `ALCdevice`, `EFX10`
- **Ввод и система:**
  - `org.lwjgl.input.Keyboard`, `org.lwjgl.input.Mouse`, `org.lwjgl.input.Cursor`, `org.lwjgl.Sys`
- **Не трогать (оставить оригинальными из `lwjgl.jar` / `lwjgl_util.jar`):**
  - `BufferUtils`, `LWJGLException`, `Vector`, `Vector2f`, `Vector3f`, `ReadableVector2f`, `ReadableVector3f`, `GLUtessellator*`, `Sphere`.
  - *(При необходимости заглушить `LWJGLUtil` / native loader, чтобы не грузилась `lwjgl64.dll` — проверить в Фазе 1.)*

---

## 3. Пошаговый план реализации

### Фаза 1: Полный аудит байткода и инициализации движка
1. Запустить `jdeps -verbose:class` (или `javap -c` через скрипт) на:
   - `starfarer_obf.jar`
   - `fs.common_obf.jar`
   - `fs.sound_obf.jar`
   - `starfarer.api.jar`
   - `jars/coop.jar`
   Собрать **точный список всех методов и полей** из `org.lwjgl.*`, которые реально вызываются в байткоде.
2. Проверить через `javap -c`:
   - `com.fs.starfarer.StarfarerLauncher` и `com.fs.starfarer.combat.CombatMain` — какие аргументы/свойства передаются при автозапуске без диалога лаунчера;
   - `com.fs.graphics.TextureLoader` и загрузчик шрифтов/спрайтов — какие именно ответы от `GL11` (`glGenTextures`, `glGetInteger`, `glGetFloat`, `glGetString`, `glGetError`, `glIsEnabled`) и `ContextCapabilities` ожидаются при загрузке текстур без VRAM;
   - инициализацию звука (`fs.sound_obf.jar`), чтобы либо выставить отключение звука в настройках, либо гарантировать, что `AL` / `AL10` / `ALC10` / `EFX10` не падают при `SoundPlayer` init.

**Критерий завершения Фазы 1:** составлен полный перечень всех вызываемых методов и констант `org.lwjgl.*` по всем JAR движка.

---

### Фаза 2: Gradle-модуль `headless` (`jars/coop-headless-lwjgl.jar`)
1. В `build.gradle`:
   - Добавить `sourceSets.headless { java { srcDirs = ['src/headless/java'] } }` с `compileClasspath` на `starsectorJars` + `sourceSets.main.output`.
   - Добавить задачу `headlessJar`, собирающую `jars/coop-headless-lwjgl.jar` с манифестом (`Implementation-Version`, `Coop-Git-Commit`), и подключить её к `build`.
2. Обновить `scripts/package-release.ps1`:
   - Зафиксировать контракты разделения: классы `org/lwjgl/**` и `coop/headless/**` **никогда** не попадают в `coop.jar` и `coop-forks.jar`, а `jars/coop-headless-lwjgl.jar` опционально включается в релиз (для запуска консольного дедика).
3. Убедиться, что `CoopSandboxCompatibilityTest` не падает (в `coop.jar` по-прежнему нет запрещённых пакетов).

---

### Фаза 3: Реализация `Null-LWJGL` заглушек (`src/headless/java/org/lwjgl/...`)
Реализовать минимально достаточные потокобезопасные заглушки для классов из Фазы 1:

1. **`org.lwjgl.opengl.Display` / `DisplayMode` / `Drawable` / `PixelFormat` / `Pbuffer`:**
   - Виртуальный режим `640x480x32 @ 60Hz` (`getDesktopDisplayMode()`, `getDisplayMode()`, `getAvailableDisplayModes()`).
   - `create(...)`: без нативного окна, `isCreated() == true`, `isVisible() == true`, `isActive() == true`, `getWidth() == 640`, `getHeight() == 480`.
   - `isCloseRequested()`: привязать к `volatile boolean shutdownRequested` (взводится из JVM shutdown hook или команды `stop` в консоли).
   - `update()` / `processMessages()` / `swapBuffers()` / `makeCurrent()`: no-op.
   - `sync(int fps)`: точный таймер на `System.nanoTime()` + `Thread.sleep` (частота ограничивается `-Dcoop.headlessTickRate=30`, по умолчанию 30 TPS — достаточно для `10 Hz` флотов, `5 Hz` времени и экономного CPU).
2. **`org.lwjgl.opengl.GLContext` / `ContextCapabilities`:**
   - Статический экземпляр `ContextCapabilities`, где нужные флаги (`OpenGL11`..`OpenGL21`, `GL_ARB_framebuffer_object`, `GL_EXT_framebuffer_object`, `GL_ARB_vertex_buffer_object`, `GL_ARB_multitexture` и др.) заранее выставлены в `true`.
   - `getCapabilities()` всегда возвращает этот экземпляр.
3. **`org.lwjgl.opengl.GL11` (и прочие `GL*` из аудита Фазы 1):**
   - Все `glDraw*`, `glVertex*`, `glTexCoord*`, `glColor*`, `glBegin`/`glEnd`, `glTexImage2D`, `glTexSubImage2D`, `glBindTexture`, `glMatrixMode`, `glPushMatrix`/`glPopMatrix`, `glBlendFunc`, `glEnable`/`glDisable`, `glClear`, `glViewport` — **no-op**.
   - Счётчики ID (`AtomicInteger` от 1): `glGenTextures()`, `glGenLists()`, `glGenBuffers()`, `glGenFramebuffers()` (как перегрузки с `int`, так и с `IntBuffer`).
   - Опросы состояния:
     - `glGetError()` → `0` (`GL_NO_ERROR`)
     - `glGetInteger(GL_MAX_TEXTURE_SIZE)` → `4096`
     - `glGetInteger(GL_VIEWPORT, IntBuffer)` → `[0, 0, 640, 480]`
     - `glGetString(GL_VENDOR/GL_RENDERER/GL_VERSION)` → `"Headless"` / `"Headless NullGL"` / `"2.1"`
     - При копировании текстур в NIO-буферы (`glReadPixels` / `glGetTexImage`) — заполнять нулями, без падений.
   - Сохранить в `GL11` все публичные константы `GL_*` из оригинального `lwjgl.jar` (или сгенерировать заглушку на основе сигнатур оригинального `GL11` через скрипт-генератор, чтобы ни одно обращение к константе/методу не дало `NoSuchFieldError` / `NoSuchMethodError`).
4. **`org.lwjgl.openal` (`AL`, `AL10`, `ALC10`, `ALCdevice`, `EFX10`):**
   - `AL.create()` — без загрузки OpenAL; `AL.isCreated()` — вернуть корректно (проверить в Фазе 1, что лучше: `false` с безопасным пропуском звука или `true` с заглушками `alGenBuffers`/`alGenSources`/`alGetSourcei(AL_SOURCE_STATE) == AL_STOPPED`, чтобы не текла очередь звуковых объектов).
5. **`org.lwjgl.input` (`Keyboard`, `Mouse`, `Cursor`) и `org.lwjgl.Sys`:**
   - `create()` — no-op, `isCreated() == true`.
   - `next() == false`, `isKeyDown(...) == false`, `isButtonDown(...) == false`, `getX()/getY() == 0`.
   - `Sys.getTimerResolution() == 1000L`, `Sys.getTime()` на `System.nanoTime() / 1_000_000L` (или `System.currentTimeMillis()`).
   - `Sys.alert(title, msg)` печатает в `System.err` вместо показа нативного окна.

---

### Фаза 4: Точка входа консольного дедика и консольное управление
1. **`src/headless/java/coop/headless/CoopHeadlessServerMain.java`:**
   - Читает CLI-аргументы (`--port=7777`, `--max-guests=4`, `--tps=30`, `--save=...`) и выставляет системные свойства `-Dcoop.dedicatedHost=true`, `-Dcoop.liveWorldInCombat=true`, `-Dcoop.allowMidSessionJoin=true`.
   - Пробует режим `-Djava.awt.headless=true` (если какая-то часть загрузки UI/Font требует AWT `BufferedImage` без дисплея — при необходимости оставить `awt.headless=false` без видимых окон; `CombatMain` не открывает `JFrame` при правильном запуске).
   - Регистрирует JVM Shutdown Hook (`SIGINT`/`SIGTERM`), выставляет `Display.requestClose()` и даёт циклу корректно сохраниться и разослать `SESSION_LEAVE` перед выходом.
   - Вызывает `com.fs.starfarer.combat.CombatMain.main(args)`.
2. **Авто-подключение к уже запущенному дедику через лобби (удобство для игроков):**
   - Убедиться, что при активном дедике клиент может как зайти сразу в игру, так и при желании получить свой личный сейв из `saves/coop_vault/<ник>.zip`.
3. **Консоль администратора (`CoopNetPump` / `CoopHeadlessConsole`):**
   - Периодический краткий статус в `stdout` (раз в 30 сек и на событиях join/leave/save): игроки онлайн, текущий игровой день, статус паузы (`EMPTY_HOLD` / `LIVE_1X` / `PAUSED`), TPS, последнее автосохранение.
   - Чтение команд из `System.in` в отдельном daemon-потоке: `status`, `save`, `kick <ник>`, `stop` (сохранить мир, закрыть сокеты, выйти).

---

### Фаза 5: Скрипты запуска (Windows и Linux)
1. **`Start Headless Dedicated Server.cmd` (Windows):**
   - Находит корень Starsector, читает `vmparams`, ставит **`..\mods\coop\jars\coop-headless-lwjgl.jar`** и **`..\mods\coop\jars\coop-forks.jar`** перед `lwjgl.jar`, запускает **`jre\bin\java.exe`** (консольный процесс, не `javaw.exe`) с главным классом `coop.headless.CoopHeadlessServerMain`.
2. **`start-headless-dedicated.sh` (Linux VDS без X11/Xvfb):**
   - Аналогичный запуск под Linux JRE через двоеточие `:` в `-classpath`, с параметром `-Djava.library.path=...` (хотя при чистых стабах LWJGL нативные `.so` не требуются).

---

### Фаза 6: Итеративная отладка на реальном `C:\@\Starsector` и приёмка
1. **Живой smoke-тест запуска без GUI:**
   - Запустить `Start Headless Dedicated Server.cmd` на `C:\@\Starsector` и проверить `starsector.log` + консоль.
   - По логу отловить и дозаглушить любые недостающие методы/поля в `TextureLoader`, `StarfarerSettings`, `Font` или `CampaignState` (особенно в первом запуске до перехода `TitleScreenState` → автозагрузка сейва → `CampaignState`).
2. **Проверка сетевой сессии с реальным клиентом:**
   - Подключить обычный графический клиент к консольному дедику на `127.0.0.1:7777` (на другом порту/установке).
   - Проверить: снятие паузы при входе гостя, возврат паузы при выходе всех гостей, стабильные ~30 TPS без просадок при свёрнутом/отсутствующем окне, `liveWorldInCombat = true` (мир не останавливается в бою), сохранение и запись в `coop_vault/<ник>.zip`, корректное завершение по `stop` / `Ctrl+C`.

---

## 4. Критерии приёмки (Acceptance Criteria)
1. Дедик стартует в обычной консоли на Windows **без единого графического окна** (`0` окон, `0%` GPU, нативные библиотеки LWJGL/OpenGL/OpenAL не загружаются).
2. Дедик стартует на Linux без `Xvfb` и без X-сервера.
3. Автоматически загружается последний сейв кампании и открывается хост-порт (по умолчанию `7777`).
4. Дедик держит стабильные ~30 TPS, ставит мир на паузу при `0` игроков, снимает паузу при входе игрока, сохраняет `1x` ход мира во время боёв клиентов.
5. Команда `stop` или `Ctrl+C` выполняет финальное сохранение (`coop_vault`) и освобождает порт `7777` без повреждения сейвов.
6. Обычный графический клиент и лаунчер работают без изменений (`coop-headless-lwjgl.jar` не попадает в его classpath), все тесты `./gradlew test` и проверка `scripts/package-release.ps1` проходят полностью.
