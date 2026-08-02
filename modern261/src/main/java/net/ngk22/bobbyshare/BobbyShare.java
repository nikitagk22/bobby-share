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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.literal;

public class BobbyShare implements ModInitializer {
    public static final String MOD_ID = "bobbyshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, TokenBucket> rateLimiters = new ConcurrentHashMap<>();
    
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

        // Register OP command (/bobbyshare reload & clearcache)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("bobbyshare")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("reload")
                    .executes(context -> {
                        BobbyShareConfigManager.load();
                        synchronized (chunkCache) {
                            chunkCache.clear(); // Clear cache to apply new capacity limits cleanly
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a[BobbyShare] Configuration reloaded and cache cleared!"), true);
                        return 1;
                    })
                )
                .then(literal("clearcache")
                    .executes(context -> {
                        synchronized (chunkCache) {
                            chunkCache.clear();
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a[BobbyShare] Server-side chunk cache cleared!"), true);
                        return 1;
                    })
                )
            );
        });

        // Register receiver for C2S requests
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            ChunkPos pos = new ChunkPos(payload.x(), payload.z());
            
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

                // 4. Server LRU Cache Check
                Optional<CompoundTag> cached = chunkCache.get(cacheKey);
                if (cached != null) {
                    LOGGER.debug("Served chunk {} to player {} from memory cache", pos, player.getName().getString());
                    ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), cached));
                    return;
                }

                // 5. Memory Check: If the chunk is currently active in the server memory, serialize it directly
                ServerChunkCache chunkManager = world.getChunkSource();
                ChunkAccess chunk = chunkManager.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (chunk != null) {
                    try {
                        CompoundTag nbt = SerializableChunkData.copyOf(world, chunk).write();
                        Optional<CompoundTag> optimized = Optional.of(optimizeChunkNbt(nbt));
                        if (optimized.isPresent()) {
                            chunkCache.put(cacheKey, optimized);
                        }
                        LOGGER.debug("Served chunk {} to player {} from live server memory", pos, player.getName().getString());
                        ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x(), pos.z(), optimized));
                        return;
                    } catch (Exception e) {
                        LOGGER.error("Failed to serialize live chunk NBT for " + pos, e);
                    }
                }

                // 6. Cache Miss & Unloaded: Fetch chunk NBT asynchronously from storage
                LOGGER.debug("Requesting chunk {} for player {} from disk asynchronously", pos, player.getName().getString());
                chunkManager.chunkMap.read(pos).thenAccept(opt -> {
                    // Optimize chunk NBT (strip unnecessary tags like structures, ticks, block entities)
                    Optional<CompoundTag> optimized = opt.map(BobbyShare::optimizeChunkNbt);
                    
                    // Put in the cache only if the chunk exists/is generated on disk
                    if (optimized.isPresent()) {
                        chunkCache.put(cacheKey, optimized);
                    }

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
     * Invalidates both the in-memory response cache and clients in this dimension.
     * Called after a successful server-side block change.
     */
    public static void invalidateChunk(ServerLevel world, ChunkPos pos) {
        ChunkKey key = new ChunkKey(world.dimension(), pos);
        synchronized (chunkCache) {
            chunkCache.remove(key);
        }

        ChunkInvalidationPayload payload = new ChunkInvalidationPayload(pos.x(), pos.z());
        for (ServerPlayer player : world.players()) {
            if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, ChunkInvalidationPayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
        LOGGER.debug("Invalidated cached chunk {} in {}", pos, world.dimension().identifier());
    }

    /**
     * Strips structure, tick lists, carving masks, block entities, and post-processing info
     * from the chunk NBT. This saves substantial network bandwidth and server-side cache memory.
     */
    private static CompoundTag optimizeChunkNbt(CompoundTag original) {
        if (original == null) return null;
        optimizedRemove(original, "structures");
        optimizedRemove(original, "block_ticks");
        optimizedRemove(original, "fluid_ticks");
        optimizedRemove(original, "PostProcessing");
        optimizedRemove(original, "block_entities");
        optimizedRemove(original, "CarvingMasks");
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
