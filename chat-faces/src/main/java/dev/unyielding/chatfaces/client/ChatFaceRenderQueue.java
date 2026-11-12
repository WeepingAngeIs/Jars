package dev.unyielding.chatfaces.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.unyielding.chatfaces.ChatFaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.ArrayList;
import java.util.List;

/**
 * Defers model rendering until Minecraft has finished emitting chat glyphs.
 *
 * <p>Figura flushes the shared entity buffer while rendering a portrait.
 * Keeping that work outside the text draw avoids coupling its buffer lifetime
 * to any installed font renderer.</p>
 *
 * <p>All entry points run on Minecraft's render thread. A context belongs to one
 * ChatComponent render call; none of its player or matrix references survive
 * that call.</p>
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
     */
    public static boolean enqueue(GuiGraphics graphics, int x, int y,
                                  ChatMessageLayout.Portrait portrait, float opacity) {
        Frame frame = activeFrame;
        if (frame == null || replayDepth != 0
                || frame.graphics != graphics || portrait == null
                || !Float.isFinite(opacity) || opacity <= 0.0F
                || frame.faces.size() >= MAX_FACES_PER_PASS) {
            return false;
        }

        try {
            frame.faces.add(new Face(
                    new Matrix4f(graphics.pose().last().pose()),
                    new Matrix3f(graphics.pose().last().normal()),
                    ScissorState.capture(),
                    x, y, portrait, opacity));
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
                    if (frame.rendererAvailable && face.portrait.player() != null) {
                        rendered = FiguraPortraitRenderer.draw(replayGraphics, face.x, face.y,
                                face.portrait.player(), face.opacity, false);
                    } else if (frame.rendererAvailable && face.portrait.type() != null) {
                        rendered = FiguraPortraitRenderer.drawCreature(replayGraphics, face.x, face.y,
                                face.portrait.type(), face.portrait.id(), face.opacity);
                    }
                } catch (RuntimeException | LinkageError failure) {
                    reportFailure(failure);
                }

                if (!rendered && face.portrait.player() != null) {
                    GuiGraphics fallbackGraphics = new GuiGraphics(
                            minecraft, minecraft.renderBuffers().bufferSource());
                    fallbackGraphics.pose().last().pose().set(face.pose);
                    fallbackGraphics.pose().last().normal().set(face.normal);
                    face.scissor.apply();
                    RenderSystem.enableBlend();
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, face.opacity);
                    PlayerFaceRenderer.draw(fallbackGraphics, face.portrait.player().getSkin(),
                            face.x, face.y, 8);
                    fallbackGraphics.flush();
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                } else if (!rendered && face.portrait.environment() != null) {
                    drawEnvironment(replayGraphics, face.x, face.y, face.portrait, face.opacity);
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

    private static void drawEnvironment(GuiGraphics graphics, int x, int y,
                                        ChatMessageLayout.Portrait portrait, float opacity) {
        Minecraft mc = Minecraft.getInstance();
        ChatMessageLayout.Environment kind = portrait.environment();
        if (kind == ChatMessageLayout.Environment.VOID) {
            int a = Math.clamp((int)(opacity * 255), 0, 255) << 24;
            graphics.fill(x, y, x + 8, y + 8, a | 0x160b26);
            graphics.fill(x + 2, y + 1, x + 3, y + 2, a | 0x9b68cc);
            graphics.fill(x + 5, y + 5, x + 6, y + 6, a | 0x7050a0);
            graphics.flush();
            return;
        }
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
        if (kind == ChatMessageLayout.Environment.GROUND) {
            BlockState state = portrait.ground() == null ? Blocks.DIRT.defaultBlockState()
                    : portrait.ground().sample(portrait.id());
            sprite = mc.getBlockRenderer().getBlockModel(state).getParticleIcon();
        } else {
            String name = switch (kind) {
                case WATER -> "block/water_still";
                case LAVA -> "block/lava_still";
                default -> "block/fire_0";
            };
            sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(ResourceLocation.fromNamespaceAndPath("minecraft", name));
        }
        RenderSystem.enableBlend();
        if (kind == ChatMessageLayout.Environment.WATER) RenderSystem.setShaderColor(0.3F, 0.5F, 1.0F, opacity);
        else RenderSystem.setShaderColor(1, 1, 1, opacity);
        graphics.blit(x, y, 0, 8, 8, sprite);
        graphics.flush();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Release session-specific renderer state when the client leaves a world. */
    public static void clear() {
        ChatMessageLayout.clear();
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
                    "Chat Faces could not render a portrait; using an available fallback",
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
                        int x, int y, ChatMessageLayout.Portrait portrait, float opacity) {
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
