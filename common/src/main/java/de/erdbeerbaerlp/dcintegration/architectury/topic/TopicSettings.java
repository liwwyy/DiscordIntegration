package de.erdbeerbaerlp.dcintegration.architectury.topic;

import dcshadow.com.moandjiezana.toml.Toml;
import dcshadow.com.moandjiezana.toml.TomlWriter;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import java.nio.file.*;

public final class TopicSettings {
    public boolean ChannelTopicUpdaterChannelTopicsAtShutdownEnabled = true;
    public int ChannelTopicUpdaterRateInMinutes = 5;
    public String ChannelTopicUpdaterChatChannelTopicFormat = "%playercount%/%playermax% players online | %totalplayers% unique players ever joined | Server online for %uptimemins% minutes | Last update: %date%";
    public String ChannelTopicUpdaterCommandLogChannelTopicFormat = "TPS: %tps% | Mem: %usedmemorygb%GB used/%freememorygb%GB free/%maxmemorygb%GB max | %serverversion%";
    public String ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat = "Server is offline | %totalplayers% unique players ever joined";
    public String ChannelTopicUpdaterCommandLogChannelTopicAtServerShutdownFormat = "Server is offline | %serverversion%";

    public int intervalMinutes() { return Math.max(10, ChannelTopicUpdaterRateInMinutes); }
    public void validate() {
        if (ChannelTopicUpdaterChatChannelTopicFormat == null || ChannelTopicUpdaterCommandLogChannelTopicFormat == null
                || ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat == null || ChannelTopicUpdaterCommandLogChannelTopicAtServerShutdownFormat == null)
            throw new IllegalArgumentException("Topic formats cannot be null");
    }

    public static final class Store {
        private TopicSettings current = new TopicSettings();
        private String stamp = "";
        public TopicSettings get() {
            Path file = DiscordIntegration.configFile.toPath().resolveSibling("Discord-Integration-Topics.toml");
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                    new TomlWriter().write(current, file.toFile());
                }
                String nextStamp = Files.getLastModifiedTime(file) + ":" + Files.size(file);
                if (!nextStamp.equals(stamp)) {
                    stamp = nextStamp;
                    TopicSettings next = new Toml().read(file.toFile()).to(TopicSettings.class);
                    next.validate();
                    current = next;
                }
            } catch (Exception error) {
                DiscordIntegration.LOGGER.error("Cannot load topic config; retaining last valid settings: " + error.getClass().getSimpleName());
            }
            return current;
        }
    }
}
