# Validation plan and results

## Standalone 1.1.0

The current CI workflow runs the client without Chat Heads. It exercises Figura
animation, skin fallback, chat scale and fading, received messages, and death
messages with multiple entity types, including a custom-named entity. Consult
the workflow run and its evidence artifact for the result of a particular
commit. These are disposable singleplayer client checks; multiplayer transport
and the complete user modpack are not covered.

The remainder of this file records the historical 1.0.0 addon validation.

## Status

**Chat Faces 1.0.0 passed five binary-contract tests and nine real-client
rendering checks** in [GitHub Actions run 43084854529](https://github.com/WeepingAngeIs/Jars/actions/runs/43084854529).
The tested source commit is
[`f8af5bb328fe18c7affc8fcfd452842fd9dd68db`](https://github.com/WeepingAngeIs/Jars/commit/f8af5bb328fe18c7affc8fcfd452842fd9dd68db).
Release files and their provenance are listed in the
[1.0.0 release record](../../releases/chat-faces/1.0.0/README.md).

The client test entered a disposable flat singleplayer world, loaded an
original diagnostic avatar through Figura's native local-avatar loader, and
rendered the actual mixed-in `ChatComponent` to an OpenGL framebuffer. It used
the offline development player's `PlayerInfo` at Chat Heads' existing
message-owner API boundary. No avatar was uploaded. This tests client rendering,
not multiplayer packet delivery or server-side message formatting.

The broader manual matrix below remains only partly covered. No runtime result
is claimed for a user's supplied avatar, a HEAD-fallback fixture, the user's
complete modpack, or arbitrary third-party avatars. Binary inspection of a
fallback method is not a runtime test of that fallback.

## Baseline setup

The successful CI run used the following exact baseline:

| Component | Tested version or setting |
| --- | --- |
| Chat Faces | 1.0.0, source commit above |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.249 |
| Java | Eclipse Adoptium OpenJDK 21.0.13+9-LTS |
| Chat Heads | 0.15.7, Modrinth version `ZPylso9i` |
| Figura | 0.1.6+1.21.1, Modrinth version `AvgeQoZm` |
| Graphics | Linux Xvfb, 1280 by 720; Mesa 26.0.8 llvmpipe software OpenGL 4.5 |
| Avatar | Original test-only custom `Portrait`; red/blue color changes and vertical geometry scaling on normal Figura `TICK` events |

Binary tests verified that the Chat Heads and Figura artifacts match the exact
SHA-256 hashes recorded in [REFERENCE_ANALYSIS.md](REFERENCE_ANALYSIS.md).
Additional manual testing should retain this baseline before adding other mods
or changing dependency versions.

For the remaining portrait checks, a head-only fallback with no rendered custom
portrait and a static custom portrait with an animated world head are still
needed. These would extend the original animated custom-portrait fixture
already exercised in CI.

Chat Heads placement values are `BEFORE_NAME` and `BEFORE_LINE`. Run the core
portrait, identity, opacity, and layout cases in both modes. In `BEFORE_NAME`,
include a normal player name and a decorated display name with a rank prefix.

## Manual acceptance matrix

**AUTOMATED PASS** means the stated scope was exercised in the successful run.
**PARTIAL** identifies the precise automated coverage and what remains.
**NOT RUN** means that runtime scenario was not exercised; source inspection
alone does not change its status.

| ID | Scenario | Expected result | Result |
| --- | --- | --- | --- |
| M01 | Start with the exact declared dependency versions. | Mod initialization and the Chat Heads target mixin complete without an error. | AUTOMATED PASS — client and local world initialized with these dependencies; the target mixins rendered actual chat. |
| M02 | `BEFORE_NAME`: send a normal message, then one from a player with a colored rank prefix. | One head occupies Chat Heads' reserved name position; original text, prefix, spacing, click targets, and sender remain correct. | PARTIAL — real BEFORE_NAME rendering and a plain synthetic [Rank] prefix passed; colored prefixes, click targets, and network reception were not checked. |
| M03 | `BEFORE_LINE`: repeat the same messages. | One head occupies the beginning-of-line slot; name text and indentation remain unchanged. | PARTIAL — real BEFORE_LINE placement passed for the diagnostic message; the broader message/formatting combinations were not checked. |
| M04 | Use a custom portrait with a visible blink or moving part; compare chat and Tab over several seconds. | Chat shows successive live states of the same authored portrait, without freezing at message arrival. | PARTIAL — successive red/blue and scaled-geometry frames appeared without resending the diagnostic message; a side-by-side Tab comparison and authored blink were not tested. |
| M05 | Use an avatar whose native portrait falls back to its head. | Chat follows Figura's head fallback and does not draw the full body. | NOT RUN — no HEAD-fallback runtime fixture. |
| M06 | Use a static custom portrait with an animated world head. | Chat follows the static custom Tab portrait; it does not inject unrelated world-head movement. | NOT RUN — no static custom-portrait runtime fixture. |
| M07 | Change an animated/shared portrait texture or a synchronized avatar toggle. | The next available Figura state appears without resending the chat message. | NOT RUN — shared texture mutation and synchronized toggles were not tested; the fixture changes part color and scale. |
| M08 | Display several recent and fading messages from one player simultaneously. | Positions and per-line opacity are correct; same-frame reuse does not freeze animation or reuse one line's opacity for another. | NOT RUN — simultaneous repeated-player lines and per-line cache reuse were not tested. |
| M09 | Display messages from several players with visibly different portraits. | Each line retains the correct UUID and portrait; a reused render target does not replace earlier players' faces. | NOT RUN — no multiple-player portrait test. |
| M10 | Send a long message that wraps and add enough messages to scroll. | Existing Chat Heads wrapping and placement are preserved; no duplicate heads on continuation lines and no detached portraits while scrolling. | NOT RUN — wrapping and scroll were not exercised. |
| M11 | Set chat scale to 50%, 75%, and 100%, then change GUI scale and window size. | Faces stay in the reserved square with no clipping shift, text overlap, or viewport distortion. | PARTIAL — both placement modes passed at 50% and 100% chat scale in the fixed CI window; 75%, GUI-scale changes, and resizing remain. |
| M12 | Close chat and wait through the normal fade; vary chat opacity. | The entire portrait fades with its line, including emissive/translucent parts; fully faded lines leave no face behind. | PARTIAL — actual ChatComponent age-190 and age-196 fading passed pixel checks; real-time close/wait, opacity settings, emissive/translucent fixtures, and complete disappearance remain. |
| M13 | Enable and disable the Chat Heads shadow setting. | Face baseline and occupied area remain consistent with Chat Heads; no double skin head or misplaced shadow appears. | PARTIAL — the default enabled-shadow setting was exercised; no on/off comparison. |
| M14 | Use a tall/wide custom portrait and open chat near the screen edge. | The face stays clipped to its own square; ears/accessories do not escape into another line or the HUD. | PARTIAL — diagnostic face pixels remained within the expected square; exaggerated geometry and screen-edge cases remain. |
| M15 | Receive a message from a player with no available Figura avatar. | The normal Chat Heads Minecraft skin face appears. | PARTIAL — a genuine local PlayerInfo with no avatar produced nonempty normal-skin pixels in its head square; remote message delivery was not tested. |
| M16 | Observe a player before an avatar loads, then after it finishes loading. | Normal skin fallback transitions to the available portrait without stale failed state. | PARTIAL — skin rendering before native local loading and animated rendering after loading passed; the intermediate asynchronous display sequence was not inspected. |
| M17 | Turn Figura avatar portraits off, then restore the setting. | The normal skin fallback is used while disabled; the next eligible frame restores the portrait. | PARTIAL — disabling portrait rendering produced the normal-skin fallback; the setting was restored, but no subsequent re-enable frame was asserted. |
| M18 | Use Figura panic mode, blocked/low-trust avatar settings, and an avatar's own portrait-visibility toggle. | Figura's controls remain effective. Chat Faces does not force a hidden/untrusted avatar visible or bypass native fallback behavior. | NOT RUN — panic, trust, and avatar visibility controls were not exercised. |
| M19 | Receive an unknown-sender/system line and a command or translated message that Chat Heads supports. | Chat Heads' own sender decision is preserved. Unknown senders are not assigned a fabricated avatar. | NOT RUN — unknown/system/command sender scenarios were not exercised. |
| M20 | Change or reload an avatar while its old messages remain visible. | Old messages use the new current avatar after Figura updates; no old texture or model is pinned indefinitely. | NOT RUN — avatar replacement or reload with retained old messages was not exercised. |
| M21 | Reload resources, disconnect, and enter a new local world. | Render resources and frame caches recover; no previous-world sender state or invalid texture remains. | NOT RUN — resource reload and reconnection were not exercised. |
| M22 | Use a deliberately invalid local test portrait, then show a valid player's portrait. | Failure remains bounded, logs are not emitted every frame, valid players still render, and skin fallback/GUI remain usable. | NOT RUN — intentionally broken avatars and recovery were not exercised. |
| M23 | After portraits render, open Tab, inventory, pause menu, and other HUD layers. | Lighting, framebuffer, viewport, depth, scissor, blend, and shader color are restored; unrelated UI is neither tinted nor clipped. | PARTIAL — framebuffer binding, viewport, scissor enable state, and a later green GUI sentinel passed; other render state and the named screens remain untested. |
| M24 | Use upside-down player rendering when supported by the original renderer. | Portrait orientation follows the same supported Figura/Chat Heads behavior without corrupting bounds. | NOT RUN — upside-down rendering was not exercised. |
| M25 | Profile a full open chat with repeated and unique players. | Work is bounded to visible faces; repeated same-frame faces are reused; no uncontrolled render-target growth or extra avatar ticks occur. | NOT RUN — no crowded-chat performance or allocation profile. |
| M26 | Compare avatar counters, physics, particles, and pings with chat hidden, open, and containing duplicate messages. | Chat Faces does not manually advance scripts, introduce an independent animation clock, or multiply tick events. | NOT RUN — no runtime counter/physics/particle/ping comparison; the diagnostic script uses normal Figura ticks. |

## Completed automated checks

### Binary contracts: five passed

The Gradle test report records five tests, zero failures, and zero ignored tests:

| Check | Result |
| --- | --- |
| Chat Heads artifact hash matches the inspected JAR. | PASS |
| Figura artifact hash matches the inspected JAR. | PASS |
| Chat Heads' two head-draw overloads converge on the hooked public static method. | PASS |
| Figura exposes the expected UUID lookup and native portrait method, including calls to custom-portrait rendering and HEAD fallback. | PASS — binary contract only |
| Minecraft 1.21.1 chat-render and offscreen-GUI method contracts match. | PASS |

### Actual client rendering: nine passed

`run-smoke/smoke-results.json` reports `success: true`, `status: complete`, and
all nine checks passing. The harness completed in 16.356 seconds after its
initialization. This is the harness duration, not the total CI/build duration.

| Recorded check | Observed result |
| --- | --- |
| `native-skin-fallback` | Nonempty ordinary skin pixels in the reserved face square before an avatar was loaded. |
| `before-line-full` | Distinct live red/blue frames, with the portrait within its bounds at 100% chat scale. |
| `before-name-full` | Distinct live red/blue frames, with the portrait within its bounds at 100% chat scale. |
| `before-line-half` | Distinct live red/blue frames, with the portrait within its bounds at 50% chat scale. |
| `before-name-half` | Distinct live red/blue frames, with the portrait within its bounds at 50% chat scale. |
| `rank-prefix-placement` | BEFORE_NAME placed the face to the right of the BEFORE_LINE position, after the diagnostic prefix. |
| `native-chat-fade` | At chat age 190, measured portrait brightness was 0.2836155378 of the opaque frame; accepted range 0.12–0.40. |
| `native-chat-near-zero-fade` | At chat age 196, measured brightness remained nonzero at 0.0459412351; accepted range 0.01–0.09. |
| `portraits-disabled-fallback` | Ordinary skin pixels remained visible with Figura portraits disabled. |

Each sampled render also asserted unchanged framebuffer binding, viewport, and
scissor enable state. A green GUI square drawn afterward was checked for clipping
or tint. These assertions passed within the nine recorded checks; they are not
an additional set of independently counted test cases. They do not establish
that every possible OpenGL state or third-party HUD interaction has been tested.

The original fixture advances through Figura's normal tick callbacks. The
harness did not manually tick the avatar or upload it. The near-zero fade case
specifically checks that the face continues to fade below 0.1 opacity instead
of disappearing at a shader alpha-discard threshold.

CI evidence includes the Gradle HTML report, `smoke-launch.log`, client
`latest.log`, `smoke-results.json`, and `smoke-*.png` captures. Selected retained
evidence and the exact release binaries are indexed by the
[release record](../../releases/chat-faces/1.0.0/README.md).

## Build and startup checks for future changes

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
launch wraps the configured Gradle `runClientSmoke` task in `xvfb-run`, with
`LIBGL_ALWAYS_SOFTWARE=1` and a 1280 by 720, 24-bit virtual screen.

Use the development launch's disposable username and an isolated game
directory named `run-smoke`. The successful harness goes beyond the title
screen: it creates its own disposable world and loads its original local avatar.
No Microsoft login, production-world access, or avatar upload is required.
Multiplayer/avatar-download behavior remains a separate test. The harness has a
230-second watchdog, and CI retains an outer timeout and collects failure logs.

A virtual display still requires operating-system permission to create local
sockets. In an environment that denies X11 socket creation, installing Xvfb
does not resolve that restriction. Run the smoke check in an authorized CI or
desktop environment instead and mark local runtime checks unexecuted.

## Execution record

| Build/commit | Environment | Cases | Observation | Evidence |
| --- | --- | --- | --- | --- |
| Chat Faces 1.0.0 / `f8af5bb328fe18c7affc8fcfd452842fd9dd68db` | Java 21.0.13, Minecraft 1.21.1, NeoForge 21.1.249, Chat Heads 0.15.7, Figura 0.1.6+1.21.1; Linux Xvfb/Mesa | Five binary contracts and nine scoped client-render checks above | PASS; broader manual coverage remains as marked in the matrix | [Successful CI run](https://github.com/WeepingAngeIs/Jars/actions/runs/43084854529), [release record](../../releases/chat-faces/1.0.0/README.md) |

The corresponding `Chat-Faces-1.0.0.jar` SHA-256 is
`ac7ba037eba0ae6d01c5740bd1cc6d94614423ebbfa1d3afa3ca4efd6325f119`.
