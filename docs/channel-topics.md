# Channel topics

The mod updates the **topic** of the existing chat-output and command-log channels.
It keeps channel names unchanged and works with bot or webhook message delivery.
The bot needs **View Channel** and **Manage Channels**. Use text or announcement
channels; threads do not have editable topics.

Start the server once to generate `config/Discord-Integration-Topics.toml`:

```toml
ChannelTopicUpdaterChannelTopicsAtShutdownEnabled = true
ChannelTopicUpdaterRateInMinutes = 5

ChannelTopicUpdaterChatChannelTopicFormat = "%playercount%/%playermax% players online | %totalplayers% unique players ever joined | Server online for %uptimemins% minutes | Last update: %date%"
ChannelTopicUpdaterCommandLogChannelTopicFormat = "TPS: %tps% | Mem: %usedmemorygb%GB used/%freememorygb%GB free/%maxmemorygb%GB max | %serverversion%"
ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat = "Server is offline | %totalplayers% unique players ever joined"
ChannelTopicUpdaterCommandLogChannelTopicAtServerShutdownFormat = "Server is offline | %serverversion%"
```

The chat target is `advanced.chatOutputChannelID` (`default` uses the main bot channel).
The log target is `commandLog.channelID`; `0` disables that target. If both point to
the same channel, the chat topic wins.

- Like DiscordSRV, the minimum live update interval is **10 minutes**; `5` becomes `10`.
  The first update runs after server startup. Discord may delay requests through rate limiting.
- An empty format disables updates for that channel/phase. Empty formats never clear a topic.
  Set both live formats empty to disable live updates. The shutdown switch independently
  controls offline topics.
- Settings are reread every 30 seconds and at shutdown. Invalid edits retain the last
  valid settings. Unchanged topics are skipped. Topics are capped at 1024 UTF-16 units
  without breaking emoji.
- Shutdown waits for pending writes before setting offline topics, with bounded waits
  before the Discord connection closes. A forced process kill cannot publish an offline topic.

## Placeholders

| Placeholder | Value |
| --- | --- |
| `%playercount%`, `%playermax%` | Current and maximum players (counts include hidden/vanished players) |
| `%totalplayers%` | Unique UUIDs seen in this world, seeded from existing player data/stats |
| `%uptimemins%`, `%uptimehours%` | Whole minutes/hours since this server startup |
| `%tps%` | Estimate from average tick time, capped at 20; not a rolling TPS sample |
| `%usedmemory%`, `%freememory%`, `%totalmemory%`, `%maxmemory%` | JVM memory in MiB; free is unused allocated heap |
| `%usedmemorygb%`, `%freememorygb%`, `%totalmemorygb%`, `%maxmemorygb%` | The same in GiB, with two decimal places |
| `%serverversion%` | Minecraft server version |
| `%motd%` | Server MOTD |
| `%date%`, `%time%` | Current date/time in the Minecraft process's local timezone |

Shutdown formats only expand `%totalplayers%`, `%serverversion%`, `%date%`, and `%time%`.
Other/unknown placeholders remain literal. Bukkit PlaceholderAPI is not available in this mod.

Unique-player history is stored as `dcintegration-topic-players.json` in the world folder.
Removing historical player files before installing this feature can make the initial count
lower; future joins remain tracked across restarts.

Behavior reference: [DiscordSRV ChannelTopicUpdater](https://github.com/DiscordSRV/DiscordSRV/blob/master/src/main/java/github/scarsz/discordsrv/objects/threads/ChannelTopicUpdater.java).
