package dev.unyielding.chatfaces.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.unyielding.chatfaces.ChatFaces;
import dev.unyielding.chatfaces.mixin.EntityRenderDispatcherAccessor;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.AvatarRenderer;
import org.figuramc.figura.model.rendering.PartFilterScheme;
import org.figuramc.figura.utils.ui.UIHelper;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Draws Figura's existing Tab portrait into a small, transparent, depth-isolated
 * target. The completed image can then follow chat opacity without changing an
 * avatar's chosen render types or applying opacity to each overlapping part.
 *
 * <p>All entry points are render-thread only. Call draw only after the chat text
 * pass has finished and its buffers have been flushed.</p>
 */
public final class FiguraPortraitRenderer {
    private static final int FACE_SIZE = 8;
    private static final int TEXTURE_SIZE = 64;
    private static final int MAX_CACHED_FACES = 64;
    private static final int UNUSED_FRAME_LIMIT = 180;

    private static final Map<UUID, CachedPortrait> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Avatar, Boolean> FAILED_AVATARS = new WeakHashMap<>();
    private static final Map<UUID, CreaturePortrait> CREATURES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<EntityType<?>> FAILED_CREATURES = new HashSet<>();
    private static long frame;

    private FiguraPortraitRenderer() {
    }

    public static void beginFrame() {
        frame++;
    }

    /** This is only an eligibility check; native rendering can still decline. */
    public static boolean isEligible(PlayerInfo owner) {
        return findAvatar(owner) != null;
    }

    private static Avatar findAvatar(PlayerInfo owner) {
        if (owner == null || AvatarManager.panic || !Configs.AVATAR_PORTRAIT.value) {
            return null;
        }
        Avatar avatar = AvatarManager.getAvatarForPlayer(owner.getProfile().getId());
        return avatar != null && avatar.loaded && avatar.renderer != null
                && !FAILED_AVATARS.containsKey(avatar) ? avatar : null;
    }

