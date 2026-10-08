# Discord RPC - Nuclear Tech Biohazard

A custom Discord Rich Presence for the **Nuclear Tech Biohazard**.

## What it shows

- **Top line** (details): `in the main menu` / `in settings` / `Playing in world "name" — 7 ❤`
- **Bottom line** (state): `Singleplayer` or `On server: <name>`
- **Large image**: the modpack logo. If you are in an NTM Space dimension or on an NTM server, it shows a planet image ( `earth`, `mun`, `minmus`, `duna`, `ike`, `moho`, `dres`, `eve`, `laythe`, `tekto` )
  with the planet name on hover.

## Structure

```
dist/
  MyDiscordRPC.jar      — ready-to-use build (all dependencies bundled)
  lib/libdiscord-rpc.so — Discord native library
  status.json           — game state (read by the program)
  run.sh                — launcher: ./run.sh
src/main/java/.../Main.java — source code
```

## Setup (one time)

1. https://discord.com/developers/applications → **New Application**, name: `random name`
   (the application name is what appears = «Playing Nuclear Tech Biohazard» on your profile).
2. Copy the **Application ID** → and paste it into `Main.java` int place of `YOUR_APPLICATION_ID` then rebuild
   (see "Building from source" below).
3. open the **Art Assets** → tab and upload your images:
   - `main` — the large modpack logo,
   - `icon` — the small icon
  
```json
{
  "state": "world",            // "menu" | "settings" | "world"
  "worldName": "My World",      // world name
  "hp": 14,                    // hp (20 = 10 hearts)
  "multiplayer": false,        // true = on a server
  "serverName": "mc.example.com",
  "ntmServer": false,          // true = server with NTM (logo becomes a planet)
  "dimension": ""              // planet key from the list above, or "" = regular world
}
```

## Building from source

Requires JDK 8+ and Gradle:

```bash
gradle installDist
```
Or manually: javac -cp discord-rpc.jar:gson.jar:jna.jar Main.java, then package the classes into a jar.
Note: the library com.github.MinnDevelopment:java-discord-rpc is only available via JitPack (it is not hosted on Maven Central).
