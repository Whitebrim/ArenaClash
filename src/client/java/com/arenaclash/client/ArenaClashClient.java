package com.arenaclash.client;

import com.arenaclash.client.gui.CardScreen;
import com.arenaclash.client.gui.DeploymentScreen;
import com.arenaclash.client.render.GameHudRenderer;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class ArenaClashClient implements ClientModInitializer {

    // Client-side state synced from server
    public static String currentPhase = "LOBBY";
    public static int timerTicks = 0;
    public static int currentRound = 0;
    public static NbtCompound cardInventoryData = null;
    public static NbtCompound deploymentSlotData = null;

    private static KeyBinding openCardsKey;
    private static KeyBinding ringBellKey;

    @Override
    public void onInitializeClient() {
        // Register keybindings
        openCardsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.arenaclash.open_cards",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.arenaclash"
        ));

        ringBellKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.arenaclash.ring_bell",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.arenaclash"
        ));

        // Register S2C packet handlers
        registerPacketHandlers();

        // Register keybind tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openCardsKey.wasPressed()) {
                if ("PREPARATION".equals(currentPhase)) {
                    // Open deployment screen during prep
                    if (cardInventoryData != null) {
                        client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                    }
                } else if ("SURVIVAL".equals(currentPhase) || "BATTLE".equals(currentPhase)) {
                    // Open card inventory view
                    if (cardInventoryData != null) {
                        client.setScreen(new CardScreen(cardInventoryData));
                    }
                }
            }

            while (ringBellKey.wasPressed()) {
                ClientPlayNetworking.send(new NetworkHandler.RingBell());
            }
        });

        // Register HUD renderer
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            GameHudRenderer.render(drawContext, renderTickCounter);
        });
    }

    private void registerPacketHandlers() {
        // Game state sync
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.GameStateSync.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        currentPhase = payload.phase();
                        timerTicks = payload.timerTicks();
                        currentRound = payload.round();
                    });
                });

        // Card inventory sync — also refresh any open screen
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.CardInventorySync.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        cardInventoryData = payload.data();
                        // If card/deployment screen is open, refresh it with new data
                        MinecraftClient client = context.client();
                        if (client.currentScreen instanceof CardScreen) {
                            client.setScreen(new CardScreen(cardInventoryData));
                        } else if (client.currentScreen instanceof DeploymentScreen) {
                            client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                        }
                    });
                });

        // Card obtained notification (totem animation)
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.CardObtained.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        MinecraftClient client = context.client();
                        if (client.player != null) {
                            // Trigger totem-like animation
                            // The actual totem effect is complex; for MVP, show message + sound
                            client.player.sendMessage(
                                    Text.literal("§6§l★ " + payload.displayName() + " Card! ★"));
                        }
                    });
                });

        // Deployment slot sync — also refresh if deployment screen is open
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.DeploymentSlotSync.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        deploymentSlotData = payload.data();
                        MinecraftClient client = context.client();
                        if (client.currentScreen instanceof DeploymentScreen && cardInventoryData != null) {
                            client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                        }
                    });
                });

        // Battle result
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.BattleResultNotify.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        MinecraftClient client = context.client();
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal("§6=== " + payload.resultType() + " - Winner: " + payload.winner() + " ==="));
                        }
                    });
                });
    }
}
