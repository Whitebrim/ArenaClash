package com.arenaclash.client.mixin;

import com.arenaclash.client.ArenaClashClient;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept outgoing chat messages from the client
 * and forward them via TCP to the opponent.
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
}
