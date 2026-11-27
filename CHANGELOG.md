# Changelog

## 0.2.10 (Unified bottom-left Lobby dock + instant game start + no duplicate in-save lobby)

- **Removed redundant green `CO-OP` button below Exit on the Title Screen (`CoopTitleScreenOverlay`):**
  kept only the compact bottom-left **`CO-OP ЛОББИ`** window with clean **`Настройки`** and
  **`Выйти из лобби`** buttons for both Host and Guest alongside **`CONNECT`**, **`ГОТОВ`**, and
  **`СТАРТ СЕССИИ`** (enabled once all lobby participants are ready).
- **One-click session start straight into the game (`CoopTitleScreenBridge.enterGameFromTitleScreen`):**
  pressing **`СТАРТ СЕССИИ`** when all participants are ready immediately launches both Host and
  Guest into the campaign (Continue latest save, or New Game with the synced seed if no save exists).
- **Eliminated duplicate in-save lobby dialog (`CoopNetPump`):**
  sessions launched from the Title Screen Pre-Game Lobby now auto-ready and skip the second
  in-campaign `CoopLobbyDialog`, dropping both players directly into live gameplay.

## 0.2.9 (Remember user-selected Starsector install folder across launcher restarts)

- **Remember chosen `starsector.exe` path across restarts (`CoopInstallLayout` + `CoopLauncherApp` + `StarsectorCoopLauncher.exe`):**
  selecting a custom `starsector.exe` via **Browse .exe** now persists the install root to the
  shared `%LOCALAPPDATA%\StarsectorCoop\install-path.txt` file (plus Java `Preferences`) and both
  the Java launcher and `Starsector Coop Launcher.exe` now prioritize the user's remembered folder
  ahead of default auto-discovery (`C:\Starsector`).

## 0.2.8 (Pre-game lobby connect fix + always-open bottom-left Lobby dock with one-click CONNECT)

- **Fixed `"Host closed the lobby"` on real network connections (`CoopPreGameLobby.tickGuest`):**
  fixed a non-blocking NIO race where `tickGuest` fell through to `readLines()` on frame 1 while
  `SocketChannel.finishConnect()` was still returning `false`, throwing `NotYetConnectedException`
  and misreporting `"Host closed the lobby"` on any connection with >1 ms TCP handshake latency;
  also added a graceful 300 ms flush window on wrong-password `REJECT` so clients always receive
  the exact password error instead of a reset socket.
- **Always-open bottom-left Title Screen Lobby & Participants dock (`CoopTitleScreenOverlay`):**
  added a compact emerald-bordered `CO-OP ЛОББИ` dock pinned to the bottom-left of the Title
  Screen showing live lobby participants and Ready status, persisted last connection settings
  (`CoopTitleScreenBridge` Preferences), and a one-click **`CONNECT`** button that connects using
  previous settings and automatically opens the full Join settings modal with the exact error
  banner if connecting fails.

## 0.2.7 (Mid-battle live co-op hot-join + <700 su Fight Alongside live piloting fix)

- **Seamless mid-battle hot-join into live co-op combat (`ALLY_JOIN_REPLY` during active combat):**
  if the engaging player clicks into battle before their nearby partner answers the reinforcement
  prompt, `CoopNetPump.drainCombatInbound` now handles `ALLY_JOIN_REPLY` live inside the running
  `CombatEngineAPI`, hot-deploys the partner's mirrored ships onto Side 0 (`ship.setAlly(true)`)
  when they were not pulled in pre-battle, arms `CoopLiveCombatAuthority` immediately, and drops
  the partner straight into the ongoing battle at the helm of their flagship.
- **Close-range (`< 700 su`) `Fight Alongside` live-join fix & clean prompt dismissal:**
  partners already pulled into the battle by vanilla's `700 su` ally radius still receive the
  interactive `ALLY_JOIN_OFFER` prompt for live real-time piloting, and `allyJoinDialogs.close()`
  clears cleanly on choice so the dialog controller never re-requests the prompt.

## 0.2.6 (Native Real-Time Co-op Combat + Direct Player Trade + Pre-Game Lobby)

- **Native Real-Time Co-op Physical Combat (replaces Wingman completely):**
  implemented our own built-in authoritative real-time joint combat system under `coop.combat.live`
  (`CoopCombatNetCodec`, `CoopLiveCombatSession`, `CoopRemotePilotShipAI`,
  `CoopCombatInputCapture`, `CoopLiveCombatAuthority`, `CoopLiveCombatClient`,
  `CoopCombatBeamRenderer`). When a nearby partner chooses **«Войти в бой лично — управление
  кораблём в реальном времени»** (`JoinChoice.JOIN_LIVE_COMBAT`, including when already pulled in
  closer than `700 su` with `Fight Alongside` active), they launch directly into an isolated native
  `CombatEngineAPI` puppet arena with zero campaign side effects, pilot their own ship in real time
  at up to 60 Hz over UDP (WASD/strafe, world-space mouse aim, LMB fire, RMB shield/phase, ship
  system `F`, flux vent `V`, weapon groups `1..7` & autofire `Ctrl+1..7`, fighter recall `Z`, live
  ship switching `F8`/`[`/`]`, and synchronized `Space` pause), and see full live synchronization of
  ships, fighters, shields (`ShieldAPI`), sparse delta-compressed armor grids (`ArmorGridAPI`),
  flux/overloads/venting (`FluxTrackerAPI`), ship systems & phase cloaks (`ShipSystemAPI`), turret
  tracking & ammo (`WeaponAPI`), ballistic/energy projectiles (`COMBAT_PROJ_SPAWN`/`HIT`), guided
  missiles & mines (`COMBAT_MISSILE_STATE`), active beam rays (`CoopCombatBeamRenderer`),
  explosions, EMP arcs, and ship destruction. Automatically falls back to vanilla AI on input
  timeout or disconnect and applies post-battle outcomes cleanly via `CoopAllyLossApplier`.
