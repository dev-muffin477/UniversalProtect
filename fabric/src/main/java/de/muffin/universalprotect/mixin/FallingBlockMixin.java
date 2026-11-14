package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void up$start(CallbackInfo ci) {
        if (((FallingBlockEntity) (Object) this).level().isClientSide()) return;
        Hooks.push(null, Hooks.Actor.of("#falling_block"), Hooks.Kind.ENTITY);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void up$end(CallbackInfo ci) {
        if (((FallingBlockEntity) (Object) this).level().isClientSide()) return;
        Hooks.pop();
    }
}
