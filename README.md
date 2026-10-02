# My Discord RPC — Nuclear Tech Biohazard

Свой Discord Rich Presence на Java для сборки **Nuclear Tech Biohazard**.

## Что показывает

- **Верхняя строка** (details): `В главном меню` / `В настройках` / `Играет в мире "название" — 7 ❤`
- **Нижняя строка** (state): `Локальный мир` или `На сервере: <имя>`
- **Большая картинка**: логотип сборки; если играешь в измерении NTM Space или на NTM-сервере —
  картинка планеты (`earth`, `mun`, `minmus`, `duna`, `ike`, `moho`, `dres`, `eve`, `laythe`, `tekto`),
  при наведении — название планеты.
- Таймер сессии рисует сам Discord.

## Структура

```
dist/
  MyDiscordRPC.jar      — готовая сборка (все зависимости внутри)
  lib/libdiscord-rpc.so — нативная библиотека Discord
  status.json           — состояние игры (его читает программа)
  run.sh                — запуск: ./run.sh
src/main/java/.../Main.java — исходник
```

## Настройка (один раз)

1. https://discord.com/developers/applications → **New Application**, имя: `Nuclear Tech Biohazard`
   (имя приложения = «Играет в Nuclear Tech Biohazard» в профиле).
2. Скопируй **Application ID** → вставь в `Main.java` вместо `YOUR_APPLICATION_ID` и пересобери
   (см. «Сборка» ниже).
3. Вкладка **Artifacts** → залей картинки:
   - `main` — большой логотип сборки,
   - `icon` — маленькая иконка,
   - по одной картинке на планету с ключами ровно `earth`, `mun`, `minmus`, `duna`, `ike`,
     `moho`, `dres`, `eve`, `laythe`, `tekto`.
4. Запусти Discord (клиент, не браузер) и `./run.sh`.

## status.json

Программа раз в 15 секунд перечитывает `status.json`:

```json
{
  "state": "world",            // "menu" | "settings" | "world"
  "worldName": "Мой мир",      // название мира
  "hp": 14,                    // здоровье (20 = 10 сердец)
  "multiplayer": false,        // true = на сервере
  "serverName": "mc.example.com",
  "ntmServer": false,          // true = сервер с NTM (логотип = планета)
  "dimension": ""              // ключ планеты из списка выше, или "" = обычный мир
}
```

Пока его можно править руками для теста. Чтобы данные шли сами из игры, нужен маленький мод,
который пишет в этот файл — скажи, и напишем (Fabric/Forge).

## Сборка из исходника

Нужен JDK 8+ и Gradle:

```bash
gradle installDist
```

Либо руками: `javac -cp discord-rpc.jar:gson.jar:jna.jar Main.java` и склейка в jar.
Примечание: библиотека `com.github.MinnDevelopment:java-discord-rpc` доступна только через
JitPack (Maven Central её не хостит).
