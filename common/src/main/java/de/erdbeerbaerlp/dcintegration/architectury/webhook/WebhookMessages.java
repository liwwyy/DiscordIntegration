package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Player chat is always content-only, including when core chat embeds/item tooltips are enabled. */
public final class WebhookMessages {
    private WebhookMessages() { }

    /** Player event icons belong to embed authors; the webhook sender retains the server identity. */
    public static MessageCreateData playerEvent(MessageCreateData message, String playerName, String head) {
        List<MessageEmbed> embeds = new ArrayList<>();
        for (MessageEmbed embed : message.getEmbeds()) {
            var author = embed.getAuthor();
            var builder = EmbedBuilder.fromData(embed.toData());
            String name = author == null ? playerName : author.getName();
            if ((author == null || playerName.equals(name)) && embed.getDescription() != null && !embed.getDescription().isBlank()) {
                name = embed.getDescription();
                builder.setDescription(null);
            }
            builder.setAuthor(name, author == null ? null : author.getUrl(), head);
            embeds.add(builder.build());
        }
        return new MessageCreateBuilder().applyData(message).setEmbeds(embeds).build();
    }

    /** A retry resumes at the first unacknowledged chunk, preserving already delivered chunks. */
    public static final class Batch {
        private final List<MessageCreateData> messages;
        private int next;

        public Batch(List<MessageCreateData> messages) {
            this.messages = List.copyOf(messages);
        }

        public CompletableFuture<Long> send(Function<MessageCreateData, CompletableFuture<Long>> sender) {
            CompletableFuture<Long> result = CompletableFuture.completedFuture(0L);
            for (int index = next; index < messages.size(); index++) {
                MessageCreateData message = messages.get(index);
                result = result.thenCompose(ignored -> sender.apply(message).thenApply(id -> {
                    next++;
                    return id;
                }));
            }
            return result;
        }
    }

    public static List<MessageCreateData> player(String text, WebhookSettings settings) {
        List<Message.MentionType> allowed = new ArrayList<>();
        if (settings.allowUserMentions) allowed.add(Message.MentionType.USER);
        if (settings.allowRoleMentions) allowed.add(Message.MentionType.ROLE);
        if (settings.allowEveryoneMentions) {
            allowed.add(Message.MentionType.EVERYONE);
            allowed.add(Message.MentionType.HERE);
        }
        List<MessageCreateData> messages = new ArrayList<>();
        for (String chunk : WebhookText.split(text)) {
            if (!chunk.isBlank()) messages.add(new MessageCreateBuilder().setContent(chunk).setAllowedMentions(allowed)
                    .setSuppressEmbeds(true).build());
        }
        return messages;
    }
}
