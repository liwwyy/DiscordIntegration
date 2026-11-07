package de.erdbeerbaerlp.dcintegration.architectury.webhook;

/** Reason classification uses translation keys, independently of server language and component styling. */
public final class DisconnectMessages {
    private DisconnectMessages() { }

    public static boolean isTimeout(String key) {
        return "disconnect.timeout".equals(key);
    }

    public static boolean isNormal(String key, String reason) {
        // A normal client quit closes the TCP channel and reaches channelInactive as endOfStream.
        return "disconnect.endOfStream".equals(key) || "disconnect.disconnected".equals(key) || "disconnect.quitting".equals(key)
                || (key.isEmpty() && (reason.isBlank() || reason.equalsIgnoreCase("Disconnected")));
    }

    public static String format(String key, String reason, String player, String leave, String timeout, WebhookSettings settings) {
        String template = isTimeout(key) ? timeout : leave;
        if (settings.showDisconnectReason && !isTimeout(key) && !isNormal(key, reason) && !leave.isBlank()) {
            template = settings.disconnectMessage;
        }
        return template.replace("%reason%", WebhookText.escapeMarkdown(WebhookText.strip(reason)))
                .replace("%player%", player);
    }
}
