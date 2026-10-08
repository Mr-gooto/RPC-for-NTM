package me.mrgooto.rpc;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {

    private static String APP_ID = "";
    private static String MAIN_LOGO_KEY = "main";
    private static String SMALL_IMAGE_KEY = "mini_logo";
    private static Lang L;

    // Файл состояния: пишет мод из игры. Можно переопределить переменной MYRPC_STATUS.
    private static File statusFile() {
        String env = System.getenv("MYRPC_STATUS");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(System.getProperty("user.home"), ".mydiscordrpc" + File.separator + "status.json");
    }
    private static final File STATUS_FILE = statusFile();
    // Игра считается закрытой, если файл состояния молчит дольше этого времени (мод пишет раз в секунду)
    private static final long STALE_MS = 60_000;

    // Измерения NTM Space: ключ картинки = имя измерения, подпись при наведении = красивое имя
    private static final Map<String, String> NTM_DIMENSIONS = new LinkedHashMap<>();
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
        NTM_DIMENSIONS.put("orbit",  "Orbit");
    }

    /** Строки интерфейса. ru по умолчанию, en — через config.json. */
    private static final Map<String, Map<String, String>> STRINGS = new HashMap<>();
    static {
        Map<String, String> ru = new HashMap<>();
        ru.put("settings",    "В настройках");
        ru.put("menu",        "В главном меню");
        ru.put("loading",     "Загружает %s");
        ru.put("onServer",    "Играет на %s — %s/%s HP");
        ru.put("inWorld",     "Играет в мире \"%s\" — %s/%s HP");
        ru.put("single",      "Одиночный мир");
        ru.put("lan",         "Открыт для сети (LAN)");
        ru.put("multiplayer", "Сетевая игра");
        ru.put("pollution",   "Загрязнение: %d%%");
        ru.put("playing",     "Играет в %s");
        ru.put("started",     "Запущен RPC для сборки:");
        ru.put("connected",   "Подключено к Discord как %s");
        ru.put("newSession",  "Новый игровой сеанс");
        ru.put("activityCleared", "Игра закрыта, активность снята");
        ru.put("newState",    "Новое состояние:");
        ru.put("stale",       "Файл состояния не обновляется, считаю игру закрытой");
        ru.put("error",       "Ошибка:");
        STRINGS.put("ru", ru);

        Map<String, String> en = new HashMap<>();
        en.put("settings",    "In settings");
        en.put("menu",        "In main menu");
        en.put("loading",     "Loading %s");
        en.put("onServer",    "Playing on %s — %s/%s HP");
        en.put("inWorld",     "Playing in \"%s\" — %s/%s HP");
        en.put("single",      "Singleplayer world");
        en.put("lan",         "Open to LAN");
        en.put("multiplayer", "Multiplayer");
        en.put("pollution",   "Pollution: %d%%");
        en.put("playing",     "Playing %s");
        en.put("started",     "RPC started for pack:");
        en.put("connected",   "Connected to Discord as %s");
        en.put("newSession",  "New game session");
        en.put("activityCleared", "Game closed, activity cleared");
        en.put("newState",    "New state:");
        en.put("stale",       "Status file is stale, treating the game as closed");
        en.put("error",       "Error:");
        STRINGS.put("en", en);
    }

    private static final Gson GSON = new Gson();
    private static String lastRaw = "";
    private static GameStatus lastGood = offStatus(); // последний успешно разобранный статус; изначально игра закрыта
    private static String lastShown = "";                  // сигнатура уже показанного присенса
    private static volatile long sessionStart = System.currentTimeMillis() / 1000;
    private static String packName = "Nuclear Tech Biohazard";

    public static void main(String[] args) throws InterruptedException {
        loadConfig();

        DiscordRPC lib = DiscordRPC.INSTANCE;
        DiscordEventHandlers handlers = new DiscordEventHandlers();
        handlers.ready = (user) -> System.out.printf(
                String.format(L.get("connected"), displayName(user.username, user.discriminator)) + "%n");
        // autoRegister=false: URL-схему Discord нам регистрировать не нужно
        lib.Discord_Initialize(APP_ID, handlers, false, null);

        Runtime.getRuntime().addShutdownHook(new Thread(lib::Discord_Shutdown));

        Thread updater = new Thread(() -> {
            long lastPush = 0;
            String prevState = "off"; // стартуем как после выхода из игры
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GameStatus s = readStatus();
                    if ("off".equals(prevState) && !"off".equals(s.state)) {
                        sessionStart = System.currentTimeMillis() / 1000;
                        System.out.println(L.get("newSession"));
                    }
                    prevState = s.state;

                    DiscordRichPresence p = buildPresence(s);
                    String signature = presenceSignature(p);
                    long now = System.currentTimeMillis();
                    // Шлём только когда видимый статус изменился, не чаще раза в 2 сек
                    if (!signature.equals(lastShown) && now - lastPush >= 2_000) {
                        lastShown = signature;
                        lastPush = now;
                        if ("off".equals(s.state)) {
                            lib.Discord_ClearPresence();
                            System.out.println(L.get("activityCleared"));
                        } else {
                            lib.Discord_UpdatePresence(p);
                        }
                    }
                } catch (Exception e) {
                    System.err.println(L.get("error") + " " + e);
                    e.printStackTrace();
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

    /** Имя без дискриминатора, если он "0" (новые аккаунты Discord). */
    private static String displayName(String name, String disc) {
        return disc == null || "0".equals(disc) || disc.isEmpty() ? name : name + "#" + disc;
    }

    // ---------------------------------------------------------------- конфиг

    /** Конфиг рядом с jar (не с рабочей папкой!). Нет конфига — создаём шаблон. */
    private static void loadConfig() {
        String lang = "ru";
        APP_ID = "1512155410449174588"; // приложение проекта по умолчанию

        try {
            File jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File cfg = new File(jar.getParentFile(), "config.json");
            if (cfg.exists()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(cfg), StandardCharsets.UTF_8)) {
                    JsonElement root = new JsonParser().parse(r);
                    if (root != null && root.isJsonObject()) {
                        JsonObject o = root.getAsJsonObject();
                        String id = stringValue(o, "app_id");
                        if (!id.isEmpty()) APP_ID = id;
                        String lg = stringValue(o, "lang");
                        if (!lg.isEmpty()) lang = lg.toLowerCase();
                        String pn = stringValue(o, "pack_name");
                        if (!pn.isEmpty()) packName = pn;
                    }
                }
            } else {
                String template = "{\n"
                        + "  \"app_id\": \"\",\n"
                        + "  \"lang\": \"ru\",\n"
                        + "  \"pack_name\": \"Nuclear Tech Biohazard\"\n"
                        + "}\n";
                Files.write(cfg.toPath(), template.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.err.println("config.json: " + e.getMessage());
        }
        L = new Lang(STRINGS.containsKey(lang) ? lang : "ru");
        System.out.println(L.get("started") + " " + packName);
    }

    /** Строка из JSON-объекта; примитивы отдаём как исходный текст — число не теряет точности. */
    private static String stringValue(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString().trim();
        return "";
    }

    // ---------------------------------------------------------------- состояние

    /** Состояние игры, которое кладёт в файл мод (или ты вручную для теста). */
    static class GameStatus {
        String state = "menu";        // menu | settings | world | loading | off
        String worldName = "";        // название мира/сборки
        int hp = 20;                  // текущее здоровье
        int maxHp = 20;               // максимум здоровья (в NTM бывает больше 20)
        boolean multiplayer = false;  // true = на сервере
        boolean lan = false;          // мир открыт для сети (LAN)
        String serverName = "";       // адрес или название сервера
        boolean ntmServer = false;    // сервер с NTM — тогда большой логотип = планета
        String dimension = "";        // ключ из NTM_DIMENSIONS или пусто (обычный мир)
        double rad = 0;               // накопленное облучение игрока (NTM)
        double pollution = 0;         // суммарное загрязнение чанка (NTM)
    }

    private static GameStatus readStatus() {
        // Нет файла — игра не запущена (или файл удалили): активность скрываем
        if (!STATUS_FILE.exists()) {
            lastGood = offStatus();
            return lastGood;
        }
        try {
            // Мод пишет раз в секунду. Молчит дольше STALE_MS — игра вылетела/умерла: считаем off
            boolean stale = System.currentTimeMillis() - STATUS_FILE.lastModified() > STALE_MS;
            String raw = new String(Files.readAllBytes(STATUS_FILE.toPath()), StandardCharsets.UTF_8);
            if (!raw.equals(lastRaw)) {
                lastRaw = raw;
                GameStatus s = GSON.fromJson(raw, GameStatus.class);
                if (s != null && s.state != null) {
                    lastGood = s;
                    System.out.println(L.get("newState") + " " + raw.trim());
                }
                // пустой/битый JSON: s == null или state == null — остаёмся на lastGood
            }
            if (stale && !"off".equals(lastGood.state)) {
                if (!staleAnnounced) {
                    staleAnnounced = true;
                    System.out.println(L.get("stale")); // один раз, а не каждую секунду
                }
                GameStatus off = offStatus();
                return off;
            }
            staleAnnounced = false;
            return lastGood;
        } catch (Exception e) {
            // ошибка чтения — не дёргаем статус, держим последний хороший
            System.err.println("status.json: " + e.getMessage());
            return lastGood;
        }
    }

    private static boolean staleAnnounced = false;

    private static GameStatus offStatus() {
        GameStatus off = new GameStatus();
        off.state = "off";
        return off;
    }

    // ---------------------------------------------------------------- присенс

    private static DiscordRichPresence buildPresence(GameStatus s) {
        DiscordRichPresence p = new DiscordRichPresence();

        // Верхняя строка: что делает игрок
        switch (s.state == null ? "menu" : s.state) {
            case "settings":
                p.details = L.get("settings");
                break;
            case "world":
                String max = String.valueOf(s.maxHp > 0 ? s.maxHp : 20);
                if (s.multiplayer) {
                    String server = s.serverName == null || s.serverName.isEmpty() ? "?" : s.serverName;
                    p.details = String.format(L.get("onServer"), server, String.valueOf(s.hp), max);
                } else {
                    String world = s.worldName == null || s.worldName.isEmpty() ? "?" : truncate(s.worldName);
                    p.details = String.format(L.get("inWorld"), world, String.valueOf(s.hp), max);
                }
                break;
            case "loading":
                p.details = String.format(L.get("loading"), packName);
                break;
            case "menu":
            default:
                p.details = L.get("menu");
                break;
        }

        // Нижняя строка: только когда реально в мире
        if ("world".equals(s.state)) {
            if (s.multiplayer) {
                p.state = L.get("multiplayer");
            } else if (s.lan) {
                p.state = L.get("lan") + (s.ntmServer ? " • NTM" : "");
            } else {
                p.state = L.get("single") + (s.ntmServer ? " • NTM" : "");
            }
            if (s.rad >= 0.00005) {
                p.state += " • " + String.format(java.util.Locale.ROOT, "%.3f", s.rad) + " RAD";
            }
            if (s.pollution >= 0.0005) {
                p.state += " • " + String.format(L.get("pollution"),
                        Math.min(100, Math.round(s.pollution * 100)));
            }
        } else {
            p.state = "";
        }

        // Таймер игрового сеанса: сбрасывается после выхода из игры
        p.startTimestamp = sessionStart;

        String dim = s.dimension == null ? "" : s.dimension.toLowerCase();
        if (s.ntmServer && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = "NTM Space: " + NTM_DIMENSIONS.get(dim);
        } else if (!dim.isEmpty() && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = NTM_DIMENSIONS.get(dim);
        } else {
            p.largeImageKey = MAIN_LOGO_KEY;
            p.largeImageText = packName;
        }

        p.smallImageKey = SMALL_IMAGE_KEY;
        p.smallImageText = String.format(L.get("playing"), packName);

        return p;
    }

    /** Сигнатура видимой части статуса — обновляем Discord только когда она меняется. */
    private static String presenceSignature(DiscordRichPresence p) {
        return p.details + "|" + p.state + "|" + p.largeImageKey + "|" + p.smallImageKey
                + "|" + p.startTimestamp;
    }

    /** Ограничение длины строки статуса (у Discord лимит 128 байт на UTF-8). */
    private static String truncate(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 60) return s;
        // режем по границе символа, а не посреди многобайтной кириллицы
        int chars = (int) (60 * ((double) s.length() / b.length));
        String cut = s.substring(0, Math.max(1, chars - 1));
        while (cut.getBytes(StandardCharsets.UTF_8).length > 57) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    /** Простая обёртка над словарём строк. */
    private static class Lang {
        private final Map<String, String> map;
        Lang(String lang) { this.map = STRINGS.get(lang); }
        String get(String key) {
            String v = map.get(key);
            return v != null ? v : key;
        }
    }
}
