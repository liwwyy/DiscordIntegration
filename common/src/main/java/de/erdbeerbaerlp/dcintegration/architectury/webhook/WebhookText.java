package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

public final class WebhookText {
    private static final Pattern FORMATTING = Pattern.compile("(?i)(?:[§&]x(?:[§&][0-9a-f]){6}|[§&]#[0-9a-f]{6}|[§&][0-9a-fk-orx])");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\`*_{}\\[\\]()~|>])");

    private static final Pattern DISCORD_TOKEN = Pattern.compile("<(?:@!?[0-9]+|@&[0-9]+|#[0-9]+|a?:[A-Za-z0-9_]+:[0-9]+)>");

    private WebhookText() { }

    public static String strip(String text) {
        return CONTROL.matcher(FORMATTING.matcher(text).replaceAll("")).replaceAll("");
    }

    public static String filter(String text, List<WebhookSettings.Filter> filters) {
        for (var rule : filters) text = text.replaceAll(rule.regex, rule.replacement);
        return text;
    }

    public static String username(String name, String fallback, WebhookSettings settings) {
        name = strip(filter(strip(name), settings.usernameFilters)).replaceAll("[\\n\\t]", " ").trim();
        if (name.isBlank()) name = strip(fallback).replaceAll("[\\n\\t]", " ").trim();
        if (name.isBlank()) name = "Minecraft Player";
        // Discord reserves this name. Keep the visible spelling recognizable without rejecting delivery.
        name = name.replaceAll("(?i)clyde", "Cly\u200bde").replaceAll("(?i)discord", "Disc\u200bord");
        return truncate(name, 80);
    }

    public static String name(String displayName, String discordName, String discordUsername, WebhookSettings settings) {
        String result = settings.useLinkedDiscordName && discordName != null ? discordName : displayName;
        if (settings.appendDiscordUsername && discordUsername != null) {
            result += settings.discordAffix.replace("{discord_username}", discordUsername)
                    .replace("{discord_name}", discordName == null ? discordUsername : discordName);
        }
        return username(result, displayName, settings);
    }

    public static String escapeMarkdown(String text) {
        return outsideDiscordTokens(text, segment -> MARKDOWN.matcher(segment).replaceAll("\\\\$1"));
    }

    public static String outsideDiscordTokens(String text, UnaryOperator<String> transform) {
        StringBuilder escaped = new StringBuilder();
        var tokens = DISCORD_TOKEN.matcher(text);
        int offset = 0;
        while (tokens.find()) {
            escaped.append(transform.apply(text.substring(offset, tokens.start())));
            escaped.append(tokens.group());
            offset = tokens.end();
        }
        return escaped.append(transform.apply(text.substring(offset))).toString();
    }

    public static String avatar(String template, String username, UUID uuid, String texture, int size) {
        return template.replace("{username}", encode(username)).replace("{uuid}", uuid.toString())
                .replace("{uuid-nodashes}", uuid.toString().replace("-", ""))
                .replace("{texture}", encode(texture)).replace("{size}", Integer.toString(size))
                .replace("%name%", encode(username)).replace("%uuid%", uuid.toString())
                .replace("%uuid_dashless%", uuid.toString().replace("-", ""))
                .replace("%randomUUID%", UUID.randomUUID().toString());
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String truncate(String text, int max) {
        int end = Math.min(text.length(), max);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** Lossless splitting, including long words and surrogate pairs. */
    public static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        while (text.length() > 2000) {
            String chunk = truncate(text, 2000);
            result.add(chunk);
            text = text.substring(chunk.length());
        }
        if (!text.isBlank()) result.add(text);
        else if (!text.isEmpty() && !result.isEmpty()) {
            // Keep trailing whitespace without sending an invalid whitespace-only Discord message.
            int last = result.size() - 1;
            if (result.get(last).length() + text.length() <= 2000) result.set(last, result.get(last) + text);
        }
        return result;
    }
}
