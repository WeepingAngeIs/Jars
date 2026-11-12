# Chat Faces

An independent client mod for Minecraft 1.21.1 / NeoForge that draws live Figura
portraits in player chat and entity portraits in supported death messages.

## Install

Use Java 21, NeoForge 21.1.22 or newer in the 21.1 series, and
`figura-0.1.6+1.21.1-neoforge-mc.jar`. Add `Chat-Faces-1.1.4.jar` to the
client's mods folder. Remove the older Chat Faces JAR. Chat Heads is not a
dependency; remove it when using Chat Faces to avoid duplicate chat portraits.
Enable Figura's avatar portrait setting and restart Minecraft.

Each viewer installs Chat Faces and Figura on their own client. No server mod
is needed. Signed chat uses Minecraft's sender identity; formatted system chat
uses a conservative online-player name match. Normal death messages include
entity hover metadata, allowing the victim and the attacker to be identified.
The actual entity is used when it is present on the client; otherwise an
instance of its registered type is rendered. A server that replaces structured
death messages with plain text may omit the information needed to identify the
attacker. Entity variants may differ if the original entity is unavailable.

On servers that send the local player `Your nickname is now …`, Chat Faces
learns each confirmed nickname and remembers it for that server. For other
players it can use a player identity carried by the chat component. If a
server sends another player's nickname as plain text without an identity, a
viewer can use `config/chatfaces-nicknames.properties` to map it to their
account name or UUID.

Figura controls avatar loading, visibility, trust, and animations. When there is
no available Figura portrait the ordinary player skin head is shown. A Figura
avatar with a separate static Tab portrait will also appear static in chat.

## Build

Run `./gradlew build` with Java 21. The installable file is
`build/libs/Chat-Faces-1.1.4.jar`; the sources JAR is not a mod to install.
GitHub Actions runs the build and client rendering checks.
