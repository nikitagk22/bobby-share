package net.ngk22.bobbyshare.client;

import net.ngk22.bobbyshare.BobbyShare;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.Optional;
import java.util.concurrent.*;

public class ClientChunkRequester {
    private static final Map<ChunkPos, CompletableFuture<Optional<CompoundTag>>> pendingRequests = new ConcurrentHashMap<>();
    private static final Queue<ChunkPos> requestQueue = new ConcurrentLinkedQueue<>();
    private static final Set<ChunkPos> invalidatedChunks = ConcurrentHashMap.newKeySet();

    public static void invalidate(ChunkPos pos) {
        invalidatedChunks.add(pos);
        // Do not let an already queued request repopulate the cache with stale data.
        requestQueue.remove(pos);
        BobbyShare.LOGGER.debug("Marked Bobby chunk {} as stale", pos);
    }

    public static boolean isInvalidated(ChunkPos pos) {
        return invalidatedChunks.contains(pos);
    }

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BobbyShare-TimeoutScheduler");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // Run queue processor every 50ms (1 tick) to throttle network requests and prevent ping spikes
        TIMEOUT_SCHEDULER.scheduleAtFixedRate(ClientChunkRequester::processQueue, 0, 50, TimeUnit.MILLISECONDS);
    }

    public static CompletableFuture<Optional<CompoundTag>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
        CompletableFuture<Optional<CompoundTag>> existing = pendingRequests.putIfAbsent(pos, future);
        if (existing != null) {
            return existing;
        }
        invalidatedChunks.remove(pos);

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

                CompletableFuture<Optional<CompoundTag>> future = pendingRequests.get(pos);
                if (future != null && !future.isDone()) {
                    ClientPlayNetworking.send(new ChunkRequestPayload(pos.x(), pos.z()));
                    sentThisTick++;

                    // Schedule a 5-second timeout check
                    TIMEOUT_SCHEDULER.schedule(() -> {
                        CompletableFuture<Optional<CompoundTag>> pending = pendingRequests.remove(pos);
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
        boolean stale = invalidatedChunks.contains(pos);
        CompletableFuture<Optional<CompoundTag>> future = pendingRequests.remove(pos);
        if (stale) {
            if (future != null) future.complete(Optional.empty());
            return;
        }
        if (payload.nbt().isPresent()) invalidatedChunks.remove(pos);
        if (future != null) {
            future.complete(payload.nbt());
        }

        // ALWAYS save the incoming NBT to Bobby's local disk cache if present, 
        // even if the response arrived late (after client-side timeout).
        payload.nbt().ifPresent(nbt -> {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client.level != null) {
                    var chunkManager = client.level.getChunkSource();
                    if (chunkManager instanceof ClientChunkCacheExt ext) {
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
        invalidatedChunks.clear();
        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.complete(Optional.empty());
            }
        });
        pendingRequests.clear();
    }
}
