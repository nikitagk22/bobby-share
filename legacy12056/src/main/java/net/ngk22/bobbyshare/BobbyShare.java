package net.ngk22.bobbyshare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BobbyShare implements ModInitializer {
    public static final String MOD_ID = "bobbyshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<UUID, TokenBucket> RATE_LIMITERS = new ConcurrentHashMap<>();
    private static final Map<CacheKey, Optional<NbtCompound>> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<CacheKey, Optional<NbtCompound>>(4096, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, Optional<NbtCompound>> e) {
                return size() > BobbyShareConfigManager.getConfig().cacheCapacity;
            }
        });

    @Override public void onInitialize() {
        BobbyShareConfigManager.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("bobbyshare")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.literal("reload").executes(context -> { BobbyShareConfigManager.load(); synchronized (CACHE) { CACHE.clear(); } context.getSource().sendFeedback(() -> Text.literal("[BobbyShare] Configuration reloaded and cache cleared!"), true); return 1; }))
            .then(CommandManager.literal("clearcache").executes(context -> { synchronized (CACHE) { CACHE.clear(); } context.getSource().sendFeedback(() -> Text.literal("[BobbyShare] Server-side chunk cache cleared!"), true); return 1; }))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RATE_LIMITERS.remove(handler.player.getUuid()));
        PayloadTypeRegistry.playC2S().register(ChunkRequestPayload.ID, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkResponsePayload.ID, ChunkResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkInvalidationPayload.ID, ChunkInvalidationPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.ID, (payload, context) -> {
            context.server().execute(() -> serve(context.server(), context.player(), new ChunkPos(payload.x(), payload.z())));
        });
    }

    public static void invalidateChunk(ServerWorld world, ChunkPos pos) {
        CacheKey key = new CacheKey(world.getRegistryKey().getValue().toString(), pos);
        synchronized (CACHE) { CACHE.remove(key); }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isDisconnected() && ServerPlayNetworking.canSend(player, ChunkInvalidationPayload.ID)) {
                ServerPlayNetworking.send(player, new ChunkInvalidationPayload(pos.x, pos.z));
            }
        }
    }

    private static void serve(net.minecraft.server.MinecraftServer server, ServerPlayerEntity player, ChunkPos pos) {
        ServerWorld world = player.getServerWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        if (BobbyShareConfigManager.getConfig().blacklistedDimensions.contains(dimension)) return;
        TokenBucket bucket = RATE_LIMITERS.computeIfAbsent(player.getUuid(), id -> new TokenBucket());
        if (!bucket.tryConsume()) return;
        double distance = BobbyShareConfigManager.getConfig().maxRequestDistance;
        double dx = player.getChunkPos().x - pos.x;
        double dz = player.getChunkPos().z - pos.z;
        if (dx * dx + dz * dz > distance * distance) return;

        CacheKey cacheKey = new CacheKey(dimension, pos);
        Optional<NbtCompound> cached = CACHE.get(cacheKey);
        if (cached != null) { send(player, pos, cached); return; }
        if (!(world.getChunkManager() instanceof net.minecraft.server.world.ServerChunkManager)) return;
        net.minecraft.server.world.ServerChunkManager manager = (net.minecraft.server.world.ServerChunkManager) world.getChunkManager();
        Chunk liveChunk = manager.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (liveChunk != null) {
            try {
                Optional<NbtCompound> result = Optional.of(optimize(ChunkSerializer.serialize(world, liveChunk)));
                CACHE.put(cacheKey, result);
                send(player, pos, result);
            } catch (Exception error) {
                LOGGER.error("Failed to serialize live chunk " + pos, error);
            }
            return;
        }
        net.minecraft.server.world.ThreadedAnvilChunkStorage storage = manager.threadedAnvilChunkStorage;
        storage.getNbt(pos).thenAccept(opt -> {
            Optional<NbtCompound> result = opt.map(BobbyShare::optimize);
            result.ifPresent(nbt -> CACHE.put(cacheKey, result));
            if (player.getServerWorld() == world && !player.isDisconnected()) send(player, pos, result);
        }).exceptionally(error -> { LOGGER.error("Failed to read chunk " + pos, error); if (player.getServerWorld() == world && !player.isDisconnected()) send(player, pos, Optional.empty()); return null; });
    }

    private static void send(ServerPlayerEntity player, ChunkPos pos, Optional<NbtCompound> nbt) {
        ServerPlayNetworking.send(player, new ChunkResponsePayload(pos.x, pos.z, nbt));
    }

    private static NbtCompound optimize(NbtCompound tag) {
        if (tag == null) return null;
        for (String key : new String[]{"structures", "block_ticks", "fluid_ticks", "PostProcessing", "block_entities", "CarvingMasks"}) tag.remove(key);
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
