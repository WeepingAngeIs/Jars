package dev.unyielding.chatfaces;

import com.mojang.logging.LogUtils;
import dev.unyielding.chatfaces.client.ChatFaceRenderQueue;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Client-only entry point. Figura remains responsible for avatar synchronization. */
@Mod(value = ChatFaces.MOD_ID, dist = Dist.CLIENT)
public final class ChatFaces {
    public static final String MOD_ID = "chatfaces";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ChatFaces() {
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        LOGGER.info("Chat Faces loaded: live Figura portraits with Chat Heads 0.15.7");
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ChatFaceRenderQueue.clear();
    }
}
