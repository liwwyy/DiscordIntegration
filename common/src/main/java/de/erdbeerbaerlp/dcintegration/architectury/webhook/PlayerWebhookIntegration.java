package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import com.google.gson.JsonParser;
import dcshadow.com.vdurmont.emoji.EmojiParser;
import de.erdbeerbaerlp.dcintegration.architectury.util.MessageUtilsImpl;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.PlayerLink;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.McServerInterface;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps core routing/configuration while replacing webhook transport and player chat identity. */
public final class PlayerWebhookIntegration extends DiscordIntegration {
    private static final Pattern EMOTE = Pattern.compile("(?<![<\\w]):([A-Za-z0-9_]+):(?![0-9]+>)");
    private static final Pattern MENTION = Pattern.compile("(?<![<\\w])@([a-zA-Z0-9_.]{2,32})(?![a-zA-Z0-9_.])");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("(?<![<\\w])#([A-Za-z0-9_-]+)");
    private static final WebhookSettings.Store SETTINGS = new WebhookSettings.Store();
    private final WebhookDelivery<Webhook, Payload> delivery = new WebhookDelivery<>(new Transport());

    public PlayerWebhookIntegration(McServerInterface serverInterface) {
        super(serverInterface);
        SETTINGS.get();
    }

    public static WebhookSettings settings() {
        return SETTINGS.get();
    }

    public static boolean usesPlayerWebhooks() {
        return Configuration.instance().webhook.enable && SETTINGS.get().playerChat;
    }

    /** Snapshot all Minecraft/player data on the caller's server thread. No Discord REST calls here. */
    public void sendPlayerChat(ServerPlayer player, String text, GuildMessageChannel channel) {
        WebhookSettings settings = SETTINGS.get();
        String displayName = MessageUtilsImpl.formatPlayerName(player);
        UUID uuid = player.getUUID();
        String template = settings.avatarUrl.isBlank() ? getSkinURL() : settings.avatarUrl;
        String avatar = WebhookText.avatar(template, player.getGameProfile().name(), uuid, skinTexture(player), settings.avatarSize);
        PlayerLink link = Configuration.instance().linking.enableLinking ? LinkManager.getLink(null, uuid) : null;
        String discordId = link == null || (settings.respectLinkedPlayerSettings && !link.settings.useDiscordNameInChannel)
                ? null : link.discordID;
        String content = WebhookText.strip(text);
        for (char blacklisted : Configuration.instance().messages.charBlacklist) content = content.replace(String.valueOf(blacklisted), "");
        try {
            content = WebhookText.strip(WebhookText.filter(content, settings.contentFilters));
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            LOGGER.error("Cannot apply webhook contentFilters; check Java regex replacement groups.");
            return;
        }
        if (content.isBlank()) return;
        String filtered = content;
        enqueue(channel, () -> linkedMember(channel, discordId).thenApply(member -> {
            String name = WebhookText.name(displayName, member == null ? null : member.getEffectiveName(),
                    member == null ? null : member.getUser().getName(), settings);
            String icon = settings.useLinkedDiscordAvatar && member != null ? member.getEffectiveAvatarUrl() : avatar;
            String translated = translate(filtered, channel, settings);
            List<MessageCreateData> messages = WebhookMessages.player(translated, settings);
            return new Payload(messages, name, icon, threadId(channel), uuid);
        }));
    }

    /** All legacy event sends converge here; never borrow the last player's per-message identity. */
    @Override
    public void sendMessage(String name, DiscordMessage message, String avatarURL, MessageChannel channel, boolean isChatMessage, String uuid) {
        if (!Configuration.instance().webhook.enable || !(channel instanceof GuildMessageChannel guildChannel)) {
            super.sendMessage(name, message, avatarURL, channel, isChatMessage, uuid);
            return;
        }
        if (getJDA() == null) return;
        if (!isChatMessage) {
            name = Configuration.instance().webhook.serverName;
            avatarURL = Configuration.instance().webhook.serverAvatarURL;
        }
        String safeName = WebhookText.username(name, "Minecraft Server", new WebhookSettings());
        String icon = avatarURL;
        UUID sender = uuid.equals("0000000") ? null : UUID.fromString(uuid);
        enqueue(guildChannel, () -> CompletableFuture.completedFuture(new Payload(
                List.of(message.buildMessages()), safeName, icon, threadId(guildChannel), sender)));
    }

    private void enqueue(GuildMessageChannel channel, java.util.function.Supplier<CompletableFuture<Payload>> payload) {
        long parent = channel instanceof ThreadChannel thread ? thread.getParentChannel().getIdLong() : channel.getIdLong();
        delivery.enqueue(parent, payload).exceptionally(error -> {
            Throwable cause = unwrap(error);
            // Do not log exception bodies or webhook URLs/tokens.
            String reason = cause instanceof ErrorResponseException response ? Integer.toString(response.getErrorCode()) : cause.getClass().getSimpleName();
            LOGGER.error("Webhook delivery failed for channel " + channel.getId() + " (" + reason + "). Check View Channel/Manage Webhooks permissions and webhook settings.");
            return null;
        });
    }

