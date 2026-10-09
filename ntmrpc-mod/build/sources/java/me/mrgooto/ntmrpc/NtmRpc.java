package me.mrgooto.ntmrpc;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import club.minnced.discord.rpc.DiscordUser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Автономный Discord RPC для NTM-сборок: всё в одном моде, никаких внешних программ.
 * Натив discord-rpc распаковывается из jar во временную папку при запуске.
 * acceptableRemoteVersions="*": клиент с модом может заходить на серверы без мода.
 */
@Mod(modid = NtmRpc.MODID, name = "NTM RPC", version = NtmRpc.VERSION,
        acceptableRemoteVersions = "*")
public class NtmRpc {

    public static final String MODID = "ntmrpc";
    public static final String VERSION = "2.1";

    // ---------------------------------------------------------------- конфиг

    private String appId = "1512155410449174588"; // приложение проекта; своё — в config/ntmrpc.cfg
    private String lang = "ru";
    private String packName = "Nuclear Tech Biohazard";
    private String mainLogo = "main";
    private String smallLogo = "mini_logo";
    private boolean hideServerIp = false;
    private boolean earthInSingleplayer = false;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        appId = cfg.get("rpc", "app_id", appId, "Discord application ID").getString().trim();
        if (appId.isEmpty()) appId = "1512155410449174588";
        lang = cfg.get("rpc", "lang", lang, "ru | en").getString().trim().toLowerCase(Locale.ROOT);
        packName = cfg.get("rpc", "pack_name", packName, "Modpack name shown in Discord").getString();
        hideServerIp = cfg.get("rpc", "hide_server_ip", hideServerIp,
                "Do not show the server address in the status").getBoolean();
        earthInSingleplayer = cfg.get("rpc", "earth_in_singleplayer", earthInSingleplayer,
                "Show the Earth planet image in singleplayer overworld instead of the pack logo").getBoolean();
        mainLogo = cfg.get("images", "main_logo", mainLogo, "Large image key").getString();
        smallLogo = cfg.get("images", "small_logo", smallLogo, "Small image key").getString();
        if (cfg.hasChanged()) cfg.save();
    }

    // ---------------------------------------------------------------- строки

    private static final Map<String, Map<String, String>> STRINGS = new HashMap<String, Map<String, String>>();
    static {
        Map<String, String> ru = new HashMap<String, String>();
        ru.put("settings", "В настройках");
        ru.put("menu", "В главном меню");
        ru.put("loading", "Загружает %s");
        ru.put("onServer", "Играет на %s — %s/%s HP");
        ru.put("inWorld", "Играет в мире \"%s\" — %s/%s HP");
        ru.put("single", "Одиночный мир");
        ru.put("lan", "Открыт для сети (LAN)");
        ru.put("multiplayer", "Сетевая игра");
        ru.put("serverWord", "сервере");
        ru.put("pollution", "Загрязнение: %s%%");
        ru.put("playing", "Играет в %s");
        STRINGS.put("ru", ru);

        Map<String, String> en = new HashMap<String, String>();
        en.put("settings", "In settings");
        en.put("menu", "In main menu");
        en.put("loading", "Loading %s");
        en.put("onServer", "Playing on %s — %s/%s HP");
        en.put("inWorld", "Playing in \"%s\" — %s/%s HP");
        en.put("single", "Singleplayer world");
        en.put("lan", "Open to LAN");
        en.put("multiplayer", "Multiplayer");
        en.put("serverWord", "a server");
        en.put("pollution", "Pollution: %s%%");
        en.put("playing", "Playing %s");
        STRINGS.put("en", en);
    }

    private String tr(String key) {
        Map<String, String> m = STRINGS.containsKey(lang) ? STRINGS.get(lang) : STRINGS.get("ru");
        String v = m.get(key);
        return v != null ? v : key;
    }

    private static final Map<String, String> NTM_DIMENSIONS = new LinkedHashMap<String, String>();
    static {
        NTM_DIMENSIONS.put("earth", "Earth");
        NTM_DIMENSIONS.put("mun", "Mun");
        NTM_DIMENSIONS.put("minmus", "Minmus");
        NTM_DIMENSIONS.put("duna", "Duna");
        NTM_DIMENSIONS.put("ike", "Ike");
        NTM_DIMENSIONS.put("moho", "Moho");
        NTM_DIMENSIONS.put("dres", "Dres");
        NTM_DIMENSIONS.put("eve", "Eve");
        NTM_DIMENSIONS.put("laythe", "Laythe");
        NTM_DIMENSIONS.put("tekto", "Tekto");
        NTM_DIMENSIONS.put("orbit", "Orbit");
    }

    // ---------------------------------------------------------------- состояние RPC

    private DiscordRPC lib;
    private boolean rpcReady = false;
    private int timer = 0;
    private long lastPush = 0;
    private long sessionStart = System.currentTimeMillis() / 1000;
    private String prevState = "off";
    private String lastShown = "";
    private boolean firstTickDone = false;
    private final java.util.Set<String> ntmWarned = new java.util.HashSet<String>(); // по одному на API

    // Поле, а не локальная переменная: JNA держит callback'и по слабым ссылкам,
    // собранный GC'ем handlers приведёт к сбою при вызове из натива
    private DiscordEventHandlers handlers;

    private static final String BANNER =
            " .-------------------------------.\n" +
            " |      NTM  RPC  v" + VERSION + "            |\n" +
            " |        ~ by gooto ~           |\n" +
            " '-------------------------------'";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!FMLCommonHandler.instance().getSide().isClient()) return; // на выделенном сервере RPC не нужен

        System.out.println("[NTM-RPC] " + BANNER);
        FMLCommonHandler.instance().bus().register(this);

        if (!extractNative()) {
            System.err.println("[NTM-RPC] Не удалось распаковать discord-rpc для этой ОС, RPC отключён");
            return;
        }

        lib = DiscordRPC.INSTANCE;
        // Discord_Initialize не бросает исключений: при выключенном Discord просто
        // переподключается в фоне, ready придёт, когда клиент появится
        handlers = new DiscordEventHandlers();
        handlers.ready = new DiscordEventHandlers.OnReady() {
            @Override
            public void accept(DiscordUser user) {
                String disc = String.valueOf(user.discriminator);
                String name = "0".equals(disc) || disc.isEmpty()
                        ? user.username : user.username + "#" + disc;
                System.out.println("[NTM-RPC] Подключено к Discord как " + name);
            }
        };
        lib.Discord_Initialize(appId, handlers, false, null);
        rpcReady = true;
        System.out.println("[NTM-RPC] RPC инициализирован, ждём подключения к Discord...");
        push(true); // «Загружает…» видно с самого старта, не дожидаясь первого тика

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                try {
                    lib.Discord_ClearPresence();
                    lib.Discord_Shutdown();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** Распаковка натива discord-rpc из мода во временную папку (атомарно, потоки закрываются). */
    private boolean extractNative() {
        String os = System.getProperty("os.name").toLowerCase();
        String res;
        if (os.contains("win")) res = "/natives/discord-rpc.dll";
        else if (os.contains("mac") || os.contains("darwin")) res = "/natives/libdiscord-rpc.dylib";
        else res = "/natives/libdiscord-rpc.so";

        File dir = new File(System.getProperty("java.io.tmpdir"), "ntmrpc-natives");
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            final File out = new File(dir, new File(res).getName());
            InputStream in = null;
            OutputStream os2 = null;
            try {
                // защита от оборванной прошлой распаковки: сравниваем размер с jar-ресурсом
                long expected = getClass().getResource(res).openConnection().getContentLengthLong();
                if (out.exists() && expected > 0 && out.length() != expected) out.delete();
                if (!out.exists()) {
                    in = getClass().getResourceAsStream(res);
                    if (in == null) return false;
                    File tmp = new File(dir, out.getName() + ".tmp");
                    os2 = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os2.write(buf, 0, n);
                    os2.close();
                    os2 = null;
                    if (out.exists()) out.delete();
                    if (!tmp.renameTo(out)) return false;
                }
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (os2 != null) os2.close(); } catch (Exception ignored) {}
            }
            // дополняем jna.library.path, а не перезаписываем
            String cur = System.getProperty("jna.library.path");
            System.setProperty("jna.library.path", cur == null || cur.isEmpty()
                    ? dir.getAbsolutePath()
                    : cur + File.pathSeparator + dir.getAbsolutePath());
            System.out.println("[NTM-RPC] native: " + out.getAbsolutePath());
            return true;
        } catch (Exception e) {
            System.err.println("[NTM-RPC] extractNative: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- тик

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (rpcReady) lib.Discord_RunCallbacks();
        if (!firstTickDone && rpcReady) {
            firstTickDone = true;               // игра ещё на экране загрузки
            push(true);
        }
        if (++timer < 20) return;               // сбор состояния раз в секунду
        timer = 0;
        if (rpcReady) push(false);
    }

    private void push(boolean force) {
        Minecraft mc = Minecraft.getMinecraft();
        GameStatus s = collect(mc);

        if ("off".equals(prevState) && !"off".equals(s.state)) {
            sessionStart = System.currentTimeMillis() / 1000; // новый игровой сеанс
        }
        prevState = s.state;

        String sig = signature(s);
        long now = System.currentTimeMillis();
        if (!force && (sig.equals(lastShown) || now - lastPush < 2_000)) return;
        lastShown = sig;
        lastPush = now;

        lib.Discord_UpdatePresence(build(s));
    }

    // ---------------------------------------------------------------- сбор состояния

    static class GameStatus {
        String state = "menu";
        String worldName = "";
        int hp = 20, maxHp = 20;
        boolean multiplayer, lan, ntmServer;
        String serverName = "";
        String dimension = "";
        float rad, pollution;
    }

    private GameStatus collect(Minecraft mc) {
        GameStatus s = new GameStatus();
        boolean inWorld = mc.theWorld != null && mc.thePlayer != null;

        if (inWorld) {
            s.state = "world";
            s.hp = (int) Math.ceil(Math.max(0.0F, mc.thePlayer.getHealth())); // 0.5 HP не превращается в 0
            s.maxHp = (int) Math.max(1.0F, mc.thePlayer.getMaxHealth());
            s.ntmServer = true; // сборка с NTM — маркер и в одиночке тоже
            if (mc.isIntegratedServerRunning()) {
                s.worldName = mc.getIntegratedServer().getWorldName();
                try { s.lan = mc.getIntegratedServer().getPublic(); } catch (Throwable ignored) {}
            } else {
                s.multiplayer = true;
                s.serverName = currentServerIp(mc);
            }
            s.dimension = dimensionKey(mc);
            // одиночка: обычный мир (и Незер/Энд) показывает логотип сборки, а не Earth
            if (!s.multiplayer && !earthInSingleplayer && "earth".equals(s.dimension)) s.dimension = "";
            s.rad = playerRad(mc);
            s.pollution = totalPollution(mc);
        } else if (mc.currentScreen instanceof GuiOptions
                || mc.currentScreen != null && mc.currentScreen.getClass().getName().endsWith("GuiVideoSettings")) {
            s.state = "settings";
        } else if (mc.currentScreen instanceof GuiMainMenu) {
            s.state = "menu";
        } else if (mc.currentScreen == null || mc.currentScreen instanceof GuiDownloadTerrain) {
            s.state = "loading";
        } else {
            s.state = "menu";
        }
        return s;
    }

    private DiscordRichPresence build(GameStatus s) {
        DiscordRichPresence p = new DiscordRichPresence();

        if ("settings".equals(s.state)) {
            p.details = tr("settings");
        } else if ("world".equals(s.state)) {
            String max = String.valueOf(s.maxHp);
            if (s.multiplayer) {
                String srv = hideServerIp ? tr("serverWord") : s.serverName;
                p.details = String.format(tr("onServer"),
                        srv == null || srv.isEmpty() ? "?" : truncate(srv), String.valueOf(s.hp), max);
            } else {
                p.details = String.format(tr("inWorld"),
                        s.worldName.isEmpty() ? "?" : truncate(s.worldName), String.valueOf(s.hp), max);
            }
        } else if ("loading".equals(s.state)) {
            p.details = String.format(tr("loading"), packName);
        } else {
            p.details = tr("menu");
        }

        if ("world".equals(s.state)) {
            p.state = s.multiplayer ? tr("multiplayer")
                    : (s.lan ? tr("lan") : tr("single")) + (s.ntmServer ? " • NTM" : "");
            if (s.rad >= 0.00005f)
                p.state += " • " + String.format(Locale.ROOT, "%.3f", s.rad) + " RAD";
            if (s.pollution >= 0.0005f)
                p.state += " • " + String.format(tr("pollution"),
                        String.valueOf(Math.min(100, Math.round(s.pollution * 100))));
        } else {
            p.state = "";
        }

        p.startTimestamp = sessionStart;

        String dim = s.dimension.toLowerCase();
        if (s.ntmServer && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = "NTM Space: " + NTM_DIMENSIONS.get(dim);
        } else if (!dim.isEmpty() && NTM_DIMENSIONS.containsKey(dim)) {
            p.largeImageKey = dim;
            p.largeImageText = NTM_DIMENSIONS.get(dim);
        } else {
            p.largeImageKey = mainLogo;
            p.largeImageText = packName;
        }
        p.smallImageKey = smallLogo;
        p.smallImageText = String.format(tr("playing"), packName);
        return p;
    }

    /** Сигнатура того, что реально видит игрок: rad/pollution уже округлены. */
    private String signature(GameStatus s) {
        return s.state + "|" + s.worldName + "|" + s.hp + "/" + s.maxHp + "|" + s.multiplayer
                + "|" + s.lan + "|" + s.serverName + "|" + s.dimension
                + "|" + String.format(Locale.ROOT, "%.3f", s.rad)
                + "|" + String.format(Locale.ROOT, "%.2f", s.pollution);
    }

    // ---------------------------------------------------------------- NTM API (рефлексия)

    private float playerRad(Minecraft mc) {
        try {
            Class<?> c = Class.forName("com.hbm.extprop.HbmLivingProps");
            Object v = c.getMethod("getRadiation", net.minecraft.entity.EntityLivingBase.class)
                    .invoke(null, mc.thePlayer);
            return v instanceof Float ? (Float) v : 0f;
        } catch (Throwable t) {
            warnOnce("getRadiation", t);
            return 0f;
        }
    }

    private float totalPollution(Minecraft mc) {
        try {
            Class<?> ph = Class.forName("com.hbm.handler.pollution.PollutionHandler");
            Object data = ph.getMethod("getPollutionData", net.minecraft.world.World.class,
                    int.class, int.class, int.class)
                    .invoke(null, mc.theWorld,
                            (int) mc.thePlayer.posX, (int) mc.thePlayer.posY, (int) mc.thePlayer.posZ);
            if (data == null) return 0f;
            float sum = 0f;
            for (java.lang.reflect.Field f : data.getClass().getFields()) {
                if (f.getType() == float.class) sum += f.getFloat(data);
                else if (f.getType() == float[].class) { // PollutionData может хранить значения массивом
                    float[] arr = (float[]) f.get(data);
                    if (arr != null) for (float v : arr) sum += v;
                }
            }
            return sum;
        } catch (Throwable t) {
            warnOnce("getPollutionData", t);
            return 0f;
        }
    }

    private void warnOnce(String api, Throwable t) {
        if (!ntmWarned.add(api)) return;
        System.err.println("[NTM-RPC] NTM API '" + api + "' недоступен — гейгер/загрязнение не показываются:");
        System.err.println("  " + t);
    }

    private String currentServerIp(Minecraft mc) {
        // публичный геттер в SRG-имени func_147104_D — сначала он
        try {
            Object data = Minecraft.class.getMethod("func_147104_D").invoke(mc);
            if (data != null) return (String) data.getClass().getField("serverIP").get(data);
        } catch (Throwable ignored) {}
        // запасной вариант — приватное поле по обоим именам
        try {
            java.lang.reflect.Field f;
            try { f = Minecraft.class.getDeclaredField("currentServerData"); }
            catch (NoSuchFieldException e) { f = Minecraft.class.getDeclaredField("field_71421_f"); }
            f.setAccessible(true);
            ServerData data = (ServerData) f.get(mc);
            return data == null ? "?" : data.serverIP;
        } catch (Exception e) {
            return "?";
        }
    }

    private String dimensionKey(Minecraft mc) {
        int id = mc.theWorld.provider.dimensionId;
        if (id == 0 || id == -1 || id == 1) return "earth";

        String cls0 = mc.theWorld.provider.getClass().getSimpleName().toLowerCase();
        if (cls0.contains("orbit")) return "orbit";

        try {
            Class<?> cb = Class.forName("com.hbm.dim.CelestialBody");
            Object body = cb.getMethod("getBody", net.minecraft.world.World.class).invoke(null, mc.theWorld);
            if (body != null) {
                String name = ((String) cb.getField("name").get(body)).toLowerCase();
                if (name.equals("kerbin")) return "earth";
                if (name.equals("moon")) return "mun";
                return name;
            }
        } catch (Throwable ignored) {}

        String cls = mc.theWorld.provider.getClass().getSimpleName().toLowerCase();
        if (cls.contains("moon") || cls.contains("mun")) return "mun";
        if (cls.contains("minmus")) return "minmus";
        if (cls.contains("duna")) return "duna";
        if (cls.contains("ike")) return "ike";
        if (cls.contains("moho")) return "moho";
        if (cls.contains("dres")) return "dres";
        if (cls.contains("eve")) return "eve";
        if (cls.contains("laythe")) return "laythe";
        if (cls.contains("tekto")) return "tekto";
        if (cls.contains("orbit")) return "orbit";
        return "";
    }

    // ---------------------------------------------------------------- утилиты

    /** Обрезка до ~60 UTF-8 байт по границам code point (не режем эмодзи/кириллицу посередине). */
    private String truncate(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 60) return s;
        StringBuilder sb = new StringBuilder();
        int len = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int bl = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (len + bl > 57) break;
            sb.appendCodePoint(cp);
            len += bl;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }
}