- **Direct player-to-player commodity exchange & credit transfers on partner fleet click:**
  clicking a friend's mirrored fleet on the campaign map releases the engagement shield strictly
  for the targeted partner mirror (`CoopNetPump.assertMirrorEngagementShields`), identifies the
  exact partner (`CoopMirrorTags.PLAYER_MIRROR_OWNER_ID` / `CoopPartnerCampaignPlugin`), and opens
  the Partner Comm Link (`CoopPartnerCommPlugin`) with one-click access to the rebuilt native
  `CoopTradeDialogPlugin`. Initiating a trade immediately auto-opens the trade window on the
  partner's screen (`TRADE_SYNC` `OPEN`/`OFFER`/`LOCK`/`UNLOCK`/`CONFIRM`/`CANCEL`) with live
  frame-by-frame refresh (`CoopTradeSession.revision()`), lists every real commodity stack in the
  player's cargo with native icons and clamped `-50/-10/-1/+1/+10/+50/All` controls, supports
  custom credit amounts + quick `+1k/+10k/+50k/+100k` transfer buttons (both two-way swaps and
  one-way gifts), enforces two-step mutual confirmation (`Lock` -> `Confirm`) with automatic unlock
  on any edit, and atomically validates both credits and every commodity stack before settling.

## 0.2.5 (3-mode Starsector launcher + .exe installer + 3000 su tactical battles & partner comm link)
- **Baldur's Gate 3-style live dialog, trade & Comm Directory spectating, partner cursor & option voting:**
  added symmetric Host &harr; Guest live mirroring (`CoopDialogSpectatorBridge`,
  `CoopDialogSpectatorState`, `CoopDialogSpectatorPlugin`, `DIALOG_SYNC` / `DIALOG_VOTE`): whenever
  either Host or Guest opens a gameplay dialog, **Market Trade** (`CoreUITabId.CARGO`), or **Comm
  Directory** (`CommDirectoryAPI`) while both players are in the **same star system** (never across
  different systems or hyperspace), partner(s) are automatically pulled into a live spectator window
  titled top-center `Диалог игрока «Ник»` / `Dialog of "Name"`. Includes auto-scrolling to new lines
  (bars/quests), gold highlighting of numbers (days, credits, units, %) plus inline native commodity
  icons, dedicated Trade table with commodity icons/stock/prices, dedicated Comm Directory view with
  NPC portraits/posts, live OpenGL partner cursor, and clickable BG3-style option voting.
- **Native in-game green `CO-OP` button on the Title Screen, live Pre-Game Lobby & in-campaign Host/Join (`F7`):**
  added a native emerald-green `CO-OP` button directly beneath the main menu on Starsector's Title
  Screen (`CoopTitleScreenBridge`, `CoopTitleScreenOverlay`, `CoopInGameLauncherSession`) with a
  live non-blocking NIO **Pre-Game Lobby** (`CoopPreGameLobby`) right inside the same green modal:
  Host and Guest(s) set their nickname, see each other live in the roster, toggle Ready status,
  auto-sync the host's sector seed/size/star age, and launch together into the world (automatically
  releasing the lobby port on campaign transition); also accessible live inside any loaded campaign
  via `F7` (`CoopCampaignConnectDialog`, `CoopNetPump.startHostRuntime` / `startGuestRuntime` /
  `stopRuntimeSession`).
- **Phase 26 completion (guest-proximity encounter triggering + multi-guest despawn + M3 polish):**
  added `CoopGuestEncounterTrigger` on the host so guests travelling away from the host in
  hyperspace / Abyssal Hyperspace trigger native abyssal systems, free-standing abyssal lights,
  and slipstream encounters around their own fleet (replicated to all peers, sharing
  `EncounterManager` timeouts to avoid double-spawning); upgraded `CoopAbyssalDespawner` to track
  all connected guests (`updateGuestPresences`) so multi-guest players never lose an active abyssal
  system; closed `CoopLightDeferredEngagement` in-place owner/target replacement and immediate
  `retireForLight` cleanup.
- **Transparent ally battle reinforcement (up to 3,000 su):** added host-configurable
  `coop.allyJoinRange` (default `3000`, range `700..10000`) and interactive reinforcement dialog
  (`CoopAllyReinforceCoordinator`, `CoopAllyJoinDialogPlugin`) letting nearby partners join any
  battle via **Tactical Map** (live ship orders) or **AI ally**, or **Decline** (Wingman battle
  integration removed).
- **Partner Comm Link & live Trade:** clicking a partner's mirrored fleet on the campaign map now
  opens live Comm Link (`CoopPartnerCampaignPlugin`, `CoopPartnerCommPlugin`) with working
  `TRADE_SYNC` credit/cargo settlement in `CoopNetPump`.
- **Native Windows `.exe` launcher & one-click bootstrapper** (`Starsector Coop Launcher.exe`):
  single standalone GUI `.exe` (no console window) that detects/picks `starsector.exe`, requests
  UAC elevation when needed, downloads the latest GitHub release (plus optional Wingman),
  repairs/patches `vmparams`, enables mods in `enabled_mods.json`, swaps `.pending` jars, and
  opens the main launcher.
- **Minimal single-screen updater & launch utility (`coop-launcher.jar`):** simplified the external
  launcher to one clean screen (Starsector path + **Open folder**, optional **The Wingman Project**
  and **Starsector 0.98.5a-RC9 Russian translation** high-contrast checkboxes with fully automatic
  GitHub download & silent vanilla-only installation — no manual `.zip` picker required, embedded
  live **Log** panel with bug-report/log-folder/clear buttons, compact **Reinstall**, **Update**
  [disabled when up to date], and **Launch** actions, automatic startup GitHub update check with
  amber notification banner, and live **RU / EN** toggle defaulting to Russian).

## 0.2.4 (Wingman battles + phases 22/25/26/27/34 + hardening)

