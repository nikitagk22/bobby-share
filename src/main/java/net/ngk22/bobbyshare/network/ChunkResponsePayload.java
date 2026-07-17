package net.ngk22.bobbyshare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record ChunkResponsePayload(int x, int z, Optional<NbtCompound> nbt) implements CustomPayload {
    public static final Id<ChunkResponsePayload> ID = new Id<>(Identifier.of("bobbyshare", "chunk_response"));
    public static final PacketCodec<RegistryByteBuf, ChunkResponsePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.INTEGER, ChunkResponsePayload::x,
        PacketCodecs.INTEGER, ChunkResponsePayload::z,
        PacketCodecs.optional(PacketCodecs.UNLIMITED_NBT_COMPOUND), ChunkResponsePayload::nbt,
        ChunkResponsePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
