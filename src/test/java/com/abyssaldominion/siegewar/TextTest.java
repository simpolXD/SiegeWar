package com.abyssaldominion.siegewar;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TextTest {
    private String plain(String s, Map<String, ?> args) {
        return PlainTextComponentSerializer.plainText().serialize(Text.of(s, args));
    }

    @Test
    void rendersBothLegacyAndMiniMessageWithoutRawCodes() {
        assertEquals(
                "Hello World!", plain("&aHello <gold><player></gold>!", Map.of("player", "World")));
        assertEquals("Test", plain("&#aabbcc&lTest", Map.of()));
        assertEquals("Rank", plain("§6Rank", Map.of()));
    }

    @Test
    void placeholderValuesAreLiteralNotExecutableMarkup() {
        String input = "<click:run_command:'/op attacker'>bad</click> &c";
        assertEquals(
                "Joined " + input, plain("<green>Joined <arena></green>", Map.of("arena", input)));
    }

    @Test
    void commandUsageAngleBracketsRemainVisible() {
        assertEquals(
                "Usage: /siegewar join <arena>",
                plain(
                        "<yellow>Usage: <usage></yellow>",
                        Map.of("usage", "/siegewar join <arena>")));
    }
}
