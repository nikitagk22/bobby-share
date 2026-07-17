package net.ngk22.bobbyshare;

import net.ngk22.bobbyshare.config.BobbyShareConfigManager;
import net.ngk22.bobbyshare.network.ChunkRequestPayload;
import net.ngk22.bobbyshare.network.ChunkResponsePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.literal;

public class BobbyShare implements ModInitializer {
    public static final String MOD_ID = "bobbyshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, TokenBucket> rateLimiters = new ConcurrentHashMap<>();
    
    // Thread-safe LRU Cache that adjusts its capacity limit dynamically based on config value
    private static final Map<ChunkPos, Optional<NbtCompound>> chunkCache = Collections.synchronizedMap(
        new LinkedHashMap<ChunkPos, Optional<NbtCompound>>(4096, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPos, Optional<NbtCompound>> eldest) {
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
        PayloadTypeRegistry.playC2S().register(ChunkRequestPayload.ID, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkResponsePayload.ID, ChunkResponsePayload.CODEC);

        // Remove rate limiters when players disconnect to prevent memory leaks
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            rateLimiters.remove(handler.player.getUuid());
        });

        // Register OP command (/bobbyshare reload & clearcache)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("bobbyshare")
                .requires(source -> source.hasPermissionLevel(2)) // Requires Operator level 2
                .then(literal("reload")
                    .executes(context -> {
                        BobbyShareConfigManager.load();
                        synchronized (chunkCache) {
                            chunkCache.clear(); // Clear cache to apply new capacity limits cleanly
                        }
                        context.getSource().sendFeedback(() -> Text.literal("§a[BobbyShare] Configuration reloaded and cache cleared!"), true);
                        return 1;
                    })
                )
                .then(literal("clearcache")
                    .executes(context -> {
                        synchronized (chunkCache) {
                            chunkCache.clear();
                        }
                        context.getSource().sendFeedback(() -> Text.literal("§a[BobbyShare] Server-side chunk cache cleared!"), true);
                        return 1;
                    })
                )
            );
        });

        // Register receiver for C2S requests
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ChunkPos pos = new ChunkPos(payload.x(), payload.z());
            ServerWorld world = player.getServerWorld();

            // 1. Blacklisted Dimensions Check
            String dimensionId = world.getRegistryKey().getValue().toString();
            if (BobbyShareConfigManager.getConfig().blacklistedDimensions.contains(dimensionId)) {
                LOGGER.debug("Chunk request ignored: Dimension {} is blacklisted", dimensionId);
                return;
            }

            // 2. Rate Limiting Check
            TokenBucket bucket = rateLimiters.computeIfAbsent(player.getUuid(), uuid -> new TokenBucket());
            if (!bucket.tryConsume()) {
                LOGGER.debug("Player {} rate-limited for chunk request {}", player.getName().getString(), pos);
                return;
            }

            // 3. Security check: Only allow chunks within configured maxRequestDistance
            double maxDistance = BobbyShareConfigManager.getConfig().maxRequestDistance;
            double dx = player.getChunkPos().x - pos.x;
            double dz = player.getChunkPos().z - pos.z;
            if (dx * dx + dz * dz > maxDistance * maxDistance) {
                LOGGER.warn("Player {} requested chunk {} too far away! Rejecting request.", player.getName().getString(), pos);
                return;
            }

            // 4. Server LRU Cache Check
            Optional<NbtCompound> cached = chunkCache.get(pos);
            if (cached != null) {
                LOGGER.debug("Served chunk {} to player {} from memory cache", pos, player.getName().getString());
                ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, cached));
                return;
            }

            // 5. Cache Miss: Fetch chunk NBT asynchronously from storage
            var chunkManager = world.getChunkManager();
            if (chunkManager instanceof ServerChunkManager serverChunkManager) {
                ServerChunkLoadingManager sclm = serverChunkManager.chunkLoadingManager;
                LOGGER.debug("Requesting chunk {} for player {} from disk asynchronously", pos, player.getName().getString());
                sclm.getNbt(pos).thenAccept(opt -> {
                    // Optimize chunk NBT (strip unnecessary tags like structures, ticks, block entities)
                    Optional<NbtCompound> optimized = opt.map(BobbyShare::optimizeChunkNbt);
                    
                    // Put in the cache
                    chunkCache.put(pos, optimized);

                    // Send the chunk NBT back to the player
                    ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, optimized));
                }).exceptionally(ex -> {
                    LOGGER.error("Failed to read chunk NBT for " + pos, ex);
                    ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, Optional.empty()));
                    return null;
                });
            } else {
                LOGGER.error("chunkManager is not an instance of ServerChunkManager");
                ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, Optional.empty()));
            }
        });
    }

    /**
     * Strips structure, tick lists, carving masks, block entities, and post-processing info
     * from the chunk NBT. This saves substantial network bandwidth and server-side cache memory.
     */
    private static NbtCompound optimizeChunkNbt(NbtCompound original) {
        if (original == null) return null;
        optimizedRemove(original, "structures");
        optimizedRemove(original, "block_ticks");
        optimizedRemove(original, "fluid_ticks");
        optimizedRemove(original, "PostProcessing");
        optimizedRemove(original, "block_entities");
        optimizedRemove(original, "CarvingMasks");
        return original;
    }

    private static void optimizedRemove(NbtCompound original, String tag) {
        if (original.contains(tag)) {
            original.remove(tag);
        }
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
