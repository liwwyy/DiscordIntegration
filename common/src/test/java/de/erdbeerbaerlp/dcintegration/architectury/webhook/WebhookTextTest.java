package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import dcshadow.com.moandjiezana.toml.Toml;
import dcshadow.com.moandjiezana.toml.TomlWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebhookTextTest {
    @Test void linkedAffixAndNameAreIndependentOptions() {
        var settings = new WebhookSettings();
        assertEquals("Steve (linkeduser)", WebhookText.name("Steve", "Guild Nick", "linkeduser", settings));
        assertEquals("Steve", WebhookText.name("Steve", null, null, settings));
        settings.useLinkedDiscordName = true;
        settings.appendDiscordUsername = false;
        assertEquals("Guild Nick", WebhookText.name("Steve", "Guild Nick", "linkeduser", settings));
        assertEquals("Steve", WebhookText.name("Steve", null, null, settings));
    }

    @Test void filtersFormattingEmptyFallbackAndUnicodeUsernameLimit() {
        var settings = new WebhookSettings();
        settings.usernameFilters.add(new WebhookSettings.Filter("\\[VIP\\]", ""));
        assertEquals("Steve", WebhookText.username("§a[VIP] &bSteve\n", "fallback", settings));
        settings.usernameFilters.add(new WebhookSettings.Filter(".*", ""));
        assertEquals("Steve", WebhookText.username("filtered", "Steve", settings));
        settings.usernameFilters.clear();
        String name = WebhookText.username("a".repeat(79) + "😀", "fallback", settings);
        assertEquals(79, name.length());
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
        assertEquals("hi red hex", WebhookText.strip("hi §cred &x&f&f&0&0&0&0hex\u0000"));
    }

    @Test void orderedContentFiltersCanRemoveMessage() {
        var settings = new WebhookSettings();
        settings.contentFilters.add(new WebhookSettings.Filter("bad", "good"));
        settings.contentFilters.add(new WebhookSettings.Filter("good", ""));
        assertEquals("", WebhookText.filter("bad", settings.contentFilters));
    }

    @Test void avatarSupportsNewAndExistingPlaceholdersAndEncodesUserInput() {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        assertEquals("https://example.com/A%20B/12345678-1234-1234-1234-123456789abc/12345678123412341234123456789abc/hash/128",
                WebhookText.avatar("https://example.com/{username}/{uuid}/{uuid-nodashes}/{texture}/{size}", "A B", uuid, "hash", 128));
        assertEquals("https://example.com/Steve/" + uuid, WebhookText.avatar("https://example.com/%name%/%uuid%", "Steve", uuid, "", 128));
    }

    @Test void escapesMarkdownWithoutBreakingDiscordMentionsAndEmotes() {
        assertEquals("\\*hello\\* <@123> <@&456> <#789> <:party_blob:1234> <a:dance:5678>",
                WebhookText.escapeMarkdown("*hello* <@123> <@&456> <#789> <:party_blob:1234> <a:dance:5678>"));
    }

    @Test void unicodeAliasesDoNotCorruptCustomEmotesWithTheSameName() {
        assertEquals("<:smile:1234> 😄", WebhookText.outsideDiscordTokens("<:smile:1234> :smile:",
                dcshadow.com.vdurmont.emoji.EmojiParser::parseToUnicode));
    }

    @Test void splitsLongWordsWithoutDroppingCharactersOrSurrogatePairs() {
        String text = "a".repeat(1999) + "😀" + "b".repeat(4001);
        var chunks = WebhookText.split(text);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 2000 && !Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))));
    }

    @Test void partialDeliveryRetryDoesNotResendAcknowledgedChunks() {
        var settings = new WebhookSettings();
        var batch = new WebhookMessages.Batch(WebhookMessages.player("a".repeat(2000) + "b".repeat(2000) + "c", settings));
        List<Character> attempts = new ArrayList<>();
        assertThrows(CompletionException.class, () -> batch.send(message -> {
            char chunk = message.getContent().charAt(0);
            attempts.add(chunk);
            return chunk == 'b' ? CompletableFuture.failedFuture(new IllegalStateException("deleted webhook"))
                    : CompletableFuture.completedFuture(1L);
        }).join());
        batch.send(message -> {
            attempts.add(message.getContent().charAt(0));
            return CompletableFuture.completedFuture(2L);
        }).join();
        assertEquals(List.of('a', 'b', 'b', 'c'), attempts);
    }

    @Test void configRoundTripsAndRejectsInvalidRegex() {
        var settings = new WebhookSettings();
        settings.usernameFilters.add(new WebhookSettings.Filter("\\[VIP\\]", ""));
        var copy = new Toml().read(new TomlWriter().write(settings)).to(WebhookSettings.class);
        copy.validate();
        assertEquals(settings.usernameFilters.getFirst().regex, copy.usernameFilters.getFirst().regex);
        assertTrue(copy.appendDiscordUsername);
        copy.contentFilters.add(new WebhookSettings.Filter("[", ""));
        assertThrows(IllegalArgumentException.class, copy::validate);
    }

    @Test void chatPayloadIsPlainContentWithNoEmbedsAndNoMassMentions() {
        var settings = new WebhookSettings();
        var message = WebhookMessages.player("hello <@123> @everyone", settings).getFirst();
        assertTrue(message.getEmbeds().isEmpty());
        assertTrue(message.isSuppressEmbeds());
        assertEquals("hello <@123> @everyone", message.getContent());
        assertFalse(message.getAllowedMentions().contains(net.dv8tion.jda.api.entities.Message.MentionType.EVERYONE));
        assertTrue(WebhookMessages.player(" ", settings).isEmpty());
        settings.allowRoleMentions = true;
        settings.allowEveryoneMentions = true;
        var enabled = WebhookMessages.player("<@&123> @everyone", settings).getFirst();
        assertTrue(enabled.getAllowedMentions().contains(net.dv8tion.jda.api.entities.Message.MentionType.ROLE));
        assertTrue(enabled.getAllowedMentions().contains(net.dv8tion.jda.api.entities.Message.MentionType.EVERYONE));
    }
}
