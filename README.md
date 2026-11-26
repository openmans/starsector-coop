# Starsector Coop

**A campaign co-op mod for Starsector (2–4 players: host + up to 3 guests).** Fly your own fleet through a shared sector, trade from
the same markets, hunt shared bounties, and manage colonies together. The host runs the world; guests' games mirror it.
Your ships, cargo, credits, officers, and skills stay yours.

<p>
  <a href="https://github.com/openmans/starsector-coop/releases">Releases</a> &nbsp;·&nbsp;
  <a href="#quick-start">Quick start</a> &nbsp;·&nbsp;
  <a href="docs/player/LIMITATIONS.md">Known limitations</a> &nbsp;·&nbsp;
  <a href="CHANGELOG.md">Changelog</a>
</p>

> [!IMPORTANT]
> **0.2.5 ships a standalone one-click Windows launcher (`Starsector Coop Launcher.exe`),
> transparent ally battle reinforcement up to 3,000 su (Tactical Map, optional Wingman, or AI),
> and live Partner Comm Link / Trade** for **Starsector 0.98.5a-RC9**. Both players must run the
> same release on 0.98.5a-RC9 and back up their saves before upgrading. Wingman is completely
> optional — tactical co-op battles and the entire shared campaign work without it.

## What you share

| Part of the campaign | How it works |
| --- | --- |
| Sector and exploration | See your partner's fleet and the same NPC fleets. Survey progress, explored ruins, and salvaged wrecks are shared. |
| Campaign time | Any player can toggle global pause (when `coop.allowGuestPause` is on) and fast-forward together. Battles and interaction screens pause the campaign automatically. |
| Battles | Transparent reinforcement within `coop.allyJoinRange` (default `3000 su`): partners get a live join prompt to command their fleet on the **Tactical Map** (no Wingman needed), send **AI officers**, or fly manually via optional **Wingman**. |
| Markets and storage | Buy from shared open, black, and military markets. Use the same storage locker (with automatic chunking for large ship lockers). Take turns docking at a market. |
| Colonies, bounties, and reputation | Govern the same colonies under one faction (with synced administrators, configurable `coop.incomeSplit`, and optional `coop.guestColonizationConsent`). Host person/system bounties, commission payouts, contacts, and whitelisted storyline flags replicate to guests. |
| Player transfers & comms | Click a partner's fleet for live **Comm Link**, trade credits/cargo/ships atomically via the direct Trade UI (`Intel → Coop Session → Trade with Partner` or Comm Link), chat in-game (`F8` / `coop.chatKey`), or use shared storage and jettisoned pods. |
| Networking & security | Supports LAN, UPnP/NAT-PMP, optional standalone CGNAT TCP relay (`coop.relayAddress` / `coop-launcher.jar --relay <port>`), and opt-in AES-256-GCM frame encryption (`coop.encryption=aes-gcm` with `coop.password`). |

The [limitations guide](docs/player/LIMITATIONS.md) explains the details, including storage fees,
local trade effects, missions, and differences between the two screens.

## Battles together (Wingman)

