package dev.unyielding.chatfaces.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

/** Associates portraits with vanilla chat messages and preserves their text styles. */
public final class ChatMessageLayout {
    private static final ResourceLocation SPACER = ResourceLocation.fromNamespaceAndPath("chatfaces", "spacer");
    private static final int MARKER = 0xE000;
    private static final String TAG = "chatfaces-portrait:";
    private static final Map<GuiMessage, Selection> MESSAGES = new WeakHashMap<>();
    private static final Map<FormattedCharSequence, List<PlacedPortrait>> LINES = new WeakHashMap<>();
    private static final ThreadLocal<GameProfile> SENDER = new ThreadLocal<>();
    private static final ThreadLocal<Selection> ORIGINAL_DEATH = new ThreadLocal<>();

    private ChatMessageLayout() {}

    @Nullable
    public static GameProfile setSender(@Nullable GameProfile profile) {
        GameProfile old = SENDER.get();
        if (profile == null) SENDER.remove(); else SENDER.set(profile);
        return old;
    }

    /** Preserve vanilla death metadata before another client mod decorates text. */
    @Nullable
    public static Object captureDeath(Component message) {
        Selection previous = ORIGINAL_DEATH.get();
        if (findDeath(message, 0) != null) ORIGINAL_DEATH.set(identify(message, null));
        return previous;
    }

    public static void restoreDeath(@Nullable Object previous) {
        if (previous == null) ORIGINAL_DEATH.remove(); else ORIGINAL_DEATH.set((Selection) previous);
    }

    public record Portrait(@Nullable PlayerInfo player, @Nullable EntityType<?> type,
                           @Nullable UUID id) {
        static Portrait player(PlayerInfo player) {
            return new Portrait(player, null, player.getProfile().getId());
        }
        static Portrait creature(EntityType<?> type, UUID id) {
            return new Portrait(null, type, id);
        }
    }

    public record PlacedPortrait(int x, Portrait portrait) {}
    private record Insertion(int index, Portrait portrait, String name) {}
    private record Selection(List<Insertion> inserts) {}
    private record Segment(Style style, String text) {}

    public static List<FormattedCharSequence> wrap(GuiMessage message, int width, Font font,
            BiFunction<FormattedText, Integer, List<FormattedCharSequence>> original) {
        Selection selection = MESSAGES.computeIfAbsent(message, ignored -> {
            Selection captured = ORIGINAL_DEATH.get();
            Selection resolved = captured == null ? null : realign(captured, message.content());
            return resolved != null ? resolved : identify(message.content(), SENDER.get());
        });
        if (selection.inserts().isEmpty()) return original.apply(message.content(), width);

        Component decorated = addMarkers(message.content(), selection);
        List<FormattedCharSequence> lines = original.apply(decorated, width);
        for (FormattedCharSequence line : lines) {
            List<PlacedPortrait> placements = new ArrayList<>();
            int[] offset = {0};
            line.accept((index, style, codepoint) -> {
                String insertion = style.getInsertion();
                if (codepoint == MARKER && SPACER.equals(style.getFont())
                        && insertion != null && insertion.startsWith(TAG)) {
                    try {
                        int slot = Integer.parseInt(insertion.substring(TAG.length()));
                        if (slot >= 0 && slot < selection.inserts().size()) {
                            placements.add(new PlacedPortrait(offset[0], selection.inserts().get(slot).portrait()));
                        }
                    } catch (NumberFormatException ignored) { }
                }
                offset[0] += font.width(FormattedCharSequence.codepoint(codepoint, style));
                return true;
            });
            if (!placements.isEmpty()) LINES.put(line, List.copyOf(placements));
        }
        return lines;
    }

    public static List<PlacedPortrait> portraits(FormattedCharSequence line) {
        return LINES.getOrDefault(line, List.of());
    }

    public static void clear() {
        MESSAGES.clear();
        LINES.clear();
        SENDER.remove();
        ORIGINAL_DEATH.remove();
    }

    private static Selection identify(Component message, @Nullable GameProfile signedSender) {
        String text = message.getString();
        TranslatableContents death = findDeath(message, 0);
        if (death != null) {
            List<Insertion> found = new ArrayList<>();
            Object[] args = death.getArgs();
            int searchFrom = 0;
            for (int i = 0; i < Math.min(args.length, 2); i++) {
                if (!(args[i] instanceof Component name)) continue;
                HoverEvent.EntityTooltipInfo entity = entityInfo(name, 0);
                Portrait portrait = portraitFor(entity, name.getString());
                if (portrait == null) continue;
                int position = indexOfIgnoreCase(text, name.getString(), searchFrom);
                if (position < 0) position = i == 0 ? 0 : text.length();
                found.add(new Insertion(position, portrait, name.getString()));
                searchFrom = Math.min(text.length(), position + name.getString().length());
            }
            found.sort(Comparator.comparingInt(Insertion::index));
            return new Selection(List.copyOf(found));
        }

        PlayerInfo player = signedSender == null ? guessSystemSender(text) : findPlayer(signedSender);
        if (player == null) return new Selection(List.of());
        int position = indexOfIgnoreCase(text, player.getProfile().getName(), 0);
        if (position < 0 || position > 160) position = 0;
        return new Selection(List.of(new Insertion(position, Portrait.player(player), player.getProfile().getName())));
    }

