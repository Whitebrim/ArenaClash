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
        // Arena Clash button — always visible
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("arenaclash.title.button"),
                button -> this.client.setScreen(new ConnectScreen(this))
        ).dimensions(this.width / 2 + 104, this.height / 4 + 48, 100, 20).build());

        // Continue button — only visible when TCP is connected and game is in progress
        var tcp = com.arenaclash.client.ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected() && !"LOBBY".equals(tcp.currentPhase)) {
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("arenaclash.title.continue"),
                    button -> {
                        String currentPhaseNow = tcp.currentPhase;
                        if ("SURVIVAL".equals(currentPhaseNow)) {
                            com.arenaclash.client.ArenaClashClient.scheduleReturnToSurvival();
                        } else {
                            com.arenaclash.client.ArenaClashClient.scheduleReturnToGame();
                        }
                    }
            ).dimensions(this.width / 2 + 104, this.height / 4 + 72, 100, 20).build());
        }
    }
}
