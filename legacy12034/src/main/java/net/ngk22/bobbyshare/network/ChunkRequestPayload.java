package net.ngk22.bobbyshare.network;

import net.minecraft.util.Identifier;

public record ChunkRequestPayload(int x, int z) {
    public static final Identifier ID = new Identifier("bobbyshare", "chunk_request");
}
