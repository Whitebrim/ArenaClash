package com.arenaclash.client.mixin;

import com.arenaclash.client.gui.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Arena Clash" button to the title screen.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void arenaclash$addButton(CallbackInfo ci) {
        // Place button below the existing buttons on the right side
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§6⚔ Arena Clash"),
                button -> this.client.setScreen(new ConnectScreen(this))
        ).dimensions(this.width / 2 + 104, this.height / 4 + 48, 100, 20).build());
    }
}
