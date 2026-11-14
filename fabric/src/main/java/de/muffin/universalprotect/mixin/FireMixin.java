package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void up$start(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Hooks.push(null, Hooks.Actor.of("#fire"), Hooks.Kind.FIRE);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void up$end(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Hooks.pop();
    }
}
