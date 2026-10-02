package me.mrgooto.rpc;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import com.google.gson.Gson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Main {

    // ID твоего Discord-приложения (см. README)
    private static final String APP_ID = "1512155410449174588";

    // Ключ картинки-логотипа сборки (вкладка Artifacts в настройках приложения)
    private static final String MAIN_LOGO_KEY = "main";
    private static final String SMALL_IMAGE_KEY = "mini_logo";

    // Файл состояния: пишет мод из игры. Можно переопределить переменной MYRPC_STATUS.
    private static File statusFile() {
        String env = System.getenv("MYRPC_STATUS");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(System.getProperty("user.home"), ".mydiscordrpc" + File.separator + "status.json");
    }
    private static File STATUS_FILE_CACHE;

    // Измерения NTM Space: ключ картинки = имя измерения, подпись при наведении = красивое имя
    private static final java.util.Map<String, String> NTM_DIMENSIONS = new java.util.LinkedHashMap<>();
    static {
        NTM_DIMENSIONS.put("earth",  "Earth");
        NTM_DIMENSIONS.put("mun",    "Mun");
        NTM_DIMENSIONS.put("minmus", "Minmus");
        NTM_DIMENSIONS.put("duna",   "Duna");
        NTM_DIMENSIONS.put("ike",    "Ike");
        NTM_DIMENSIONS.put("moho",   "Moho");
        NTM_DIMENSIONS.put("dres",   "Dres");
        NTM_DIMENSIONS.put("eve",    "Eve");
        NTM_DIMENSIONS.put("laythe", "Laythe");
        NTM_DIMENSIONS.put("tekto",  "Tekto");
        NTM_DIMENSIONS.put("orbit",  "Орбита");
    }

    private static final Gson GSON = new Gson();

    // Последнее прочитанное состояние (чтобы не перетирать статус одинаковыми данными)
    private static String lastRaw = "";
    private static String lastPushed = "";
    private static volatile long sessionStart = System.currentTimeMillis() / 1000;

    public static void main(String[] args) throws InterruptedException {
        DiscordRPC lib = DiscordRPC.INSTANCE;
        DiscordEventHandlers handlers = new DiscordEventHandlers();
        handlers.ready = (user) ->
                System.out.printf("Подключено к Discord как %s#%s%n", user.username, user.discriminator);
        lib.Discord_Initialize(APP_ID, handlers, true, null);

        Runtime.getRuntime().addShutdownHook(new Thread(lib::Discord_Shutdown));

        Thread updater = new Thread(() -> {
            long lastPush = 0;
            String prevState = "off"; // стартуем как после выхода из игры
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GameStatus s = readStatus();
                    // Новый сеанс игры: после "off" (выход) пришло любое живое состояние —
                    // начинаем отсчёт таймера заново
                    if ("off".equals(prevState) && !"off".equals(s.state)) {
                        sessionStart = System.currentTimeMillis() / 1000;
                        System.out.println("Новый игровой сеанс");
                    }
                    prevState = s.state;
                    String payload = GSON.toJson(s);
                    long now = System.currentTimeMillis();
                    // Шлём сразу при изменении, но не чаще раза в 2 сек (лимиты Discord)
                    if (!payload.equals(lastPushed) && now - lastPush >= 2_000) {
                        lastPushed = payload;
                        lastPush = now;
                        if ("off".equals(s.state)) {
                            lib.Discord_ClearPresence(); // игра закрыта — активности нет
                            System.out.println("Игра закрыта, активность снята");
                        } else {
                            lib.Discord_UpdatePresence(buildPresence(s));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка обновления статуса: " + e.getMessage());
                }
                try {
                    Thread.sleep(1_000); // опрашиваем файл каждую секунду
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "RPC-Updater");
        updater.start();

        // Callback-поток: Discord должен уметь присылать ready/join, иначе IPC может отваливаться
        while (updater.isAlive()) {
            lib.Discord_RunCallbacks();
            Thread.sleep(500);
        }
    }

    // ---------------------------------------------------------------- состояние

    /** Состояние игры, которое кладёт в файл мод (или ты вручную для теста). */
    static class GameStatus {
        String state = "menu";        // menu | settings | world
        String worldName = "";        // название мира/сборки
        int hp = 20;                  // сердца * 2 (как в MC)
        boolean multiplayer = false;  // true = на сервере
        boolean lan = false;          // мир открыт для сети (LAN)
        String serverName = "";       // адрес или название сервера
        boolean ntmServer = false;    // сервер с NTM — тогда большой логотип = планета
        String dimension = "";        // ключ из NTM_DIMENSIONS или пусто (обычный мир)
        double rad = 0;               // накопленное облучение игрока (NTM)
        double pollution = 0;         // суммарное загрязнение чанка (NTM)
    }

    private static GameStatus readStatus() {
        try {
            File f = statusFile();
            if (STATUS_FILE_CACHE == null) STATUS_FILE_CACHE = f;
            if (STATUS_FILE_CACHE.exists()) {
                String raw = new String(Files.readAllBytes(STATUS_FILE_CACHE.toPath()), StandardCharsets.UTF_8);
                if (!raw.equals(lastRaw)) {
                    lastRaw = raw;
                    GameStatus s = GSON.fromJson(raw, GameStatus.class);
                    System.out.println("Новое состояние: " + raw.trim());
                    return s != null ? s : new GameStatus();
                }
                // файл не менялся — всё равно вернём распарсенное
                return GSON.fromJson(raw, GameStatus.class);
            }
        } catch (Exception e) {
            System.err.println("Не удалось прочитать status.json: " + e.getMessage());
        }
        return new GameStatus(); // дефолт: главное меню
    }

    // ---------------------------------------------------------------- присенс

    private static DiscordRichPresence buildPresence(GameStatus s) {
        DiscordRichPresence p = new DiscordRichPresence();

        // Верхняя строка: что делает игрок
        switch (s.state) {
            case "settings":
                p.details = "В настройках";
                break;
            case "world":
                if (s.multiplayer) {
                    // на сервере: в верхней строке — адрес сервера
                    String server = s.serverName == null || s.serverName.isEmpty()
                            ? "сервере" : s.serverName;
                    p.details = "Играет на " + server + " — " + s.hp + "/20 HP";
                } else {
                    String world = s.worldName == null || s.worldName.isEmpty()
                            ? "одиночном мире" : "мире \"" + s.worldName + "\"";
                    p.details = "Играет в " + world + " — " + s.hp + "/20 HP";
                }
                break;
            case "loading":
                p.details = "Загружает Nuclear Tech Biohazard";
                break;
            case "menu":
            default:
                p.details = "В главном меню";
                break;
        }

        // Нижняя строка: только когда реально в мире
        if ("world".equals(s.state)) {
            if (s.multiplayer) {
                p.state = "Сетевая игра";
            } else if (s.lan) {
                p.state = "Открыт для сети (LAN)" + (s.ntmServer ? " • NTM" : "");
            } else {
                p.state = "Одиночный мир" + (s.ntmServer ? " • NTM" : "");
            }
            // Гейгер: накопленное облучение — пишем только если больше нуля
            if (s.rad >= 0.00005) {
                p.state += " • " + String.format(java.util.Locale.ROOT, "%.3f", s.rad) + " RAD";
            }
            // Общее загрязнение чанка — тоже только если есть
            if (s.pollution >= 0.0005) {
                p.state += " • Загрязнение: " + Math.min(100, Math.round(s.pollution * 100)) + "%";
            }
        } else {
            p.state = ""; // в меню/настройках/загрузке нижняя строка не нужна
        }

        // Таймер игрового сеанса: сбрасывается после выхода из игры
        p.startTimestamp = sessionStart;

        // Большая картинка: на NTM-сервере показываем планету, иначе логотип сборки
        String dim = s.dimension == null ? "" : s.dimension.toLowerCase();
        if (s.ntmServer && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = "NTM Space: " + NTM_DIMENSIONS.get(dim);
        } else if (!dim.isEmpty() && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = NTM_DIMENSIONS.get(dim);
        } else {
            p.largeImageKey = MAIN_LOGO_KEY;
            p.largeImageText = "Nuclear Tech Biohazard";
        }

        p.smallImageKey = SMALL_IMAGE_KEY;
        p.smallImageText = "Играет в Nuclear Tech Biohazard";

        return p;
    }
}
