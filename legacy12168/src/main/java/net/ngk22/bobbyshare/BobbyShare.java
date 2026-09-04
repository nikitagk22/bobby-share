package net.ngk22.bobbyshare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.SerializedChunk;
import net.ngk22.bobbyshare.config.BobbyShareConfigManager;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.ngk22.bobbyshare.network.ChunkInvalidationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class BobbyShare implements ModInitializer {
    public static final String MOD_ID = "bobbyshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<UUID, TokenBucket> RATE_LIMITERS = new ConcurrentHashMap<>();
    private static final Map<RegistryKey<World>, Set<ChunkPos>> PENDING_INVALIDATIONS = new ConcurrentHashMap<>();

    public static final AtomicLong STATS_TOTAL_REQUESTS = new AtomicLong();
    public static final AtomicLong STATS_RAM_HITS = new AtomicLong();
    public static final AtomicLong STATS_LIVE_HITS = new AtomicLong();
    public static final AtomicLong STATS_DISK_READS = new AtomicLong();
    public static final AtomicLong STATS_RATE_LIMITED = new AtomicLong();
    public static final AtomicLong STATS_INVALIDATIONS_SENT = new AtomicLong();

    private static final Map<CacheKey, Optional<NbtCompound>> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<CacheKey, Optional<NbtCompound>>(4096, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, Optional<NbtCompound>> e) {
                return size() > BobbyShareConfigManager.getConfig().cacheCapacity;
            }
        });

    @Override public void onInitialize() {
        BobbyShareConfigManager.load();

        ServerTickEvents.END_SERVER_TICK.register(BobbyShare::processPendingInvalidations);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("bobbyshare")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.literal("reload").executes(context -> {
                BobbyShareConfigManager.load();
                synchronized (CACHE) { CACHE.clear(); }
                context.getSource().sendFeedback(() -> Text.literal("[BobbyShare] Configuration reloaded and cache cleared!"), true);
                return 1;
            }))
            .then(CommandManager.literal("clearcache").executes(context -> {
                int size;
                synchronized (CACHE) { size = CACHE.size(); CACHE.clear(); }
                context.getSource().sendFeedback(() -> Text.literal("[BobbyShare] Cleared " + size + " chunks from server RAM cache!"), true);
                return 1;
            }))
            .then(CommandManager.literal("stats").executes(context -> {
                int cacheSize;
                synchronized (CACHE) { cacheSize = CACHE.size(); }
                int capacity = BobbyShareConfigManager.getConfig().cacheCapacity;
                long total = STATS_TOTAL_REQUESTS.get();
                long ram = STATS_RAM_HITS.get();
                long live = STATS_LIVE_HITS.get();
                long disk = STATS_DISK_READS.get();
                long limited = STATS_RATE_LIMITED.get();
                long invalidations = STATS_INVALIDATIONS_SENT.get();
                double hitRate = total > 0 ? (ram * 100.0 / total) : 0.0;

                context.getSource().sendFeedback(() -> Text.literal(
                    String.format(
                        "§6=== [BobbyShare Server Stats] ===\n" +
                        "§eRAM Cache: §a%d / %d chunks\n" +
                        "§eTotal Requests: §f%d\n" +
                        "§e  - RAM Hits: §a%d (%.1f%%)\n" +
                        "§e  - Live Memory: §b%d\n" +
                        "§e  - Disk Reads: §e%d\n" +
                        "§eRate Limited: §c%d\n" +
                        "§eInvalidations Broadcasted: §d%d",
                        cacheSize, capacity, total, ram, hitRate, live, disk, limited, invalidations
                    )
                ), false);
                return 1;
            }))
        ));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RATE_LIMITERS.remove(handler.player.getUuid()));

        PayloadTypeRegistry.playC2S().register(ChunkRequestPayload.ID, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkResponsePayload.ID, ChunkResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkInvalidationPayload.ID, ChunkInvalidationPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.ID, (payload, context) -> {
            STATS_TOTAL_REQUESTS.incrementAndGet();
            context.server().execute(() -> serve(context.server(), context.player(), new ChunkPos(payload.x(), payload.z())));
        });
    }

    public static void queueChunkInvalidation(ServerWorld world, int x, int z) {
        PENDING_INVALIDATIONS
            .computeIfAbsent(world.getRegistryKey(), k -> ConcurrentHashMap.newKeySet())
            .add(new ChunkPos(x, z));
    }

    private static void processPendingInvalidations(MinecraftServer server) {
        if (PENDING_INVALIDATIONS.isEmpty()) return;
        double maxDist = BobbyShareConfigManager.getConfig().maxRequestDistance;
        double maxDistSq = maxDist * maxDist;

        for (ServerWorld world : server.getWorlds()) {
            Set<ChunkPos> dirty = PENDING_INVALIDATIONS.remove(world.getRegistryKey());
            if (dirty == null || dirty.isEmpty()) continue;

            String dimension = world.getRegistryKey().getValue().toString();
            for (ChunkPos pos : dirty) {
                CACHE.remove(new CacheKey(dimension, pos));
                ChunkInvalidationPayload payload = new ChunkInvalidationPayload(pos.x, pos.z);
                for (ServerPlayerEntity player : world.getPlayers()) {
                    if (!player.isDisconnected() && ServerPlayNetworking.canSend(player, ChunkInvalidationPayload.ID)) {
                        double dx = player.getChunkPos().x - pos.x;
                        double dz = player.getChunkPos().z - pos.z;
                        if (dx * dx + dz * dz <= maxDistSq) {
                            ServerPlayNetworking.send(player, payload);
                            STATS_INVALIDATIONS_SENT.incrementAndGet();
                        }
                    }
                }
            }
        }
    }

    private static void serve(MinecraftServer server, ServerPlayerEntity player, ChunkPos pos) {
        ServerWorld world = player.getWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        if (BobbyShareConfigManager.getConfig().blacklistedDimensions.contains(dimension)) return;
        TokenBucket bucket = RATE_LIMITERS.computeIfAbsent(player.getUuid(), id -> new TokenBucket());
        if (!bucket.tryConsume()) {
            STATS_RATE_LIMITED.incrementAndGet();
            return;
        }
        double distance = BobbyShareConfigManager.getConfig().maxRequestDistance;
        double dx = player.getChunkPos().x - pos.x;
        double dz = player.getChunkPos().z - pos.z;
        if (dx * dx + dz * dz > distance * distance) return;

        CacheKey cacheKey = new CacheKey(dimension, pos);
        Optional<NbtCompound> cached = CACHE.get(cacheKey);
        if (cached != null) {
            STATS_RAM_HITS.incrementAndGet();
            send(player, pos, cached);
            return;
        }
        if (!(world.getChunkManager() instanceof net.minecraft.server.world.ServerChunkManager)) return;
        net.minecraft.server.world.ServerChunkManager manager = (net.minecraft.server.world.ServerChunkManager) world.getChunkManager();
        Chunk liveChunk = manager.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (liveChunk != null) {
            STATS_LIVE_HITS.incrementAndGet();
            SerializedChunk serializedChunk = SerializedChunk.fromChunk(world, liveChunk);
            CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.of(optimize(serializedChunk.serialize()));
                } catch (Exception error) {
                    LOGGER.error("Failed to serialize live chunk " + pos, error);
                    return Optional.<NbtCompound>empty();
                }
            }).thenAccept(result -> {
                if (result.isPresent()) CACHE.put(cacheKey, result);
                if (player.getWorld() == world && !player.isDisconnected()) send(player, pos, result);
            });
            return;
        }
        STATS_DISK_READS.incrementAndGet();
        net.minecraft.world.storage.StorageIoWorker storage = (net.minecraft.world.storage.StorageIoWorker) manager.getChunkIoWorker();
        storage.readChunkData(pos).thenAccept(opt -> {
            Optional<NbtCompound> result = opt.map(BobbyShare::optimize);
            CACHE.put(cacheKey, result);
            if (player.getWorld() == world && !player.isDisconnected()) send(player, pos, result);
        }).exceptionally(error -> {
            LOGGER.error("Failed to read chunk " + pos, error);
            if (player.getWorld() == world && !player.isDisconnected()) send(player, pos, Optional.empty());
            return null;
        });
    }

    private static void send(ServerPlayerEntity player, ChunkPos pos, Optional<NbtCompound> nbt) {
        ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, nbt));
    }

    private static NbtCompound optimize(NbtCompound tag) {
        if (tag == null) return null;
        for (String key : new String[]{"structures", "block_ticks", "fluid_ticks", "PostProcessing", "CarvingMasks"}) {
            tag.remove(key);
        }
        tag.getList("block_entities").ifPresent(list -> {
            list.streamCompounds().forEach(be -> {
                be.remove("Items");
                be.remove("Inventory");
                be.remove("LootTable");
                be.remove("LootTableSeed");
            });
        });
        return tag;
    }

    private record CacheKey(String dimension, ChunkPos pos) {}

    private static final class TokenBucket {
        private double tokens = BobbyShareConfigManager.getConfig().rateLimitBurst;
        private long last = System.nanoTime();
        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(BobbyShareConfigManager.getConfig().rateLimitBurst, tokens + (now - last) / 1_000_000_000.0 * BobbyShareConfigManager.getConfig().rateLimitRefill);
            last = now;
            if (tokens < 1) return false;
            tokens--; return true;
        }
    }
}
