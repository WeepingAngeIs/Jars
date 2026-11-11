package dev.unyielding.chatfaces.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.unyielding.chatfaces.ChatFaces;
import dzwdz.chat_heads.ChatHeads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.ArrayList;
import java.util.List;

/**
 * Defers live model rendering until Chat Heads has finished emitting chat glyphs.
 *
 * <p>Chat Heads' default placement calls its icon renderer from inside Font's
 * glyph visitor. Figura flushes the shared entity buffer while rendering a
 * portrait. Keeping that work outside the visitor avoids coupling its buffer
 * lifetime to any installed font renderer.</p>
 *
 * <p>All entry points run on Minecraft's render thread. A context belongs to one
 * ChatComponent render call; none of its player or matrix references survive
 * that call. Direct Chat Heads callers such as its configuration preview and
 * command suggestions deliberately retain their existing skin rendering.</p>
 */
public final class ChatFaceRenderQueue {
    private static final int MAX_FACES_PER_PASS = 512;

    private static Frame activeFrame;
    private static int replayDepth;
    private static boolean reportedFailure;

    private ChatFaceRenderQueue() {
    }

    /** Wrap the whole chat pass so a failed original render cannot leak a queue. */
    public static void renderChat(GuiGraphics graphics, Runnable original) {
        Frame parent = activeFrame;
        Frame frame = new Frame(parent, graphics);
        boolean ownsRendererFrame = parent == null;

        if (ownsRendererFrame) {
            try {
                FiguraPortraitRenderer.beginFrame();
                frame.rendererAvailable = true;
            } catch (RuntimeException | LinkageError failure) {
                reportFailure(failure);
            }
        } else {
            frame.rendererAvailable = parent.rendererAvailable;
        }

        activeFrame = frame;
        boolean completed = false;
        try {
            original.run();
            completed = true;
        } finally {
            // Restore the parent before replay. replayDepth also guards the
            // vanilla fallback if a chat renderer happens to be nested.
            activeFrame = parent;
            try {
                if (completed && !frame.faces.isEmpty()) {
                    replay(frame);
                }
            } finally {
                frame.faces.clear();
                if (ownsRendererFrame) {
                    try {
                        FiguraPortraitRenderer.endFrame();
                    } catch (RuntimeException | LinkageError failure) {
                        reportFailure(failure);
                    }
                }
            }
        }
    }

    /**
     * Return true only when this call has been accepted for deferred rendering.
     * A false result leaves the unmodified Chat Heads renderer in control.
     */
    public static boolean enqueue(GuiGraphics graphics, int x, int y,
                                  PlayerInfo owner, float opacity, boolean drawShadow) {
        Frame frame = activeFrame;
        if (frame == null || replayDepth != 0 || !frame.rendererAvailable
                || frame.graphics != graphics || owner == null
                || !Float.isFinite(opacity) || opacity <= 0.0F
                || frame.faces.size() >= MAX_FACES_PER_PASS) {
            return false;
        }

        try {
            if (!FiguraPortraitRenderer.isEligible(owner)) {
                return false;
            }

            // The font visitor temporarily replaces this pose in BEFORE_NAME
            // mode. Copy it now, before Chat Heads restores the previous pose.
            frame.faces.add(new Face(
                    new Matrix4f(graphics.pose().last().pose()),
                    new Matrix3f(graphics.pose().last().normal()),
                    ScissorState.capture(),
                    x, y, owner, opacity, drawShadow));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            reportFailure(failure);
            return false;
        }
    }

