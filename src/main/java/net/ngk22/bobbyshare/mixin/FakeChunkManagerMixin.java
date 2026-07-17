package net.ngk22.bobbyshare.mixin;

import net.ngk22.bobbyshare.client.ClientChunkRequester;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(value = FakeChunkManager.class)
public class FakeChunkManagerMixin {
    @Inject(method = "loadTag(Lnet/minecraft/util/math/ChunkPos;I)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), cancellable = true, remap = true)
    private void onLoadTag(ChunkPos pos, int index, CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {
        if (index != 0) {
            return;
        }

        CompletableFuture<Optional<NbtCompound>> localFuture = cir.getReturnValue();
        CompletableFuture<Optional<NbtCompound>> netFuture = localFuture.thenCompose(opt -> {
            if (opt.isPresent()) {
                return CompletableFuture.completedFuture(opt);
            } else {
                // Not found in local cache or fallback worlds. Stream from server!
                return ClientChunkRequester.requestChunk(pos);
            }
        });

        cir.setReturnValue(netFuture);
    }
}
