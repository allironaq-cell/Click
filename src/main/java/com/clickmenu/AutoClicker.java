package com.clickmenu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * The click engine. Runs once per client tick (20/s).
 *
 * Fractional accumulator: each tick adds CPS/20. Every time it reaches 1.0 one click is
 * performed and 1.0 is subtracted. Over a second this yields exactly CPS clicks on average,
 * including values that do not divide 20 (7, 13, ...), e.g. CPS 13 -> pattern of 0/1 clicks
 * per tick averaging 0.65.
 */
public final class AutoClicker {
    /**
     * Input is processed once per tick, so more than 1 click/tick is already beyond normal
     * play. With CPS limited to 20 the accumulator only ever yields 1 click per tick, but if
     * the config is edited or the limit raised later, we hard-cap at 2 clicks per tick and
     * discard the excess. This keeps us from flooding the server with packets in one tick.
     */
    private static final int MAX_CLICKS_PER_TICK = 2;

    private static boolean active = false;
    private static boolean keyWasDown = false;
    private static double accumulator = 0.0;

    private AutoClicker() {}

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        // No world / no player / dead -> stop completely (silently).
        if (client.world == null || player == null || client.interactionManager == null || !player.isAlive()) {
            active = false;
            keyWasDown = false;
            accumulator = 0.0;
            return;
        }

        ClickMenuConfig cfg = ClickMenuConfig.get();
        boolean keyDown = isActivationKeyDown(client, cfg);

        // Never click while a screen is open. Keep keyWasDown in sync so closing the menu
        // while the key is held does not register as a phantom "press".
        if (client.currentScreen != null) {
            keyWasDown = keyDown;
            accumulator = 0.0;
            if (cfg.mode == ClickMenuConfig.Mode.HOLD) {
                active = false;
            }
            return;
        }

        switch (cfg.mode) {
            case HOLD -> setActive(client, keyDown);
            case TOGGLE -> {
                if (keyDown && !keyWasDown) { // rising edge = one press
                    setActive(client, !active);
                }
            }
        }
        keyWasDown = keyDown;

        if (!active) {
            accumulator = 0.0;
            return;
        }

        accumulator += cfg.cps / 20.0;
        int clicksThisTick = 0;
        while (accumulator >= 1.0) {
            accumulator -= 1.0;
            if (clicksThisTick < MAX_CLICKS_PER_TICK) {
                click(client, player, cfg.clickType);
                clicksThisTick++;
            } // else: excess is dropped (see MAX_CLICKS_PER_TICK)
        }
    }

    private static void setActive(MinecraftClient client, boolean newState) {
        if (active == newState) return;
        active = newState;
        accumulator = 0.0;
        if (ClickMenuConfig.get().showMessages) {
            client.inGameHud.setOverlayMessage(
                    Text.translatable(newState ? "clickmenu.message.on" : "clickmenu.message.off"), false);
        }
    }

    /** Polls GLFW directly so keyboard keys and mouse buttons both work. */
    private static boolean isActivationKeyDown(MinecraftClient client, ClickMenuConfig cfg) {
        int code = cfg.activationKey.code;
        if (code < 0) return false;
        long window = client.getWindow().getHandle();
        if (cfg.activationKey.type == ClickMenuConfig.KeyKind.MOUSE) {
            return GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
    }

    private static void click(MinecraftClient client, ClientPlayerEntity player, ClickMenuConfig.ClickType type) {
        if (type == ClickMenuConfig.ClickType.LEFT) {
            leftClick(client, player);
        } else {
            rightClick(client, player);
        }
    }

    // ------------------------------------------------------------------ LEFT CLICK
    // Mirrors MinecraftClient#doAttack / handleBlockBreaking: same interaction-manager
    // calls, so the server receives genuine attack / dig / swing packets.
    private static void leftClick(MinecraftClient client, ClientPlayerEntity player) {
        ClientPlayerInteractionManager im = client.interactionManager;
        HitResult target = client.crosshairTarget;

        if (target != null) {
            switch (target.getType()) {
                case ENTITY -> {
                    im.attackEntity(player, ((EntityHitResult) target).getEntity());
                    player.swingHand(Hand.MAIN_HAND);
                    return;
                }
                case BLOCK -> {
                    BlockHitResult hit = (BlockHitResult) target;
                    BlockPos pos = hit.getBlockPos();
                    if (!client.world.getBlockState(pos).isAir()) {
                        // updateBlockBreakingProgress() starts breaking (attackBlock) if this
                        // is a new block, otherwise advances the progress. Because we only call
                        // it once per click, mining speed scales with CPS (20 CPS ~ vanilla hold).
                        if (im.updateBlockBreakingProgress(pos, hit.getSide())) {
                            client.particleManager.addBlockBreakingParticles(pos, hit.getSide());
                        }
                        player.swingHand(Hand.MAIN_HAND);
                        return;
                    }
                }
                default -> { /* MISS falls through to the plain swing below */ }
            }
        }
        // Nothing to hit: just swing.
        player.swingHand(Hand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ RIGHT CLICK
    // Mirrors MinecraftClient#doItemUse: for each hand try entity -> block -> item.
    private static void rightClick(MinecraftClient client, ClientPlayerEntity player) {
        ClientPlayerInteractionManager im = client.interactionManager;

        // Vanilla does not start a new use while the player is already using an item
        // (eating, drawing a bow, blocking with a shield...).
        if (player.isUsingItem()) return;

        HitResult target = client.crosshairTarget;

        for (Hand hand : Hand.values()) {
            ItemStack stack = player.getStackInHand(hand);

            if (target != null) {
                if (target.getType() == HitResult.Type.ENTITY) {
                    EntityHitResult entityHit = (EntityHitResult) target;
                    ActionResult result = im.interactEntityAtLocation(player, entityHit.getEntity(), entityHit, hand);
                    if (!result.isAccepted()) {
                        result = im.interactEntity(player, entityHit.getEntity(), hand);
                    }
                    if (result.isAccepted()) {
                        if (result.shouldSwingHand()) player.swingHand(hand);
                        return;
                    }
                } else if (target.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult blockHit = (BlockHitResult) target;
                    ActionResult result = im.interactBlock(player, hand, blockHit);
                    if (result.isAccepted()) {
                        if (result.shouldSwingHand()) player.swingHand(hand);
                        return;
                    }
                    if (result == ActionResult.FAIL) return;
                }
            }

            // Item use, respecting the item cooldown (ender pearls, shields, etc.).
            if (!stack.isEmpty() && !player.getItemCooldownManager().isCoolingDown(stack)) {
                ActionResult result = im.interactItem(player, hand);
                if (result.isAccepted()) {
                    if (result.shouldSwingHand()) player.swingHand(hand);
                    return;
                }
            }
        }
    }
}
