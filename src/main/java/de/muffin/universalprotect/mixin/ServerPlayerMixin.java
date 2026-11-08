package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Container öffnen/schließen: Inhalt vorher/nachher vergleichen. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "initMenu", at = @At("RETURN"), require = 0)
    private void up$open(AbstractContainerMenu menu, CallbackInfo ci) {
        if (Hooks.instance() != null) Hooks.instance().menuOpened((ServerPlayer) (Object) this, menu);
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"), require = 0)
    private void up$close(CallbackInfo ci) {
        if (Hooks.instance() != null) Hooks.instance().menuClosed((ServerPlayer) (Object) this);
    }
}
