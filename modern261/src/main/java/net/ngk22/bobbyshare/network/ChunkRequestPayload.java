package net.ngk22.bobbyshare.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ChunkRequestPayload(int x, int z) implements CustomPacketPayload {
    public static final Type<ChunkRequestPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("bobbyshare", "chunk_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkRequestPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ChunkRequestPayload::x,
        ByteBufCodecs.INT, ChunkRequestPayload::z,
        ChunkRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
