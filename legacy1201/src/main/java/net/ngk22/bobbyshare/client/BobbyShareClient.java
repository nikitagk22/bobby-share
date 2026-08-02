package net.ngk22.bobbyshare.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.ngk22.bobbyshare.network.ChunkInvalidationPayload;

public class BobbyShareClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ChunkResponsePayload.ID, (client, handler, buf, responseSender) -> {
            int x = buf.readInt();
            int z = buf.readInt();
            java.util.Optional<net.minecraft.nbt.NbtCompound> nbt = buf.readBoolean() ? java.util.Optional.ofNullable(buf.readNbt()) : java.util.Optional.empty();
            client.execute(() -> ClientChunkRequester.handleResponse(new ChunkResponsePayload(x, z, nbt)));
        });
        ClientPlayNetworking.registerGlobalReceiver(ChunkInvalidationPayload.ID, (client, handler, buf, responseSender) -> {
            int x = buf.readInt(); int z = buf.readInt();
            client.execute(() -> ClientChunkRequester.invalidate(new net.minecraft.util.math.ChunkPos(x, z)));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientChunkRequester.clearPendingRequests());
    }
}