Release build of the expanded 0.2.3 tree. Everything below is new relative to the
released 0.2.3: global pause/fast-forward, guest bounties, tactical orders and map, multi-guest
3-4 players, ambient system-call sync, the hardened Wingman path (MethodHandles, auto-join,
UPnP), chat, trade UI, transport encryption, the CGNAT relay server, guest snapshot restore,
allied wreck recovery, the Nexerelin adapter and the Linux/macOS launcher.

## 0.2.3 (Wingman battles)

Full battle synchronisation through The Wingman Project (0.9.4-beta or later, installed and
enabled on both PCs). When `coop.wingmanBattles` is on (default), the coop host opens its
Wingman session from the campaign map and every battle the host fights is served by Wingman:
the guest connects Wingman to the host's PC once and then flies in the host's battles in real
time, with real damage to their own ships, reconciled into the shared campaign by the existing
battle-result path.

- Host: opens the Wingman session at coop session start, maps port `24816` (TCP+UDP) via
  UPnP/NAT-PMP when `coop.portMapping` is `auto`, pumps Wingman on campaign frames, learns the
  guest's Wingman name (new reliable `WINGMAN_HELLO` message), and tracks/reserves the guest's
  current mirror-fleet flagship for them (updating automatically if the flagship changes and
  clearing stale reservations on session reset) so the guest gets their own ship whenever their
  fleet deploys (Fight Alongside toggle).
- Guest: reports its Wingman name, posts a one-time hint with the configured host address and
  Wingman port, and keeps the coop battle bridge in spectator mode while inside a Wingman join
  battle (including when Wingman launches combat before `BATTLE_BEGIN` arrives over TCP, while
  preserving guest-initiated local battles against mirrored NPC fleets and Dweller encounters).
- Every Wingman call binds via sandbox-safe `java.lang.invoke.MethodHandles` (never
  `java.lang.reflect`), checks whether Wingman is enabled before loading `WingmanAPI`, logs
  failures once, and fails closed to the classic solo own-fleet model; with Wingman absent nothing
  changes at all.
- New host-policy option `coop.wingmanBattles` (Coop Options page, default on).
- Release polish: `COOP TEST` career start is now dev-mode only (`$global.isDevMode`), and
  `coop.version` / launcher update checks point consistently at the `openmans/starsector-coop`
  fork.
- **Phase 25 (Global Pause & Fast-Forward Control):** wired `coop.allowGuestPause` (default on) so
  any player can toggle global pause (Spacebar) and global fast-forward (Shift, new `FF_INTENT`
  message) immediately without a confirmation step, while keeping screen-open, combat, and reconnect
  holds intact.
- **Phase 34 (Bounties for the Guest):** host replicates active person and system bounties to guests
  via `BOUNTY_SET` (`CoopPersonBountyIntel` / `CoopSystemBountyIntel`), suppresses the guest's
  divergent `SystemBountyManager`, and settles person & system bounty payouts via `CREDITS_GRANT`
  in a pre-reconcile hook before `BATTLE_RESULT` despawns the target fleet.
- **Phase 22 (Tactical Orders for Joined Ships):** extended `BATTLE_STATUS` (5 Hz, fog-filtered)
  with ship positions, facing, CR, objectives, and map bounds; added an opt-in GL-drawn
  `CoopTacticalMapPanel` opened from **Intel → Coop Session** while a partner fights; added
  `BATTLE_ORDER` / `BATTLE_ORDER_ACK` + combat-frame inbound drain so the non-fighting owner can
  issue ENGAGE, ESCORT, DEFEND, CAPTURE, WAYPOINT, RETREAT, and CANCEL orders to their joined allied
  ships.
- **Phase 27 (Multi-Guest 3–4 Players):** lifted `coop.maxGuests` cap to `1..3` (default `1`) and
  wired `coop.allowMidSessionJoin`; added multi-peer `CoopSessionState`, multi-guest presence and
  player-mirror registries with host-side snapshot/roster relay, N-way colony income split,
  per-guest `coop.guestFleetSnapshots` saves, per-guest departure cleanup, targeted `ENGAGE_GUEST` /
  customs dispatch, and per-guest Wingman flagship reservations.
- **Reliability & Cross-Platform:** added transparent chunking (`CoopMarketSnapshotChunker`, up to
  16 MiB) for large shared storage lockers past the 1 MiB frame cap, excluded unmirrored empty NPC
  fleets from structural `NPC_FLEET_SET` churn (S4-C), and added `Coop Launcher.sh` for Linux/macOS.
