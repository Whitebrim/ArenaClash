package com.arenaclash.mixin;

import com.arenaclash.tcp.SingleplayerBridge;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept broadcast messages (achievements, deaths, join/leave) in singleplayer
 * and forward them to the opponent via TCP as BROADCAST.
 *
 * We serialize the full Component as JSON so that the opponent's client
 * can reconstruct it with all vanilla formatting intact — colours, hover events
 * on advancement names, entity references, etc.
 */
@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"))
    private void arenaclash$interceptBroadcast(Component message, boolean overlay, CallbackInfo ci) {
        if (!overlay && SingleplayerBridge.survivalPhaseActive) {
            try {
                // Serialize the full styled Component as JSON — preserves colours,
                // hover events, click events, translatable components, etc.
                RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                String jsonText = ComponentSerialization.CODEC.encodeStart(ops, message).result()
                        .map(JsonElement::toString).orElse(message.getString());
                SingleplayerBridge.pendingBroadcasts.add(jsonText);
            } catch (Exception e) {
                // Fallback: plain text (loses formatting but still delivers the message)
                String plain = message.getString();
                if (plain != null && !plain.isEmpty()) {
                    SingleplayerBridge.pendingBroadcasts.add(plain);
                }
            }
        }
    }
}
