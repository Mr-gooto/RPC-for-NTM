package me.mrgooto.ntmrpc;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Автономный Discord RPC для NTM-сборок: всё в одном моде, никаких внешних программ.
 * Натив discord-rpc распаковывается из jar во временную папку при запуске.
 */
@Mod(modid = NtmRpc.MODID, name = "NTM RPC", version = NtmRpc.VERSION)
public class NtmRpc {

    public static final String MODID = "ntmrpc";
    public static final String VERSION = "2.0";

    // ---------------------------------------------------------------- конфиг

    private String appId = "1512155410449174588"; // приложение проекта; своё — в config/ntmrpc.cfg
    private String lang = "ru";
    private String packName = "Nuclear Tech Biohazard";
    private String mainLogo = "main";
    private String smallLogo = "mini_logo";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        appId = cfg.get("rpc", "app_id", appId, "Discord application ID").getString();
        lang = cfg.get("rpc", "lang", lang, "ru | en").getString();
        packName = cfg.get("rpc", "pack_name", packName, "Modpack name shown in Discord").getString();
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

    private static final String BANNER =
            " .-------------------------------.\n" +
            " |      NTM  RPC  v" + VERSION + "          |\n" +
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
        DiscordEventHandlers handlers = new DiscordEventHandlers();
        handlers.ready = (user) -> System.out.println("[NTM-RPC] Подключено к Discord как "
                + ("0".equals(user.discriminator) || "0".equals(String.valueOf(user.discriminator))
                    ? user.username : user.username + "#" + user.discriminator));
        try {
            lib.Discord_Initialize(appId, handlers, false, null);
            rpcReady = true;
        } catch (Throwable t) {
            System.err.println("[NTM-RPC] Discord IPC недоступен (Discord запущен?): " + t);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                try {
                    lib.Discord_ClearPresence();
                    lib.Discord_Shutdown();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** Распаковка натива discord-rpc из мода во временную папку. */
    private boolean extractNative() {
        String os = System.getProperty("os.name").toLowerCase();
        String res;
        if (os.contains("win")) res = "/natives/discord-rpc.dll";
        else if (os.contains("mac") || os.contains("darwin")) res = "/natives/libdiscord-rpc.dylib";
        else res = "/natives/libdiscord-rpc.so";

        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "ntmrpc-natives");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, new File(res).getName());
            if (!out.exists()) {
                InputStream in = getClass().getResourceAsStream(res);
                if (in == null) return false;
                OutputStream os2 = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os2.write(buf, 0, n);
                in.close(); os2.close();
            }
            System.setProperty("jna.library.path", dir.getAbsolutePath());
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

        DiscordRichPresence p = build(s);
        lib.Discord_UpdatePresence(p);
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
            s.hp = (int) Math.max(0.0F, mc.thePlayer.getHealth());
            s.maxHp = (int) Math.max(1.0F, mc.thePlayer.getMaxHealth());
            if (mc.isIntegratedServerRunning()) {
                s.worldName = mc.getIntegratedServer().getWorldName();
                try { s.lan = mc.getIntegratedServer().getPublic(); } catch (Throwable ignored) {}
            } else {
                s.multiplayer = true;
                s.serverName = currentServerIp(mc);
                s.ntmServer = true;
            }
            s.dimension = dimensionKey(mc);
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
                p.details = String.format(tr("onServer"),
                        s.serverName.isEmpty() ? "?" : s.serverName, String.valueOf(s.hp), max);
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
                    : (s.lan ? tr("lan") : tr("single")) + (s.ntmServer && !s.multiplayer ? " • NTM" : "");
            if (s.rad >= 0.00005f) p.state += " • " + String.format(Locale.ROOT, "%.3f", s.rad) + " RAD";
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

    /** Сигнатура видимого статуса: обновляем Discord только когда она меняется. */
    private String signature(GameStatus s) {
        return s.state + "|" + s.worldName + "|" + s.hp + "/" + s.maxHp + "|" + s.multiplayer
                + "|" + s.lan + "|" + s.serverName + "|" + s.dimension + "|" + s.rad + "|" + s.pollution;
    }

    // ---------------------------------------------------------------- NTM API (рефлексия)

    private float playerRad(Minecraft mc) {
        try {
            Class<?> c = Class.forName("com.hbm.extprop.HbmLivingProps");
            Object v = c.getMethod("getRadiation", net.minecraft.entity.EntityLivingBase.class)
                    .invoke(null, mc.thePlayer);
            return v instanceof Float ? (Float) v : 0f;
        } catch (Throwable t) { return 0f; }
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
            for (java.lang.reflect.Field f : data.getClass().getFields())
                if (f.getType() == float.class) sum += f.getFloat(data);
            return sum;
        } catch (Throwable t) { return 0f; }
    }

    private String currentServerIp(Minecraft mc) {
        try {
            java.lang.reflect.Field f;
            try { f = Minecraft.class.getDeclaredField("currentServerData"); }
            catch (NoSuchFieldException e) { f = Minecraft.class.getDeclaredField("field_71421_f"); }
            f.setAccessible(true);
            ServerData data = (ServerData) f.get(mc);
            return data == null ? "?" : data.serverIP;
        } catch (Exception e) { return "?"; }
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

    private String truncate(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 60) return s;
        int chars = (int) (60 * ((double) s.length() / b.length));
        String cut = s.substring(0, Math.max(1, chars - 1));
        while (cut.getBytes(StandardCharsets.UTF_8).length > 57) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }
}