    private CompletableFuture<Member> linkedMember(GuildMessageChannel channel, String discordId) {
        if (discordId == null || discordId.isBlank()) return CompletableFuture.completedFuture(null);
        Member cached = channel.getGuild().getMemberById(discordId);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return channel.getGuild().retrieveMemberById(discordId).submit().exceptionally(error -> null);
    }

    private static long threadId(GuildMessageChannel channel) {
        return channel instanceof ThreadChannel ? channel.getIdLong() : 0;
    }

    private static String translate(String text, GuildMessageChannel channel, WebhookSettings settings) {
        if (settings.translateMentions && !Configuration.instance().compatibility.disableParsingMentionsIngame) {
            text = MENTION.matcher(text).replaceAll(match -> {
                String username = match.group(1);
                for (Member member : channel.getGuild().getMembers()) {
                    if (member.getUser().getName().equalsIgnoreCase(username) || member.getEffectiveName().equalsIgnoreCase(username))
                        return Matcher.quoteReplacement(member.getAsMention());
                }
                var roles = channel.getGuild().getRolesByName(username, true);
                return Matcher.quoteReplacement(roles.isEmpty() ? match.group() : roles.getFirst().getAsMention());
            });
            text = CHANNEL_MENTION.matcher(text).replaceAll(match -> {
                var channels = channel.getGuild().getTextChannelsByName(match.group(1), true);
                return Matcher.quoteReplacement(channels.isEmpty() ? match.group() : channels.getFirst().getAsMention());
            });
        }
        if (settings.translateEmotes) {
            text = EMOTE.matcher(text).replaceAll(match -> {
                var emojis = channel.getGuild().getEmojisByName(match.group(1), false);
                return Matcher.quoteReplacement(emojis.isEmpty() ? match.group() : emojis.getFirst().getAsMention());
            });
            text = WebhookText.outsideDiscordTokens(text, EmojiParser::parseToUnicode);
        }
        return settings.escapeMarkdown ? WebhookText.escapeMarkdown(text) : text;
    }

    private static String skinTexture(ServerPlayer player) {
        try {
            for (var property : player.getGameProfile().properties().get("textures")) {
                var json = JsonParser.parseString(new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8)).getAsJsonObject();
                String url = json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
                return url.substring(url.lastIndexOf('/') + 1);
            }
        } catch (RuntimeException ignored) {
            // Offline/Bedrock accounts may not expose a Java skin texture; UUID/name URLs still work.
        }
        return "";
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static final class Payload {
        final WebhookMessages.Batch messages;
        final String username;
        final String avatar;
        final long thread;
        final UUID sender;

        Payload(List<MessageCreateData> messages, String username, String avatar, long thread, UUID sender) {
            this.messages = new WebhookMessages.Batch(messages);
            this.username = username;
            this.avatar = avatar;
            this.thread = thread;
            this.sender = sender;
        }
    }

    private final class Transport implements WebhookDelivery.Transport<Webhook, Payload> {
        @Override
        public CompletableFuture<Webhook> acquire(long channelId) {
            if (!(getJDA().getGuildChannelById(channelId) instanceof IWebhookContainer channel))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported webhook channel"));
            String name = WebhookText.username(Configuration.instance().webhook.webhookName, "MC_DC_INTEGRATION", new WebhookSettings());
            return channel.retrieveWebhooks().submit().thenCompose(hooks -> {
                for (Webhook webhook : hooks) {
                    if (name.equals(webhook.getName()) && webhook.getToken() != null && webhook.getOwnerAsUser() != null
                            && webhook.getOwnerAsUser().getIdLong() == getJDA().getSelfUser().getIdLong())
                        return CompletableFuture.completedFuture(webhook);
                }
                return channel.createWebhook(name).submit();
            });
        }

        @Override
        public CompletableFuture<Long> send(Webhook webhook, Payload payload) {
            return payload.messages.send(message -> {
                var action = webhook.sendMessage(message).setUsername(payload.username).setAvatarUrl(payload.avatar);
                if (payload.thread != 0) action.setThreadId(payload.thread);
                return action.submit().thenApply(sent -> {
                    rememberRecentMessage(sent.getIdLong(), payload.sender);
                    return sent.getIdLong();
                });
            });
        }

        @Override
        public boolean isUnknownWebhook(Throwable error) {
            return unwrap(error) instanceof ErrorResponseException response
                    && (response.getErrorResponse() == ErrorResponse.UNKNOWN_WEBHOOK || response.getResponse().code == 404);
        }
    }
}
