# Chat Faces

Live Figura faces in Minecraft chat for **Minecraft 1.21.1 / NeoForge / Java 21**.

Chat Faces is a client-side addon for **Chat Heads** and **Figura**. It draws the
player's current Figura portrait in the space Chat Heads already reserves for a
skin head. It follows Figura's Tab-list portrait selection, including a custom
`Portrait` group and Figura's native head fallback.

## Install

Keep these exact dependency versions in the client's `mods` folder, and add
`Chat-Faces-1.0.0.jar` alongside them:

| Mod | Supported version | Reference filename |
| --- | --- | --- |
| Chat Heads | 0.15.7, NeoForge 1.21/1.21.1 build | `chat_heads-0.15.7-neoforge-1.21.jar` |
| Figura | 0.1.6+1.21.1, NeoForge build | `figura-0.1.6+1.21.1-neoforge-mc.jar` |
| Chat Faces | 1.0.0 | `Chat-Faces-1.0.0.jar` |

Restart Minecraft after adding the JAR. Each player who wants to see the faces
installs the addon on their own client. The addon needs no server installation
and no changes to Unyielding.

Use Chat Heads' existing placement settings for **Before Name** or **Before
Line**, and its existing shadow/sender-detection settings. Figura's avatar
portrait setting must be enabled. Figura continues to control avatar loading,
sharing, visibility, and trust.

## Behavior

- Uses the message sender already identified by Chat Heads, preserving rank
  prefixes, message wrapping, scrolling, and name placement.
- Draws the current Figura portrait on each visible chat frame, so supported
  animations and texture changes continue after a message is sent.
- Fits the face to the existing 8 by 8 chat icon, with the chat's scale, opacity,
  fade, and shadow.
- Reuses a player's portrait render within the current frame when several of
  their messages are visible.
- Falls back to the normal Minecraft skin head when Figura has no usable
  portrait, is disabled/panicked, or the native renderer declines the draw.
- Limits a failed portrait to its avatar instance; reloading the avatar permits
  another attempt. Other players remain eligible.

This version changes chat messages. Chat Heads' configuration preview and
command-suggestion icons retain their standard skin heads.

### An animated model can have a static portrait

Figura avatars may author their Tab portrait separately from the full model.
Blinking or moving the world head does not automatically animate that separate
portrait. Chat Faces follows the face Figura actually provides; it does not add
missing animation tracks or modify avatar files. A good first comparison is the
same player's face in Tab.

## Build

Install a Java 21 JDK, then run from this directory:

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The installable file is
`build/libs/Chat-Faces-1.0.0.jar`. The `-sources.jar` file is source code and is
not the file to install.

The project pins NeoForge 21.1.249 and the exact public Modrinth artifact IDs
matched to the inspected reference JARs. It does not bundle dependency mods.
The Gradle wrapper download has a pinned SHA-256 checksum. GitHub Actions builds
the mod and publishes the JAR and hashes as a workflow artifact.

## How it works

The integration hooks the shared Chat Heads head-drawing method and captures its
sender, coordinates, pose, opacity, and shadow choice. The native Figura draw is
deferred until the chat text pass finishes, then rendered into a small
transparent texture. Compositing that result into the chat icon handles scaling
and fade independently of the model's own render layers.

The texture is refreshed from the same live avatar each chat frame. Chat Faces
does not tick an avatar again, take a permanent snapshot, implement a second
avatar backend, or require a leaderboard payload.

See [reference analysis](docs/REFERENCE_ANALYSIS.md) for the exact inspected
entry points and [validation results](docs/TESTING.md) for the boundary between
binary/build checks and live-client testing.

## Credits and license

Chat Faces code is MIT licensed. It integrates with
[Chat Heads by dzwdz and Fourmisain](https://github.com/dzwdz/chat_heads) and
[Figura](https://github.com/FiguraMC/Figura). Their respective licenses continue
to apply to their own mods; no dependency implementation or avatar asset is
redistributed in Chat Faces.
