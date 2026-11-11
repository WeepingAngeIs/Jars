# Validation plan and results

## Status

**The manual/runtime cases below have not yet been executed.** This document
records the acceptance criteria established from the inspected JARs. Compilation,
static inspection, a title-screen launch, and an in-world visual check are
different forms of evidence; do not report one as proof of another.

Update the result record after execution with the exact Chat Faces build,
Minecraft/NeoForge/dependency versions, scenario, observed result, and relevant
log or screenshot. Record failures and untested cases explicitly.

## Baseline setup

Use a disposable Minecraft 1.21.1 client with Java 21, the intended NeoForge
build, Chat Heads 0.15.7, Figura 0.1.6+1.21.1, and Chat Faces. Keep the same
configuration for comparison with Chat Faces disabled. Testing does not require
changing a production server or uploading an avatar.

For local portrait checks, use a locally loaded test avatar with permission to
use it. At least two fixtures are needed: an animated custom `Portrait` branch
and a head-only fallback with no rendered custom portrait. A static custom
portrait is also useful to confirm that Chat Faces respects the authored
portrait instead of forcing the world-head animation onto it.

Chat Heads placement values are `BEFORE_NAME` and `BEFORE_LINE`. Run the core
portrait, identity, opacity, and layout cases in both modes. In `BEFORE_NAME`,
include a normal player name and a decorated display name with a rank prefix.

## Manual acceptance matrix

All results currently have status **NOT RUN**.

| ID | Scenario | Expected result | Result |
| --- | --- | --- | --- |
| M01 | Start with the exact declared dependency versions. | Mod initialization and the Chat Heads target mixin complete without an error. | NOT RUN |
| M02 | `BEFORE_NAME`: send a normal message, then one from a player with a colored rank prefix. | One head occupies Chat Heads' reserved name position; original text, prefix, spacing, click targets, and sender remain correct. | NOT RUN |
| M03 | `BEFORE_LINE`: repeat the same messages. | One head occupies the beginning-of-line slot; name text and indentation remain unchanged. | NOT RUN |
| M04 | Use a custom portrait with a visible blink or moving part; compare chat and Tab over several seconds. | Chat shows successive live states of the same authored portrait, without freezing at message arrival. | NOT RUN |
| M05 | Use an avatar whose native portrait falls back to its head. | Chat follows Figura's head fallback and does not draw the full body. | NOT RUN |
| M06 | Use a static custom portrait with an animated world head. | Chat follows the static custom Tab portrait; it does not inject unrelated world-head movement. | NOT RUN |
| M07 | Change an animated/shared portrait texture or a synchronized avatar toggle. | The next available Figura state appears without resending the chat message. | NOT RUN |
| M08 | Display several recent and fading messages from one player simultaneously. | Positions and per-line opacity are correct; same-frame reuse does not freeze animation or reuse one line's opacity for another. | NOT RUN |
| M09 | Display messages from several players with visibly different portraits. | Each line retains the correct UUID and portrait; a reused render target does not replace earlier players' faces. | NOT RUN |
| M10 | Send a long message that wraps and add enough messages to scroll. | Existing Chat Heads wrapping and placement are preserved; no duplicate heads on continuation lines and no detached portraits while scrolling. | NOT RUN |
| M11 | Set chat scale to 50%, 75%, and 100%, then change GUI scale and window size. | Faces stay in the reserved square with no clipping shift, text overlap, or viewport distortion. | NOT RUN |
| M12 | Close chat and wait through the normal fade; vary chat opacity. | The entire portrait fades with its line, including emissive/translucent parts; fully faded lines leave no face behind. | NOT RUN |
| M13 | Enable and disable the Chat Heads shadow setting. | Face baseline and occupied area remain consistent with Chat Heads; no double skin head or misplaced shadow appears. | NOT RUN |
| M14 | Use a tall/wide custom portrait and open chat near the screen edge. | The face stays clipped to its own square; ears/accessories do not escape into another line or the HUD. | NOT RUN |
| M15 | Receive a message from a player with no available Figura avatar. | The normal Chat Heads Minecraft skin face appears. | NOT RUN |
| M16 | Observe a player before an avatar loads, then after it finishes loading. | Normal skin fallback transitions to the available portrait without stale failed state. | NOT RUN |
| M17 | Turn Figura avatar portraits off, then restore the setting. | The normal skin fallback is used while disabled; the next eligible frame restores the portrait. | NOT RUN |
| M18 | Use Figura panic mode, blocked/low-trust avatar settings, and an avatar's own portrait-visibility toggle. | Figura's controls remain effective. Chat Faces does not force a hidden/untrusted avatar visible or bypass native fallback behavior. | NOT RUN |
| M19 | Receive an unknown-sender/system line and a command or translated message that Chat Heads supports. | Chat Heads' own sender decision is preserved. Unknown senders are not assigned a fabricated avatar. | NOT RUN |
| M20 | Change or reload an avatar while its old messages remain visible. | Old messages use the new current avatar after Figura updates; no old texture or model is pinned indefinitely. | NOT RUN |
| M21 | Reload resources, disconnect, and enter a new local world. | Render resources and frame caches recover; no previous-world sender state or invalid texture remains. | NOT RUN |
| M22 | Use a deliberately invalid local test portrait, then show a valid player's portrait. | Failure remains bounded, logs are not emitted every frame, valid players still render, and skin fallback/GUI remain usable. | NOT RUN |
| M23 | After portraits render, open Tab, inventory, pause menu, and other HUD layers. | Lighting, framebuffer, viewport, depth, scissor, blend, and shader color are restored; unrelated UI is neither tinted nor clipped. | NOT RUN |
| M24 | Use upside-down player rendering when supported by the original renderer. | Portrait orientation follows the same supported Figura/Chat Heads behavior without corrupting bounds. | NOT RUN |
| M25 | Profile a full open chat with repeated and unique players. | Work is bounded to visible faces; repeated same-frame faces are reused; no uncontrolled render-target growth or extra avatar ticks occur. | NOT RUN |
| M26 | Compare avatar counters, physics, particles, and pings with chat hidden, open, and containing duplicate messages. | Chat Faces does not manually advance scripts, introduce an independent animation clock, or multiply tick events. | NOT RUN |