- **Full Hardening, All Options Wired & Extended Features:**
  - **Wingman clean path:** forward-compatible optional `MethodHandles` hooks for `join(host, port)`,
    `disconnect()`, and `closeHost()` in `ReflectiveCoopWingmanApi` / `CoopWingmanIntegration` +
    upstream PR specification in `docs/WINGMAN_API_PR.md`.
  - **Colony administrators & conflict guard:** sandbox-safe `MethodHandles` read of `Market.admin`
    without triggering lazy mutation (`CoopColonyManagement` `A|` / `B|` lines), replicating
    `PLAYER`, `AI_CORE`, and `PERSON` admins, plus optimistic `baseContentHash` concurrent-edit
    conflict detection.
  - **Phase 26 system-call sync & multi-guest forks:** upgraded all 8 presence/ambient forks under
    `forks/` + `CoopFullFidelitySystemDriver` for multi-guest iteration (`CoopPresenceRegistry.getAllForFork`),
    added `CoopAmbientSystemSync` (`AMBIENT_CALL`) for guest-proximity abyssal encounter triggers
    and slipsurge/flare host dispatch.
  - **Recovery & Integrity:** per-peer reconnect grace windows in `CoopReconnectCoordinator`,
    host-to-guest save restoration (`CoopGuestSnapshotRestorer` + `GUEST_SNAPSHOT_RESTORE`),
    allied battle wreck recovery (`CoopAllyWreckRecovery` + `recoverableDestroyedIds`), and real
    launcher-side mod `.jar` SHA-256 digests (`CoopModJarDigester` -> `coop.launcher.modJarDigests`).
  - **Zero inert options:** wired all 5 remaining options (`coop.incomeSplit` `equal|host-banks`,
    `coop.lootSplit` `equal|pilot-keeps`, `coop.guestColonizationConsent` + `COLONY_CONSENT_REQ/RES`,
    `coop.feedVerbosity` `all|important|minimal`, and `coop.partnerColor` presets / `#RRGGBB`);
    `CoopOptionsView.INERT_KEYS` is now empty.
  - **Campaign content sync & functional test framework:** added `CoopCommissionRewardSync`
    (monthly stipend & hostile-kill commission bounties for guests), `CoopContactAuthority`
    (`CONTACT_SET` + first-come contact-mission claim arbitration), and `CoopStorySyncFramework`
    (`STORY_STATE` for Galatia `$ga*` and Sindrian Diktat `$sd*` flags + divergence detector).
  - **Extended capabilities:** direct player-to-player atomic trade UI (`CoopTradeSession` +
    `CoopTradeDialogPlugin`, `TRADE_SYNC`), in-game co-op text chat (`CoopChatService` +
    `CoopChatDialog`, `CHAT_MSG`, `coop.chatKey`), opt-in AES-256-GCM transport encryption
    (`CoopCipher`, `coop.encryption`), standalone CGNAT TCP relay (`CoopRelayProtocol` +
    `CoopRelayServer`, `coop.relayAddress`, `coop-launcher.jar --relay <port>`), and optional
    Nexerelin diplomacy/invasion sync adapter (`CoopNexerelinAdapter`, `NEX_EVENT`).
  - **Launcher `vmparams` classpath integrity guard & repair:** added `CoopVmparamsText.classpathSanity`
    and a blocking red `vmparams classpath integrity` check in `CoopInstallCheck` that catches
    third-party tuning-pack corruption (double quotes, embedded spaces, absolute drive/UNC paths,
    non-`.jar` entries, missing required stock jars such as `janino.jar`), paired with idempotent
    `CoopInstallFixer.repairClasspath` (preserving all JVM tuning flags, `-Dcoop.*` properties, and
    the main class, with one-time `vmparams.backup`).

## 0.2.2 (community port to 0.98.5a-RC9)

This is a community port of the 0.2.1 preview to **Starsector 0.98.5a-RC9** (upstream 0.2.1 targets
0.98.5a-RC5, which Fractal Softworks no longer offers for download). Back up both co-op saves and
install the same `coop-0.2.2.zip` on both PCs. If the game update reset `vmparams`, open
**Installation Details** in the co-op launcher and click **Fix** beside the classpath check.

- Game version pin moved from `0.98.5a-RC5` to `0.98.5a-RC9` (mod_info.json, handshake and hook
  version strings).
- Fork porting verified against the RC9 engine jars: all 21 forked classes keep their field
  layouts and method signatures; `Misc.hasFunctionalHeavyIndustry`, added by a 0.98.5a hotfix, is
  added to the Misc fork so RC9 engine callers cannot hit a `NoSuchMethodError`.
- The full-fidelity guest-system drive resolves the RC9 signature of `BaseLocation.advance`
  (second parameter class moved to `com.fs.starfarer.util.oOOO$new`).
- `StarfarerSettings` toggle handles and `CampaignState.fastForward` verified present in RC9 with
  the same names; fast-forward toggle mode needs no change.
- Version Checker feed points at the fork; mod version bumped to 0.2.2.

## 0.2.1 preview

This preview targets **Starsector 0.98.5a-RC5**. Back up both co-op saves and install
`coop-0.2.1.zip` on both PCs. If the game update reset `vmparams`, open **Installation Details**
in the co-op launcher and click **Fix** beside the classpath check.

- Updated nine engine forks, the fast-forward and guest-system hooks, the seasonal slipstream
  tools, and the Galatia Academy rules for RC5.
- Shared lights preserve the Juvenile Dweller's lifetime and battle type.
- Co-op campaigns use Normal mode. Loading a Survival save blocks the co-op connection.

Fresh two-player campaigns and a copied RC8 campaign pair passed the RC5 smoke test, including
fast-forward and coordinated saves. The new Sindrian storyline and support fleet interactions
still need co-op testing.

## 0.2.0 preview

Shares more of the hyperspace world. Install the same packaged preview ZIP on both PCs and back
up both saves first. Download this preview manually. The launcher continues to follow the stable
release feed.

- Host seasonal slipstreams and slipsurges are shared, with local discovery preserved. Native
  Leviathan and Calf trails share shape and fade checkpoints as they grow.
- Floating hyperspace wrecks share resolved salvage and lifetime. Guest recovery and partial
  salvage reach Host, including transmitter-trap fleet creation directed at the Guest.
- Supported temporary abyssal systems share their generated contents, survey and consumed salvage.
  Free-standing lights and Dweller encounters are shared; rewards belong to the consuming player.
- Host owns the hyperspace storm grid and native storm phases. Ability-driven clearing and boost
  controls keep their owner rules. Guest cosmetic sensor ghosts run locally.
- Light callback failures are contained, retries are bounded, and uncertain rewards cannot grant
  twice. Natural expiry no longer grows saved consumption histories. A failed seasonal cleanup no
  longer prevents NPC replication. Idle trails and timer-only storm changes avoid repeated full
  snapshots; storms retain a five-second full refresh.
- Developer MCP dependencies are updated. They remain outside the player archive.

Guest-proximity encounter generation remains unbuilt. Guest ability surges, cosmetic ghosts, star
flares and visual timing retain local behavior. Native storm/boost/mirror and ghost/trail visual
acceptance remains open; this preview does not claim those checks passed. See
[Known limitations](docs/player/LIMITATIONS.md) for supported content and replay limits.

## 0.1.5

Fixes to credit transfers, reconnects, consecutive ally battles, and the launcher.

**Credits and reconnecting**

- A credit transfer is marked delivered only after the recipient's balance is updated. A refused
  balance update leaves the gift eligible for delivery when the connection resumes.
