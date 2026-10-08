# Discord RPC for NTM

A Discord Rich Presence for modpacks running **HBM's Nuclear Tech Mod** (Minecraft 1.7.10 Forge)
— and any other 1.7.10 pack. Two parts:

1. **Bridge mod** (`NtmRpcBridge`) — writes the game state to `~/.mydiscordrpc/status.json`
   every second.
2. **RPC app** (`MyDiscordRPC.jar`) — reads that file and shows the status in Discord over
   local IPC. Runs in the background; Minecraft can be closed.

> [!NOTE]
> The profile title («Playing …») is the Discord application name. By default the author's
> application is used; you can create your own at [discord.com/developers](https://discord.com/developers/applications)
> and put its ID into `config.json`.

## What it shows

| Situation | Status |
|---|---|
| Pack loading | `Loading …` |
| Main menu (incl. mod menus) | `In main menu` |
| Settings | `In settings` |
| In a singleplayer world | `Playing in "…" — 14/20 HP` + `Singleplayer world` |
| World open to LAN | `…` + `Open to LAN` |
| On a server | `Playing on mc.example.com — …` + `Multiplayer` |

Additionally in-world: a **Geiger counter** (accumulated radiation from NTM, shown only if > 0:
`0.35 RAD`) and **chunk pollution** (`Pollution: 27%`).

**Large image** — the modpack logo; in NTM Space dimensions it becomes the planet (Earth, Mun,
Minmus, Duna, Ike, Moho, Dres, Eve, Laythe, Tekto, Orbit). Dimensions are resolved through the
NTM celestial-body registry, so every planet of the mod works automatically.

The session timer resets when you quit the game (including after a crash — the app treats the
game as closed if the status file goes silent for 60 seconds).

## Installation

1. Copy `NtmRpcBridge-x.x.jar` into `mods/` (client-side only, server not needed).
2. Run `run.sh` (Linux/macOS) or `run.bat` (Windows) from `dist` / `dist-win`.
   Requires Java 8+; Discord/Vesktop must be running.
3. Preferably set the app to autostart (on Linux — a systemd user unit).

## Configuration (config.json, next to MyDiscordRPC.jar)

```json
{
  "app_id": "",
  "lang": "ru",
  "pack_name": "Nuclear Tech Biohazard"
}
```

- `app_id` — your Discord application ID (as a string!). Empty = the author's app.
- `lang` — `ru` or `en` (status strings language).
- `pack_name` — modpack name shown in the status.

Images are uploaded in Discord Developer Portal → Rich Presence → Art Assets:
`main` (large logo), `mini_logo` (small), and one per planet with the key
`earth`, `mun`, `moho`, `duna`, `ike`, `eve`, `laythe`, `tekto`, `minmus`, `dres`, `orbit`.

## status.json format (for integrations)

Any program can write this file — the app will display it:

```json
{
  "state": "world",
  "worldName": "My World",
  "hp": 14, "maxHp": 20,
  "multiplayer": false,
  "lan": false,
  "serverName": "",
  "ntmServer": false,
  "dimension": "moho",
  "rad": 0.35,
  "pollution": 0.27
}
```

`state`: `menu | settings | world | loading | off`. `"state": "off"` hides the activity
completely. Write the file atomically (temp file + rename).

## Building from source

- **App**: dependencies `com.github.MinnDevelopment:java-discord-rpc:2.0.2` (JitPack), `gson`,
  `jna`; `gradle jar` + the `discord-rpc` native library from
  [discord/discord-rpc v3.4.0](https://github.com/discord/discord-rpc/releases/tag/v3.4.0)
  (`libdiscord-rpc.so` / `discord-rpc.dll` next to the jar, run with `-Djna.library.path=lib`).
- **Mod**: ForgeGradle 1.2 (the `com.anatawa12.forge` fork) + Gradle 4.10.3, JDK 8:
  `gradle build`.

Tested with NTM `1.0.27_X5778_H261`. NTM APIs are accessed via reflection: without NTM the mod
still works, just without the Geiger counter and planet images.

## License

GPL-3.0. Uses [java-discord-rpc](https://github.com/MinnDevelopment/java-discord-rpc) (Apache-2.0)
and the [discord-rpc SDK](https://github.com/discord/discord-rpc) (MIT).

---
~ by gooto ~
