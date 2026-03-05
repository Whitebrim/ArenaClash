package com.arenaclash.client.mixin;

import com.arenaclash.client.gui.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Arena Clash" button to the title screen.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void arenaclash$addButton(CallbackInfo ci) {
        // Arena Clash button — always visible
        this.addRenderableWidget(Button.builder(
                Component.translatable("arenaclash.title.button"),
                button -> this.minecraft.setScreen(new ConnectScreen(this))
        ).bounds(this.width / 2 + 104, this.height / 4 + 48, 100, 20).build());

        // Continue button — only visible when TCP is connected and game is in progress
        var tcp = com.arenaclash.client.ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected() && !"LOBBY".equals(tcp.currentPhase)) {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("arenaclash.title.continue"),
                    button -> {
                        String currentPhaseNow = tcp.currentPhase;
                        if ("SURVIVAL".equals(currentPhaseNow)) {
                            com.arenaclash.client.ArenaClashClient.scheduleReturnToSurvival();
                        } else {
                            com.arenaclash.client.ArenaClashClient.scheduleReturnToGame();
                        }
                    }
            ).bounds(this.width / 2 + 104, this.height / 4 + 72, 100, 20).build());
        }
    }
}
