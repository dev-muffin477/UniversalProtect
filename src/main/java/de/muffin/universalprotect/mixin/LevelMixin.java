package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Jede Blockänderung läuft hier durch; geloggt wird nur, wenn ein Verursacher-Kontext aktiv ist. */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), require = 0)
    private void up$setBlock(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> cir) {
        Hooks h = Hooks.instance();
        if (h != null) h.blockChange((Level) (Object) this, pos, state);
    }
}
