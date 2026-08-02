package net.ngk22.bobbyshare.mixin;

import net.ngk22.bobbyshare.client.ClientChunkRequester;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(value = FakeChunkManager.class)
public class FakeChunkManagerMixin {
    @Inject(method = "loadTag(Lnet/minecraft/world/level/ChunkPos;I)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), cancellable = true, remap = false)
    private void onLoadTag(ChunkPos pos, int index, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (index != 0) {
            return;
        }

        CompletableFuture<Optional<CompoundTag>> localFuture = cir.getReturnValue();
        CompletableFuture<Optional<CompoundTag>> netFuture = localFuture.thenCompose(opt -> {
            if (opt.isPresent() && !ClientChunkRequester.isInvalidated(pos)) {
                return CompletableFuture.completedFuture(opt);
            } else {
                // Missing or invalidated local cache entry: stream the current chunk from the server.
                return ClientChunkRequester.requestChunk(pos);
            }
        });

        cir.setReturnValue(netFuture);
    }
}