## Build and startup checks

These checks are useful before the visual matrix but do not replace it:

1. Compile with Java 21 against the inspected dependencies.
2. Inspect the output JAR for the intended mod ID, version, client-side
   dependency declarations, and mixin registration.
3. Launch a disposable development client and check the log for mixin
   application errors, linkage errors, and missing resource failures.
4. Enter a disposable local world before judging any chat behavior. A title
   screen alone does not exercise the head draw call or render-target path.
5. Exercise both `BEFORE_NAME` and `BEFORE_LINE` with actual rendered messages.
   Automated startup alone is insufficient evidence for portrait alignment,
   transparency, or animation.

## Headless smoke-test option

A Linux environment with an X server and software OpenGL can launch the
development client under Xvfb. Java 21, the Gradle runtime/game assets, the two
dependency mods, Xvfb, and Mesa's software renderer must be available. A typical
launch wraps the configured Gradle `runClient` task in `xvfb-run`, with
`LIBGL_ALWAYS_SOFTWARE=1` and a 1280 by 720, 24-bit virtual screen.

Use the development launch's disposable username and an isolated game
directory. No Microsoft login, production-world access, or avatar upload is
needed for a title-screen smoke test. Local-world rendering can be checked with
local test content. Multiplayer/avatar-download behavior remains a separate
test. Use a bounded timeout and collect logs even when startup fails.

A virtual display still requires operating-system permission to create local
sockets. In an environment that denies X11 socket creation, installing Xvfb
does not resolve that restriction. Run the smoke check in an authorized CI or
desktop environment instead and mark local runtime checks unexecuted.

## Execution record

No runtime or manual acceptance results have been recorded yet.

| Build/commit | Environment | Cases | Observation | Evidence |
| --- | --- | --- | --- | --- |
| Pending | Pending | Pending | Not executed | None |
