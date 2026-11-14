package de.muffin.universalprotect.mixin;

import de.muffin.universalprotect.Hooks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

@Mixin(SignBlockEntity.class)
public abstract class SignMixin {
    @Inject(method = "updateSignText", at = @At("RETURN"), require = 0)
    private void up$sign(Player player, boolean front, List<FilteredText> text, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp) || Hooks.instance() == null) return;
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        String[] lines = readLines(self, front);
        if (lines != null) Hooks.instance().sign(sp, self.getLevel(), self.getBlockPos(), lines);
    }

    private static String[] readLines(SignBlockEntity be, boolean front) {
        try {
            Object st = be.getClass().getMethod(front ? "getFrontText" : "getBackText").invoke(be);
            for (Method m : st.getClass().getMethods()) {
                if (m.getName().equals("getMessages") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                    Component[] comps = (Component[]) m.invoke(st, false);
                    String[] out = new String[comps.length];
                    for (int i = 0; i < comps.length; i++) out[i] = comps[i] == null ? "" : comps[i].getString();
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
