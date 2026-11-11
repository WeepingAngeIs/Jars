package dev.unyielding.chatfaces.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import dev.unyielding.chatfaces.client.ChatMessageLayout;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;

import java.time.Instant;

/** Retains the authenticated sender while Minecraft adds a received message. */
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
    @WrapMethod(method = "handleSystemMessage")
    private void chatfaces$captureSystem(Component message, boolean overlay, Operation<Void> original) {
        Object previous = ChatMessageLayout.captureDeath(message);
        try {
            original.call(message, overlay);
        } finally {
            ChatMessageLayout.restoreDeath(previous);
        }
    }

    @WrapMethod(method = "showMessageToPlayer")
    private boolean chatfaces$sender(ChatType.Bound type, PlayerChatMessage message,
                                    Component decorated, GameProfile profile, boolean secure,
                                    Instant received, Operation<Boolean> original) {
        GameProfile previous = ChatMessageLayout.setSender(profile);
        try {
            return original.call(type, message, decorated, profile, secure, received);
        } finally {
            ChatMessageLayout.setSender(previous);
        }
    }
}
