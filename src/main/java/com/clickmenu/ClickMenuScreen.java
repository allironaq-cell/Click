package com.clickmenu;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Settings screen. Layout follows the sketch:
 *   [ Left/Right Click button ]                       [ Button: <key> ]
 *   [ CPS =  ====slider====  [20] ]
 *   [ Mode: Hold/Toggle ]
 */
public class ClickMenuScreen extends Screen {
    private static final int BOX_BG = 0xA0000000;
    private static final int BOX_BORDER = 0xFFFFFFFF;
    private static final int WHITE = 0xFFFFFFFF;

    private final ClickMenuConfig cfg = ClickMenuConfig.get();

    private ButtonWidget clickTypeButton;
    private ButtonWidget keyButton;
    private ButtonWidget modeButton;
    private CpsSlider cpsSlider;
    private TextFieldWidget cpsField;

    private boolean listening = false; // waiting for the next key / mouse button
    private boolean updating = false;  // guards slider <-> text field feedback loops

    // Box rectangles (x, y, w, h), computed in init().
    private int[] boxClick, boxKey, boxCps, boxMode;

    public ClickMenuScreen() {
        super(Text.translatable("clickmenu.screen.title"));
    }

    @Override
    protected void init() {
        int margin = 20;
        int boxH = 28;

        boxClick = new int[]{margin, 24, 160, boxH};
        boxKey = new int[]{this.width - margin - 160, 24, 160, boxH};
        boxCps = new int[]{margin, 24 + boxH + 10, 250, boxH};
        boxMode = new int[]{margin, 24 + (boxH + 10) * 2, 160, boxH};

        // Top-left: Left / Right click cycle button.
        clickTypeButton = ButtonWidget.builder(Text.empty(), b -> {
            cfg.clickType = cfg.clickType == ClickMenuConfig.ClickType.LEFT
                    ? ClickMenuConfig.ClickType.RIGHT : ClickMenuConfig.ClickType.LEFT;
            ClickMenuConfig.save();
            refreshLabels();
        }).dimensions(boxClick[0] + 4, boxClick[1] + 4, boxClick[2] - 8, 20).build();

        // Top-right: activation key button.
        keyButton = ButtonWidget.builder(Text.empty(), b -> {
            listening = true;
            refreshLabels();
        }).dimensions(boxKey[0] + 4, boxKey[1] + 4, boxKey[2] - 8, 20).build();

        // Mode toggle (Hold / Toggle).
        modeButton = ButtonWidget.builder(Text.empty(), b -> {
            cfg.mode = cfg.mode == ClickMenuConfig.Mode.HOLD
                    ? ClickMenuConfig.Mode.TOGGLE : ClickMenuConfig.Mode.HOLD;
            ClickMenuConfig.save();
            refreshLabels();
        }).dimensions(boxMode[0] + 4, boxMode[1] + 4, boxMode[2] - 8, 20).build();

        // CPS row: label "CPS =" is drawn in render(); slider + numeric field sit to its right.
        updating = true;
        cpsSlider = new CpsSlider(boxCps[0] + 48, boxCps[1] + 4, 140, 20, cfg.cps);
        cpsField = new TextFieldWidget(this.textRenderer, boxCps[0] + 194, boxCps[1] + 4, 48, 20, Text.empty());
        cpsField.setMaxLength(2);
        cpsField.setTextPredicate(s -> s.isEmpty() || s.matches("\\d{1,2}"));
        cpsField.setText(String.valueOf(cfg.cps));
        cpsField.setChangedListener(this::onCpsTyped);
        updating = false;

        addDrawableChild(clickTypeButton);
        addDrawableChild(keyButton);
        addDrawableChild(modeButton);
        addDrawableChild(cpsSlider);
        addDrawableChild(cpsField);

        refreshLabels();
    }

