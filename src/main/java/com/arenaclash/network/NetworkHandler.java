package com.arenaclash.network;

import com.arenaclash.arena.Lane;
import com.arenaclash.card.MobCard;
import com.arenaclash.game.GamePhase;
import com.arenaclash.game.TeamSide;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * All custom network packets for Arena Clash.
 */
public class NetworkHandler {

    // === S2C (Server to Client) Packets ===

    /**
     * Sync game phase and timer to client.
     */
    public record GameStateSync(String phase, int timerTicks, int round) implements CustomPacketPayload {
        public static final Type<GameStateSync> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "game_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GameStateSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GameStateSync::phase,
                ByteBufCodecs.INT, GameStateSync::timerTicks,
                ByteBufCodecs.INT, GameStateSync::round,
                GameStateSync::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Sync card inventory to client.
     */
    public record CardInventorySync(CompoundTag data) implements CustomPacketPayload {
        public static final Type<CardInventorySync> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "card_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CardInventorySync> CODEC = StreamCodec.composite(
                ByteBufCodecs.COMPOUND_TAG, CardInventorySync::data,
                CardInventorySync::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Notify client that a card was obtained (for totem animation).
     */
    public record CardObtained(String mobId, String displayName) implements CustomPacketPayload {
        public static final Type<CardObtained> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "card_obtained"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CardObtained> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CardObtained::mobId,
                ByteBufCodecs.STRING_UTF8, CardObtained::displayName,
                CardObtained::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Sync deployment slot state to client.
     */
    public record DeploymentSlotSync(CompoundTag data) implements CustomPacketPayload {
        public static final Type<DeploymentSlotSync> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "slot_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeploymentSlotSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.COMPOUND_TAG, DeploymentSlotSync::data,
                DeploymentSlotSync::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Battle result notification.
     */
    public record BattleResultNotify(String resultType, String winner, CompoundTag stats) implements CustomPacketPayload {
        public static final Type<BattleResultNotify> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "battle_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BattleResultNotify> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BattleResultNotify::resultType,
                ByteBufCodecs.STRING_UTF8, BattleResultNotify::winner,
                ByteBufCodecs.COMPOUND_TAG, BattleResultNotify::stats,
                BattleResultNotify::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Server tells client to open the Card Upgrade GUI.
     * Sent when the player right-clicks the workbench and passes all server-side checks.
     */
    public record OpenUpgradeGui() implements CustomPacketPayload {
        public static final Type<OpenUpgradeGui> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "open_upgrade_gui"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenUpgradeGui> CODEC = StreamCodec.unit(new OpenUpgradeGui());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // === C2S (Client to Server) Packets ===

    /**
     * Client requests to place a card in a deployment slot.
     */
    public record PlaceCardRequest(String cardId, String laneId, int slotIndex) implements CustomPacketPayload {
        public static final Type<PlaceCardRequest> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "place_card"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceCardRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlaceCardRequest::cardId,
                ByteBufCodecs.STRING_UTF8, PlaceCardRequest::laneId,
                ByteBufCodecs.INT, PlaceCardRequest::slotIndex,
                PlaceCardRequest::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Client requests to remove a card from a deployment slot.
     */
    public record RemoveCardRequest(String laneId, int slotIndex) implements CustomPacketPayload {
        public static final Type<RemoveCardRequest> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "remove_card"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RemoveCardRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RemoveCardRequest::laneId,
                ByteBufCodecs.INT, RemoveCardRequest::slotIndex,
                RemoveCardRequest::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Client rings the bell (ready / retreat).
     */
    public record RingBell() implements CustomPacketPayload {
        public static final Type<RingBell> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "ring_bell"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RingBell> CODEC = StreamCodec.unit(new RingBell());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Client requests to open card inventory GUI.
     */
    public record OpenCardGui() implements CustomPacketPayload {
        public static final Type<OpenCardGui> ID = new Type<>(Identifier.fromNamespaceAndPath("arenaclash", "open_cards"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenCardGui> CODEC = StreamCodec.unit(new OpenCardGui());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Register all packet types on both sides.
     */
    public static void registerS2CPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(GameStateSync.ID, GameStateSync.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CardInventorySync.ID, CardInventorySync.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CardObtained.ID, CardObtained.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DeploymentSlotSync.ID, DeploymentSlotSync.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BattleResultNotify.ID, BattleResultNotify.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenUpgradeGui.ID, OpenUpgradeGui.CODEC);
    }

    public static void registerC2SPayloads() {
        PayloadTypeRegistry.serverboundPlay().register(PlaceCardRequest.ID, PlaceCardRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RemoveCardRequest.ID, RemoveCardRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RingBell.ID, RingBell.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OpenCardGui.ID, OpenCardGui.CODEC);
    }
}
