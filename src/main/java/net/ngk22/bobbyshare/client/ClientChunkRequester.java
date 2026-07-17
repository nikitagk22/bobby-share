package net.ngk22.bobbyshare.client;

import net.ngk22.bobbyshare.BobbyShare;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public class ClientChunkRequester {
    private static final Map<ChunkPos, CompletableFuture<Optional<NbtCompound>>> pendingRequests = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BobbyShare-TimeoutScheduler");
        thread.setDaemon(true);
        return thread;
    });

    public static CompletableFuture<Optional<NbtCompound>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<NbtCompound>> future = new CompletableFuture<>();
        CompletableFuture<Optional<NbtCompound>> existing = pendingRequests.putIfAbsent(pos, future);
        if (existing != null) {
            return existing;
        }

        // Send the request packet to the server
        if (ClientPlayNetworking.canSend(ChunkRequestPayload.ID)) {
            ClientPlayNetworking.send(new ChunkRequestPayload(pos.x, pos.z));
            
            // Timeout check: complete with Optional.empty() if no reply in 5 seconds
            TIMEOUT_SCHEDULER.schedule(() -> {
                CompletableFuture<Optional<NbtCompound>> pending = pendingRequests.remove(pos);
                if (pending != null && !pending.isDone()) {
                    BobbyShare.LOGGER.warn("Request for chunk {} timed out", pos);
                    pending.complete(Optional.empty());
                }
            }, 5, TimeUnit.SECONDS);
        } else {
            // Server doesn't support the mod or we are not connected, return empty immediately
            pendingRequests.remove(pos);
            future.complete(Optional.empty());
        }

        return future;
    }

    public static void handleResponse(ChunkResponsePayload payload) {
        ChunkPos pos = new ChunkPos(payload.x(), payload.z());
        CompletableFuture<Optional<NbtCompound>> future = pendingRequests.remove(pos);
        if (future != null) {
            future.complete(payload.nbt());

            // If the chunk NBT was found, write it asynchronously to Bobby's local cache
            payload.nbt().ifPresent(nbt -> {
                try {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.world != null) {
                        var chunkManager = client.world.getChunkManager();
                        if (chunkManager instanceof ClientChunkManagerExt ext) {
                            var fakeChunkManager = ext.bobby_getFakeChunkManager();
                            if (fakeChunkManager != null) {
                                var storage = fakeChunkManager.getStorage();
                                if (storage != null) {
                                    // Save asynchronously to avoid blocking the main client/render thread
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            storage.save(pos, nbt);
                                            BobbyShare.LOGGER.debug("Saved chunk {} from server to local Bobby cache", pos);
                                        } catch (Exception e) {
                                            BobbyShare.LOGGER.error("Failed to save chunk " + pos + " to Bobby cache asynchronously", e);
                                        }
                                    });
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    BobbyShare.LOGGER.error("Failed to retrieve Bobby storage on main thread", e);
                }
            });
        }
    }

    /**
     * Clears all pending requests and completes their futures with empty values.
     * Called on server disconnect to prevent memory leaks and dangling timeouts.
     */
    public static void clearPendingRequests() {
        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.complete(Optional.empty());
            }
        });
        pendingRequests.clear();
    }
}