- An undelivered gift is refunded to the campaign it came from. Loading another save no longer
  puts the old campaign's refund into that save's balance.
- A gift rejected by a full outgoing message queue now returns the sender's credits.
- A late reconnect reply from a replaced connection can no longer resume the wrong connection.

**Battles**

- Moving straight from one ally battle into another preserves the second battle's starting roster.
  Each battle's damage and ship losses are reported in order.

**Launcher**

- The host's connection check keeps its router mapping open and renews it while the launcher waits
  for the guest. Repeated guest checks can reach it. The mapping is released before the game starts.
- Launching again from the same open launcher preserves preferences changed in-game, including
  **Hide the link HUD** and **Event feed detail**.
- **Start over inside the host's campaign** is cleared after a successful launch, so the next launch
  requires fresh consent.

Automated checks: 3,791 tests passed, one skipped. The two-player check is complete.

## 0.1.4

Your partner's fleet can now fight beside you as an AI ally. The toggle is called Fight Alongside
and sits on your ability bar.

**Battles**

- Your fleet can fight in your partner's battles as an AI ally. A toggle ability on your ability
  bar controls it, one per fleet, off by default and saved with the campaign. Turn it on and your
  partner's encounters may pull your fleet in, exactly as a friendly patrol is pulled in. Your
  partner has no say in it either way.
- It joins only a fight against a side your fleet is already hostile to. That is the vanilla rule
  for allies: attacking a neutral trader with your partner parked on top of it is still a solo
  fight, and nothing goes wrong, nothing is listed in the dialog.
- Losses are real. Ships destroyed in your partner's battle are removed from your fleet, and the
  hull and CR the fight left your survivors at are written onto your real ships. You get one line
  naming what was lost. Destroyed is destroyed: vanilla offers no recovery dialog for allied
  wrecks and neither does this.
- No spoils are split. The player who pilots the fight keeps the XP, the salvage, the credits and
  the ship recoveries, as vanilla does for a battle fought with allies.
- Your officers go with your ships. The fleet snapshot now carries each ship's officer and your
  own fleet-wide skills, so the ally fights near the strength it has on your screen.
- You get a line on the HUD when your fleet is pulled into a fight, naming your partner and what
  they are fighting, and a second line with the result when the battle ends.

## 0.1.3

Fixes from the first two-player Internet session on 0.1.2 (68 minutes, nine link drops from
minimized windows, no gameplay fault seen), plus documentation work. No gameplay changes.

**Reconnecting**

- A link drop inside the reconnect window no longer tears down the guest's world. NPC mirror
  fleets and the mirrored pirate and Pather bases stay in place while the countdown runs, and the
  host's full rebroadcast on resume corrects whatever changed during the stall. Before, every resume
  ended all six hidden bases and rebuilt them under new market ids and re-created every mirror.
  Teardown still happens once when the window expires or the partner leaves.
- `Wait 5 more minutes` stops at 30 minutes remaining. Presses past that do nothing and the
  countdown stays put. One guest pressed it 53 times in 16 seconds and held a window of over four
  hours.
- When the partner has said it is leaving, the socket reset that follows is one info line in the
  log instead of a warning with a stack trace.

**Log markers**

- The day in a marker line was a 39-digit number on both sides, because the engine answers the largest float
  for a zero timestamp. It is now the cycle times 360 plus the day into the cycle, hours as a fraction.
- Characters above ASCII in a marker note are written as `\uXXXX` escapes, so both logs hold the
  same bytes whatever code page each Windows uses.

**Launcher**

- Settings has an **Agent bridge** checkbox in the Developer group. Off for normal play. Ticked, the game
  starts with the 127.0.0.1 socket the developer tooling talks to, on port 7801 for a host launch and
  7802 for a guest launch unless the port field beside it says otherwise. The typed port survives
  unticking the box.
- A check in the Installation window whose detail runs past one line now wraps inside its own row.
  Each row was measured before the window had a width, so it was given one line of room and drew the
  rest of its text over the rows above and below it, hiding their dots and names. The stale
  `-Dcoop.*` warning, which lists every flag it found on the java line, did this on every install
  that had one.
- Loading a save that has never been in a co-op session while the invite names a campaign now
  shows the wrong-save notice at load, naming the save to load. Before, that case waited for
  the seed lock's refusal on connect.
- The wrong-version notice, the wrong-save notices and the Installation window's game-version row now point at the Developer tab of the Settings window. They said "Advanced", a card the launcher has not had since the Settings window replaced it.

**Developer tooling**

- New read-only bridge verb `hirable`: the markets that currently hold officers or administrators for
  hire, nearest the player fleet first, with each person's name, level and personality.

**Documentation**

- `docs/player/LIMITATIONS.md` and `docs/player/CONNECT.md` explain the minimized-window stall:
  the other side sees the link drop after 15 seconds and the session resumes on its own when the
  window is restored.
- `docs/CONNECTIVITY.md` is retired; `docs/player/CONNECT.md` is the one networking guide.
  `docs/COOP_MP_DESIGN.md` describes the code as built. `docs/CODEBASE_MAP.md` is new: packages,
  the per-frame pump order, the wire contract and the in-game traps, for developers and AI workers.

## 0.1.2

A hotkey that stamps a marker into both players' logs, for the first sessions played over the
Internet. No gameplay changes.

**Log markers**

- Press F11 in the campaign when something looks wrong. The mod writes one line into your log and
  the same line into your partner's, carrying a marker number, the campaign day, the system you are
  in and the sync sequence number. Search either file for `COOP-MARK` afterwards and every marker
  from both players is there, in the same words on both sides.
- On the open map the key also opens a note box. What you type goes under the same marker number
  in both logs. In combat, inside a market or in any other dialog there is no box; the marker still
  lands, so say the number out loud or write it down.
- Both players see it on the link readout for five seconds: `marked host#3: the note` on the one
  who pressed it, `partner marked host#3: the note` on the other.
