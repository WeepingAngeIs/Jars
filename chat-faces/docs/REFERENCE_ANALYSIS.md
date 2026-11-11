# Reference analysis

This document records the historical 1.0.0 integration research. Version 1.1.0
has its own chat layout and sender detection; Chat Heads is no longer used or
required. See the current README and CI workflow for the standalone version.

## Scope and evidence

Chat Faces targets Minecraft 1.21.1 on NeoForge, with Java 21. It is a client
add-on for Chat Heads and Figura. The implementation was designed after
inspecting the actual dependency JARs and the existing Unyielding leaderboard
integration. Method names below describe those inspected binaries; they are not
a compatibility guarantee for other versions.

| Dependency | Inspected artifact | SHA-256 |
| --- | --- | --- |
| Chat Heads | `chat_heads-0.15.7-neoforge-1.21.jar` | `02e10437ceeed34e14e0efe206204658e4b8aa8ee7df10f2a9e28ac20f1b0391` |
| Figura | `figura-0.1.6+1.21.1-neoforge-mc.jar` | `f379147b52953e8013c27ee5b9d1552618b649f80a0cbe974ed0c6e8de89515c` |

Upstream projects: [Chat Heads](https://github.com/dzwdz/chat_heads) and
[Figura](https://github.com/FiguraMC/Figura). Dependency implementations and
licenses remain with their respective projects. Chat Faces does not redistribute
their classes or avatar assets.

## Chat Heads provides identity and layout

Both supported placement modes, `BEFORE_NAME` and `BEFORE_LINE`, eventually draw
through this method in `dzwdz.chat_heads.ChatHeads`:

```java
public static void renderChatHead(
    GuiGraphics graphics, int x, int y, PlayerInfo owner,
    float opacity, boolean drawShadow
)
```

The five-argument overload delegates to this overload using the configured
shadow setting. The original face occupies an 8 by 8 GUI-unit square. Its Y
position is offset by minus one when a shadow is enabled. The owner UUID is
`owner.getProfile().getId()`.

Chat Heads already associates messages with players, handles its supported
signed, disguised, and system-message cases, reserves head space, and applies
its own configured heuristics. Chat Faces should preserve those decisions.
Decorated names, team prefixes, and rank glyphs must not become a second,
independent identity-matching implementation. If Chat Heads cannot identify a
sender, Chat Faces must not invent one.

Replacing this shared draw point covers both placement modes without rewriting
chat wrapping or message contents. The original method remains the fallback
when an eligible Figura portrait is unavailable.

## Figura provides the current portrait

The inspected Tab-list integration is
`org.figuramc.figura.mixin.gui.PlayerTabOverlayMixin`. It obtains the player's
avatar using:

```java
AvatarManager.getAvatarForPlayer(UUID player)
```

It then calls the existing avatar instance:

```java
boolean Avatar.renderPortrait(
    GuiGraphics graphics, int x, int y, int size,
    float modelScale, boolean upsideDown
)
```

Figura's Tab implementation passes a model scale of `16.0F`. A successful
portrait suppresses the ordinary skin face; a false return preserves it.

In this Figura binary, `renderPortrait`:

1. Returns false when avatar portraits are disabled, the renderer is absent,
   or the avatar has not loaded.
2. Creates a square scissor region and uses full-bright, flat GUI lighting.
3. Renders the avatar's separate `Portrait` parts through
   `PartFilterScheme.PORTRAIT` and `renderSpecialParts()`.
4. Falls back to `headRender(...)` only when the separate portrait produces no
   geometry. A nonempty custom portrait takes precedence over the world head.
5. Flushes the render buffer, balances its pose/scissor operations, and restores
   the normal path's paperdoll/pivot flags.

The method has no opacity parameter and expects the Tab-list coordinate setup.
Chat rendering adds an independently scaled coordinate system and fading text.
Those differences require integration work around the native renderer; simply
calling it at the chat coordinates does not establish correct clipping and
transparency.

## Rendering contract

Chat Faces should render Figura's native portrait into a transparent render
target, then composite that face into the existing Chat Heads square with the
requested chat opacity. Drawing queued composites after the text pass avoids
mixing an offscreen target change into an unfinished text batch. A frame-local
reuse of a player's portrait avoids repeating the same native render for every
visible message from that player.

The reuse is bounded to the current frame and relevant render inputs. It is not
a permanent screenshot cache: an avatar replacement, animation frame, texture
change, or visibility change must be visible on a subsequent frame. Different
chat lines may reuse geometry while retaining their individual opacity and
position.

The integration must restore the active framebuffer, viewport, pose stack,
scissor, shader color, blend/depth state, and any Figura state it temporarily
changes. A failure must leave later chat text, HUD layers, and the Tab list
renderable. Use fallback or bounded failure handling for an unavailable or
broken avatar; one avatar's failure must not permanently disable every player.

Avatar selection, visibility, trust settings, and Figura's panic mode remain
authoritative. Chat Faces must not request elevated avatar permissions, upload
avatars, run extra avatar ticks, or start a separate animation clock. Its task
is to display the current client-side Figura state.

The compositor preserves ordinary translucent geometry and opaque/binary-alpha
cutout faces. Unusual fractional-alpha cutout textures or standalone additive
and glint effects can have different blending semantics after an RGBA capture;
pixel-identical output for every custom shader is not claimed. The integration
leaves the avatar's render types intact. Its final compositor uses the texture
shader whose zero-alpha test occurs before chat opacity is applied, preserving
the last few percent of the normal chat fade.

## What the Unyielding leaderboard demonstrates

The inspected Unyielding 1.1.8 leaderboard component is `culeaderboard` 1.2.0.
It does not implement a GUI portrait renderer and does not synchronize rendered
images.

Its optional client payload, `culeaderboard:figura_podium`, contains two UUIDs:
the synthetic podium entity and the original player. The client retains that
mapping, removes departed entities, and clears it on logout. An optional mixin
intercepts:

```text
AvatarManager.getAvatar(Entity) -> Avatar
```

For a known podium entity, the mixin returns
`AvatarManager.getAvatarForPlayer(ownerUUID)`. This lets the normal Figura
renderer use the original player's live avatar on the podium. The reflective
bridge caches the method lookup, not animation frames or rendered images.

The relevant lesson is to reuse Figura's existing avatar by player UUID. Chat
already has a real sender identity, so Chat Faces needs no synthetic entity,
podium packet, server-side copy, or change to the leaderboard.

## Avatar-authored portrait limitations

A custom `Portrait` group is a separate model branch. An animation targeting
the world head does not automatically animate similarly named eye parts in that
portrait. A model can therefore have a blinking world face and a static Tab
portrait. Shared animated textures can still change the portrait's appearance.

Likewise, accessories attached only to the world head need not appear in a
separately authored portrait. A script may deliberately hide that portrait or
assign the world head a nonstandard parent type. Removing the portrait group
or forcing the world head into its place is not a universal correction.

Chat Faces follows Figura's authored Tab portrait behavior. It supports live
portrait changes that the avatar and Figura actually provide; it does not
manufacture missing animation tracks or silently modify avatar files.

## Verification boundary

Binary inspection establishes the API and expected behavior above. It does not
establish that the final mixin applies, that OpenGL state is balanced in a live
client, or that every avatar script is compatible. Build results and executed
runtime checks must be recorded separately. See [TESTING.md](TESTING.md).
