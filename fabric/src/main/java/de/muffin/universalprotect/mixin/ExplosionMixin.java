package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import de.muffin.universalprotect.Mc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Inject(method = "explode", at = @At("HEAD"), require = 0)
    private void up$start(CallbackInfoReturnable<?> cir) {
        Entity src = ((Explosion) (Object) this).getDirectSourceEntity();
        Hooks.push(null, Hooks.Actor.of(src == null ? "#explosion" : "#" + Mc.entityPath(src)), Hooks.Kind.BREAK);
    }

    @Inject(method = "explode", at = @At("RETURN"), require = 0)
    private void up$end(CallbackInfoReturnable<?> cir) {
        Hooks.pop();
    }
}
