package net.ngk22.bobbyshare;

import net.ngk22.bobbyshare.config.BobbyShareConfigManager;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.ngk22.bobbyshare.network.ChunkInvalidationPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
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

import static net.minecraft.commands.Commands.literal;

public class BobbyShare implements ModInitializer {
    public static final String MOD_ID = "bobbyshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, TokenBucket> rateLimiters = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Set<ChunkPos>> pendingInvalidations = new ConcurrentHashMap<>();

    // Server-side statistics counters
    public static final AtomicLong statsTotalRequests = new AtomicLong();
    public static final AtomicLong statsRamHits = new AtomicLong();
    public static final AtomicLong statsLiveHits = new AtomicLong();
    public static final AtomicLong statsDiskReads = new AtomicLong();
    public static final AtomicLong statsRateLimited = new AtomicLong();
    public static final AtomicLong statsInvalidationsSent = new AtomicLong();

    // Thread-safe LRU Cache that adjusts its capacity limit dynamically based on config value
    private static final Map<ChunkKey, Optional<CompoundTag>> chunkCache = Collections.synchronizedMap(
        new LinkedHashMap<ChunkKey, Optional<CompoundTag>>(4096, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkKey, Optional<CompoundTag>> eldest) {
                return size() > BobbyShareConfigManager.getConfig().cacheCapacity;
            }
        }
    );

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Bobby Share (Loading config)...");
        
        // Load configuration file
        BobbyShareConfigManager.load();

        // Register payloads with Fabric Networking API
        PayloadTypeRegistry.serverboundPlay().register(ChunkRequestPayload.ID, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(ChunkResponsePayload.ID, ChunkResponsePayload.CODEC, 16 * 1024 * 1024);
        PayloadTypeRegistry.clientboundPlay().register(ChunkInvalidationPayload.ID, ChunkInvalidationPayload.CODEC);

        // Remove rate limiters when players disconnect to prevent memory leaks
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            rateLimiters.remove(handler.player.getUUID());
        });

        // Register tick event to batch process chunk invalidations at the end of each tick
        ServerTickEvents.END_SERVER_TICK.register(BobbyShare::processPendingInvalidations);

        // Register OP commands (/bobbyshare reload, clearcache, stats)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("bobbyshare")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("reload")
                    .executes(context -> {
                        BobbyShareConfigManager.load();
                        synchronized (chunkCache) {
                            chunkCache.clear();
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a[BobbyShare] Configuration reloaded and cache cleared!"), true);
                        return 1;
                    })
                )
                .then(literal("clearcache")
                    .executes(context -> {
                        int size;
                        synchronized (chunkCache) {
                            size = chunkCache.size();
                            chunkCache.clear();
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a[BobbyShare] Cleared " + size + " chunks from server RAM cache!"), true);
                        return 1;
                    })
                )
                .then(literal("stats")
                    .executes(context -> {
                        int cacheSize;
                        synchronized (chunkCache) {
                            cacheSize = chunkCache.size();
                        }
                        int capacity = BobbyShareConfigManager.getConfig().cacheCapacity;
                        long total = statsTotalRequests.get();
                        long ram = statsRamHits.get();
                        long live = statsLiveHits.get();
                        long disk = statsDiskReads.get();
                        long limited = statsRateLimited.get();
                        long invalidations = statsInvalidationsSent.get();
                        double hitRate = total > 0 ? (ram * 100.0 / total) : 0.0;

                        context.getSource().sendSuccess(() -> Component.literal(
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
                    })
                )
            );
        });

        // Register receiver for C2S requests
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            ChunkPos pos = new ChunkPos(payload.x(), payload.z());
            statsTotalRequests.incrementAndGet();
            
            // Execute on the server main thread to ensure thread safety
            context.server().execute(() -> {
                ServerLevel world = player.level();
                ChunkKey cacheKey = new ChunkKey(world.dimension(), pos);

                // 1. Blacklisted Dimensions Check
                String dimensionId = world.dimension().identifier().toString();
                if (BobbyShareConfigManager.getConfig().blacklistedDimensions.contains(dimensionId)) {
                    LOGGER.debug("Chunk request ignored: Dimension {} is blacklisted", dimensionId);
                    return;
                }

                // 2. Rate Limiting Check
                TokenBucket bucket = rateLimiters.computeIfAbsent(player.getUUID(), uuid -> new TokenBucket());
                if (!bucket.tryConsume()) {
                    statsRateLimited.incrementAndGet();
                    LOGGER.debug("Player {} rate-limited for chunk request {}", player.getName().getString(), pos);
                    return;
                }

                // 3. Security check: Only allow chunks within configured maxRequestDistance
                double maxDistance = BobbyShareConfigManager.getConfig().maxRequestDistance;
                double dx = player.chunkPosition().x() - pos.x();
                double dz = player.chunkPosition().z() - pos.z();
                if (dx * dx + dz * dz > maxDistance * maxDistance) {
                    LOGGER.warn("Player {} requested chunk {} too far away! Rejecting request.", player.getName().getString(), pos);
                    return;
                }

                // 4. Server LRU Cache Check (including negative cache hits)
                Optional<CompoundTag> cached = chunkCache.get(cacheKey);
                if (cached != null) {
                    statsRamHits.incrementAndGet();
                    LOGGER.debug("Served chunk {} to player {} from memory cache", pos, player.getName().getString());
                    ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), cached));
                    return;
                }

                // 5. Memory Check: If the chunk is currently active in the server memory, serialize asynchronously
                ServerChunkCache chunkManager = world.getChunkSource();
                ChunkAccess chunk = chunkManager.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (chunk != null) {
                    statsLiveHits.incrementAndGet();
                    SerializableChunkData data = SerializableChunkData.copyOf(world, chunk);
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            CompoundTag nbt = data.write();
                            return Optional.of(optimizeChunkNbt(nbt));
                        } catch (Exception e) {
                            LOGGER.error("Failed to serialize live chunk NBT for " + pos, e);
                            return Optional.<CompoundTag>empty();
                        }
                    }).thenAccept(optimized -> {
                        if (optimized.isPresent()) {
                            chunkCache.put(cacheKey, optimized);
                        }
                        if (player.level() == world && !player.hasDisconnected()) {
                            ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), optimized));
                        }
                    });
                    return;
                }

                // 6. Cache Miss & Unloaded: Fetch chunk NBT asynchronously from storage
                statsDiskReads.incrementAndGet();
                LOGGER.debug("Requesting chunk {} for player {} from disk asynchronously", pos, player.getName().getString());
                chunkManager.chunkMap.read(pos).thenAccept(opt -> {
                    Optional<CompoundTag> optimized = opt.map(BobbyShare::optimizeChunkNbt);
                    
                    // Put in the cache even if empty (negative caching) to avoid disk thrashing
                    chunkCache.put(cacheKey, optimized);

                    // Send the chunk NBT back to the player
                    if (player.level() == world && !player.hasDisconnected()) {
                        ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), optimized));
                    }
                }).exceptionally(ex -> {
                    LOGGER.error("Failed to read chunk NBT for " + pos, ex);
                    if (player.level() == world && !player.hasDisconnected()) {
                        ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), Optional.empty()));
                    }
                    return null;
                });
            });
        });
    }

    /**
     * Queues an invalidation for a modified chunk. Processed in batch at the end of the server tick.
     */
    public static void queueChunkInvalidation(ServerLevel world, int chunkX, int chunkZ) {
        pendingInvalidations
            .computeIfAbsent(world.dimension(), k -> ConcurrentHashMap.newKeySet())
            .add(new ChunkPos(chunkX, chunkZ));
    }

    /**
     * Batch processes all pending invalidations accumulated during this tick.
     */
    private static void processPendingInvalidations(MinecraftServer server) {
        if (pendingInvalidations.isEmpty()) return;

        double maxDist = BobbyShareConfigManager.getConfig().maxRequestDistance;
        double maxDistSq = maxDist * maxDist;

        for (ServerLevel world : server.getAllLevels()) {
            Set<ChunkPos> dirty = pendingInvalidations.remove(world.dimension());
            if (dirty == null || dirty.isEmpty()) continue;

            for (ChunkPos pos : dirty) {
                ChunkKey key = new ChunkKey(world.dimension(), pos);
                chunkCache.remove(key);

                ChunkInvalidationPayload payload = new ChunkInvalidationPayload(pos.x(), pos.z());
                for (ServerPlayer player : world.players()) {
                    if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, ChunkInvalidationPayload.ID)) {
                        double dx = player.chunkPosition().x() - pos.x();
                        double dz = player.chunkPosition().z() - pos.z();
                        if (dx * dx + dz * dz <= maxDistSq) {
                            ServerPlayNetworking.send(player, payload);
                            statsInvalidationsSent.incrementAndGet();
                        }
                    }
                }
            }
        }
    }

    /**
     * Strips heavy, non-visual tags from the chunk NBT.
     * Retains block entities (chests, signs, banners, bells, beds) while stripping
     * item inventories and loot tables.
     */
    private static CompoundTag optimizeChunkNbt(CompoundTag original) {
        if (original == null) return null;
        optimizedRemove(original, "structures");
        optimizedRemove(original, "block_ticks");
        optimizedRemove(original, "fluid_ticks");
        optimizedRemove(original, "PostProcessing");
        optimizedRemove(original, "CarvingMasks");

        // Keep block entities for visual rendering, but strip items to protect privacy and save bandwidth
        original.getList("block_entities").ifPresent(list -> {
            list.compoundStream().forEach(be -> {
                be.remove("Items");
                be.remove("Inventory");
                be.remove("LootTable");
                be.remove("LootTableSeed");
            });
        });

        return original;
    }

    private static void optimizedRemove(CompoundTag original, String tag) {
        if (original.contains(tag)) {
            original.remove(tag);
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos pos) {
    }

    /**
     * Simple, high-performance thread-safe token bucket for rate-limiting.
     * Uses dynamic config values.
     */
    private static class TokenBucket {
        private double tokens;
        private long lastRefillTime;

        public TokenBucket() {
            this.tokens = BobbyShareConfigManager.getConfig().rateLimitBurst;
            this.lastRefillTime = System.nanoTime();
        }

        public synchronized boolean tryConsume() {
            double capacity = BobbyShareConfigManager.getConfig().rateLimitBurst;
            double refillRate = BobbyShareConfigManager.getConfig().rateLimitRefill;

            long now = System.nanoTime();
            double deltaSeconds = (now - lastRefillTime) / 1_000_000_000.0;
            lastRefillTime = now;
            
            tokens = Math.min(capacity, tokens + deltaSeconds * refillRate);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
