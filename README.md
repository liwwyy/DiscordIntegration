# Discord Integration — Minecraft 26.3

Connect Minecraft server chat to Discord. This fork supports **Fabric and NeoForge**.

## Added features

- DiscordSRV-style player chat: plain webhook messages with player names and Crafthead skin heads.
- Optional linked Discord names/avatars and a configurable `Minecraft name (Discord username)` affix.
- Player event embeds show the player's head beside the event text, while the sender keeps the server's name/avatar.
- Normal departures say `Player left the server`; timeouts and kicks show specific reasons, with a toggle.
- Configurable regex filters, mention/emote translation, and automatic recovery for deleted webhooks.
- Automatic GitHub builds and prereleases containing both loader JARs and checksums.

## Setup

Enable `webhook.enable` in `config/Discord-Integration.toml`.
Additional options live in `config/Discord-Integration-Webhooks.toml`.
All Minecraft skin providers use Crafthead, including saved provider URLs and fallbacks.

[Configuration guide](docs/webhook-chat.md) · [Downloads](https://github.com/liwwyy/DiscordIntegration/releases) · [Upstream wiki](https://wiki.erdbeerbaerlp.de/dcintegration:root)

## Build

Requires Java 25:

```sh
./gradlew build :fabric:shadowJar :neoforge:shadowJar
```

JARs appear in `fabric/build/libs/` and `neoforge/build/libs/`.
Use the JAR without a `-raw`, `-dev`, or `-sources` suffix.

Forked from [meowora/DiscordIntegration](https://github.com/meowora/DiscordIntegration),
based on [ErdbeerbaerLP/DiscordIntegration](https://github.com/ErdbeerbaerLP/DiscordIntegration).
