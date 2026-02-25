package com.arenaclash.client.mixin;

import com.arenaclash.client.ArenaClashClient;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercept outgoing chat messages and commands from the client
 * and forward them via TCP to the opponent / dedicated server.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientChatMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    private void arenaclash$interceptChatSend(String message, CallbackInfo ci) {
        ArenaClashTcpClient tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            tcp.sendChat(message);
        }
    }

    /**
     * Intercept /ac commands: forward them via TCP to the dedicated server
     * and cancel the local singleplayer server execution.
     */
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void arenaclash$interceptCommand(String command, CallbackInfoReturnable<?> cir) {
        if (command.startsWith("ac ") || command.equals("ac")) {
            ArenaClashTcpClient tcp = ArenaClashClient.getTcpClient();
            if (tcp != null && tcp.isConnected()) {
                tcp.sendChat("/" + command);
                cir.cancel();
            }
        }
    }
}