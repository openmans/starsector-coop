# Upstream PR Specification: WingmanAPI Clean-Path Extensions

`starsector-coop` integrates with [The Wingman Project](https://github.com/NEOysod/TheWingmanProject)
through sandbox-safe `java.lang.invoke.MethodHandles` in
`coop.wingman.ReflectiveCoopWingmanApi`.

As of Wingman `0.9.4-beta`, `wingman.api.WingmanAPI` exposes:
- `openHost()`
- `pump()`
- `players()` / `playerName(int)`
- `reserveShip(String name, String fleetMemberId)`
- `isJoined()`
- `localName()`

To enable **zero-click automatic guest connection** and **clean session teardown / guest-initiated
Wingman battle role switching**, `ReflectiveCoopWingmanApi` already binds the following three
optional static methods if present on `wingman.api.WingmanAPI`:

```java
package wingman.api;

public final class WingmanAPI {
    /**
     * Programmatically connects the local Wingman client to {@code host:port} using the
     * player's configured Wingman name. Returns {@code true} if the connection attempt started
     * or was already connected.
     */
    public static boolean join(String host, int port);

    /**
     * Cleanly disconnects the local Wingman client session (no-op if not joined).
     */
    public static boolean disconnect();

    /**
     * Cleanly shuts down the active Wingman host listener opened via {@link #openHost()}
     * (no-op if not hosting).
     */
    public static boolean closeHost();
}
```

## Runtime Behaviour in `starsector-coop`

- **When all 3 methods are present in Wingman:**
  - Guest automatically joins `connectHost:24816` when a co-op session starts (`CoopWingmanIntegration.tickGuest`).
  - Session teardown automatically closes the Wingman host socket on the host and disconnects on the guest.
- **When running against stock Wingman `0.9.4-beta` (methods absent):**
  - `ReflectiveCoopWingmanApi.supportsAutoJoin()` returns `false`.
  - Guest displays the existing one-time manual connect hint (`host:24816`).
  - Zero crashes, zero warnings, full backward compatibility.
