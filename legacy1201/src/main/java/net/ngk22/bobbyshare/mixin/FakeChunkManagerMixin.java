package net.ngk22.bobbyshare.mixin;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.ngk22.bobbyshare.client.ClientChunkRequester;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(FakeChunkManager.class)
public class FakeChunkManagerMixin {
    @Inject(method = "loadTag(Lnet/minecraft/util/math/ChunkPos;I)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), cancellable = true)
    private void bobbyShare$load(ChunkPos pos, int index, CallbackInfoReturnable<CompletableFuture<Optional<Pair<NbtCompound, FakeChunkStorage>>>> cir) {
        if (index != 0) return;
        FakeChunkManager manager = (FakeChunkManager) (Object) this;
        cir.setReturnValue(cir.getReturnValue().thenCompose(local -> local.isPresent() && !ClientChunkRequester.isInvalidated(pos)
            ? CompletableFuture.completedFuture(local)
            : ClientChunkRequester.requestChunk(pos).thenApply(nbt -> nbt.map(tag -> Pair.of(tag, manager.getStorage())))));
    }
}
