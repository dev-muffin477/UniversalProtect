package de.muffin.universalprotect.mixin;

import com.mojang.brigadier.ParseResults;
import de.muffin.universalprotect.Hooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), require = 0)
    private void up$command(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        if (parse.getContext().getSource().getEntity() instanceof ServerPlayer sp && Hooks.instance() != null) {
            Hooks.instance().command(sp, command);
        }
    }
}
