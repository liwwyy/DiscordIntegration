# Player webhook chat (Minecraft 26.3)

Player chat can appear as messages authored by individual Minecraft players, using
plain Discord webhook `content`. Chat embeds, item embeds, and automatic URL previews
are suppressed for this path. Player-associated events keep the server webhook name/avatar and show player heads
in embed authors. Start/stop messages also keep the server identity. Event embed
settings remain available.

## Enable

In `config/Discord-Integration.toml`, enable the existing switch:

```toml
[webhook]
enable = true
```

Edit the existing `[webhook]` table rather than adding another one. Keep your bot token
and channel configuration as usual. The bot needs View Channel and Manage Webhooks in
each output channel. Existing `advanced.chatOutputChannelID` / `serverChannelID` /
`deathsChannelID` routing still applies. Text channels, announcement channels, and
existing channel threads are supported; threads share their parent's webhook.

Start the server once. It creates `config/Discord-Integration-Webhooks.toml` beside
the main config. This companion file is necessary because the separately distributed
core library rewrites its own TOML and discards unfamiliar fields. Edits to the
companion file are loaded on the next outgoing player message; invalid edits retain
the last valid settings and log an error. Main config changes use the existing reload
command. A restart picks up a changed managed `webhookName`.

The defaults use the Minecraft display name, Crafthead skin head, and, for linked
guild members, an affix: `Steve (discorduser)`. Unlinked users keep `Steve`.

## Identity and avatar settings

These keys are at the **top level** of `Discord-Integration-Webhooks.toml`:

```toml
playerChat = true
avatarUrl = "https://crafthead.net/helm/{username}/{size}#%randomUUID%"
avatarSize = 128
useLinkedDiscordAvatar = false
useLinkedDiscordName = false
appendDiscordUsername = true
discordAffix = " ({discord_username})"
respectLinkedPlayerSettings = false
translateMentions = true
translateEmotes = true
escapeMarkdown = true
allowUserMentions = true
allowRoleMentions = false
allowEveryoneMentions = false
```

- `playerChat = false` restores the previous chat format; `webhook.enable = false`
  restores normal bot delivery. A blank `discordChatMessage` in Messages.toml still
  disables outgoing chat. Permission checks, addon cancellation, vanish detection,
  linked-player `hideFromDiscord`, and character blacklists still apply.
- `avatarUrl` defaults to a Crafthead username head URL with a random UUID fragment.
  An empty value inherits the core URL, normalized to Crafthead. Only Crafthead URLs are
  used for Minecraft skins; linked Discord avatars remain independently configurable.
- URL placeholders: `{username}` is the account name, `{uuid}` is the dashed UUID,
  `{uuid-nodashes}` removes dashes, `{texture}` is the skin texture hash, and `{size}`
  is `avatarSize` (16–512). Existing `%name%`, `%uuid%`, `%uuid_dashless%`, and
  `%randomUUID%` placeholders also work. Template values are URL-encoded. Offline or
  Bedrock accounts may not have a Java skin texture; use a UUID/name-based URL for them.
- `useLinkedDiscordAvatar` uses the guild member's effective Discord avatar, including
  a server-specific avatar. `useLinkedDiscordName` uses their effective guild name.
  Each option is independent of the affix. If the linked user cannot be found in the
  destination guild, delivery falls back to the Minecraft identity.
- `appendDiscordUsername` adds `discordAffix` for linked members. The affix accepts
  `{discord_username}` (account username) and `{discord_name}` (effective guild name).
  Set `respectLinkedPlayerSettings = true` to suppress Discord identity and the affix
  when a player disables their existing `useDiscordNameInChannel` preference.
- Names are stripped of Minecraft formatting/control characters, filtered, and capped
  at 80 UTF-16 code units without splitting emoji. Empty names fall back to the player
  name. Discord-reserved words are normalized for delivery.
- Mention translation resolves cached guild usernames/effective names and role names
  written as `@name`, and text channels written as `#channel`. Custom emotes written as
  `:emote_name:` and standard emoji aliases such as `:smile:` are translated separately.
  Raw Discord mention/emote syntax remains valid. The existing
  `compatibility.disableParsingMentionsIngame` also disables mention translation.
- Allowed mentions are explicit on every chat payload: users by default, roles and
  `@everyone`/`@here` only when enabled here. Translation does not grant ping permission.
  With `escapeMarkdown = true`, chat markdown is escaped while Discord mention/emote
  tokens remain intact.

## Regex filters