    private void refreshLabels() {
        clickTypeButton.setMessage(Text.translatable(cfg.clickType == ClickMenuConfig.ClickType.LEFT
                ? "clickmenu.screen.click_type.left" : "clickmenu.screen.click_type.right"));

        keyButton.setMessage(listening
                ? Text.translatable("clickmenu.screen.button.listening")
                : Text.translatable("clickmenu.screen.button", cfg.getActivationKey().getLocalizedText()));

        modeButton.setMessage(Text.translatable(cfg.mode == ClickMenuConfig.Mode.HOLD
                ? "clickmenu.screen.mode.hold" : "clickmenu.screen.mode.toggle"));
    }

    // ---- CPS handling ----

    /** Typed number -> clamp, save, move slider. */
    private void onCpsTyped(String text) {
        if (updating || text.isEmpty()) return;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        int clamped = Math.max(ClickMenuConfig.MIN_CPS, Math.min(ClickMenuConfig.MAX_CPS, value));
        applyCps(clamped, clamped != value);
    }

    /** Single place that changes the CPS: saves config and syncs slider + field. */
    private void applyCps(int cps, boolean rewriteField) {
        cfg.cps = cps;
        ClickMenuConfig.save();
        updating = true;
        cpsSlider.setCps(cps);
        if (rewriteField) {
            cpsField.setText(String.valueOf(cps));
        }
        updating = false;
    }

    /** Slider whose 0..1 range maps onto the integers 1..20. */
    private class CpsSlider extends SliderWidget {
        CpsSlider(int x, int y, int w, int h, int cps) {
            super(x, y, w, h, Text.empty(), toValue(cps));
        }

        private static double toValue(int cps) {
            return (cps - ClickMenuConfig.MIN_CPS) / (double) (ClickMenuConfig.MAX_CPS - ClickMenuConfig.MIN_CPS);
        }

        private static int toCps(double value) {
            return ClickMenuConfig.MIN_CPS
                    + (int) Math.round(value * (ClickMenuConfig.MAX_CPS - ClickMenuConfig.MIN_CPS));
        }

        void setCps(int cps) {
            this.value = toValue(cps);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.empty()); // the exact value is shown in the text field next to the slider
        }

        @Override
        protected void applyValue() {
            if (updating) return;
            int cps = toCps(this.value);
            this.value = toValue(cps); // snap to whole numbers
            applyCps(cps, true);
        }
    }

    // ---- key binding capture ----

    private void bindActivation(ClickMenuConfig.KeyKind kind, int code) {
        cfg.activationKey.type = kind;
        cfg.activationKey.code = code;
        ClickMenuConfig.save();
        listening = false;
        refreshLabels();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (listening) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                listening = false; // Escape cancels
                refreshLabels();
            } else {
                bindActivation(ClickMenuConfig.KeyKind.KEYSYM, input.key());
            }
            return true;
        }
        // M closes the menu (unless the player is typing a number into the CPS field).
        if (!cpsField.isFocused() && ClickMenuMod.OPEN_MENU_KEY.matchesKey(input)) {
            close();
            return true;
        }
        return super.keyPressed(input); // Escape closes via the default Screen behaviour
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (listening) {
            bindActivation(ClickMenuConfig.KeyKind.MOUSE, click.button());
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    // ---- rendering ----

    private void drawBox(DrawContext ctx, int[] b) {
        ctx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], BOX_BG);
        ctx.drawBorder(b[0], b[1], b[2], b[3], BOX_BORDER);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // The dimmed/blurred world background is already drawn by the game before render().
        drawBox(ctx, boxClick);
        drawBox(ctx, boxKey);
        drawBox(ctx, boxCps);
        drawBox(ctx, boxMode);

        super.render(ctx, mouseX, mouseY, delta); // widgets on top of the boxes

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, WHITE);
        ctx.drawTextWithShadow(this.textRenderer, Text.translatable("clickmenu.screen.cps"),
                boxCps[0] + 6, boxCps[1] + 10, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("clickmenu.screen.hint"),
                this.width / 2, this.height - 20, 0xFFAAAAAA);
    }
}
