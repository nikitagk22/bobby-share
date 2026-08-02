package net.ngk22.bobbyshare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ChunkRequestPayload(int x, int z) implements CustomPayload {
    public static final CustomPayload.Id<ChunkRequestPayload> ID = new CustomPayload.Id<>(Identifier.of("bobbyshare", "chunk_request"));
    public static final PacketCodec<RegistryByteBuf, ChunkRequestPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, ChunkRequestPayload::x, PacketCodecs.INTEGER, ChunkRequestPayload::z, ChunkRequestPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
