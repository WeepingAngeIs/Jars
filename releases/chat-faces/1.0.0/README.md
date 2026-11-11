# Chat Faces 1.0.0

Live Figura chat portraits for Minecraft **1.21.1 / NeoForge / Java 21**.

## Downloads

| File | Purpose |
| --- | --- |
| [Chat-Faces-1.0.0.jar](Chat-Faces-1.0.0.jar) | Install this mod in the client's `mods` folder. |
| [Chat-Faces-1.0.0-NeoForge-1.21.1.zip](Chat-Faces-1.0.0-NeoForge-1.21.1.zip) | The same installable JAR with installation instructions, README, and license. Extract before installing. |
| [Chat-Faces-1.0.0-sources.jar](Chat-Faces-1.0.0-sources.jar) | Source archive for developers; not an installable mod. |
| [SHA256SUMS.txt](SHA256SUMS.txt) | SHA-256 checksums of the three downloads. |

These are the exact files downloaded from the successful GitHub Actions build,
without rebuilding or repackaging them. The JAR inside the install ZIP is
byte-for-byte identical to the separate runtime JAR. Dependency mods and avatar
assets are not bundled.

## Installation

Close Minecraft and add `Chat-Faces-1.0.0.jar` to the client instance's `mods`
folder. Keep both of these dependencies installed alongside it:

- `chat_heads-0.15.7-neoforge-1.21.jar`
- `figura-0.1.6+1.21.1-neoforge-mc.jar`

Use Java 21, Minecraft 1.21.1, and NeoForge **21.1.22 or newer in the 21.1
series**. The tested NeoForge version is **21.1.249**. Restart Minecraft after
installation. No server installation or Unyielding change is needed.

Enable Figura's avatar portrait setting. Chat Heads' existing Before Name and
Before Line settings control placement. Figura continues to handle avatar
loading, permissions, and sharing. When it has no usable portrait, Chat Faces
uses the ordinary Minecraft skin head.

The rendered face follows the same current avatar and portrait selection as
Figura's Tab list. Authored portrait animations continue while chat is visible.
A separately authored Tab portrait needs its own animation or shared animated
state; blinking on the world model alone does not add blinking to that portrait.

## Build provenance and validation

- **Tested source:** [f8af5bb328fe18c7affc8fcfd452842fd9dd68db](https://github.com/WeepingAngeIs/Jars/commit/f8af5bb328fe18c7affc8fcfd452842fd9dd68db)
- **Successful build and client test:** [Actions run 43084854529](https://github.com/WeepingAngeIs/Jars/actions/runs/43084854529)
- **Machine-readable build information:** [BUILD-INFO.json](BUILD-INFO.json)
- **Runtime results:** [smoke-results.json](validation/smoke-results.json)
- **Detailed coverage and remaining manual cases:** [TESTING.md](../../../chat-faces/docs/TESTING.md)

All **five binary compatibility tests** and **nine real-client rendering
checks** passed. The client checks entered a disposable local world and used
Minecraft's actual chat renderer with Chat Heads, Figura, and Chat Faces.
An original Figura test avatar was loaded through Figura's local loader; its
Lua animation changed the face between red and blue while the same message
remained visible.

The checks covered both placement modes at 100% and 50% chat scale, placement
after a rank prefix, ordinary fade and near-zero opacity, normal skin fallback,
and fallback with Figura portraits disabled. They also checked framebuffer,
viewport, and scissor restoration and drew a green GUI marker after the portrait
to detect clipping or tint leaking into later rendering.

The paired [red frame](validation/smoke-before-name-full-red.png) and
[blue frame](validation/smoke-before-name-full-blue.png) show the original test
fixture. They are diagnostic screenshots, not previews of a user's avatar.

This run did not test multiplayer transport, avatar uploads, the supplied Miku
avatar in-game, a separate native HEAD-fallback fixture, or a full modpack with
additional rendering mods. The manual test matrix keeps those boundaries
explicit.

## Source

The complete Gradle project is in [chat-faces](../../../chat-faces). With a Java
21 JDK installed, run `./gradlew build` from that directory, or
`gradlew.bat build` on Windows. The repository also includes the automated
client test and its original avatar fixture; those development assets are
excluded from the installable JAR.
