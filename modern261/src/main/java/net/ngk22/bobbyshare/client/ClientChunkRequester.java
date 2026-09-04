package net.ngk22.bobbyshare.client;

import net.ngk22.bobbyshare.BobbyShare;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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
    private static final Map<ChunkPos, Integer> staleDiscards = new ConcurrentHashMap<>();

    public static void invalidate(ChunkPos pos) {
        invalidatedChunks.add(pos);
        BobbyShare.LOGGER.debug("Marked Bobby chunk {} as stale", pos);

        // If a request for this chunk was already in flight, mark it to discard its stale response
        if (pendingRequests.containsKey(pos)) {
            staleDiscards.compute(pos, (k, v) -> v == null ? 1 : v + 1);
            CompletableFuture<Optional<CompoundTag>> oldFuture = pendingRequests.remove(pos);
            if (oldFuture != null && !oldFuture.isDone()) {
                oldFuture.complete(Optional.empty());
            }
        }
        requestQueue.remove(pos);

        if (ClientPlayNetworking.canSend(ChunkRequestPayload.ID)) {
            requestChunk(pos);
        }
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
        TIMEOUT_SCHEDULER.scheduleAtFixedRate(ClientChunkRequester::processQueue, 0, 50, TimeUnit.MILLISECONDS);
    }

    public static CompletableFuture<Optional<CompoundTag>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
        CompletableFuture<Optional<CompoundTag>> existing = pendingRequests.putIfAbsent(pos, future);
        if (existing != null) {
            return existing;
        }

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
            int maxPerTick = 3;

            while (sentThisTick < maxPerTick) {
                ChunkPos pos = requestQueue.poll();
                if (pos == null) {
                    break;
                }

                CompletableFuture<Optional<CompoundTag>> future = pendingRequests.get(pos);
                if (future != null && !future.isDone()) {
                    ClientPlayNetworking.send(new ChunkRequestPayload(pos.x(), pos.z()));
                    sentThisTick++;

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

        Integer discards = staleDiscards.get(pos);
        if (discards != null && discards > 0) {
            if (discards == 1) {
                staleDiscards.remove(pos);
            } else {
                staleDiscards.put(pos, discards - 1);
            }
            BobbyShare.LOGGER.debug("Discarded stale in-flight response for chunk {}", pos);
            return;
        }

        invalidatedChunks.remove(pos);
        CompletableFuture<Optional<CompoundTag>> future = pendingRequests.remove(pos);
        if (future != null) {
            future.complete(payload.nbt());
        }

        if (!FabricLoader.getInstance().isModLoaded("bobby")) {
            return;
        }
        payload.nbt().ifPresentOrElse(nbt -> {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client.level != null && client.level.getChunkSource() instanceof ClientChunkCacheExt ext) {
                    var fakeManager = ext.bobby_getFakeChunkManager();
                    if (fakeManager != null && fakeManager.getStorage() != null) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                fakeManager.getStorage().save(pos, nbt);
                                client.execute(() -> {
                                    fakeManager.unload(pos.x(), pos.z(), false);
                                    fakeManager.loadMissingChunksFromCache();
                                });
                            } catch (Exception e) {
                                BobbyShare.LOGGER.error("Failed to save chunk " + pos + " to Bobby cache", e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                BobbyShare.LOGGER.error("Failed to update Bobby cache for " + pos, e);
            }
        }, () -> {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client.level != null && client.level.getChunkSource() instanceof ClientChunkCacheExt ext) {
                    var fakeManager = ext.bobby_getFakeChunkManager();
                    if (fakeManager != null) {
                        client.execute(() -> fakeManager.unload(pos.x(), pos.z(), false));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public static void clearPendingRequests() {
        requestQueue.clear();
        invalidatedChunks.clear();
        staleDiscards.clear();
        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.complete(Optional.empty());
            }
        });
        pendingRequests.clear();
    }
}
