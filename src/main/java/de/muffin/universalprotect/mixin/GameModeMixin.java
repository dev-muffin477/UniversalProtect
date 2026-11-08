package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Einstiegspunkte der Spieleraktionen: Abbauen, Rechtsklick auf Block, Rechtsklick in die Luft. */
@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeMixin {
    @Shadow @Final protected ServerPlayer player;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), require = 0)
    private void up$breakStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Hooks.push(player, Hooks.Kind.BREAK);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("RETURN"), require = 0)
    private void up$breakEnd(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Hooks.pop();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
    private void up$useOnStart(ServerPlayer p, net.minecraft.world.level.Level level, ItemStack stack,
                               net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit,
                               CallbackInfoReturnable<?> cir) {
        Hooks.push(p, stack.getItem() instanceof BlockItem ? Hooks.Kind.PLACE : Hooks.Kind.USE);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void up$useOnEnd(ServerPlayer p, net.minecraft.world.level.Level level, ItemStack stack,
                             net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit,
                             CallbackInfoReturnable<?> cir) {
        Hooks.pop();
    }

    @Inject(method = "useItem", at = @At("HEAD"), require = 0)
    private void up$useStart(ServerPlayer p, net.minecraft.world.level.Level level, ItemStack stack,
                             net.minecraft.world.InteractionHand hand, CallbackInfoReturnable<?> cir) {
        Hooks.push(p, Hooks.Kind.USE);
    }

    @Inject(method = "useItem", at = @At("RETURN"), require = 0)
    private void up$useEnd(ServerPlayer p, net.minecraft.world.level.Level level, ItemStack stack,
                           net.minecraft.world.InteractionHand hand, CallbackInfoReturnable<?> cir) {
        Hooks.pop();
    }
}
