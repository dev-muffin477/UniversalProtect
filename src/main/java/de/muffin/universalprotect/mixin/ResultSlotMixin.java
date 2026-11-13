package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @Inject(method = "onTake", at = @At("HEAD"), require = 0)
    private void up$take(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp && Hooks.instance() != null) Hooks.instance().craft(sp, stack.copy());
    }
}
