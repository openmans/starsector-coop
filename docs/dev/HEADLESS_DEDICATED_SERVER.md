# Headless dedicated server (Null-LWJGL)

Status: **implemented and smoke-tested on Windows** (`0.98.5a-RC9`, mod `0.2.20`).
Design: `docs/dev/HEADLESS_DEDICATED_SERVER_PLAN.md`. Audit: `docs/dev/HEADLESS_LWJGL_AUDIT.md`.

The server runs the **real** `starfarer_obf.jar` — campaign engine, XStream saves, mods and
`coop.jar` are untouched. Only LWJGL is replaced: `jars/coop-headless-lwjgl.jar` sits first on the
classpath and every `org.lwjgl.*` class resolves to a stub that creates no window, loads no native
library and issues no GL/AL call. No GPU, no OpenGL/OpenAL driver, no X server, no Xvfb.

## Run

Windows (from the mod folder inside the install):

```
Start Headless Dedicated Server.cmd [port] [maxGuests] [tps]
```

Linux (no X11 required):

```
scripts/start-headless-dedicated.sh --root /path/to/Starsector --port 7777
```

Both read the install's `vmparams`, keep every JVM flag it carries (including `-noverify`, which the
obfuscated engine needs), and build a classpath whose first entry is the headless jar. See
`scripts/start-headless.ps1` / `scripts/start-headless-dedicated.sh` for the details.

Console (stdin):

| command | effect |
|---|---|
| `status` | role, players, game day, pause holder, TPS |
| `save` | coordinated autosave now (retries while a screen is open) |
| `kick <name>` | drops one guest by lobby name or player id |
| `stop` | autosave, then exit; a second Ctrl+C halts immediately |
| `help` | list the commands |

A status line is also printed every 30 s, and `SIGINT`/`SIGTERM` are turned into `stop` instead of
killing the process mid-write.

## What it is made of

| piece | where | why |
|---|---|---|
| Null-LWJGL stubs + the two engine shims | `src/headless/java`, `tools/headless/engine-shims.jar` | replaces LWJGL without touching the simulation |
| `CoopHeadlessServerMain` | `src/headless/java/coop/headless/CoopHeadlessServerMain.java` | sets `-Dcoop.dedicatedHost` etc., boots the engine |
| `CoopHeadlessConsole` | `src/main/java/coop/headless/CoopHeadlessConsole.java` | stdin/signal commands on the campaign thread; ships in `coop.jar` |
| `headlessJar` task | `build.gradle` | builds `jars/coop-headless-lwjgl.jar` |

### Null-LWJGL shim

`tools/headless/gen_null_lwjgl.py` reads the game's `lwjgl.jar`, walks every class reachable from
the audit seeds, and emits a complete stand-in: same fields (with their real constant values), same
method descriptors, same superclasses/interfaces. Only a handful of methods carry behaviour —
`Display` (virtual 1024x768 mode, frame pacing, close flag), `glGen*`/`alGen*` id counters,
`glGetInteger`/`glGetString`/`glGet*` answers, `glCheckFramebufferStatusEXT` = complete,
`alGetSourcei(state)` = stopped, `Sys.getTime`, `Keyboard`/`Mouse` no-ops, `AL` "created" with
`ALC` extension queries false. Everything else is a no-op.

The jar is a **complete LWJGL replacement**: it also carries the original classes the engine still
needs (`BufferUtils`, the vector classes, the Java tessellator). Copying them is not cosmetic —
`lwjgl.jar` is `Sealed: true`, so mixing classes from two jars in package `org.lwjgl` fails with
`sealing violation: can't seal package org.lwjgl: already defined`. One jar, one code source.

Regenerate after a game/lwjgl bump:

```
python tools/headless/gen_null_lwjgl.py            # stubs  (lwjgl.jar from the install)
python tools/headless/make_engine_shims.py         # engine shims (starfarer_obf.jar)
python tools/headless/audit_lwjgl.py <jars>        # refresh docs/dev/HEADLESS_LWJGL_AUDIT.md
```

### Two engine-level shims

1. **`com.fs.starfarer.combat.CombatMain`** — a source shim that forwards to the real class, which
   ships renamed as `coop.headless.RealCombatMain`. The launcher calls
   `CombatMain.main(new String[]{w, h, fullscreen})`; with three arguments the real class probes
   `Toolkit.getScreenSize()` and its own catch answers with `System.exit(1)` when that throws (no
   display). The shim always passes zero arguments — the display is fixed by the stub anyway.
2. **`com.fs.starfarer.launcher.ooOO…` (the launcher settings class)** — its screen-scale method
   computes `Toolkit.getDefaultToolkit().getScreenSize()`, and `CombatMain` calls it
   unconditionally. The committed class has those six bytes rewritten to
   `coop.headless.HeadlessScreen.size()` (same length, so no offset moves), which removes the last
   display dependency from the boot path. That is what makes `-Djava.awt.headless=true` work on
   both platforms.

Both live in `tools/headless/engine-shims.jar` (the second class has a 282-character name and does
not fit a Windows path as a loose file). The jar is committed so a release build needs no Python.

## Verified (2026-11-27, Windows 11, test copy of the install)

- Boots with **0 windows**, `opengl32.dll`/`GLU32.dll`/`lwjgl64.dll`/`OpenAL64.dll` **not loaded**
  (the earlier Windows-Toolkit build did map `opengl32.dll`; headless AWT plus the screen patch
  removed it), GPU engine utilisation 0 %.
- Auto-loads the newest campaign save, opens the host port (TCP+UDP), releases the lobby for
  drop-ins and holds the world paused (`EMPTY_HOLD`) with zero players.
- Steady ~30 TPS with `--tps=30` (`Display.update` paces the frame; the engine's own loop adds no
  extra sleep).
- `status` prints role/players/day/pause/TPS; `stop` saves (`CampaignGameManager: Finished saving`)
  and exits cleanly; the autosave lands as a new `save_<name>_<id>` directory.
- `./gradlew test`: 4685 tests, 0 failures.

Not verified live: a graphical client joining a headless host, and `kick` against a real guest
(both need a second machine; see `TEST-CHECKLIST-RU.md`).

## Notes and limits

- `coop.maxGuests` is clamped by the mod to 3 (4 players total); asking for more only logs a warning.
- The dedicated host still runs UPnP port mapping (`-Dcoop.portMapping=off` disables it) — on a
  test box that maps the router, so pass `off` unless the server should be reachable from outside.
- Save cadence: the periodic autosave (10 min, guests present) and `save`/`stop` all go through
  `CampaignUIAPI.autosave()`, so they are skipped while a blocking screen is open and retried.
- The headless jar is never on a graphical client's classpath; `coop.jar` only gains the console
  class, which is inert without `-Dcoop.headless=true`.
