package dev.unyielding.chatfaces.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import dev.unyielding.chatfaces.client.ChatMessageLayout;
import net.minecraft.client.CameraType;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.local.LocalAvatarLoader;
import org.figuramc.figura.config.Configs;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Development-only OpenGL smoke test. This source set must never enter the
 * released mod JAR. It creates only a uniquely named world in run-smoke.
 *
 * <p>The test supplies sender metadata to the independent chat layout, then
 * renders the actual mixed-in ChatComponent. It does
 * not simulate a multiplayer packet or claim to test server-side delivery.</p>
 */
@Mod(value = "chatfaces_smoke", dist = Dist.CLIENT)
public final class ChatFacesSmoke {
    private enum Stage { TITLE, WORLD, SKIN, AVATAR, ANIMATION, FADE, DISABLED, DEATH, DONE }

    private static final long TIMEOUT_MILLIS = 230_000L;
    private static final List<Case> CASES = List.of(
            new Case("before-line-full", false, 1.0),
            new Case("before-name-full", true, 1.0),
            new Case("before-line-half", false, 0.5),
            new Case("before-name-half", true, 0.5));

    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final long started = System.currentTimeMillis();
    private volatile Stage stage = Stage.TITLE;
    private Path output;
    private Path fixture;
    private ChatComponent chat;
    private PlayerInfo owner;
    private int createdAt;
    private int ticks;
    private int stageTick;
    private int lastSampleTick = -1;
    private int caseIndex;
    private boolean sawRed;
    private boolean sawBlue;
    private int beforeLineX = -1;
    private int beforeNameX = -1;
    private int deathCase;
    private List<ChatMessageLayout.PlacedPortrait> deathPlacements;
    private final List<EntityType<?>> deathTypes = List.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.ZOMBIE, EntityType.PLAYER);

    public ChatFacesSmoke() {
        if (!Boolean.getBoolean("chatfaces.smoke")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MILLIS);
                if (!finished.get()) {
                    fail("Timed out in stage " + stage, null);
                    // Only this isolated development launch exists in this
                    // process; do not leave a wedged CI game running forever.
                    System.exit(1);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "chatfaces-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void tick(ClientTickEvent.Post ignored) {
        if (finished.get()) {
            return;
        }
        ticks++;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (output == null) {
                Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                require(game.getFileName().toString().equals("run-smoke"),
                        "Smoke harness refuses to create a world outside run-smoke");
                output = game;
                Files.createDirectories(output);
                writeResult(false, "running", null);
            }
            switch (stage) {
                case TITLE -> {
                    if (mc.screen instanceof AccessibilityOnboardingScreen
                            && mc.getOverlay() == null) {
                        mc.options.onboardingAccessibilityFinished();
                        mc.setScreen(new TitleScreen());
                    }
                    if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                        createWorld(mc);
                    }
                }
                case WORLD -> {
                    if (mc.level != null && mc.player != null && mc.getConnection() != null
                            && mc.getOverlay() == null) {
                        owner = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                        if (owner != null) {
                            mc.setScreen(null);
                            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                            // Native local loading marks this UUID as fetched. No
                            // synthetic remote player or avatar upload is used.
                            AvatarManager.loadLocalAvatar(null);
                            prepareChat(CASES.getFirst());
                            advance(Stage.SKIN);
                        }
                    }
                }
                case AVATAR -> {
                    String loadError = LocalAvatarLoader.getLoadError();
                    require(loadError == null, "Fixture load failed: " + loadError);
                    Avatar avatar = AvatarManager.getLoadedAvatar(owner.getProfile().getId());
                    if (avatar != null && avatar.loaded && avatar.renderer != null) {
                        require(!avatar.scriptError, "Fixture Lua initialization failed");
                        if (ticks - stageTick >= 10) {
                            caseIndex = 0;
                            prepareAnimatedCase();
                        }
                    }
                }
                case ANIMATION -> {
                    require(ticks - stageTick < 120,
                            "Did not observe both animated colors for " + currentCase().name);
                }
                default -> { }
            }
        } catch (Throwable failure) {
            fail(failure.getMessage(), failure);
        }
    }

    private void createWorld(Minecraft mc) throws Exception {
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(2);
        mc.options.simulationDistance().set(2);
        mc.options.framerateLimit().set(60);
        mc.options.chatOpacity().set(1.0);
        mc.options.chatLineSpacing().set(0.0);
        mc.options.textBackgroundOpacity().set(0.0);
        fixture = output.resolve("original-test-avatar");
        Files.createDirectories(fixture);
        for (String name : List.of("avatar.json", "face.bbmodel", "script.lua", "white.png")) {
            try (InputStream in = getClass().getResourceAsStream("/chatfaces-smoke/avatar/" + name)) {
                require(in != null, "Missing original fixture resource " + name);
                Files.copy(in, fixture.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        LevelSettings settings = new LevelSettings("Chat Faces disposable smoke",
                GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                rules, WorldDataConfiguration.DEFAULT);
        advance(Stage.WORLD);
        mc.createWorldOpenFlows().createFreshLevel(
                "chatfaces-smoke-" + started, settings, new WorldOptions(1729L, false, false),
                registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).createWorldDimensions(),
                new TitleScreen());
    }

    private void prepareChat(Case spec) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.chatScale().set(spec.scale);
        chat = new ChatComponent(mc);
        createdAt = mc.gui.getGuiTicks();
        Component message = Component.literal((spec.ranked ? "[Rank] " : "") + owner.getProfile().getName()
                + ": original animated fixture");
        var previous = ChatMessageLayout.setSender(owner.getProfile());
        try { chat.addMessage(message, null, null); }
        finally { ChatMessageLayout.setSender(previous); }
    }

    private void prepareAnimatedCase() {
        prepareChat(currentCase());
        sawRed = false;
        sawBlue = false;
        advance(Stage.ANIMATION);
    }

    private Case currentCase() {
        return CASES.get(caseIndex);
    }

    private void advance(Stage next) {
        stage = next;
        stageTick = ticks;
        lastSampleTick = -1;
    }

    private void render(RenderGuiEvent.Post event) {
        if (finished.get() || chat == null || ticks == lastSampleTick) {
            return;
        }
        if (stage != Stage.SKIN && stage != Stage.ANIMATION
                && stage != Stage.FADE && stage != Stage.DISABLED && stage != Stage.DEATH) {
            return;
        }
        lastSampleTick = ticks;
        try {
            GuiGraphics graphics = event.getGuiGraphics();
            switch (stage) {
                case SKIN -> {
                    try (NativeImage image = drawAndCapture(graphics, createdAt, true)) {
                        require(iconEnergy(image, graphics) > 150,
                                "No ordinary skin pixels in the reserved face square");
                        save(image, "smoke-skin-fallback.png");
                        pass("native-skin-fallback", "Nonempty reserved head square without an avatar");
                    }
                    Configs.AVATAR_PORTRAIT.value = true;
                    AvatarManager.loadLocalAvatar(fixture);
                    advance(Stage.AVATAR);
                }
                case ANIMATION -> sampleAnimation(graphics);
                case FADE -> verifyFade(graphics);
                case DISABLED -> {
                    Configs.AVATAR_PORTRAIT.value = false;
                    try (NativeImage image = drawAndCapture(graphics, createdAt, true)) {
                        require(iconEnergy(image, graphics) > 150,
                                "No skin fallback after disabling Figura portraits");
                        save(image, "smoke-disabled-fallback.png");
                        pass("portraits-disabled-fallback", "Native skin remains visible");
                    } finally {
                        Configs.AVATAR_PORTRAIT.value = true;
                    }
                    deathCase = 0;
                    prepareDeath();
                    advance(Stage.DEATH);
                }
                case DEATH -> verifyDeath(graphics);
                default -> { }
            }
        } catch (Throwable failure) {
            fail(failure.getMessage(), failure);
        }
    }

    private void sampleAnimation(GuiGraphics graphics) throws Exception {
        Avatar avatar = AvatarManager.getLoadedAvatar(owner.getProfile().getId());
        require(avatar != null && !avatar.scriptError, "Local test avatar became unavailable or errored");
        try (NativeImage image = drawAndCapture(graphics, createdAt, true)) {
            Marker marker = scanMarker(image, graphics);
            if (marker.count < 2) {
                return;
            }
            int limit = (int) Math.ceil(8 * currentCase().scale
                    * image.getWidth() / graphics.guiWidth()) + 3;
            require(marker.maxX - marker.minX + 1 <= limit,
                    "Portrait escaped its expected horizontal square in " + currentCase().name);
            require(marker.maxY - marker.minY + 1 <= limit,
                    "Portrait escaped its expected vertical square in " + currentCase().name);
            if (marker.red > marker.blue && !sawRed) {
                sawRed = true;
                save(image, "smoke-" + currentCase().name + "-red.png");
                if (caseIndex == 0) beforeLineX = marker.minX;
                if (caseIndex == 1) beforeNameX = marker.minX;
            }
            if (marker.blue > marker.red && !sawBlue) {
                sawBlue = true;
                save(image, "smoke-" + currentCase().name + "-blue.png");
            }
        }
        if (sawRed && sawBlue) {
            pass(currentCase().name, "Saw distinct red and blue live frames within the head bounds");
            caseIndex++;
            if (caseIndex < CASES.size()) {
                prepareAnimatedCase();
            } else {
                require(beforeNameX > beforeLineX + 2,
                        "BEFORE_NAME did not move the head after the rank prefix");
                pass("rank-prefix-placement", "BEFORE_NAME face is to the right of BEFORE_LINE face");
                prepareChat(CASES.getFirst());
                advance(Stage.FADE);
            }
        }
    }

    private void verifyFade(GuiGraphics graphics) throws Exception {
        try (NativeImage full = drawAndCapture(graphics, createdAt, true)) {
            Marker marker = scanMarker(full, graphics);
            require(marker.count >= 2, "No native portrait pixels available for fade comparison");
            try (NativeImage faded = drawAndCapture(graphics, createdAt + 190, false)) {
                double fullEnergy = markerEnergy(full, full);
                double fadedEnergy = markerEnergy(full, faded);
                double ratio = fadedEnergy / fullEnergy;
                require(ratio > 0.12 && ratio < 0.40,
                        "Expected approximately 25% brightness at chat age 190; got " + ratio);
                save(full, "smoke-fade-full.png");
                save(faded, "smoke-fade-quarter.png");
                pass("native-chat-fade", "Measured brightness ratio " + ratio);
            }
            // Age 196 still passes ChatComponent's minimum-alpha threshold,
            // but exercises opacity below the old shader's 0.1 discard cutoff.
            try (NativeImage nearZero = drawAndCapture(graphics, createdAt + 196, false)) {
                double ratio = markerEnergy(full, nearZero) / markerEnergy(full, full);
                save(nearZero, "smoke-fade-near-zero.png");
                require(ratio > 0.01 && ratio < 0.09,
                        "Expected approximately 4% brightness at chat age 196; got " + ratio);
                pass("native-chat-near-zero-fade", "Measured brightness ratio " + ratio);
            }
        }
        advance(Stage.DISABLED);
    }

    private NativeImage drawAndCapture(GuiGraphics graphics, int time, boolean focused) {
        graphics.flush();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
        graphics.flush();
        int framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        // This invokes the real ChatComponent, the product mixins, Figura's
        // renderer, the creature renderer, and the product compositor.
        chat.render(graphics, time, -1, -1, focused);
        graphics.flush();

        int[] afterViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, afterViewport);
        require(framebuffer == GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
                "Chat rendering leaked its framebuffer binding");
        require(Arrays.equals(viewport, afterViewport), "Chat rendering leaked its viewport");
        require(scissor == GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                "Chat rendering leaked its scissor enable state");

        // A subsequent GUI draw catches an unbalanced clip or shader tint.
        graphics.fill(8, 8, 14, 14, 0xFF00FF00);
        graphics.flush();
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        int sx = (int) (11.0 * image.getWidth() / graphics.guiWidth());
        int sy = (int) (11.0 * image.getHeight() / graphics.guiHeight());
        int pixel = image.getPixelRGBA(sx, sy);
        if (green(pixel) < 180 || red(pixel) > 30 || blue(pixel) > 30) {
            image.close();
            throw new IllegalStateException("GUI sentinel was clipped or tinted after chat rendering");
        }
        return image;
    }

    private static Marker scanMarker(NativeImage image, GuiGraphics graphics) {
        int redCount = 0, blueCount = 0;
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        int y0 = Math.max(0, (int) ((graphics.guiHeight() - 65.0)
                * image.getHeight() / graphics.guiHeight()));
        int y1 = Math.min(image.getHeight(), (int) ((graphics.guiHeight() - 30.0)
                * image.getHeight() / graphics.guiHeight()));
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < image.getWidth() / 2; x++) {
                int p = image.getPixelRGBA(x, y);
                if (!isMarker(p)) continue;
                if (red(p) > blue(p)) redCount++; else blueCount++;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        return new Marker(redCount + blueCount, redCount, blueCount, minX, minY, maxX, maxY);
    }

    private static long iconEnergy(NativeImage image, GuiGraphics graphics) {
        double scale = Minecraft.getInstance().options.chatScale().get();
        double px = (double) image.getWidth() / graphics.guiWidth();
        double py = (double) image.getHeight() / graphics.guiHeight();
        int x0 = (int) Math.floor(4 * scale * px);
        int x1 = (int) Math.ceil(12 * scale * px);
        int y0 = (int) Math.floor((graphics.guiHeight() - 40 - 9 * scale) * py);
        int y1 = (int) Math.ceil((graphics.guiHeight() - 40 - scale) * py);
        long sum = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = image.getPixelRGBA(x, y);
                sum += red(p) + green(p) + blue(p);
            }
        }
        return sum;
    }

    private static double markerEnergy(NativeImage mask, NativeImage image) {
        long sum = 0;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (isMarker(mask.getPixelRGBA(x, y))) {
                    int p = image.getPixelRGBA(x, y);
                    sum += Math.max(red(p), blue(p));
                }
            }
        }
        return sum;
    }

    private static boolean isMarker(int pixel) {
        return green(pixel) < 60 && ((red(pixel) > 120 && blue(pixel) < 60)
                || (blue(pixel) > 120 && red(pixel) < 60));
    }

    private static int red(int pixel) { return pixel & 255; }
    private static int green(int pixel) { return pixel >>> 8 & 255; }
    private static int blue(int pixel) { return pixel >>> 16 & 255; }

    private void save(NativeImage image, String name) throws Exception {
        image.writeToFile(output.resolve(name));
    }

    private void pass(String name, String detail) {
        checks.add(Map.of("name", name, "passed", true, "detail", detail));
        System.out.println("CHAT_FACES_SMOKE PASS " + name + ": " + detail);
    }

    private synchronized void succeed() throws Exception {
        if (finished.get()) return;
        stage = Stage.DONE;
        writeResult(true, "complete", null);
        finished.set(true);
        System.out.println("CHAT_FACES_SMOKE SUCCESS");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    private synchronized void fail(String message, Throwable failure) {
        if (!finished.compareAndSet(false, true)) return;
        System.err.println("CHAT_FACES_SMOKE FAILURE: " + message);
        if (failure != null) failure.printStackTrace();
        try {
            writeResult(false, "failed", message);
        } catch (Exception writeFailure) {
            writeFailure.printStackTrace();
        }
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    private synchronized void writeResult(boolean success, String status, String error) throws Exception {
        if (output == null) return;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("status", status);
        result.put("time", Instant.now().toString());
        result.put("elapsedMillis", System.currentTimeMillis() - started);
        result.put("stage", stage.name());
        result.put("checks", new ArrayList<>(checks));
        result.put("error", error);
        result.put("scope", "Actual ChatComponent and OpenGL rendering in a disposable local world; "
                + "original native-loaded Figura fixture; no multiplayer transport, avatar upload, "
                + "third-party avatar compatibility, or HEAD-fallback fixture is claimed.");
        Files.writeString(output.resolve("smoke-results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void prepareDeath() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.chatScale().set(1.0);
        chat = deathCase == 1 ? mc.gui.getChat() : new ChatComponent(mc);
        if (deathCase == 1) chat.clearMessages(false);
        createdAt = mc.gui.getGuiTicks();
        Entity mob = deathTypes.get(deathCase) == EntityType.PLAYER ? mc.player : deathTypes.get(deathCase).create(mc.level);
        require(mob != null, "Could not create test creature");
        if (deathCase == 3) mob.setCustomName(Component.literal("Smudge"));
        Component victim = mc.player.getDisplayName();
        Component attacker = mob.getDisplayName();
        Component message = Component.translatable("death.attack.mob", victim, attacker);
        if (deathCase == 1) mc.getChatListener().handleSystemMessage(message, false);
        else chat.addMessage(message);
        try {
            var field = ChatComponent.class.getDeclaredField("trimmedMessages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") List<GuiMessage.Line> lines = (List<GuiMessage.Line>) field.get(chat);
            deathPlacements = lines.stream().flatMap(line -> ChatMessageLayout.portraits(line.content()).stream()).toList();
            require(deathPlacements.size() == 2, "Expected both victim and attacker markers in a death message");
            require(deathPlacements.stream().anyMatch(p -> p.portrait().player() != null), "Victim identity missing");
            if (deathTypes.get(deathCase) == EntityType.PLAYER) {
                require(deathPlacements.stream().filter(p -> p.portrait().player() != null).count() == 2, "Player attacker identity missing");
            } else {
                require(deathPlacements.stream().anyMatch(p -> p.portrait().type() == deathTypes.get(deathCase)), "Attacker identity missing");
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void verifyDeath(GuiGraphics graphics) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage image = drawAndCapture(graphics, createdAt, true)) {
            int x = 4 + deathPlacements.getLast().x();
            require(iconEnergyAt(image, graphics, x) > 60,
                    "No creature portrait in death message for " + deathTypes.get(deathCase));
            int victimX = 4 + deathPlacements.stream().filter(p -> p.portrait().player() != null)
                    .findFirst().orElseThrow().x();
            require(iconEnergyAt(image, graphics, victimX) > 60, "No victim portrait in death message");
            save(image, "smoke-death-" + deathCase + ".png");
            pass("death-portrait-" + deathCase, "Victim and creature appear in the vanilla death message");
        }
        deathCase++;
        if (deathCase < deathTypes.size()) prepareDeath(); else succeed();
    }

    private static long iconEnergyAt(NativeImage image, GuiGraphics graphics, int guiX) {
        double px = (double) image.getWidth() / graphics.guiWidth();
        double py = (double) image.getHeight() / graphics.guiHeight();
        int x0 = (int) Math.floor(guiX * px), x1 = (int) Math.ceil((guiX + 8) * px);
        int y0 = (int) Math.floor((graphics.guiHeight() - 49) * py);
        int y1 = (int) Math.ceil((graphics.guiHeight() - 41) * py);
        long sum = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int p = image.getPixelRGBA(x, y);
            sum += red(p) + green(p) + blue(p);
        }
        return sum;
    }

    private record Case(String name, boolean ranked, double scale) { }
    private record Marker(int count, int red, int blue, int minX, int minY, int maxX, int maxY) { }
}
