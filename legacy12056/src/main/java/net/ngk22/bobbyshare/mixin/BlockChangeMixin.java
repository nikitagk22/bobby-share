package net.ngk22.bobbyshare.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.ngk22.bobbyshare.BobbyShare;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class BlockChangeMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void bobbyShare$onSetBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (Object) this instanceof ServerWorld world) {
            BobbyShare.invalidateChunk(world, new net.minecraft.util.math.ChunkPos(pos));
        }
    }
}
