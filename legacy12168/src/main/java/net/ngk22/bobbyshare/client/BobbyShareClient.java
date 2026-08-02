package net.ngk22.bobbyshare.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.ngk22.bobbyshare.network.ChunkInvalidationPayload;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;

public class BobbyShareClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(ChunkRequestPayload.ID, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkResponsePayload.ID, ChunkResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkInvalidationPayload.ID, ChunkInvalidationPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ChunkResponsePayload.ID, (payload, context) -> {
            context.client().execute(() -> ClientChunkRequester.handleResponse(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ChunkInvalidationPayload.ID, (payload, context) -> {
            context.client().execute(() -> ClientChunkRequester.invalidate(new net.minecraft.util.math.ChunkPos(payload.x(), payload.z())));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientChunkRequester.clearPendingRequests());
    }
}
