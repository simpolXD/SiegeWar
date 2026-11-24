package com.abyssaldominion.siegewar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trusted templates support both MiniMessage and the reference plugin's legacy colors. */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String CODES = "0123456789abcdefklmnor";
    private static final String[] TAGS = {
        "black",
        "dark_blue",
        "dark_green",
        "dark_aqua",
        "dark_red",
        "dark_purple",
        "gold",
        "gray",
        "dark_gray",
        "blue",
        "green",
        "aqua",
        "red",
        "light_purple",
        "yellow",
        "white",
        "obfuscated",
        "bold",
        "strikethrough",
        "underlined",
        "italic",
        "reset"
    };
    private static final Pattern HEX = Pattern.compile("(?i)[&§]#([0-9a-f]{6})");

    private Text() {}

    public static Component of(String template) {
        return of(template, Map.of());
    }

    public static Component of(String template, Map<String, ?> values) {
        TagResolver.Builder tags = TagResolver.builder();
        values.forEach(
                (key, value) ->
                        tags.resolver(
                                Placeholder.component(
                                        key,
                                        value instanceof Component c
                                                ? c
                                                : Component.text(String.valueOf(value)))));
        return MM.deserialize(legacy(template == null ? "" : template), tags.build())
                .decoration(TextDecoration.ITALIC, false);
    }

    public static String legacy(String s) {
        Matcher hex = HEX.matcher(s);
        s = hex.replaceAll("<#$1>");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                int code = CODES.indexOf(Character.toLowerCase(s.charAt(i + 1)));
                if (code >= 0) {
                    // A legacy color resets decorations, as in Bukkit/WorldEdit messages.
                    if (code < 16) out.append("<reset>");
                    out.append('<').append(TAGS[code]).append('>');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
