package com.clickmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/clickmenu.json. Loaded at startup, saved on every change. */
public final class ClickMenuConfig {
    public enum ClickType { LEFT, RIGHT }
    public enum Mode { HOLD, TOGGLE }
    public enum KeyKind { KEYSYM, MOUSE }

    public static final class ActivationKey {
        public KeyKind type = KeyKind.KEYSYM;
        public int code = GLFW.GLFW_KEY_R;
    }

    public static final int MIN_CPS = 1;
    public static final int MAX_CPS = 20;

    public ClickType clickType = ClickType.LEFT;
    public int cps = 10;
    public ActivationKey activationKey = new ActivationKey();
    public Mode mode = Mode.HOLD;
    public boolean showMessages = true;

    // ---- singleton plumbing ----
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("clickmenu.json");
    private static ClickMenuConfig instance = new ClickMenuConfig();

    public static ClickMenuConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                ClickMenuConfig loaded = GSON.fromJson(reader, ClickMenuConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (Exception e) {
                System.err.println("[clickmenu] Could not read config, using defaults: " + e);
                instance = new ClickMenuConfig();
            }
        }
        instance.sanitize();
        save(); // writes the file on first start and normalises bad values
    }

    public static void save() {
        instance.sanitize();
        try (Writer writer = Files.newBufferedWriter(PATH)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            System.err.println("[clickmenu] Could not save config: " + e);
        }
    }

    /** Replaces missing/invalid values (hand-edited JSON) and clamps CPS to 1-20. */
    private void sanitize() {
        if (clickType == null) clickType = ClickType.LEFT;
        if (mode == null) mode = Mode.HOLD;
        if (activationKey == null) activationKey = new ActivationKey();
        if (activationKey.type == null) activationKey.type = KeyKind.KEYSYM;
        cps = Math.max(MIN_CPS, Math.min(MAX_CPS, cps));
    }

    /** The activation key as an InputUtil.Key (UNKNOWN_KEY if unbound). */
    public InputUtil.Key getActivationKey() {
        if (activationKey.code < 0) {
            return InputUtil.UNKNOWN_KEY;
        }
        return (activationKey.type == KeyKind.MOUSE ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM)
                .createFromCode(activationKey.code);
    }
}
