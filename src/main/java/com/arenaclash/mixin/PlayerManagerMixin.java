package com.arenaclash.mixin;

import com.arenaclash.tcp.SingleplayerBridge;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept chat messages and broadcast-type messages in singleplayer
 * to forward them to the opponent via TCP.
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    /**
     * Intercept broadcast messages (death, achievements, join/leave).
     */
    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void arenaclash$interceptBroadcast(Text message, boolean overlay, CallbackInfo ci) {
        if (!overlay) {
            String text = message.getString();
            if (text != null && !text.isEmpty()) {
                SingleplayerBridge.pendingChatMessages.add(text);
            }
        }
    }
}
