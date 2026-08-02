package net.ngk22.bobbyshare.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public record ChunkResponsePayload(int x, int z, Optional<CompoundTag> nbt) implements CustomPacketPayload {
    public static final Type<ChunkResponsePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("bobbyshare", "chunk_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkResponsePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ChunkResponsePayload::x,
        ByteBufCodecs.INT, ChunkResponsePayload::z,
        ByteBufCodecs.OPTIONAL_COMPOUND_TAG, ChunkResponsePayload::nbt,
        ChunkResponsePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