    @Nullable
    private static Selection realign(Selection original, Component displayed) {
        String text = displayed.getString();
        List<Insertion> placed = new ArrayList<>();
        int from = 0;
        for (Insertion insert : original.inserts()) {
            String name = insert.name();
            int position = indexOfIgnoreCase(text, name, from);
            if (position < 0) position = findStyledEntity(displayed, insert.portrait().id(), from);
            if (position < 0 && insert.portrait().player() != null) {
                name = insert.portrait().player().getProfile().getName();
                position = indexOfIgnoreCase(text, name, from);
            }
            if (position < 0) return null;
            placed.add(new Insertion(position, insert.portrait(), name));
            from = position + name.length();
        }
        return placed.isEmpty() ? null : new Selection(List.copyOf(placed));
    }

    private static int findStyledEntity(Component displayed, @Nullable UUID id, int from) {
        if (id == null) return -1;
        int[] cursor = {0};
        int[] result = {-1};
        displayed.visit((style, text) -> {
            int start = cursor[0];
            cursor[0] += text.length();
            HoverEvent hover = style.getHoverEvent();
            HoverEvent.EntityTooltipInfo info = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            if (result[0] < 0 && cursor[0] > from && info != null && id.equals(info.id)) {
                result[0] = Math.max(start, from);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return result[0];
    }

    @Nullable
    private static TranslatableContents findDeath(Component component, int depth) {
        if (depth > 16) return null;
        if (component.getContents() instanceof TranslatableContents translated) {
            if (translated.getKey().startsWith("death.")) return translated;
            for (Object arg : translated.getArgs()) {
                if (arg instanceof Component child) {
                    TranslatableContents found = findDeath(child, depth + 1);
                    if (found != null) return found;
                }
            }
        }
        for (Component child : component.getSiblings()) {
            TranslatableContents found = findDeath(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static HoverEvent.EntityTooltipInfo entityInfo(Component component, int depth) {
        if (depth > 16) return null;
        HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover != null) {
            HoverEvent.EntityTooltipInfo info = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            if (info != null) return info;
        }
        for (Component child : component.getSiblings()) {
            HoverEvent.EntityTooltipInfo info = entityInfo(child, depth + 1);
            if (info != null) return info;
        }
        return null;
    }

    @Nullable
    private static Portrait portraitFor(@Nullable HoverEvent.EntityTooltipInfo entity, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != null) {
            if (entity.type == EntityType.PLAYER) {
                PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(entity.id);
                if (info == null) info = new PlayerInfo(new GameProfile(entity.id, name), false);
                return Portrait.player(info);
            }
            return Portrait.creature(entity.type, entity.id);
        }
        if (mc.getConnection() != null) {
            for (PlayerInfo player : mc.getConnection().getOnlinePlayers()) {
                if (name.equalsIgnoreCase(player.getProfile().getName())) return Portrait.player(player);
            }
        }
        return null;
    }

    private static PlayerInfo findPlayer(GameProfile profile) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(profile.getId());
        return info != null ? info : new PlayerInfo(profile, false);
    }

    @Nullable
    private static PlayerInfo guessSystemSender(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;
        int colon = text.indexOf(':');
        int angle = text.indexOf('>');
        int limit = colon >= 0 ? colon : angle;
        if (limit < 0 || limit > 160) return null;
        PlayerInfo selected = null;
        int best = Integer.MAX_VALUE;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            int at = indexOfIgnoreCase(text, name, 0);
            if (at < 0 || at + name.length() > limit || at >= best) continue;
            boolean left = at == 0 || !isNameChar(text.charAt(at - 1));
            boolean right = at + name.length() == text.length() || !isNameChar(text.charAt(at + name.length()));
            if (left && right) { selected = info; best = at; }
        }
        return selected;
    }

    private static boolean isNameChar(char c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static Component addMarkers(Component component, Selection selection) {
        List<Segment> segments = new ArrayList<>();
        component.visit((style, text) -> {
            segments.add(new Segment(style, text));
            return Optional.empty();
        }, Style.EMPTY);
        MutableComponent result = Component.empty();
        int absolute = 0;
        int slot = 0;
        for (Segment segment : segments) {
            int end = absolute + segment.text().length();
            int cursor = 0;
            while (slot < selection.inserts().size() && selection.inserts().get(slot).index() <= end) {
                int local = Math.clamp(selection.inserts().get(slot).index() - absolute, cursor, segment.text().length());
                if (local > cursor) result.append(Component.literal(segment.text().substring(cursor, local)).setStyle(segment.style()));
                result.append(marker(slot++));
                cursor = local;
            }
            if (cursor < segment.text().length()) result.append(Component.literal(segment.text().substring(cursor)).setStyle(segment.style()));
            absolute = end;
        }
        while (slot < selection.inserts().size()) result.append(marker(slot++));
        return result;
    }

    private static Component marker(int slot) {
        return Component.literal("\uE000").setStyle(Style.EMPTY.withFont(SPACER).withInsertion(TAG + slot));
    }
}
