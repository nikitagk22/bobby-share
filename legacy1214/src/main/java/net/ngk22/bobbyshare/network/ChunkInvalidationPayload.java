package net.ngk22.bobbyshare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ChunkInvalidationPayload(int x, int z) implements CustomPayload {
    public static final CustomPayload.Id<ChunkInvalidationPayload> ID = new CustomPayload.Id<>(Identifier.of("bobbyshare", "chunk_invalidation"));
    public static final PacketCodec<RegistryByteBuf, ChunkInvalidationPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, ChunkInvalidationPayload::x, PacketCodecs.INTEGER, ChunkInvalidationPayload::z, ChunkInvalidationPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
