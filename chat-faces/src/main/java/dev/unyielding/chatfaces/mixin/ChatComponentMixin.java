package dev.unyielding.chatfaces.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.unyielding.chatfaces.client.ChatFaceRenderQueue;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

/** The wrapper also cleans up when another chat renderer throws an exception. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @WrapMethod(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V")
    private void chatfaces$renderChatWithFaces(GuiGraphics graphics, int tickCount,
                                             int mouseX, int mouseY, boolean focused,
                                             Operation<Void> original) {
        ChatFaceRenderQueue.renderChat(graphics,
                () -> original.call(graphics, tickCount, mouseX, mouseY, focused));
    }
}
