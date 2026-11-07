package de.erdbeerbaerlp.dcintegration.architectury.webhook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisconnectMessagesTest {
    private final WebhookSettings settings = new WebhookSettings();
    private String format(String key, String reason) {
        return DisconnectMessages.format(key, reason, "Steve", "%player% left.", "%player% timed out.", settings);
    }
    @Test void normalLeaveKeepsExistingLocalization() {
        assertEquals("Steve left.", format("disconnect.disconnected", "Disconnected"));
        assertEquals("Steve left.", format("disconnect.endOfStream", "End of stream"));
        assertEquals("Steve left.", format("", "Disconnected"));
    }
    @Test void timeoutUsesTranslationKeyInsteadOfRenderedOrStyledText() {
        assertEquals("Steve timed out.", format("disconnect.timeout", "Délai dépassé"));
    }
    @Test void kickShowsReasonAndCanBeDisabled() {
        assertEquals("Steve disconnected: Kicked for inactivity", format("multiplayer.disconnect.idling", "Kicked for inactivity"));
        assertEquals("Steve disconnected: Custom reason", format("", "Custom reason"));
        settings.showDisconnectReason = false;
        assertEquals("Steve left.", format("", "Custom reason"));
        assertEquals("Steve timed out.", format("disconnect.timeout", "Timed out"));
    }
    @Test void configurableFormatStripsReasonFormattingAndHonorsSuppressedLeave() {
        settings.disconnectMessage = "%player% was kicked (%reason%).";
        assertEquals("Steve was kicked (Bad behavior).", format("", "§cBad behavior"));
        assertEquals("", DisconnectMessages.format("", "Kicked", "Steve", "", "", settings));
    }
}
