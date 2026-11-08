package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ziel je nach Minecraft-Version in einem der beiden Pakete; AbstractVillager implementiert Merchant. */
@Mixin(targets = {
        "net.minecraft.world.entity.npc.villager.AbstractVillager",
        "net.minecraft.world.entity.npc.AbstractVillager"
})
public abstract class TradeMixin {
    @Inject(method = "notifyTrade", at = @At("HEAD"), require = 0)
    private void up$trade(MerchantOffer offer, CallbackInfo ci) {
        Merchant self = (Merchant) (Object) this;
        if (self.getTradingPlayer() instanceof ServerPlayer sp && Hooks.instance() != null) {
            Hooks.instance().trade(sp, offer.getResult(), offer.getCostA(), offer.getCostB());
        }
    }
}
