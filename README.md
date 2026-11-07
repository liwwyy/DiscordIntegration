# Discord-Integration
![Modrinth Downloads](https://img.shields.io/modrinth/dt/dcintegration?style=flat&logo=modrinth)

This mod links your server chat with a channel on your discord server.

Wiki: https://wiki.erdbeerbaerlp.de/dcintegration:root

[![Discord Server](https://discordapp.com/api/guilds/881880836377546824/widget.png?style=banner2)](https://erd.wtf/discord)


This Repository contains the NeoForge, Forge, and Fabric builds

## Building this fork

Use Java 25 and the included Gradle wrapper:

```sh
./gradlew build :fabric:shadowJar :neoforge:shadowJar
```

Installable JARs are written to `fabric/build/libs/` and `neoforge/build/libs/`.
Choose the JAR without a `-raw`, `-dev`, or `-sources` suffix.

GitHub Actions builds every push and pull request targeting `26.3`. Each successful
push to `26.3` in this fork automatically publishes a GitHub prerelease with both
loader JARs and SHA-256 checksums. You can also run **Build and release** manually
on `26.3`. Pull requests only build and upload workflow artifacts.

Downloads: https://github.com/liwwyy/DiscordIntegration/releases

## Player webhook chat

This fork supports DiscordSRV-style player names/skin heads, optional linked Discord
identities, and a configurable `Minecraft name (Discord username)` affix. Server events
keep the configured server webhook identity. See [setup and configuration](docs/webhook-chat.md).