    private static void replay(Frame frame) {
        ScissorState previousScissor = ScissorState.capture();
        boolean previousDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int previousBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int previousBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int previousBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int previousBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        float[] previousColor = RenderSystem.getShaderColor().clone();
        ShaderInstance previousShader = RenderSystem.getShader();
        int previousTexture = RenderSystem.getShaderTexture(0);

        replayDepth++;
        try {
            // At this point the original chat pass has returned, so no glyph
            // visitor is active. Finish its buffers before drawing portraits.
            frame.graphics.flush();

            Minecraft minecraft = Minecraft.getInstance();
            for (Face face : frame.faces) {
                // A separate GUI object also isolates its pose/scissor stacks
                // if a custom avatar renderer fails partway through a draw.
                GuiGraphics replayGraphics = new GuiGraphics(
                        minecraft, minecraft.renderBuffers().bufferSource());
                replayGraphics.pose().last().pose().set(face.pose);
                replayGraphics.pose().last().normal().set(face.normal);
                face.scissor.apply();

                boolean rendered = false;
                try {
                    rendered = FiguraPortraitRenderer.draw(
                            replayGraphics, face.x, face.y, face.owner,
                            face.opacity, face.drawShadow);
                } catch (RuntimeException | LinkageError failure) {
                    reportFailure(failure);
                }

                if (!rendered) {
                    // An avatar can become unavailable after capture, or its
                    // native portrait renderer can decline the draw. Keep the
                    // original skin fallback and the exact saved layout.
                    GuiGraphics fallbackGraphics = new GuiGraphics(
                            minecraft, minecraft.renderBuffers().bufferSource());
                    fallbackGraphics.pose().last().pose().set(face.pose);
                    fallbackGraphics.pose().last().normal().set(face.normal);
                    face.scissor.apply();
                    ChatHeads.renderChatHead(
                            fallbackGraphics, face.x, face.y, face.owner,
                            face.opacity, face.drawShadow);
                }
                replayGraphics.flush();
            }
        } finally {
            replayDepth--;
            previousScissor.apply();
            if (previousDepthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthMask(previousDepthMask);
            RenderSystem.depthFunc(previousDepthFunction);
            RenderSystem.blendFuncSeparate(previousBlendSrcRgb, previousBlendDstRgb,
                    previousBlendSrcAlpha, previousBlendDstAlpha);
            if (previousBlend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (previousCull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            RenderSystem.setShaderColor(previousColor[0], previousColor[1],
                    previousColor[2], previousColor[3]);
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShader(() -> previousShader);
        }
    }

    /** Release session-specific renderer state when the client leaves a world. */
    public static void clear() {
        for (Frame frame = activeFrame; frame != null; frame = frame.parent) {
            frame.faces.clear();
            frame.rendererAvailable = false;
        }
        try {
            FiguraPortraitRenderer.clear();
        } catch (RuntimeException | LinkageError failure) {
            reportFailure(failure);
        } finally {
            reportedFailure = false;
        }
    }

    private static void reportFailure(Throwable failure) {
        if (!reportedFailure) {
            reportedFailure = true;
            ChatFaces.LOGGER.warn(
                    "Chat Faces could not render a live portrait; using the Chat Heads skin fallback",
                    failure);
        }
    }

    private static final class Frame {
        private final Frame parent;
        private final GuiGraphics graphics;
        private final List<Face> faces = new ArrayList<>();
        private boolean rendererAvailable;

        private Frame(Frame parent, GuiGraphics graphics) {
            this.parent = parent;
            this.graphics = graphics;
        }
    }

    private record Face(Matrix4f pose, Matrix3f normal, ScissorState scissor,
                        int x, int y, PlayerInfo owner, float opacity, boolean drawShadow) {
    }

    /** Raw framebuffer coordinates preserve any clip installed by another mod. */
    private record ScissorState(boolean enabled, int x, int y, int width, int height) {
        private static ScissorState capture() {
            if (!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                return new ScissorState(false, 0, 0, 0, 0);
            }
            int[] bounds = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, bounds);
            return new ScissorState(true, bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        private void apply() {
            if (enabled) {
                RenderSystem.enableScissor(x, y, width, height);
            } else {
                RenderSystem.disableScissor();
            }
        }
    }
}
