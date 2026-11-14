package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import de.muffin.universalprotect.Mc;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void up$tickStart(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof net.minecraft.world.entity.player.Player || self.level().isClientSide()) return;
        Hooks.push(null, Hooks.Actor.of("#" + Mc.entityPath(self)), Hooks.Kind.ENTITY);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void up$tickEnd(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof net.minecraft.world.entity.player.Player || self.level().isClientSide()) return;
        Hooks.pop();
    }

    @Inject(method = "onEquippedItemBroken", at = @At("HEAD"), require = 0)
    private void up$broken(Item item, EquipmentSlot slot, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer sp && Hooks.instance() != null) Hooks.instance().toolBreak(sp, item);
    }
}