- The key is the **Log marker key** field in the launcher's Settings window, an LWJGL key name such
  as `F11` or `F10`. An unknown name is logged and falls back to F11. `REPORTING.md` describes what
  to do with the markers.

**Documentation**

- The README was rewritten around what the mod does today, and three internal notes files were
  folded into the runtime-limitations section of the plan.

## 0.1.1

Shared storage, shared submarkets and player-to-player credits, plus everything five two-player test
sessions turned up in 0.1.0. The last of those sessions ran with the second player on the far side of
an Internet tunnel instead of on the same LAN.

**Shared storage and submarkets**

- Storage is shared at every market that has a locker. Either player's 5000-credit unlock opens it
  for both, and a ship one of you parks comes out for the other with its refit, weapon groups, hull
  damage and modules. Each game still bills its own monthly storage fee against the contents.
- The black market and the military submarket are shared on the same terms as the open market: the
  host's shelf, refreshed each time either of you docks. What a trade causes stays local, so
  smuggling suspicion, scan odds, blueprints learned from a sale and price impact are still each
  game's own.
- A commission held by the host lets the guest buy that faction's commission-gated military items.
  The salary and the commission's own bounties stay with the host, and the guest can no longer sign
  or resign a commission.
- Pirate and Luddic Path bases share their shop between the two games. Each game still names the
  base and places its orbit itself, and a base carries no storage locker.
- Ship listings now carry weapon groups, current hull damage, the variant's display name and module
  loadouts, so a refitted ship survives the round trip in either direction.

**Credits**

- A Send credits row on the Coop Options page moves money between players: type the amount, press
  Send, confirm. It leaves your account on send and lands once on the other side, and a transfer made
  while the link is down goes through on the resume.

**A dropped link no longer eats what you were doing**

- 0.1.0 lost any message that was still on its way when the connection died. Three were confirmed
  lost in testing: a market purchase, a 3,000 credit transfer and a 50 supply storage deposit. In
  each case the goods or the money left one side and never arrived on the other. Reliable messages
  are now acknowledged, kept until the acknowledgement comes back, and resent after the reconnect.
  They land exactly once.
- A transfer that can never be delivered, because the other game is gone for good, is put back in
  your account when the session ends, with a line in the message feed.
- A resent message could arrive one millisecond before the reconnect finished and be thrown away for
  arriving too early, and nothing retried it. Messages from a connection that has not proved itself
  yet are held and delivered behind the resume.

**Encounters and NPC fleets**

- A fleet that caught the guest with its transponder off always offered a free **Leave**. The engine
  treats a fleet as hostile to a transponder-off player only once it has identified them, and a
  mirrored fleet carries none of that memory, so the encounter was never forced. It is now.
- Mirrored fleets could show every ship at 0% combat readiness in the guest's encounter dialog. The
  roster was rebuilt without a crew, and the dialog holds the game paused, so the crew never arrived
  while the dialog was open. Rosters are crewed on the same frame now. The host also fills in a
  fleet's real loadout and d-mods before sending it to a guest standing close enough to read them.
- Jump points in a system neither player had visited sat at a different angle in each game, in one
  case 30,000 units apart. A guest jumping in landed beside its own copy of the exit and then watched
  it move away. The host now corrects every system's jump points in the background at one system a
  second, 233 systems and 460 jump points in about four minutes, and an arrival stays put.
- Planets and jump points are kept in step in the guest's system too, not just the host's.
- Ship hulls a trade fleet is carrying as cargo draw as mothballed instead of as warships at 0%.
- Nothing engages a player whose game is paused.
- NPC tooltips stopped driving the traffic. One fleet's action line changing from "returning to X" to
  "delivering Y" re-sent all 33 fleets in the sector, once a second for minutes at a time, at roughly
  20 KB a send. Action text and ship health now ride a 10 second floor, and only fleets in a system
  one of you is standing in force an immediate send.

**Colonies, gates and the bar**

- A gate one of you scans now shows up in the other's Gates intel tab. It was marked scanned and
  counted, but the part of the vanilla rule that the tab actually reads was never run.
- Both players could accept the same bar mission. First-come claiming was built but nothing at the
  bar ever called it. The loser now reads `<partner> already took that offer.` and keeps no orphan
  mission in their intel.
- A colony edit that failed to apply rolled the other player's edit back with it.
- The guest no longer carries a Hostile Activity meter for a shared colony, which only the host's
  economy drives, and the Galatia Academy story chain is off on the guest rather than half working.
- A guest sitting alone in a system no longer has ambient fleets spawned on top of it.

**Markets**

- Commodity stacks on the guest sat a unit below the host's on every stack the economy was decaying
  toward its limit. The guest's own market plugin ran its restock pass after the mod had applied the
  host's numbers and shaved a unit off each over-limit stack on every dock. Its timers are reset
  after each sync now.
- A ship in shared storage was torn down and rebuilt every time the locker was opened, because the
  two games list a ship's weapon groups in different order and the comparison read the order. It
  compares a sorted form now, so the ship keeps its identity.

**Saving and quitting**

- The coordinated save gave up after 30 seconds if the other player had a screen open, left the two
  saves out of step and told nobody. It now waits up to ten minutes and says on the host's message
  feed whether it caught up.
- Quitting to the menu or closing the game tells the other player at once. They used to wait out the
  full 60 second reconnect countdown for someone who had left on purpose.
- Load a save from the wrong campaign and the refusal now names the save that does match, and points
  at the launcher checkbox rather than at a command line switch.
- The credit transfer is one amount field and a Send button instead of a row of stepper buttons.

**Launcher**

- Compact Host/Join setup keeps the Launch bar in place. Campaign, connection, installation and
  settings details open separately; Logs is a movable window that can stay open during play.
- Full-size Copy/Paste actions, stable progress labels, keyboard-accessible controls and explicit
  connection results replace the small inline actions. Changed invitations prompt a fresh copy;
  stale connection results are discarded when the role or endpoint changes.
- Outline icons and aligned header, connection status and Launch action match the UI prototype.
  The pencil beside Copy invite opens connection settings without adding another row.

