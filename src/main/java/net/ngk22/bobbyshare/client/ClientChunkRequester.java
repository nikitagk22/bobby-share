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
import java.util.Queue;
import java.util.Optional;
import java.util.concurrent.*;

public class ClientChunkRequester {
    private static final Map<ChunkPos, CompletableFuture<Optional<NbtCompound>>> pendingRequests = new ConcurrentHashMap<>();
    private static final Queue<ChunkPos> requestQueue = new ConcurrentLinkedQueue<>();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BobbyShare-TimeoutScheduler");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // Run queue processor every 50ms (1 tick) to throttle network requests and prevent ping spikes
        TIMEOUT_SCHEDULER.scheduleAtFixedRate(ClientChunkRequester::processQueue, 0, 50, TimeUnit.MILLISECONDS);
    }

    public static CompletableFuture<Optional<NbtCompound>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<NbtCompound>> future = new CompletableFuture<>();
        CompletableFuture<Optional<NbtCompound>> existing = pendingRequests.putIfAbsent(pos, future);
        if (existing != null) {
            return existing;
        }

        // Add to queue to be processed at a throttled rate
        requestQueue.add(pos);
        return future;
    }

    private static void processQueue() {
        try {
            if (!ClientPlayNetworking.canSend(ChunkRequestPayload.ID)) {
                if (!requestQueue.isEmpty()) {
                    requestQueue.clear();
                }
                clearPendingRequests();
                return;
            }

            int sentThisTick = 0;
            int maxPerTick = 3; // 3 chunks per 50ms = 60 chunks per second (stays safely under the server 80/sec limit)

            while (sentThisTick < maxPerTick) {
                ChunkPos pos = requestQueue.poll();
                if (pos == null) {
                    break;
                }

                CompletableFuture<Optional<NbtCompound>> future = pendingRequests.get(pos);
                if (future != null && !future.isDone()) {
                    ClientPlayNetworking.send(new ChunkRequestPayload(pos.x, pos.z));
                    sentThisTick++;

                    // Schedule a 5-second timeout check
                    TIMEOUT_SCHEDULER.schedule(() -> {
                        CompletableFuture<Optional<NbtCompound>> pending = pendingRequests.remove(pos);
                        if (pending != null && !pending.isDone()) {
                            BobbyShare.LOGGER.debug("Request for chunk {} timed out", pos);
                            pending.complete(Optional.empty());
                        }
                    }, 5, TimeUnit.SECONDS);
                }
            }
        } catch (Exception e) {
            BobbyShare.LOGGER.error("Error in ClientChunkRequester queue processor", e);
        }
    }

    public static void handleResponse(ChunkResponsePayload payload) {
        ChunkPos pos = new ChunkPos(payload.x(), payload.z());
        
        // Complete the future if it is still registered in the pending queue
        CompletableFuture<Optional<NbtCompound>> future = pendingRequests.remove(pos);
        if (future != null) {
            future.complete(payload.nbt());
        }

        // ALWAYS save the incoming NBT to Bobby's local disk cache if present, 
        // even if the response arrived late (after client-side timeout).
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

    /**
     * Clears all pending requests, empties the queue, and completes futures with empty values.
     * Called on server disconnect to prevent memory leaks and dangling timeouts.
     */
    public static void clearPendingRequests() {
        requestQueue.clear();
        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.complete(Optional.empty());
            }
        });
        pendingRequests.clear();
    }
}
