package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), require = 0)
    private void up$dropStart(ItemStack stack, boolean randomly, boolean includeThrower, CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof ServerPlayer sp && Hooks.instance() != null) Hooks.instance().drop(sp, stack);
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("RETURN"), require = 0)
    private void up$dropEnd(ItemStack stack, boolean randomly, boolean includeThrower, CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof ServerPlayer sp && Hooks.instance() != null) Hooks.instance().dropped(sp, cir.getReturnValue());
    }
}
