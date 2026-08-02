package net.ngk22.bobbyshare.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Tells a client that its Bobby copy of a chunk must not be used anymore. */
public record ChunkInvalidationPayload(int x, int z) implements CustomPacketPayload {
    public static final Type<ChunkInvalidationPayload> ID =
        new Type<>(Identifier.fromNamespaceAndPath("bobbyshare", "chunk_invalidation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkInvalidationPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.INT, ChunkInvalidationPayload::x,
            ByteBufCodecs.INT, ChunkInvalidationPayload::z,
            ChunkInvalidationPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
