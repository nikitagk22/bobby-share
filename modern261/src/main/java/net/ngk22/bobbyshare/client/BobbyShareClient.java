package net.ngk22.bobbyshare.client;

import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.ngk22.bobbyshare.network.ChunkInvalidationPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class BobbyShareClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register receiver for S2C chunk responses
        ClientPlayNetworking.registerGlobalReceiver(ChunkResponsePayload.ID, (payload, context) -> {
            // Execute on the client main thread to avoid concurrency issues with Minecraft client
            context.client().execute(() -> {
                ClientChunkRequester.handleResponse(payload);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ChunkInvalidationPayload.ID, (payload, context) -> {
            context.client().execute(() ->
                ClientChunkRequester.invalidate(new net.minecraft.world.level.ChunkPos(payload.x(), payload.z()))
            );
        });

        // Clean up pending requests when disconnecting from a server to prevent memory leaks
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientChunkRequester.clearPendingRequests();
        });
    }
}
