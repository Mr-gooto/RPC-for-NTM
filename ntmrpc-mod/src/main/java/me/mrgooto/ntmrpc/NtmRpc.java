package me.mrgooto.ntmrpc;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

@Mod(modid = NtmRpc.MODID, name = "NTM RPC Bridge", version = NtmRpc.VERSION)
public class NtmRpc {

    public static final String MODID = "ntmrpc";
    public static final String VERSION = "1.8";

    /** Куда пишет состояние; RPC-приложение читает этот же файл. */
    private static final File OUT_DIR = new File(System.getProperty("user.home"), ".mydiscordrpc");
    private static final File OUT_FILE = new File(OUT_DIR, "status.json");

    private int timer = 0;

    private static final String BANNER =
            " .-------------------------------.\n" +
            " |      NTM  RPC  Bridge         |\n" +
            " |        ~ by gooto ~           |\n" +
            " '-------------------------------'";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        System.out.println("[NTM-RPC] Bridge loaded, status file: " + OUT_FILE.getAbsolutePath());
        System.out.println(BANNER);
        // Сразу сигнализируем о загрузке: init выполняется ещё во время старта сборки,
        // а до первого тика может пройти заметное время
        write("{\"state\":\"loading\"}");
        // При выходе из игры сообщаем RPC-приложению, что активность пора снять
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                write("{\"state\":\"off\"}");
            }
        });
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++timer < 20) return; // раз в секунду достаточно
        timer = 0;

        Minecraft mc = Minecraft.getMinecraft();
        String json = collect(mc);
        if (json != null) write(json);
    }

    // ---------------------------------------------------------------- сбор состояния

    private String collect(Minecraft mc) {
        boolean inWorld = mc.theWorld != null && mc.thePlayer != null;

        String state;
        String worldName = "";
        int hp = 20;
        int maxHp = 20;         // в NTM максимум здоровья бывает больше 20
        boolean multiplayer = false;
        boolean lan = false;
        float rad = 0f;         // накопленное облучение игрока (NTM)
        float pollution = 0f;   // суммарное загрязнение чанка (NTM)
        String serverName = "";
        boolean ntmServer = false;
        String dimension = "";

        // В мире — играем, ESC-пауза не меняет статус
        if (inWorld) {
            state = "world";
            hp = (int) Math.max(0.0F, mc.thePlayer.getHealth());
            maxHp = (int) Math.max(1.0F, mc.thePlayer.getMaxHealth());

            if (mc.isIntegratedServerRunning()) {
                // одиночная игра / открытый в LAN мир
                worldName = mc.getIntegratedServer().getWorldName();
                multiplayer = false;
                try {
                    lan = mc.getIntegratedServer().getPublic();
                } catch (Throwable ignored) {
                    lan = false;
                }
            } else {
                multiplayer = true;
                serverName = currentServerIp(mc);
                ntmServer = true; // на чужом сервере считаем, что NTM может быть — планета покажется при известном измерении
            }

            dimension = dimensionKey(mc);
            rad = playerRad(mc);
            pollution = totalPollution(mc);
        } else if (mc.currentScreen instanceof GuiOptions
                || mc.currentScreen != null && mc.currentScreen.getClass().getName().endsWith("GuiVideoSettings")) {
            // настоящие опции из главного меню
            state = "settings";
        } else if (mc.currentScreen instanceof GuiMainMenu) {
            state = "menu";
        } else if (mc.currentScreen == null || mc.currentScreen instanceof GuiDownloadTerrain) {
            state = "loading"; // старт игры (splash) или подключение к миру
        } else {
            // меню модов, выбор мира, сетевая игра и прочее — обычное меню
            state = "menu";
        }

        return "{\"state\":\"" + state + "\""
                + ",\"worldName\":\"" + esc(worldName) + "\""
                + ",\"hp\":" + hp
                + ",\"maxHp\":" + maxHp
                + ",\"multiplayer\":" + multiplayer
                + ",\"lan\":" + lan
                + ",\"serverName\":\"" + esc(serverName) + "\""
                + ",\"ntmServer\":" + ntmServer
                + ",\"dimension\":\"" + dimension + "\""
                + ",\"rad\":" + String.format(java.util.Locale.ROOT, "%.4f", rad)
                + ",\"pollution\":" + String.format(java.util.Locale.ROOT, "%.4f", pollution)
                + "}";
    }

    /** Накопленное облучение игрока из NTM (HbmLivingProps.getRadiation). 0 если NTM отсутствует. */
    private float playerRad(Minecraft mc) {
        try {
            Class<?> c = Class.forName("com.hbm.extprop.HbmLivingProps");
            Object v = c.getMethod("getRadiation", net.minecraft.entity.EntityLivingBase.class)
                    .invoke(null, mc.thePlayer);
            return v instanceof Float ? (Float) v : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Суммарное загрязнение чанка из NTM (PollutionHandler.getPollutionData): смог+сажа+яды+металлы. */
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
            }
            return sum;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** В 1.7.10 currentServerData приватный — достаём через рефлексию (SRG-имя field_71421_f). */
    private String currentServerIp(Minecraft mc) {
        try {
            java.lang.reflect.Field f;
            try {
                f = Minecraft.class.getDeclaredField("currentServerData");
            } catch (NoSuchFieldException e) {
                f = Minecraft.class.getDeclaredField("field_71421_f");
            }
            f.setAccessible(true);
            ServerData data = (ServerData) f.get(mc);
            return data == null ? "сервер" : data.serverIP;
        } catch (Exception e) {
            return "сервер";
        }
    }

    /**
     * Обычный мир → earth; измерения NTM → по официальному реестру CelestialBody
     * (имена планет: kerbin, mun, moho, ...). Если реестр недоступен — эвристика по имени провайдера.
     */
    private String dimensionKey(Minecraft mc) {
        int id = mc.theWorld.provider.dimensionId;
        if (id == 0 || id == -1 || id == 1) return "earth"; // overworld

        // Орбиту проверяем первой: в реестре NTM станция привязана к Кербину,
        // и реестр ответил бы «kerbin», хотя ты в космосе
        String cls0 = mc.theWorld.provider.getClass().getSimpleName().toLowerCase();
        if (cls0.contains("orbit")) return "orbit";

        // Основной путь: реестр небесных тел NTM
        try {
            Class<?> cb = Class.forName("com.hbm.dim.CelestialBody");
            Object body = cb.getMethod("getBody", net.minecraft.world.World.class)
                    .invoke(null, mc.theWorld);
            if (body != null) {
                String name = ((String) cb.getField("name").get(body)).toLowerCase();
                if (name.equals("kerbin")) return "earth";
                if (name.equals("moon"))   return "mun";
                return name; // moho, duna, ike, eve, laythe, tekto, minmus, dres, orbit...
            }
        } catch (Throwable ignored) {
            // ниже запасной вариант
        }

        // Запасной вариант: по имени класса провайдера
        String cls = mc.theWorld.provider.getClass().getSimpleName().toLowerCase();
        if (cls.contains("moon"))   return "mun";
        if (cls.contains("mun"))    return "mun";
        if (cls.contains("minmus")) return "minmus";
        if (cls.contains("duna"))   return "duna";
        if (cls.contains("ike"))    return "ike";
        if (cls.contains("moho"))   return "moho";
        if (cls.contains("dres"))   return "dres";
        if (cls.contains("eve"))    return "eve";
        if (cls.contains("laythe")) return "laythe";
        if (cls.contains("tekto"))  return "tekto";
        if (cls.contains("orbit"))  return "orbit";
        if (cls.contains("celestial")) return ""; // общий провайдер — имя не определить
        return ""; // неизвестное измерение — обычный логотип
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Атомарная запись: temp + rename, чтобы читатель (RPC-приложение) не поймал обрезанный JSON. */
    private void write(String json) {
        try {
            if (!OUT_DIR.exists()) OUT_DIR.mkdirs();
            File tmp = new File(OUT_DIR, "status.json.tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            w.write(json);
            w.close();
            if (OUT_FILE.exists() && !OUT_FILE.delete()) return;
            if (!tmp.renameTo(OUT_FILE))
                throw new IllegalStateException("rename failed");
        } catch (Exception e) {
            System.err.println("[NTM-RPC] Failed to write status.json: " + e.getMessage());
        }
    }
}
