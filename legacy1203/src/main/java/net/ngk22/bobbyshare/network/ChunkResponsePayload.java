package net.ngk22.bobbyshare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record ChunkResponsePayload(int x, int z, Optional<NbtCompound> nbt) {
    public static final Identifier ID = new Identifier("bobbyshare", "chunk_response");
}