    /**
     * Returns false when the caller must draw the ordinary skin face.
     * Repeated messages from one player reuse that player's current-frame image.
     */
    public static boolean draw(GuiGraphics gui, int x, int y, PlayerInfo owner,
                               float opacity, boolean drawShadow) {
        Avatar avatar = findAvatar(owner);
        if (avatar == null) {
            return false;
        }
        float alpha = Math.clamp(opacity, 0.0f, 1.0f);
        if (alpha <= 0.0f) {
            return true;
        }

        UUID uuid = owner.getProfile().getId();
        boolean upsideDown = isUpsideDown(uuid);
        CachedPortrait cached = CACHE.get(uuid);
        if (cached == null || cached.avatar != avatar || cached.renderer != avatar.renderer) {
            if (cached != null) {
                cached.close();
            }
            trimCacheForNewEntry();
            cached = new CachedPortrait(avatar);
            CACHE.put(uuid, cached);
        }
        cached.lastSeenFrame = frame;

        try (RenderState saved = new RenderState()) {
            if (cached.renderedFrame != frame || cached.upsideDown != upsideDown) {
                cached.renderedFrame = frame;
                cached.upsideDown = upsideDown;
                cached.hasPortrait = false;
                try {
                    cached.hasPortrait = renderNative(cached, upsideDown, saved);
                } finally {
                    // The composite uses the caller's projection and target.
                    saved.restoreTargetAndMatrices();
                }
            }
            if (!cached.hasPortrait) {
                return false;
            }

            RenderSystem.enableBlend();
            // Native translucent drawing into clear RGBA produces premultiplied
            // RGB. Multiplying both RGB and A below avoids dark fringe pixels.
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, cached.target.getColorTextureId());

            if (drawShadow) {
                blit(gui, x + 1, y, alpha, 0.25f);
            }
            blit(gui, x, y - (drawShadow ? 1 : 0), alpha, 1.0f);
            return true;
        } catch (RuntimeException | LinkageError error) {
            FAILED_AVATARS.put(avatar, Boolean.TRUE);
            CachedPortrait failed = CACHE.remove(uuid);
            if (failed != null) {
                failed.close();
            }
            ChatFaces.LOGGER.warn(
                    "Could not render Figura chat portrait for {}. Using the skin face until this avatar reloads.",
                    uuid, error);
            return false;
        }
    }

    /** Renders the attacker's actual entity when it is nearby, or a local model
     * of the entity type supplied by Minecraft's death-message hover data. */
    public static boolean drawCreature(GuiGraphics gui, int x, int y, EntityType<?> type,
                                       UUID id, float opacity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || id == null || FAILED_CREATURES.contains(type)) return false;
        CreaturePortrait cached = CREATURES.get(id);
        if (cached == null || cached.type != type) {
            if (cached != null) cached.close();
            if (CREATURES.size() >= MAX_CACHED_FACES) {
                Iterator<CreaturePortrait> it = CREATURES.values().iterator();
                CreaturePortrait oldest = it.next();
                it.remove();
                oldest.close();
            }
            Entity entity = null;
            for (Entity candidate : mc.level.entitiesForRendering()) {
                if (id.equals(candidate.getUUID()) && candidate.getType() == type) {
                    entity = candidate;
                    break;
                }
            }
            if (entity == null) entity = type.create(mc.level);
            if (entity == null) return false;
            cached = new CreaturePortrait(type, entity);
            CREATURES.put(id, cached);
        }
        cached.lastSeenFrame = frame;
        try (RenderState saved = new RenderState()) {
            if (cached.renderedFrame != frame) {
                cached.renderedFrame = frame;
                try {
                    renderCreature(cached, saved);
                } finally {
                    saved.restoreTargetAndMatrices();
                }
            }
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, cached.target.getColorTextureId());
            blit(gui, x, y, Math.clamp(opacity, 0.0f, 1.0f), 1.0f);
            return true;
        } catch (RuntimeException | LinkageError error) {
            FAILED_CREATURES.add(type);
            CreaturePortrait failed = CREATURES.remove(id);
            if (failed != null) failed.close();
            ChatFaces.LOGGER.warn("Could not render chat portrait for entity type {}", type, error);
            return false;
        }
    }

    private static void renderCreature(CreaturePortrait cached, RenderState saved) {
        Minecraft mc = Minecraft.getInstance();
        Entity entity = cached.entity;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (cached.target == null) {
            cached.target = new TextureTarget(TEXTURE_SIZE, TEXTURE_SIZE, true, Minecraft.ON_OSX);
            cached.target.setClearColor(0, 0, 0, 0);
            cached.target.setFilterMode(GL11.GL_NEAREST);
        }
        try (GraphicsModeScope mode = new GraphicsModeScope(mc)) {
            RenderSystem.disableScissor();
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            cached.target.clear(Minecraft.ON_OSX);
            cached.target.bindWrite(true);
            saved.usePortraitMatrices();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableBlend();
            GuiGraphics offscreen = new OffscreenGraphics(mc, buffers);
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            Quaternionf oldCamera = dispatcher.cameraOrientation() == null ? null
                    : new Quaternionf(dispatcher.cameraOrientation());
            boolean oldShadow = ((EntityRenderDispatcherAccessor) dispatcher).chatfaces$shouldRenderShadow();
            float oldYaw = entity.getYRot(), oldPitch = entity.getXRot();
            float oldYawO = entity.yRotO, oldPitchO = entity.xRotO;
            LivingEntity living = entity instanceof LivingEntity l ? l : null;
            float body = living == null ? 0 : living.yBodyRot;
            float bodyO = living == null ? 0 : living.yBodyRotO;
            float head = living == null ? 0 : living.yHeadRot;
            float headO = living == null ? 0 : living.yHeadRotO;
            try {
                entity.setYRot(180);
                entity.yRotO = 180;
                entity.setXRot(0);
                entity.xRotO = 0;
                if (living != null) {
                    living.yBodyRot = living.yBodyRotO = 180;
                    living.yHeadRot = living.yHeadRotO = 180;
                }
                float faceExtent = Math.max(0.6f, Math.min(entity.getBbWidth(), entity.getBbHeight()));
                float scale = Math.min(12.0f, Math.max(3.0f, 8.0f / faceExtent));
                float focus = living == null ? entity.getBbHeight() * 0.65f : living.getEyeHeight();
                offscreen.pose().pushPose();
                offscreen.pose().translate(4.0, 4.0, 50.0);
                offscreen.pose().scale(scale, scale, -scale);
                offscreen.pose().translate(0, focus, 0);
                offscreen.pose().mulPose(new Quaternionf().rotateZ((float) Math.PI));
                Lighting.setupForEntityInInventory();
                dispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
                dispatcher.setRenderShadow(false);
                // Draw the live entity model directly. The world dispatcher also
                // draws flames and other world-only overlays, which can escape a
                // small GUI target with other rendering mods installed.
                var renderer = dispatcher.getRenderer(entity);
                renderer.render(entity, 0, 1.0f, offscreen.pose(), buffers, 15728880);
                offscreen.flush();
                offscreen.pose().popPose();
                if (entity.displayFireAnimation()) {
                    // Composite Minecraft's animated fire directly into the face texture.
                    cached.target.bindWrite(true);
                    RenderSystem.disableDepthTest();
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    var atlas = mc.getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                    var flame = atlas.apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/fire_0"));
                    offscreen.blit(0, 3, 0, FACE_SIZE, 5, flame);
                    offscreen.flush();
                }
            } finally {
                buffers.endBatch();
                dispatcher.setRenderShadow(oldShadow);
                if (oldCamera != null) dispatcher.overrideCameraOrientation(oldCamera);
                entity.setYRot(oldYaw);
                entity.yRotO = oldYawO;
                entity.setXRot(oldPitch);
                entity.xRotO = oldPitchO;
                if (living != null) {
                    living.yBodyRot = body;
                    living.yBodyRotO = bodyO;
                    living.yHeadRot = head;
                    living.yHeadRotO = headO;
                }
            }
        }
    }

    private static boolean isUpsideDown(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(uuid);
        return player != null && LivingEntityRenderer.isEntityUpsideDown(player);
    }

    private static boolean renderNative(CachedPortrait cached, boolean upsideDown, RenderState saved) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        try (FiguraState figuraState = new FiguraState(cached.renderer);
             GraphicsModeScope graphicsMode = new GraphicsModeScope(minecraft)) {
            if (cached.target == null) {
                cached.target = new TextureTarget(TEXTURE_SIZE, TEXTURE_SIZE, true, Minecraft.ON_OSX);
                cached.target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                cached.target.setFilterMode(GL11.GL_NEAREST);
            }
            RenderSystem.disableScissor();
            // An outer GUI may use stencil clipping. This target has its own
            // depth attachment and no stencil mask; clipping returns on blit.
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            cached.target.clear(Minecraft.ON_OSX);
            cached.target.bindWrite(true);
            saved.usePortraitMatrices();

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            OffscreenGraphics portraitGui = new OffscreenGraphics(minecraft, buffers);
            try {
                // Figura selects PORTRAIT, then its ordinary HEAD fallback. Its
                // renderer observes the live avatar; no ticks or scripts are replayed.
                return cached.avatar.renderPortrait(portraitGui, 0, 0, FACE_SIZE, 16.0f, upsideDown);
            } catch (RuntimeException | LinkageError error) {
                // Figura's native method has no finally. Keep an exceptional
                // avatar from leaving queued vertices for another player's face.
                FiguraRecovery.afterFailure(cached.renderer);
                throw error;
            } finally {
                buffers.endBatch();
            }
        }
    }

    /**
     * Vanilla uses a Fancy scope for GUI entity rendering. Fabulous
     * line/item render types otherwise bind world transparency targets. The
     * direct option setter has an empty value-update callback in 1.21.1; only
     * the video-settings UI setter rebuilds the world renderer. Nothing is saved.
     */
    private static final class GraphicsModeScope implements AutoCloseable {
        private final OptionInstance<GraphicsStatus> option;
        private final GraphicsStatus previous;
        private final boolean changed;

        private GraphicsModeScope(Minecraft minecraft) {
            option = minecraft.options.graphicsMode();
            previous = option.get();
            changed = Minecraft.useShaderTransparency();
            if (changed) {
                option.set(GraphicsStatus.FANCY);
            }
        }

        @Override
        public void close() {
            if (changed) {
                option.set(previous);
            }
        }
    }

    private static void blit(GuiGraphics gui, int x, int y, float alpha, float brightness) {
        Matrix4f matrix = gui.pose().last().pose();
        float color = alpha * brightness;
        // This shader applies ColorModulator after testing source transparency.
        // The vertex-color shader discards alpha below 0.1 and would truncate
        // the last part of Minecraft's chat fade.
        RenderSystem.setShaderColor(color, color, color, alpha);
        BufferBuilder vertices = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Framebuffer textures have their origin at the lower left.
        vertices.addVertex(matrix, x, y + FACE_SIZE, 0.0f).setUv(0.0f, 0.0f);
        vertices.addVertex(matrix, x + FACE_SIZE, y + FACE_SIZE, 0.0f).setUv(1.0f, 0.0f);
        vertices.addVertex(matrix, x + FACE_SIZE, y, 0.0f).setUv(1.0f, 1.0f);
        vertices.addVertex(matrix, x, y, 0.0f).setUv(0.0f, 1.0f);
        BufferUploader.drawWithShader(vertices.buildOrThrow());
    }

    private static void trimCacheForNewEntry() {
        if (CACHE.size() < MAX_CACHED_FACES) {
            return;
        }
        Iterator<CachedPortrait> iterator = CACHE.values().iterator();
        CachedPortrait oldest = iterator.next();
        iterator.remove();
        oldest.close();
    }

    public static void endFrame() {
        Iterator<CachedPortrait> iterator = CACHE.values().iterator();
        while (iterator.hasNext()) {
            CachedPortrait cached = iterator.next();
            if (frame - cached.lastSeenFrame > UNUSED_FRAME_LIMIT) {
                iterator.remove();
                cached.close();
            }
        }
        Iterator<CreaturePortrait> creatures = CREATURES.values().iterator();
        while (creatures.hasNext()) {
            CreaturePortrait cached = creatures.next();
            if (frame - cached.lastSeenFrame > UNUSED_FRAME_LIMIT) {
                creatures.remove();
                cached.close();
            }
        }
    }

    public static void clear() {
        for (CachedPortrait cached : CACHE.values()) {
            cached.close();
        }
        CACHE.clear();
        FAILED_AVATARS.clear();
        for (CreaturePortrait cached : CREATURES.values()) cached.close();
        CREATURES.clear();
        FAILED_CREATURES.clear();
    }

    private static final class CreaturePortrait {
        final EntityType<?> type;
        final Entity entity;
        TextureTarget target;
        long renderedFrame = Long.MIN_VALUE;
        long lastSeenFrame;
        CreaturePortrait(EntityType<?> type, Entity entity) { this.type = type; this.entity = entity; }
        void close() {
            if (target != null) {
                int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                int framebuffer = target.frameBufferId;
                int color = target.getColorTextureId();
                int depth = target.getDepthTextureId();
                try { target.destroyBuffers(); }
                finally {
                    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == framebuffer ? 0 : read);
                    GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == framebuffer ? 0 : draw);
                    GlStateManager._bindTexture(texture == color || texture == depth ? 0 : texture);
                    target = null;
                }
            }
        }
    }

    private static final class CachedPortrait implements AutoCloseable {
        private final Avatar avatar;
        private final AvatarRenderer renderer;
        private TextureTarget target;
        private long renderedFrame = Long.MIN_VALUE;
        private long lastSeenFrame;
        private boolean upsideDown;
        private boolean hasPortrait;

        private CachedPortrait(Avatar avatar) {
            this.avatar = avatar;
            this.renderer = avatar.renderer;
        }

        @Override
        public void close() {
            if (target != null) {
                TextureTarget closing = target;
                target = null;
                // RenderTarget.destroyBuffers explicitly unbinds framebuffer
                // and texture state, including when called during cache pruning.
                int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                int deletedFramebuffer = closing.frameBufferId;
                int deletedColor = closing.getColorTextureId();
                int deletedDepth = closing.getDepthTextureId();
                try {
                    closing.destroyBuffers();
                } catch (RuntimeException | LinkageError cleanupError) {
                    ChatFaces.LOGGER.debug("Could not fully release a cached Figura chat portrait", cleanupError);
                } finally {
                    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                            oldRead == deletedFramebuffer ? 0 : oldRead);
                    GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                            oldDraw == deletedFramebuffer ? 0 : oldDraw);
                    GlStateManager._bindTexture(oldTexture == deletedColor || oldTexture == deletedDepth
                            ? 0 : oldTexture);
                }
            }
        }
    }

    /** Replaces only the window-dependent scissor conversion for the tiny target. */
    private static final class OffscreenGraphics extends GuiGraphics {
        private final Deque<int[]> clips = new ArrayDeque<>();

        private OffscreenGraphics(Minecraft minecraft, MultiBufferSource.BufferSource buffers) {
            super(minecraft, buffers);
        }

        @Override
        public void enableScissor(int x1, int y1, int x2, int y2) {
            int[] rectangle = {x1, y1, x2, y2};
            if (!clips.isEmpty()) {
                int[] parent = clips.peek();
                rectangle[0] = Math.max(rectangle[0], parent[0]);
                rectangle[1] = Math.max(rectangle[1], parent[1]);
                rectangle[2] = Math.min(rectangle[2], parent[2]);
                rectangle[3] = Math.min(rectangle[3], parent[3]);
            }
            clips.push(rectangle);
            applyClip(rectangle);
        }

        @Override
        public void disableScissor() {
            if (!clips.isEmpty()) {
                clips.pop();
            }
            if (clips.isEmpty()) {
                RenderSystem.disableScissor();
            } else {
                applyClip(clips.peek());
            }
        }

        private static void applyClip(int[] rectangle) {
            int scale = TEXTURE_SIZE / FACE_SIZE;
            int left = Math.clamp(rectangle[0] * scale, 0, TEXTURE_SIZE);
            int top = Math.clamp(rectangle[1] * scale, 0, TEXTURE_SIZE);
            int right = Math.clamp(rectangle[2] * scale, 0, TEXTURE_SIZE);
            int bottom = Math.clamp(rectangle[3] * scale, 0, TEXTURE_SIZE);
            RenderSystem.enableScissor(left, TEXTURE_SIZE - bottom,
                    Math.max(0, right - left), Math.max(0, bottom - top));
        }
    }

    /** Saves native renderer setup, without changing any avatar model or script. */
    private static final class FiguraState implements AutoCloseable {
        private final AvatarRenderer renderer;
        private final boolean paperdoll = UIHelper.paperdoll;
        private final float dollScale = UIHelper.dollScale;
        private final boolean allowPivotParts;
        private final boolean allowMatrixUpdate;
        private final boolean allowHiddenTransforms;
        private final boolean ignoreVanillaVisibility;
        private final boolean interceptRendersIntoFigura;
        private final boolean updateLight;
        private final boolean doIrisEmissiveFix;
        private final boolean offsetRenderLayers;
        private final boolean translucent;
        private final boolean glowing;
        private final float alpha;
        private final float tickDelta;
        private final int light;
        private final int overlay;
        private final MultiBufferSource bufferSource;
        private final PartFilterScheme filter;
        private final FiguraMat4 position;
        private final FiguraMat3 normal;

        private FiguraState(AvatarRenderer renderer) {
            this.renderer = renderer;
            allowPivotParts = renderer.allowPivotParts;
            allowMatrixUpdate = renderer.allowMatrixUpdate;
            allowHiddenTransforms = renderer.allowHiddenTransforms;
            ignoreVanillaVisibility = renderer.ignoreVanillaVisibility;
            interceptRendersIntoFigura = renderer.interceptRendersIntoFigura;
            updateLight = renderer.updateLight;
            doIrisEmissiveFix = renderer.doIrisEmissiveFix;
            offsetRenderLayers = renderer.offsetRenderLayers;
            translucent = renderer.translucent;
            glowing = renderer.glowing;
            alpha = renderer.alpha;
            tickDelta = renderer.tickDelta;
            light = renderer.light;
            overlay = renderer.overlay;
            bufferSource = renderer.bufferSource;
            filter = renderer.currentFilterScheme;
            position = renderer.posMat.copy();
            normal = renderer.normalMat.copy();
        }

        @Override
        public void close() {
            UIHelper.paperdoll = paperdoll;
            UIHelper.dollScale = dollScale;
            renderer.allowPivotParts = allowPivotParts;
            renderer.allowMatrixUpdate = allowMatrixUpdate;
            renderer.allowHiddenTransforms = allowHiddenTransforms;
            renderer.ignoreVanillaVisibility = ignoreVanillaVisibility;
            renderer.interceptRendersIntoFigura = interceptRendersIntoFigura;
            renderer.updateLight = updateLight;
            renderer.doIrisEmissiveFix = doIrisEmissiveFix;
            renderer.offsetRenderLayers = offsetRenderLayers;
            renderer.translucent = translucent;
            renderer.glowing = glowing;
            renderer.alpha = alpha;
            renderer.tickDelta = tickDelta;
            renderer.light = light;
            renderer.overlay = overlay;
            renderer.bufferSource = bufferSource;
            renderer.currentFilterScheme = filter;
            renderer.posMat.set(position);
            renderer.normalMat.set(normal);
        }
    }

    /** GL state is restored through RenderSystem where it maintains a cache. */
    private static final class RenderState implements AutoCloseable {
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] viewport = new int[4];
        private final int[] scissor = new int[4];
        private final boolean hasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean hasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean hasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean hasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean hasStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int targetRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int targetAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final boolean[] colorMask = new boolean[4];
        private final float[] clearColor = new float[4];
        private final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        private final float[] shaderColor = RenderSystem.getShaderColor().clone();
        private final ShaderInstance shader = RenderSystem.getShader();
        private final int[] shaderTextures = new int[12];
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final Vector3f[] lights = captureLights();
        private boolean pushedModelView;

        private RenderState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                ByteBuffer mask = memory.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < colorMask.length; i++) {
                    colorMask[i] = mask.get(i) != 0;
                }
            }
            for (int i = 0; i < shaderTextures.length; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
        }

        private void usePortraitMatrices() {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(
                    0.0f, FACE_SIZE, FACE_SIZE, 0.0f, -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            pushedModelView = true;
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
        }

        private void restoreTargetAndMatrices() {
            if (pushedModelView) {
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
                pushedModelView = false;
            }
            RenderSystem.setProjectionMatrix(projection, sorting);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (hasStencil) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            restoreScissor();
        }

        private void restoreScissor() {
            if (hasScissor) {
                RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            } else {
                RenderSystem.disableScissor();
            }
        }

        @Override
        public void close() {
            restoreTargetAndMatrices();
            RenderSystem.blendFuncSeparate(sourceRgb, targetRgb, sourceAlpha, targetAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (hasBlend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (hasDepth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (hasCull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.clearDepth(clearDepth);
            RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShader(() -> shader);
            for (int i = 0; i < shaderTextures.length; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
            }
            RenderSystem.activeTexture(activeTexture);
            if (lights != null) {
                RenderSystem.setShaderLights(lights[0], lights[1]);
            } else {
                Lighting.setupFor3DItems();
            }
        }

        private static Vector3f[] captureLights() {
            try {
                Field lightField = RenderSystem.class.getDeclaredField("shaderLightDirections");
                lightField.setAccessible(true);
                Vector3f[] current = (Vector3f[]) lightField.get(null);
                if (current[0] != null && current[1] != null) {
                    return new Vector3f[]{new Vector3f(current[0]), new Vector3f(current[1])};
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // No public light getter is exposed in the supported renderer.
            }
            return null;
        }
    }

    /** Best-effort recovery for the exact inspected Figura renderer, only on error. */
    private static final class FiguraRecovery {
        private static void afterFailure(AvatarRenderer renderer) {
            try {
                Field active = AvatarRenderer.class.getDeclaredField("isRendering");
                active.setAccessible(true);
                active.setBoolean(renderer, false);
                Field customizations = renderer.getClass().getDeclaredField("customizationStack");
                customizations.setAccessible(true);
                PartCustomization.PartCustomizationStack stack =
                        (PartCustomization.PartCustomizationStack) customizations.get(renderer);
                while (!stack.isEmpty()) {
                    stack.pop();
                }
                Field queued = renderer.getClass().getDeclaredField("VERTEX_BUFFER");
                queued.setAccessible(true);
                Object buffers = queued.get(null);
                for (String name : new String[]{"primaryBuffers", "secondaryBuffers"}) {
                    Field pending = buffers.getClass().getDeclaredField(name);
                    pending.setAccessible(true);
                    ((Map<?, ?>) pending.get(buffers)).clear();
                }
            } catch (ReflectiveOperationException | RuntimeException cleanupError) {
                ChatFaces.LOGGER.debug("Could not fully reset Figura's failed portrait renderer", cleanupError);
            }
        }
    }
}
