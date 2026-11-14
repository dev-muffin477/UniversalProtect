package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Unique private int up$before;
    @Unique private ItemStack up$stack = ItemStack.EMPTY;

    @Inject(method = "playerTouch", at = @At("HEAD"), require = 0)
    private void up$start(Player player, CallbackInfo ci) {
        ItemStack s = ((ItemEntity) (Object) this).getItem();
        up$before = s.getCount();
        up$stack = s.copy();
    }

    @Inject(method = "playerTouch", at = @At("RETURN"), require = 0)
    private void up$end(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp) || Hooks.instance() == null) return;
        int taken = up$before - ((ItemEntity) (Object) this).getItem().getCount();
        if (taken > 0) Hooks.instance().pickup(sp, up$stack, taken);
    }
}
