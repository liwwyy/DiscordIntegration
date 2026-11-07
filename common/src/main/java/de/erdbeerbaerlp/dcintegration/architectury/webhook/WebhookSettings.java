package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import dcshadow.com.moandjiezana.toml.Toml;
import dcshadow.com.moandjiezana.toml.TomlComment;
import dcshadow.com.moandjiezana.toml.TomlWriter;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Companion config: the external core rewrites its own TOML and discards unknown keys. */
public final class WebhookSettings {
    @TomlComment({"Requires webhook.enable=true in Discord-Integration.toml.",
            "Overrides chat embeds only. Player events keep the server sender identity and show Crafthead heads in embed authors."})
    public boolean playerChat = true;
    @TomlComment("Show kick/error reasons in leave messages. False restores normal leave messages (timeouts still use playerTimeout).")
    public boolean showDisconnectReason = true;
    @TomlComment("Special disconnect message. Placeholders: %player%, %reason%. Timeouts use Messages.toml; normal disconnects show a short notice.")
    public String disconnectMessage = "%player% disconnected: %reason%";
    @TomlComment("Empty uses the core's playerAvatarURL (including its tested fallback). Supports old %name%/%uuid% placeholders too.")
    public String avatarUrl = "https://crafthead.net/helm/{username}/{size}#%randomUUID%";
    @TomlComment("Used for {size} in avatar URLs (16-512).")
    public int avatarSize = 128;
    public boolean useLinkedDiscordAvatar = false;
    public boolean useLinkedDiscordName = false;
    @TomlComment("Append the linked Discord account username, e.g. Steve (discorduser).")
    public boolean appendDiscordUsername = true;
    @TomlComment("Placeholders: {discord_username}, {discord_name}. Only applied to linked guild members.")
    public String discordAffix = " ({discord_username})";
    @TomlComment("Honor each linked player's useDiscordNameInChannel preference for Discord identity and affix.")
    public boolean respectLinkedPlayerSettings = false;
    public boolean translateMentions = true;
    public boolean translateEmotes = true;
    public boolean escapeMarkdown = true;
    @TomlComment("User pings default to the core's behavior. Role and everyone pings require explicit opt-in.")
    public boolean allowUserMentions = true;
    public boolean allowRoleMentions = false;
    public boolean allowEveryoneMentions = false;
    @TomlComment("Ordered Java regex/replacement rules, applied before translation. Empty content drops the message.")
    public List<Filter> usernameFilters = new ArrayList<>();
    public List<Filter> contentFilters = new ArrayList<>();

    public void validate() {
        if (avatarSize < 16 || avatarSize > 512) throw new IllegalArgumentException("avatarSize must be 16-512");
        if (disconnectMessage == null || avatarUrl == null || discordAffix == null || usernameFilters == null || contentFilters == null)
            throw new IllegalArgumentException("Webhook strings and filters cannot be null");
        if (!avatarUrl.isBlank() && !avatarUrl.startsWith("https://") && !avatarUrl.startsWith("http://"))
            throw new IllegalArgumentException("avatarUrl must be HTTP(S) or empty");
        usernameFilters.forEach(Filter::validate);
        contentFilters.forEach(Filter::validate);
    }

    public static final class Filter {
        public String regex = "";
        public String replacement = "";

        public Filter() { }
        public Filter(String regex, String replacement) {
            this.regex = regex;
            this.replacement = replacement;
        }
        private void validate() {
            if (regex == null || replacement == null) throw new IllegalArgumentException("Null regex or replacement");
            Pattern.compile(regex);
        }
    }

    public static final class Store {
        private WebhookSettings current = new WebhookSettings();
        private FileTime modified;
        private long size = -1;

        /** Reload edits on the next outgoing message, retaining the last good config on errors. */
        public synchronized WebhookSettings get() {
            Path path = DiscordIntegration.configFile.toPath().resolveSibling("Discord-Integration-Webhooks.toml");
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path.toAbsolutePath().getParent());
                    new TomlWriter().write(current, path.toFile());
                }
                FileTime timestamp = Files.getLastModifiedTime(path);
                long length = Files.size(path);
                if (!timestamp.equals(modified) || size != length) {
                    modified = timestamp;
                    size = length;
                    WebhookSettings next = new Toml().read(path.toFile()).to(WebhookSettings.class);
                    next.validate();
                    current = next;
                }
            } catch (Exception error) {
                DiscordIntegration.LOGGER.error("Cannot load webhook settings; retaining last valid settings: " + error.getClass().getSimpleName());
            }
            return current;
        }
    }
}