**Under the hood**

- Round trip times measured across a battle are discarded. A guest sitting in the combat screen is
  not answering the campaign pump, so the host measured 43 seconds of round trip, warned about a
  degraded connection after every fight the guest had, and widened the distance at which it hands a
  chasing fleet over to the guest from about 100 units to nearly 4,000.
- A diagnostic probe on your own fleet, silent unless diagnostics are switched on, records every drop
  in combat readiness, hull or supplies alongside the engine event that caused it. It is what
  identified the storm limitation below.
- A battle result can no longer name a player's own fleet.

**Known limitations**

The full list is in `docs/player/LIMITATIONS.md` and has not changed since 0.1.0, but one entry is
worth stating plainly because it looks exactly like a bug. Hyperspace storms are rolled separately in
each game. A storm cell that hits your fleet does not exist on your partner's screen, so they watch
your combat readiness fall with nothing on their map to explain it, and the same the other way round.
In one test run the guest lost 12%, 20% and 33% combat readiness to three storm hits over four days
of hyperspace travel, then repaired back to 70% over the next three weeks; the host saw none of it.
The damage is real, and it is only ever done to the fleet standing in the cell.

## 0.1.0, first release

Two-player co-operative Starsector. One shared sector, one campaign clock, two fleets.

**Playing together**

- One sector generated from a shared seed. Both players start in it and stay in it.
- One campaign clock. Time passes for both of you or neither; a menu, a dialog or a battle on either
  side pauses the other, and the status line names who is holding it.
- Fast-forward is shared. Both clocks speed up together.
- A status line in the corner of the campaign screen: role, session state, who is pausing, round
  trip, packet loss and which transport your fleet positions are travelling over.
- A "Coop Session" page in the intel screen with link history, what your partner measures, and a log
  of everything that has gone wrong this session.
- A "Coop Stats" page tallying the campaign: battles, travel, trade, colonies and every ship lost,
  per player and as a team.
- A "Coop Options" page. The host sets the session's rules there and they travel with the campaign's
  save and sync to the guest; each player sets their own preferences separately.
- Every setting also lives in a file: `saves\common\coop_options.json.data` for yours, which the
  options page writes for you, and a shipped `data\config\coop_options.json` listing every key, its
  default and when a change takes effect. The `.data` suffix is the engine's; the contents are
  plain JSON.
- The host can let the world keep running while the guest reads its map, cargo or refit screen.
  Interaction dialogs, the menu and combat still pause both games.

**The world**

- Your partner's fleet moves on your map, with its real ships, cargo, sensor profile and abilities.
- Patrols, traders, pirates and bounty fleets are simulated once and mirrored, so you both see the
  same fleets doing the same things, including the ones chasing your partner.
- Markets, shop stock, officer pools and bar offers are shared. Docking refreshes what you see.
- Salvage, surveys and exploration are shared: one of you loots the wreck, both of you see it gone.
- Faction reputation is shared. Your partner's smuggling shows up on your standing.

**Colonies**

- One player faction, two governors. Both of you can found colonies, build industries, run the
  construction queue and use colony storage, on the same colonies.
- Monthly income is split evenly.
- Raids and bombardments work in both directions, and an incoming expedition raises a warning with a
  countdown on both screens.

**Combat**

- Each player pilots their own battles. The other is held paused and gets a banner when the fight
  starts and ends.
- Battle results are reconciled into the shared world: losses, salvage, reputation, bounties.

**Connecting**

- The host is the only side that has to be reachable. The game asks the router to open the port over
  UPnP or NAT-PMP at startup and tells you in the log what happened.
- Optional lobby password. It stops strangers from joining an open port; it does not encrypt the
  session.
- A dropped link is held for 60 seconds with a countdown on both screens. The session picks up where
  it left off when the connection returns, including when the other player had to restart the game
  and reload their co-op save.
- A lobby in front of every session. Both games load with the world paused, the guest's five join
  steps are on screen as they pass, and the clock starts when the host presses Start and a three
  second countdown runs out.
- A refused or ended session gets a dialog naming the cause and what to do about it, with a code
  (`COOP-SEED`, `COOP-MODS`, `COOP-SESSION`) that also lands in both logs.
- A "connection doctor" block in the log answers "why can't we connect" in one screen, on both sides.

**Starting the game**