With [The Wingman Project](https://github.com/NEOysod/TheWingmanProject) 0.9.4-beta or later
installed and enabled on **both** PCs, battles are fully synchronised: when the host fights,
the guest joins the same battle over Wingman and pilots a ship in real time.

- The host opens the Wingman session automatically from the campaign map (port `24816`, TCP+UDP).
  When `coop.portMapping` is `auto`, the host also asks the router to map port `24816` via
  UPnP/NAT-PMP; if you forward ports manually, forward `24816` (TCP+UDP) alongside `7777`. The
  guest connects Wingman **once**: title screen &rarr; CO-OP &rarr; host address &rarr; port `24816`.
  The guest's coop client posts the same hint as a campaign message when a session starts.
- The guest's fleet joins the host's battles through the usual **Fight Alongside** toggle; the
  coop host reserves the guest's flagship with Wingman, so the guest gets their own ship when
  it deploys. Without the toggle, the guest flies the host's ships instead (nothing of the
  guest's is risked).
- Damage is real: a ship the guest loses in the host's battle is lost for real, exactly as in
  the AI-ally model, and the result is reconciled into the shared campaign by the same path.
- Everything degrades gracefully: without Wingman, without the connection, or with
  `coop.wingmanBattles` off, battles fall back to the solo own-fleet model below.

## Requirements

- **Starsector `0.98.5a-RC9` on both PCs**, with Ironman off. Co-op uses Normal campaign mode.
- **The same co-op build and other mods on both PCs**, including matching mod versions.
- **Launcher on Windows (`Starsector Coop Launcher.exe` / `Coop Launcher.cmd`) and Linux/macOS (`Coop Launcher.sh`).** Three modes (**Install / Update**, **Host a game**, **Join a game**) using the Java runtime bundled with Starsector.
- **A connection to the host's PC.** Use a LAN, VPN, IPv6, port forwarding, or a router with
  UPnP/NAT-PMP. The default port is `7777`; forward both TCP and UDP if configuring it manually.
  See the [connection guide](docs/player/CONNECT.md).

The games check versions and builds when connecting and reject mismatched installations.

## Quick start

### 1. Install on both PCs

**One-click on Windows (recommended):** download **`Starsector Coop Launcher.exe`** from the
[latest release](https://github.com/openmans/starsector-coop/releases/latest), run it, pick
`starsector.exe`, and click **Install & Launch** (optionally tick **The Wingman Project** if you
want manual co-op piloting). It downloads `coop-0.2.5.zip`, unpacks to `<Starsector>\mods\coop`,
backs up and patches `vmparams` (repairing broken tuning-pack classpaths too), and enables `"coop"`
in `mods\enabled_mods.json`.

**Manual install:** download `coop-0.2.5.zip` (not GitHub's source ZIP) and extract it into
`<Starsector>\mods\coop` so the files sit directly inside that folder:

```text
Starsector/
└── mods/
    └── coop/
        ├── Starsector Coop Launcher.exe
        ├── Coop Launcher.cmd
        ├── mod_info.json
        └── jars/
            ├── coop.jar
            ├── coop-forks.jar
            ├── coop-launcher.jar
            └── flatlaf.jar
```

Open **`Starsector Coop Launcher.exe`** (or **`Coop Launcher.cmd`**) and use the **Install / Update**
tab to run **Fix vmparams & mods** or **Install / Update** from GitHub.

For manual setup or a failed install check, follow the [installation guide](docs/player/INSTALL.md).

### 2. Send an invite

| Host | Guest |
| --- | --- |
| Choose **Host a game**. Leave **Campaign** on `New campaign`, or select a saved campaign to continue. | Choose **Join a game**. |
| Press **Copy invite** and send the line to your partner. | Press **Paste invite** to fill in the address, port, password, and world settings. |

The invite includes the session password. Share it privately with your partner.

### 3. Check the connection and play

1. The host presses **Check connection** and leaves the launcher open.
2. The guest presses **Check connection** with the invite pasted in.
3. Both press **Launch Starsector**, then **Play** in Starsector's own launcher.
4. Follow the save advice shown in the co-op launcher: start a **New Game** for a fresh campaign,
   or load the co-op save it names.

A failed check has details in the launcher. The [connection guide](docs/player/CONNECT.md) covers
router setup, firewall rules, and the messages shown when a connection fails.

## Before starting a campaign

**Keep your co-op saves and stay on the same build.** The host's save holds the campaign, and both
players' saves require the mod. To rejoin, load your co-op save; starting a new game with the same
seed does not restore your progress. Save compatibility across mod releases is not guaranteed.

A few other limits affect how you play:

- **Story content:** the Galatia Academy chain runs on the host. Supported temporary abyssal
  systems and free-standing lights are shared; their generation still follows the host's position.
- **Weather and ghosts:** hyperspace storm state, Host slipstreams and Leviathan trails are shared.
  Guest ability surges and cosmetic ghosts stay local; star flares and visual timing can differ.
  Native storm/boost and ghost/trail visual acceptance remains open for this preview.
- **Solo play:** disable the mod for separate solo campaigns. Co-op rules apply while it is enabled,
  even without a connected partner.

Read [what is and is not shared](docs/player/LIMITATIONS.md) before committing to a long campaign.

## Report a problem

**Collect reports before relaunching the game**, which rewrites `starsector.log`.

On both PCs, open **Logs → Save a bug report** in the co-op launcher. Then
[open an issue](https://github.com/openmans/starsector-coop/issues/new/choose) with both report ZIPs,
what each player was doing, and any `[COOP-DOCTOR]` line from the session-ending dialog or
`report.txt` inside the ZIP.

Reports include connection addresses and, by default, your newest save. Review them before posting
publicly. The [reporting guide](docs/player/REPORTING.md) lists the contents and explains how to
collect logs if the launcher cannot run.

## Development

Start with the [developer guide](README_DEV.md) for builds, tests, the two-client setup, the agent
bridge, and release packaging. The [design document](docs/COOP_MP_DESIGN.md) describes the
architecture; the [implementation plan](docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md) records the phases.

## License

The mod's own code, data, and documentation use **CC BY-NC 4.0**. See [LICENSE](LICENSE) for the
full terms, the exception for Fractal Softworks, and notices covering game-derived and third-party code.
