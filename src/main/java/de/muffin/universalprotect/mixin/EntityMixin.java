package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zerstörte Items (Lava, Feuer, Kakteen) und abgebrochene Item-Rahmen/Gemälde. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 0)
    private void up$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason == Entity.RemovalReason.KILLED && Hooks.instance() != null) Hooks.instance().entityKilled((Entity) (Object) this);
    }
}
