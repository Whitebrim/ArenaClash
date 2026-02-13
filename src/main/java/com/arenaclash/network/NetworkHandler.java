package com.arenaclash.network;

import com.arenaclash.arena.Lane;
import com.arenaclash.card.MobCard;
import com.arenaclash.game.GamePhase;
import com.arenaclash.game.TeamSide;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
    public record GameStateSync(String phase, int timerTicks, int round) implements CustomPayload {
        public static final Id<GameStateSync> ID = new Id<>(Identifier.of("arenaclash", "game_state"));
        public static final PacketCodec<RegistryByteBuf, GameStateSync> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, GameStateSync::phase,
                PacketCodecs.INTEGER, GameStateSync::timerTicks,
                PacketCodecs.INTEGER, GameStateSync::round,
                GameStateSync::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Sync card inventory to client.
     */
    public record CardInventorySync(NbtCompound data) implements CustomPayload {
        public static final Id<CardInventorySync> ID = new Id<>(Identifier.of("arenaclash", "card_sync"));
        public static final PacketCodec<RegistryByteBuf, CardInventorySync> CODEC = PacketCodec.tuple(
                PacketCodecs.NBT_COMPOUND, CardInventorySync::data,
                CardInventorySync::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Notify client that a card was obtained (for totem animation).
     */
    public record CardObtained(String mobId, String displayName) implements CustomPayload {
        public static final Id<CardObtained> ID = new Id<>(Identifier.of("arenaclash", "card_obtained"));
        public static final PacketCodec<RegistryByteBuf, CardObtained> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, CardObtained::mobId,
                PacketCodecs.STRING, CardObtained::displayName,
                CardObtained::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Sync deployment slot state to client.
     */
    public record DeploymentSlotSync(NbtCompound data) implements CustomPayload {
        public static final Id<DeploymentSlotSync> ID = new Id<>(Identifier.of("arenaclash", "slot_sync"));
        public static final PacketCodec<RegistryByteBuf, DeploymentSlotSync> CODEC = PacketCodec.tuple(
                PacketCodecs.NBT_COMPOUND, DeploymentSlotSync::data,
                DeploymentSlotSync::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Battle result notification.
     */
    public record BattleResultNotify(String resultType, String winner, NbtCompound stats) implements CustomPayload {
        public static final Id<BattleResultNotify> ID = new Id<>(Identifier.of("arenaclash", "battle_result"));
        public static final PacketCodec<RegistryByteBuf, BattleResultNotify> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, BattleResultNotify::resultType,
                PacketCodecs.STRING, BattleResultNotify::winner,
                PacketCodecs.NBT_COMPOUND, BattleResultNotify::stats,
                BattleResultNotify::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    // === C2S (Client to Server) Packets ===

    /**
     * Client requests to place a card in a deployment slot.
     */
    public record PlaceCardRequest(String cardId, String laneId, int slotIndex) implements CustomPayload {
        public static final Id<PlaceCardRequest> ID = new Id<>(Identifier.of("arenaclash", "place_card"));
        public static final PacketCodec<RegistryByteBuf, PlaceCardRequest> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, PlaceCardRequest::cardId,
                PacketCodecs.STRING, PlaceCardRequest::laneId,
                PacketCodecs.INTEGER, PlaceCardRequest::slotIndex,
                PlaceCardRequest::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Client requests to remove a card from a deployment slot.
     */
    public record RemoveCardRequest(String laneId, int slotIndex) implements CustomPayload {
        public static final Id<RemoveCardRequest> ID = new Id<>(Identifier.of("arenaclash", "remove_card"));
        public static final PacketCodec<RegistryByteBuf, RemoveCardRequest> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, RemoveCardRequest::laneId,
                PacketCodecs.INTEGER, RemoveCardRequest::slotIndex,
                RemoveCardRequest::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Client rings the bell (ready / retreat).
     */
    public record RingBell() implements CustomPayload {
        public static final Id<RingBell> ID = new Id<>(Identifier.of("arenaclash", "ring_bell"));
        public static final PacketCodec<RegistryByteBuf, RingBell> CODEC = PacketCodec.unit(new RingBell());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Client requests to open card inventory GUI.
     */
    public record OpenCardGui() implements CustomPayload {
        public static final Id<OpenCardGui> ID = new Id<>(Identifier.of("arenaclash", "open_cards"));
        public static final PacketCodec<RegistryByteBuf, OpenCardGui> CODEC = PacketCodec.unit(new OpenCardGui());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Register all packet types on both sides.
     */
    public static void registerS2CPayloads() {
        PayloadTypeRegistry.playS2C().register(GameStateSync.ID, GameStateSync.CODEC);
        PayloadTypeRegistry.playS2C().register(CardInventorySync.ID, CardInventorySync.CODEC);
        PayloadTypeRegistry.playS2C().register(CardObtained.ID, CardObtained.CODEC);
        PayloadTypeRegistry.playS2C().register(DeploymentSlotSync.ID, DeploymentSlotSync.CODEC);
        PayloadTypeRegistry.playS2C().register(BattleResultNotify.ID, BattleResultNotify.CODEC);
    }

    public static void registerC2SPayloads() {
        PayloadTypeRegistry.playC2S().register(PlaceCardRequest.ID, PlaceCardRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(RemoveCardRequest.ID, RemoveCardRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(RingBell.ID, RingBell.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenCardGui.ID, OpenCardGui.CODEC);
    }
}