Rules run in listed order, using Java regular expressions and Java replacement syntax
(`$1` for a captured group). Minecraft formatting is stripped before and after filtering.
A content filter that removes all text suppresses the message. The username filters
also apply to the linked name/affix. They do not rename server events.

Delete the generated `usernameFilters = []` and/or `contentFilters = []` line before
adding corresponding rule tables **at the end of the file**:

```toml
[[usernameFilters]]
regex = '\[VIP\]\s*'
replacement = ''

[[contentFilters]]
regex = '(?i)blockedword'
replacement = '[removed]'

[[contentFilters]]
regex = '^!private.*$'
replacement = ''
```

## Delivery and verification

One bot-owned webhook is discovered/created and cached per parent channel, using the
existing `webhook.webhookName`. Identity overrides belong to individual requests; they
do not edit the persistent webhook's identity. Queues preserve order while member
lookups, webhook creation, and Discord rate limits complete asynchronously. A 404 or
Unknown Webhook invalidates the cache and retries once with a discovered/new webhook.
Already delivered chunks are not resent. Other errors are logged without webhook
URLs/tokens; they do not switch to bot messages or block subsequent queued messages.

Regression tests run with `./gradlew :common:test`. They cover channel caching/order,
recovery/retry limits, formatting/filtering, username limits, URL expansion, TOML
round-tripping, and content-only payloads with controlled mentions.

For an end-to-end check with a test server and Discord channel:

1. Chat as two players. Check each message has that player's name/head, plain content,
   and no embed. Link one account and check its affix and optional Discord name/avatar.
2. Trigger join, leave, death, and advancement messages after player chat. Check the
   sending identity remains `webhook.serverName` / `serverAvatarURL`.
3. Delete the managed webhook in Discord, then send another chat message. Verify one
   replacement is created and the message appears once.
4. Test a filtered/empty message, long display name, emoji, `:custom_emote:`, user mention,
   and `@everyone` (which should not ping with defaults).
5. Disable `playerChat`, then disable the main `webhook.enable` switch, checking the
   previous webhook/chat-embed and bot delivery paths respectively.

Behavior reference: [DiscordSRV webhook delivery](https://deepwiki.com/DiscordSRV/DiscordSRV).
Discord API reference: [Execute Webhook](https://docs.discord.com/developers/resources/webhook#execute-webhook).

## Disconnect and kick reasons

Version 3.2.3 adds these top-level keys to `config/Discord-Integration-Webhooks.toml`.
For an existing file, add them before any `[[usernameFilters]]` / `[[contentFilters]]`
tables. Changes take effect on the next disconnect; no restart is needed.

```toml
showDisconnectReason = true
disconnectMessage = "%player% disconnected: %reason%"
```

Normal departures show `Steve left the server` while the toggle is enabled, and use
`playerLeave` from Messages.toml when disabled.
Timeouts are detected by Minecraft's translation key and use `playerTimeout`
(`Steve timed out.`), even if the reason component has styling. Other disconnects
and kicks use `disconnectMessage`, for example `Steve disconnected: Kicked for inactivity`.
Minecraft formatting is stripped from the reason and Discord markdown is escaped.

Set `showDisconnectReason = false` to restore normal leave text for kicks/errors;
timeouts retain their existing dedicated message. These options also work in bot
mode. Existing hidden/vanished-player and server-stop suppression still apply.
With leave embeds enabled, special disconnects replace the embed description with
the selected message, including in custom JSON embeds. The webhook sender remains the configured server identity.

## Player event icons and skin providers (3.2.6)

Player-associated event messages retain the configured server sender name and avatar.
Their embeds show the player's Crafthead head in `author.icon_url`; standard event text
is placed in `author.name` without a duplicate description. Plain event messages keep
the server identity. Player chat continues to use the player's head as the webhook
sender avatar, with optional linked Discord avatars.

All Minecraft skin paths now use Crafthead, including the shared core's periodic avatar
check and fallback provider. Existing non-Crafthead URLs are normalized at runtime, so
saved configs cannot silently retain Minotar, Crafatar, or Starlight. Crafthead path
customization remains available through `avatarUrl`, for example:
`https://crafthead.net/helm/{username}/{size}#%randomUUID%`.

With `showDisconnectReason = true`, a normal departure says `Steve left the server`.
Generic reasons containing `Disconnected` are recognized as normal. Timeouts and
meaningful kick/error reasons keep their dedicated messages. Turning the setting off
preserves the localized leave text.
