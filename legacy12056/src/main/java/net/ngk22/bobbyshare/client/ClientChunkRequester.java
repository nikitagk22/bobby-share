package net.ngk22.bobbyshare.client;

import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.ngk22.bobbyshare.BobbyShare;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;

import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Optional;
import java.util.concurrent.*;

public final class ClientChunkRequester {
    private static final Map<ChunkPos, CompletableFuture<Optional<NbtCompound>>> PENDING = new ConcurrentHashMap<>();
    private static final Queue<ChunkPos> REQUEST_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<ChunkPos> INVALIDATED = ConcurrentHashMap.newKeySet();
    private static final Map<ChunkPos, Integer> STALE_DISCARDS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BobbyShare-TimeoutScheduler"); t.setDaemon(true); return t;
    });

    static {
        TIMEOUTS.scheduleAtFixedRate(ClientChunkRequester::processQueue, 0, 50, TimeUnit.MILLISECONDS);
    }

    public static void invalidate(ChunkPos pos) {
        INVALIDATED.add(pos);
        if (PENDING.containsKey(pos)) {
            STALE_DISCARDS.compute(pos, (k, v) -> v == null ? 1 : v + 1);
            CompletableFuture<Optional<NbtCompound>> old = PENDING.remove(pos);
            if (old != null && !old.isDone()) {
                old.complete(Optional.empty());
            }
        }
        REQUEST_QUEUE.remove(pos);

        if (ClientPlayNetworking.canSend(ChunkRequestPayload.ID)) {
            requestChunk(pos);
        }
    }

    public static boolean isInvalidated(ChunkPos pos) { return INVALIDATED.contains(pos); }

    public static CompletableFuture<Optional<NbtCompound>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<NbtCompound>> future = new CompletableFuture<>();
        CompletableFuture<Optional<NbtCompound>> existing = PENDING.putIfAbsent(pos, future);
        if (existing != null) return existing;
        REQUEST_QUEUE.add(pos);
        return future;
    }

    private static void processQueue() {
        try {
            if (!ClientPlayNetworking.canSend(ChunkRequestPayload.ID)) {
                REQUEST_QUEUE.clear();
                clearPendingRequests();
                return;
            }
            for (int sent = 0; sent < 3; sent++) {
                ChunkPos pos = REQUEST_QUEUE.poll();
                if (pos == null) break;
                CompletableFuture<Optional<NbtCompound>> future = PENDING.get(pos);
                if (future == null || future.isDone()) continue;
                ClientPlayNetworking.send(new ChunkRequestPayload(pos.x, pos.z));
                TIMEOUTS.schedule(() -> {
                    CompletableFuture<Optional<NbtCompound>> pending = PENDING.remove(pos);
                    if (pending != null && !pending.isDone()) pending.complete(Optional.empty());
                }, 5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            BobbyShare.LOGGER.error("Error processing Bobby Share request queue", e);
        }
    }

    public static void handleResponse(ChunkResponsePayload payload) {
        ChunkPos pos = new ChunkPos(payload.x(), payload.z());

        Integer discards = STALE_DISCARDS.get(pos);
        if (discards != null && discards > 0) {
            if (discards == 1) {
                STALE_DISCARDS.remove(pos);
            } else {
                STALE_DISCARDS.put(pos, discards - 1);
            }
            BobbyShare.LOGGER.debug("Discarded stale in-flight response for chunk {}", pos);
            return;
        }

        INVALIDATED.remove(pos);
        CompletableFuture<Optional<NbtCompound>> future = PENDING.remove(pos);
        if (future != null) future.complete(payload.nbt());

        if (!FabricLoader.getInstance().isModLoaded("bobby")) return;
        payload.nbt().ifPresentOrElse(nbt -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null && client.world.getChunkManager() instanceof ClientChunkManagerExt ext) {
                    var manager = ext.bobby_getFakeChunkManager();
                    if (manager != null && manager.getStorage() != null) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                manager.getStorage().save(pos, nbt);
                                client.execute(() -> {
                                    manager.unload(pos.x, pos.z, false);
                                    manager.loadMissingChunksFromCache();
                                });
                            } catch (Exception e) {
                                BobbyShare.LOGGER.error("Failed to save chunk " + pos + " to Bobby cache", e);
                            }
                        });
                    }
                }
            } catch (Exception e) { BobbyShare.LOGGER.error("Failed to update Bobby cache for " + pos, e); }
        }, () -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null && client.world.getChunkManager() instanceof ClientChunkManagerExt ext) {
                    var manager = ext.bobby_getFakeChunkManager();
                    if (manager != null) {
                        client.execute(() -> manager.unload(pos.x, pos.z, false));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public static void clearPendingRequests() {
        REQUEST_QUEUE.clear();
        INVALIDATED.clear();
        STALE_DISCARDS.clear();
        PENDING.values().forEach(f -> f.complete(Optional.empty()));
        PENDING.clear();
    }
    private ClientChunkRequester() {}
}
