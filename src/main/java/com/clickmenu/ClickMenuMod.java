package com.clickmenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client entrypoint for "Click Menu". */
public class ClickMenuMod implements ClientModInitializer {
    public static final String MOD_ID = "clickmenu";

    /** "Open Menu" key (default M), listed under Options > Controls > Key Binds > Click Menu. */
    public static KeyBinding OPEN_MENU_KEY;

    @Override
    public void onInitializeClient() {
        ClickMenuConfig.load();

        // 1.21.9+ API: key bindings belong to a KeyBinding.Category object.
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

        OPEN_MENU_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clickmenu.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Drain all queued presses; only open the menu when no other screen is showing.
            while (OPEN_MENU_KEY.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new ClickMenuScreen());
                }
            }
            AutoClicker.tick(client);
        });
    }
}
