package dev.unyielding.chatfaces;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Checks external binary contracts without initializing Minecraft or an OpenGL context. */
class DependencyContractTest {
    private static final String AVATAR = "org/figuramc/figura/avatar/Avatar";

    @Test
    void figuraArtifactMatchesTheInspectedJar() throws Exception {
        assertEquals("f379147b52953e8013c27ee5b9d1552618b649f80a0cbe974ed0c6e8de89515c", artifactSha256(AVATAR));
    }

    @Test
    void figuraExposesTheNativeTabPortraitAndOwnerLookup() throws IOException {
        MethodNode portrait = method(readClass(AVATAR), "renderPortrait", "(Lnet/minecraft/client/gui/GuiGraphics;IIIFZ)Z");
        assertNotEquals(0, portrait.access & Opcodes.ACC_PUBLIC);
        method(readClass("org/figuramc/figura/avatar/AvatarManager"), "getAvatarForPlayer",
                "(Ljava/util/UUID;)Lorg/figuramc/figura/avatar/Avatar;");
        boolean portraitParts = false;
        boolean headFallback = false;
        for (var instruction : portrait.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                portraitParts |= call.name.equals("renderSpecialParts");
                headFallback |= call.owner.equals(AVATAR) && call.name.equals("headRender");
            }
        }
        assertTrue(portraitParts, "Native portrait selection changed");
        assertTrue(headFallback, "Native HEAD fallback changed");
    }

    @Test
    void chatRenderAndOffscreenGuiContractsMatchMinecraft1211() throws IOException {
        method(readClass("net/minecraft/client/gui/components/ChatComponent"), "render",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V");
        ClassNode graphics = readClass("net/minecraft/client/gui/GuiGraphics");
        method(graphics, "<init>", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V");
        MethodNode scissors = method(graphics, "enableScissor", "(IIII)V");
        assertEquals(0, scissors.access & Opcodes.ACC_FINAL, "Offscreen clipping must be overridable");
    }

    private static ClassNode readClass(String internalName) throws IOException {
        if (internalName.startsWith("net/minecraft/")) {
            String gameJar = System.getProperty("chatfaces.test.minecraftJar");
            assertNotNull(gameJar, "The build must provide its actual mapped Minecraft artifact");
            try (JarFile archive = new JarFile(gameJar)) {
                var entry = archive.getJarEntry(internalName + ".class");
                assertNotNull(entry, "Missing game class: " + internalName);
                try (var stream = archive.getInputStream(entry)) {
                    ClassNode result = new ClassNode();
                    new ClassReader(stream).accept(result, 0);
                    return result;
                }
            }
        }
        try (var stream = DependencyContractTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(stream, "Missing dependency class: " + internalName);
            ClassNode result = new ClassNode();
            new ClassReader(stream).accept(result, 0);
            return result;
        }
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(it -> it.name.equals(name) && it.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + descriptor + " missing"));
    }

    private static String artifactSha256(String internalName) throws IOException, URISyntaxException, NoSuchAlgorithmException {
        var resource = DependencyContractTest.class.getClassLoader().getResource(internalName + ".class");
        assertNotNull(resource);
        assertInstanceOf(JarURLConnection.class, resource.openConnection());
        var archive = (JarURLConnection) resource.openConnection();
        Path path = Path.of(archive.getJarFileURL().toURI());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
