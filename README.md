# Discord RPC for NTM

Standalone Discord Rich Presence for modpacks running **HBM's Nuclear Tech Mod**
(Minecraft 1.7.10 Forge) — and any other 1.7.10 pack.

**Everything is in one mod.** Download `NtmRpc-2.2.jar`, drop it into `mods/` — done.
No companion apps, no archives, nothing else to run.

> [!NOTE]
> As of v2.0 the separate companion app is **not needed anymore** — the mod now bundles
> the discord-rpc native libraries (Windows / Linux / macOS) and connects to Discord itself.
> The old `MyDiscordRPC` app in `dist/` is kept only for legacy users of v1.x.

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

The session timer resets when you quit the game; activity is removed when the game closes.

## Installation

1. Download `NtmRpc-2.2.jar` from [Releases](https://github.com/Mr-gooto/RPC-for-NTM/releases).
2. Put it into your instance's `mods/` folder (client-side only, server not needed).
3. Launch the game with Discord (or Vesktop) running. That's it.

## Configuration (`config/ntmrpc.cfg`, created on first launch)

```ini
rpc {
    app_id = "1512155410449174588"
    lang = "ru"          # ru | en
    pack_name = "Nuclear Tech Biohazard"
}
images {
    main_logo = "main"
    small_logo = "mini_logo"
}
```

- `app_id` — your own Discord application ID if you want custom branding
  (create one at [discord.com/developers](https://discord.com/developers/applications)).
- `lang` — `ru` or `en` (status strings language).
- `pack_name` — modpack name shown in the status.

Images are uploaded in Discord Developer Portal → Rich Presence → Art Assets:
`main` (large logo), `mini_logo` (small), and one per planet with the key
`earth`, `mun`, `moho`, `duna`, `ike`, `eve`, `laythe`, `tekto`, `minmus`, `dres`, `orbit`.
By default the author's images are used (his application ID is preset).

## Building from source

- **Mod**: ForgeGradle 1.2 (the `com.anatawa12.forge` fork) + Gradle 4.10.3, JDK 8:
  `gradle build`. Compilation needs `java-discord-rpc` and `jna` jars in `libs/`
  (see `libs/` in the repo); the built jar is then repacked with JNA + natives bundled —
  see the `NtmRpc-2.2.jar` in Releases for the final artifact.
- The legacy standalone app lives in `dist/` / `dist-win/` (see git history of v1.x for docs).

Tested with NTM `1.0.27_X5778_H261`. NTM APIs are accessed via reflection: without NTM the mod
still works, just without the Geiger counter and planet images.

## License

GPL-3.0. Uses [java-discord-rpc](https://github.com/MinnDevelopment/java-discord-rpc) (Apache-2.0),
[JNA](https://github.com/java-native-access/jna) (LGPL-2.1/Apache-2.0) and the
[discord-rpc SDK](https://github.com/discord/discord-rpc) (MIT).

---
~ by gooto ~
