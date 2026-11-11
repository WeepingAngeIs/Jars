package dev.unyielding.chatfaces.mixin;

import dev.unyielding.chatfaces.client.ChatFaceRenderQueue;
import dzwdz.chat_heads.ChatHeads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One hook covers both Before Name and Before Line without changing chat data. */
@Mixin(value = ChatHeads.class, remap = false)
public abstract class ChatHeadsMixin {
    @Inject(
            method = "renderChatHead(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/multiplayer/PlayerInfo;FZ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private static void chatfaces$queuePortrait(GuiGraphics graphics, int x, int y,
                                               PlayerInfo owner, float opacity,
                                               boolean drawShadow, CallbackInfo callback) {
        if (ChatFaceRenderQueue.enqueue(graphics, x, y, owner, opacity, drawShadow)) {
            callback.cancel();
        }
    }
}
