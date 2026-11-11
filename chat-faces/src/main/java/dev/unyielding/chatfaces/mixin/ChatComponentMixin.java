package dev.unyielding.chatfaces.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.unyielding.chatfaces.client.ChatFaceRenderQueue;
import dev.unyielding.chatfaces.client.ChatMessageLayout;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** The wrapper also cleans up when another chat renderer throws an exception. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @WrapMethod(method = "addMessage(Lnet/minecraft/network/chat/Component;)V")
    private void chatfaces$captureSystemLine(Component message, Operation<Void> original) {
        Object previous = ChatMessageLayout.captureDeath(message);
        try {
            original.call(message);
        } finally {
            ChatMessageLayout.restoreDeath(previous);
        }
    }

    @WrapMethod(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V")
    private void chatfaces$captureOriginal(Component message, MessageSignature signature,
                                           GuiMessageTag tag, Operation<Void> original) {
        Object previous = ChatMessageLayout.captureDeath(message);
        try {
            original.call(message, signature, tag);
        } finally {
            ChatMessageLayout.restoreDeath(previous);
        }
    }

    @WrapMethod(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V")
    private void chatfaces$renderChatWithFaces(GuiGraphics graphics, int tickCount,
                                             int mouseX, int mouseY, boolean focused,
                                             Operation<Void> original) {
        ChatFaceRenderQueue.renderChat(graphics,
                () -> original.call(graphics, tickCount, mouseX, mouseY, focused));
    }

    @WrapOperation(method = "addMessageToDisplayQueue",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
    private List<FormattedCharSequence> chatfaces$layout(FormattedText text, int width, Font font,
            Operation<List<FormattedCharSequence>> original, @Local(argsOnly = true) GuiMessage message) {
        return ChatMessageLayout.wrap(message, width, font,
                (content, available) -> original.call(content, available, font));
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int chatfaces$drawLine(GuiGraphics graphics, Font font, FormattedCharSequence line,
                                   int x, int y, int color, Operation<Integer> original) {
        int result = original.call(graphics, font, line, x, y, color);
        for (ChatMessageLayout.PlacedPortrait placed : ChatMessageLayout.portraits(line)) {
            ChatFaceRenderQueue.enqueue(graphics, x + placed.x(), y, placed.portrait(), (color >>> 24) / 255.0F);
        }
        return result;
    }
}