- `Coop Launcher.cmd` in the mod folder: a dark-themed window with the settings in it, so nobody has
  to learn a `-D` property to play. It runs on the JRE that ships with the game, writes
  `saves\common\coop_options.json.data`, and starts the game; the vanilla launcher still comes up and
  you still press Play there. Windows only in this release. Its Advanced card, folded shut by default,
  exposes the developer flags (diagnostics, datagram wiretap, frame profiler, the full-fidelity
  guest-system and shared fast-forward and clock-reconciler switches, and the guest's one-shot
  start-over-in-the-host's-campaign override) as checkboxes instead of `-D` properties.
- An invite line, `coop://host:port/?seed=...&pw=...&size=...&age=...`. The host presses Copy invite,
  the guest presses Paste invite and has the address, the port, the password, the seed, the sector
  size and the star age filled in at once. The password is generated for the host rather than left
  empty.
- The launcher names the save to load. A Starsector save folder is named after the character, and
  the game's load screen lists it by character and date; neither says which co-op campaign it
  belongs to, so two campaigns meant guessing. The host now picks the campaign from a drop-down that
  lists each of their co-op campaigns as its newest save, the invite carries the pick, and both
  players get one line under the card: `Load the save "Kaz Alba", level 12, saved 2026-05-28 18:16
  (folder save_Kaz_...)`, or that this session is a new campaign and neither of you loads anything.
  Picking a save greys the seed, sector size and star age, which only do anything for a new game. A
  save folder the game has already pruned is never named.
- If you load the wrong save anyway, the mod says so in-game and names the right one: character,
  level, in-game date and folder. It is a warning you close, not a refusal; the seed check at connect
  still has the last word.
- Check my connection on the host runs the port mapping and the connection doctor before the game is
  loaded and puts the result on screen. It then holds the port so the guest's Test connection can
  measure TCP, UDP and round trip against it.
- An install check that names what is wrong: the missing classpath entry, an unticked mod, a mixed-up
  pair of jars, an unreadable settings file, and a leftover `-Dcoop.*` in `vmparams` that would
  silently outrank everything you set in the launcher.
- A **Fix** button on the two rows the launcher can put right itself. It adds
  `..\mods\coop\jars\coop-forks.jar;` to the front of the classpath in `vmparams`, keeping a copy of
  the old file as `vmparams.backup` and changing nothing else on the line, and it adds `coop` to
  `mods\enabled_mods.json` without touching the other mods in the list. A Starsector update wipes the
  classpath entry out again; the row goes red and the same button puts it back. If the game lives
  under `Program Files`, where Windows refuses the write, the launcher offers to restart itself as
  administrator. The by-hand instructions stay in `INSTALL.md` for the installs that refuse both, and
  a leftover `-Dcoop.*` is never deleted for you.
- The mod checks the Starsector it is running on against the one it was built for, and refuses to
  start a session on any other version with the code `COOP-GAME`. Parts of the mod are compiled
  against the game's own classes, so this is the failure that produced the strangest bug reports.
  The launcher shows it as a `Game version` row, read out of `starsector.log`, before you press
  LAUNCH. Testers can turn the refusal off with **Allow game version mismatch** under Advanced.
- Save a bug report: one zip on your Desktop with both game logs, the launcher log, your settings,
  your `vmparams`, your mod list, your newest save and a summary carrying the last `[COOP-DOCTOR]`
  line. The password is blanked out of everything in it. Both players press it; `REPORTING.md` says
  what to do with the two files.
- An update check at start, one request to GitHub's releases API, shown as a row in the install
  check. LazyWizard's Version Checker is supported too, through a `coop.version` file. Both matter
  more here than in a single-player mod: the handshake compares the commit baked into the jar, so a
  stale download on one side refuses the session.
- The release is one packaged artifact. `scripts\package-release.ps1` builds the zip and refuses to
  make one from an uncommitted tree, mismatched version strings, or jars built at another commit, so
  the download both players install is the same download.

**Saving**

- Co-ordinated saves on both machines. The guest rejoins by loading its co-op save.

**Fixed before release**

Found in the two-player test run on 2026-09-03 and in the launcher work that followed, and fixed the
same day.

- A player who had to restart the game was refused for the whole 60 second wait. Reloading the co-op
  save now ends the wait the moment it connects, and "wait longer" no longer pushes it further away.
- The lobby kept the row of the player who had just come back, so it listed three players and
  offered to start without the one sitting there ready.
- The reconnect countdown counted up from the drop instead of down to the deadline.
- A guest whose sector did not match was reported as a lost connection, and the host waited 60
  seconds for a guest it had already refused. Both sides now say `COOP-SEED` straight away.
- The lobby, connecting and reconnect screens rebuilt themselves once a second, which read on screen
  as a flashing panel.
- A seed longer than the game can store crashed it on the New Game screen. An unusable seed is now
  ignored with a log line, and the launch scripts refuse it before writing it into `vmparams`.
- Two of the refusal dialogs handed the host the instructions meant for the player at the other end.
- The seed, the sector size and the star age could only be given as `-D` properties, which left the
  launcher no way to set them. All three now read from `saves\common\coop_options.json.data` as well.
- The guest's New Game banner said the seed and world settings come from the host. They do not: the
  guest generates its own sector and the seed lock verifies it afterwards. It now says the seed comes
  from the invite.
- The mod's description in Starsector's own mod list was reworded.

**Fixed in the pre-release audit**

A full read of the code, the scripts and the documents on 2026-09-03 and 2026-09-04, before the
release build. 107 confirmed defects, fixed across twelve passes. The ones a player would have met:

- The launcher replayed the whole game log from the top whenever the game wrote a byte it could not
  decode, which spun a core and eventually truncated the live log during a rollover. It also kept
  holding the co-op port after a connection check that finished late, so the game it then started
  could not bind it.
- A colony upgrade the guest cancelled came back on the next sync, and an industry the mirror had
  already finished could be reported as a downgrade. Colony state is now compared as content, per
  industry, and a failure in one industry no longer starves the ones behind it.
- Two players could edit the same colony from the command tab at once and one player's edit was
  dropped without a refund. The colony editor is now claimed by whoever opens it.
- A second hostile act in the same raid was dropped, so part of a raid's outcome never reached the
  other player.
- A battle result built from a roster the engine would not fully read could report a live fleet as
  destroyed. An unreadable roster is now omitted from the result instead.
- Ship repair state was paired to the wrong ships whenever a fleet was merely reordered.
- A guest that pressed "Cancel countdown" during a start-anyway countdown was ignored; the session
  started anyway.
- Mirror fleets stopped for a customs stop stayed eligible to be pulled into someone else's battle
  for the rest of their lives.
- A port already mapped by another device on the network was evicted on every renewal. The mod now
  asks who owns it and says to pick another port.
- A guest that lost its save could be told the campaign was already in flight because a failed seed
  lock had written the campaign id anyway.
- A build from a modified checkout was stamped with the last commit's hash, so two players could
  shake hands on different code. It reports `<hash>-dirty` now.

Development tooling gained the agent bridge verbs the smoke checks were missing: `cargo` (supplies,
fuel, crew, capacity, overload) and `addship`, and the bridge accepts four clients at once instead of
one. None of it runs unless the bridge is switched on.

**Known limits.** Both players need identical Starsector versions and identical mod lists, and one
line of `vmparams` changed on both PCs, which the launcher's Fix button does for you. Traffic is
plaintext. One guest. Both players piloting in the same battle is not in this release.
`docs/player/LIMITATIONS.md` has the full list of where two co-op campaigns differ from one solo
campaign.
