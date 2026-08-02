package net.ngk22.bobbyshare.client;

import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
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
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BobbyShare-TimeoutScheduler"); t.setDaemon(true); return t;
    });

    static {
        TIMEOUTS.scheduleAtFixedRate(ClientChunkRequester::processQueue, 0, 50, TimeUnit.MILLISECONDS);
    }

    public static void invalidate(ChunkPos pos) { INVALIDATED.add(pos); REQUEST_QUEUE.remove(pos); }
    public static boolean isInvalidated(ChunkPos pos) { return INVALIDATED.contains(pos); }

    public static CompletableFuture<Optional<NbtCompound>> requestChunk(ChunkPos pos) {
        CompletableFuture<Optional<NbtCompound>> future = new CompletableFuture<>();
        CompletableFuture<Optional<NbtCompound>> existing = PENDING.putIfAbsent(pos, future);
        if (existing != null) return existing;
        INVALIDATED.remove(pos);
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
                PacketByteBuf out = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
                out.writeInt(pos.x); out.writeInt(pos.z);
                ClientPlayNetworking.send(ChunkRequestPayload.ID, out);
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
        boolean stale = INVALIDATED.contains(pos);
        CompletableFuture<Optional<NbtCompound>> future = PENDING.remove(pos);
        if (stale) { if (future != null) future.complete(Optional.empty()); return; }
        if (payload.nbt().isPresent()) INVALIDATED.remove(pos);
        if (future != null) future.complete(payload.nbt());
        payload.nbt().ifPresent(nbt -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null && client.world.getChunkManager() instanceof ClientChunkManagerExt ext) {
                    var manager = ext.bobby_getFakeChunkManager();
                    if (manager != null && manager.getStorage() != null) {
                        CompletableFuture.runAsync(() -> manager.getStorage().save(pos, nbt));
                    }
                }
            } catch (Exception e) { BobbyShare.LOGGER.error("Failed to save chunk " + pos, e); }
        });
    }

    public static void clearPendingRequests() {
        REQUEST_QUEUE.clear();
        INVALIDATED.clear();
        PENDING.values().forEach(f -> f.complete(Optional.empty()));
        PENDING.clear();
    }
    private ClientChunkRequester() {}
}
