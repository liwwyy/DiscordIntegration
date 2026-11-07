package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import java.net.URI;

/** Normalize saved configs as well as fresh defaults; skin fetching always uses Crafthead. */
public final class SkinAvatars {
    public static final String DEFAULT = "https://crafthead.net/helm/%name%/128#%randomUUID%";
    private SkinAvatars() { }

    public static void normalizeCoreConfig() {
        var webhook = de.erdbeerbaerlp.dcintegration.common.storage.Configuration.instance().webhook;
        webhook.playerAvatarURL = template(webhook.playerAvatarURL);
        webhook.fallbackAvatarURL = template(webhook.fallbackAvatarURL);
    }

    public static String template(String configured) {
        try {
            // Placeholders aren't valid URI path characters; inspect only the authority.
            String authority = configured.split("/", 4)[2];
            URI origin = URI.create("https://" + authority);
            if ("crafthead.net".equalsIgnoreCase(origin.getHost()) && origin.getUserInfo() == null
                    && configured.startsWith("https://")) return configured;
        } catch (RuntimeException ignored) { }
        return DEFAULT;
    }
}
