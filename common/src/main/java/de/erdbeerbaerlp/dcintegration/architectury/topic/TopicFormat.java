package de.erdbeerbaerlp.dcintegration.architectury.topic;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class TopicFormat {
    private static final Set<String> OFFLINE = Set.of("totalplayers", "serverversion", "date", "time");
    private static final Pattern TOKEN = Pattern.compile("%([a-z]+)%");
    private TopicFormat() { }
    public static String render(String format, Map<String, String> values, boolean offline) {
        String text = TOKEN.matcher(format).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                (!offline || OFFLINE.contains(match.group(1))) ? values.getOrDefault(match.group(1), match.group()) : match.group()));
        text = text.replaceAll("(?i)§[0-9a-fk-orx]", "").replaceAll("[\\p{Cc}]", " ");
        // Text/announcement topic limit. Keep Unicode surrogate pairs intact.
        if (text.length() > 1024) {
            int end = Character.isHighSurrogate(text.charAt(1023)) ? 1023 : 1024;
            text = text.substring(0, end);
        }
        return text;
    }
}
