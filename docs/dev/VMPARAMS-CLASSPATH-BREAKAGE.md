# Сломанный classpath от тюнинг-паков vmparams: суть и план фикса

Дата: 2026-11-26. Инцидент: ПК Denis (DESKTOP-B3DG57R), coop 0.2.4, Wingman 0.9.4-beta, игра 0.98.5a-RC9.

## Суть проблемы

Игрок применил сторонний тюнинг-пак vmparams (RU-сборка с «производительными» флагами JVM). Пак
перегенерирует строку `-classpath`, подставляя **абсолютный путь в двойных кавычках** как элемент
classpath. Если установка игры лежит в пути **с пробелами**, JVM получает classpath разрезанным на
несколько аргументов — и все джары после кавычки выпадают из classpath.

Два реальных образца из инцидента:

```
# образец 1 — vmparams (испорчен)
-classpath ..\mods\coop\jars\coop-forks.jar;"G:\Starsector 0.98.5\Starsector\vmparams" janino.jar;commons-compiler.jar;...

# образец 2 — vmparams.txt, шаблон тюнинг-пака (испорчен иначе)
-classpath "G:\Starsector 0.98.5\Starsector\mods\coop\jars\coop-forks.jar" janino.jar;commons-compiler.jar;...
```

**Симптом:** `starsector.exe` стартует и мгновенно выходит (код 0), либо игра падает на загрузке
модов. Хвост `starsector.log`:

```
java.lang.NoClassDefFoundError: org/codehaus/janino/JavaSourceClassLoader
	at com.fs.starfarer.loading.scripts.ScriptStore.o00000(Unknown Source)
	...
Caused by: java.lang.ClassNotFoundException: org.codehaus.janino.JavaSourceClassLoader
```

## Механика поломки

1. `starsector.exe` (нативный лаунчер игры) собирает командную строку JVM из текста vmparams,
   разбивая аргументы по пробелам. Значение `-classpath` обязано быть **одним аргументом без
   пробелов** (либо целиком в кавычках как единый токен).
2. Запись `;"<путь с пробелами>" janino.jar;...` содержит пробел ВНУТРИ значения classpath:
   после закрывающей кавычки всё (`janino.jar;commons...`) уходит отдельным аргументом JVM.
   В classpath попадают только записи слева от кавычки: forks-джар и мусорный путь.
3. JVM стартует штатно (fork-джар и мусорный элемент в classpath есть), игра доезжает до
   `ScriptStore`, где впервые нужен janino (компиляция скриптов модов) → `NoClassDefFoundError`.
4. Внешне выглядит как «кооп-мод сломал загрузку», хотя мод ни при чём: classpath был испорчен
   третьим инструментом, а сам факт порчи не виден нигде, кроме полного текста vmparams.

Важно: поломка не зависит от версии игры/мода и воспроизводится на стоковом Zulu 17. Флаги
тюнинг-пака (`-XX:UseAVX=3`, `-XX:+ZeroTLAB`, `-XX:ShenandoahGCMode=iu`, ...) Zulu 17.0.10
принимает (проверено на машине игрока: JVM стартует, exit 0) — они не причина.

## Почему наш лаунчер этого не поймал

`CoopInstallCheck` / `CoopVmparamsText.hasForksFirstOnClasspath` проверяют ТОЛЬКО первый элемент
classpath (`coop-forks.jar`). В инциденте он был на месте — строка показывала `[OK]`, игра всё
равно падала. Ни одна проверка не смотрит на:

- двойные кавычки / пробелы внутри значения classpath;
- абсолютные пути как элементы classpath (тем более пути не к джару — `...\vmparams` без `.jar`);
- наличие остальных стоковых джаров (janino.jar и др.) в списке.

## План фикса (в лаунчере)

### 1. Новая проверка `classpath sanity` в CoopInstallCheck (red-строка, блокирует запуск)

Считать classpath НЕисправным, если выполнено любое:

- в значении classpath есть двойная кавычка;
- есть элемент, начинающийся с буквы диска (`C:\`, `D:\`...) или `\\` (абсолютный путь);
- есть элемент без `.jar` (кроме последнего, несущего main-класс);
- в списке голых имён отсутствует любой из обязательных стоковых джаров: `janino.jar`,
  `commons-compiler.jar`, `commons-compiler-jdk.jar`, `starfarer.api.jar`, `lwjgl.jar`,
  `lwjgl_util.jar`, `log4j-1.2.9.jar` (или полный канонический список, сравнить с тем, что
  поставляет установщик игры).

Текст строки для игрока: «vmparams classpath был сгенерирован сторонним инструментом и сломан —
нажмите Fix, лаунчер восстановит канонический список».

### 2. Новый Fix в CoopInstallFixer: `repairClasspath(text)`

- выбросить из classpath ВСЕ элементы, кроме стокового списка джаров (сравнение по имени файла,
  регистр/слэши игнорировать, как в `isForksEntry`);
- оставить нетронутыми все флаги JVM, `-D...`-свойства и main-класс (тюнинг-флаги сохраняются);
- собрать: `<голова до -classpath>` + ` -classpath ` + `..\mods\coop\jars\coop-forks.jar;` +
  `<канонический список>` + ` com.fs.starfarer.StarfarerLauncher`;
- идемпотентно (двойной Fix даёт тот же файл), бэкап один раз в `vmparams.backup`, как в
  текущем `applyVmparams`;
- НЕ трогать `-Dcoop.*` (текущее правило сохраняется).

### 3. Юнит-тесты

- `CoopVmparamsTextTest`: `hasForksFirstOnClasspath` по-прежнему true на обоих образцах (первый
  элемент валиден) — и НОВАЯ функция `classpathSanity` возвращает fail на обоих.
- `CoopInstallFixerTest`: `repairClasspath` превращает оба инцидентных образца в канон
  (относительные голые имена, forks первым, флаги и main-класс на месте); идемпотентность.
- `CoopInstallCheckTest`: red-строка появляется на испорченных образцах и исчезает после Fix.

### 4. Доки

- `docs/player/INSTALL.md` секция 3: предупреждение — vmparams-тюнеры (RU-паки
  «оптимизации»), которые переписывают classpath абсолютными путями в кавычках, ломают запуск
  при установке в путь с пробелами; после применения тюнера нажать Fix в лаунчере.

## Ручное исправление у игрока (пока фикс не в релизе)

PowerShell на машине игрока (или через Agent Gateway):

```powershell
$p='<install>\vmparams'
$t=[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)
# образец 1: убрать путь к файлу vmparams, попавший в classpath
$t=$t -replace ';"[^"]*\\vmparams" ?',';'
# образец 2: абсолютный путь к coop-forks.jar в кавычках -> каноническая относительная запись
$t=$t -replace '-classpath "G:[^"]*coop-forks\.jar" ','-classpath ..\mods\coop\jars\coop-forks.jar;'
[IO.File]::Copy($p,$p+'.broken.bak',$true)
[IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))
```

После этого тюнинг-пак повторно НЕ применять — он снова сгенерирует испорченный classpath.
Флаги производительности при этом не теряются: они остаются в отремонтированном файле.

## Итог инцидента (что уже сделано на машине игрока)

- classpath в `vmparams` и в шаблоне `vmparams.txt` исправлен до канонического вида;
- испорченные версии сохранены как `vmparams.broken.bak` / `vmparams.txt.broken.bak`;
- флаги тюнинга проверены на Zulu 17.0.10 игрока (JVM стартует, exit 0);
- папка игры переименована в путь без пробелов (`G:\Starsector0.98.5\Starsector`) — лаунчер
  путь определяет от своего местоположения, перенастройка не нужна.
