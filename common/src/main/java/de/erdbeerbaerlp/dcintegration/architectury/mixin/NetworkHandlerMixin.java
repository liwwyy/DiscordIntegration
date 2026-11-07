package de.erdbeerbaerlp.dcintegration.architectury.mixin;


import de.erdbeerbaerlp.dcintegration.architectury.DiscordIntegrationMod;
import de.erdbeerbaerlp.dcintegration.architectury.webhook.DisconnectMessages;
import de.erdbeerbaerlp.dcintegration.architectury.webhook.PlayerWebhookIntegration;
import de.erdbeerbaerlp.dcintegration.architectury.util.MessageUtilsImpl;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.Localization;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.TextColors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

import static de.erdbeerbaerlp.dcintegration.common.DiscordIntegration.INSTANCE;

@Mixin(value = ServerGamePacketListenerImpl.class)
public class NetworkHandlerMixin {
    @Shadow
    public ServerPlayer player;

    /**
     * Handle possible timeout
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onDisconnect(DisconnectionDetails disconnectionDetails, CallbackInfo ci) {
        final Component reason = disconnectionDetails.reason();
        if (DiscordIntegrationMod.stopped) return; //Try to fix player leave messages after stop!
        if (INSTANCE == null) return;
        if(INSTANCE.getServerInterface().isPlayerVanish(player.getUUID())) return;
        if (LinkManager.isPlayerLinked(player.getUUID()) && LinkManager.getLink(null, player.getUUID()).settings.hideFromDiscord) {
            return;
        }
        final String reasonKey = reason.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
        final String playerName = MessageUtilsImpl.formatPlayerName(player);
        final var settings = PlayerWebhookIntegration.settings();
        final String leaveMessage = DisconnectMessages.format(reasonKey, reason.getString(), playerName,
                Localization.instance().playerLeave, Localization.instance().playerTimeout, settings);
        INSTANCE.callEventC((a)->a.onPlayerLeave(player.getUUID()));
        if (leaveMessage.isBlank()) return;
        final String avatarURL = INSTANCE.getSkinURL().replace("%uuid%", player.getUUID().toString()).replace("%uuid_dashless%", player.getUUID().toString().replace("-", "")).replace("%name%", player.getName().getString()).replace("%randomUUID%", UUID.randomUUID().toString());
        if (Configuration.instance().embedMode.enabled && Configuration.instance().embedMode.playerLeaveMessages.asEmbed) {
            final EmbedBuilder builder;
            if (!Configuration.instance().embedMode.playerLeaveMessages.customJSON.isBlank()) {
                builder = Configuration.instance().embedMode.playerLeaveMessages.toEmbedJson(Configuration.instance().embedMode.playerLeaveMessages.customJSON
                        .replace("%uuid%", player.getUUID().toString())
                        .replace("%uuid_dashless%", player.getUUID().toString().replace("-", ""))
                        .replace("%name%", playerName)
                        .replace("%randomUUID%", UUID.randomUUID().toString())
                        .replace("%avatarURL%", avatarURL)
                        .replace("%playerColor%", "" + TextColors.generateFromUUID(player.getUUID()).getRGB()));
                // A custom embed may hard-code "left"; override its description for special disconnects.
                if (DisconnectMessages.isTimeout(reasonKey) || settings.showDisconnectReason)
                    builder.setDescription(leaveMessage);
            } else {
                builder = Configuration.instance().embedMode.playerLeaveMessages.toEmbed()
                        .setAuthor(playerName, null, avatarURL).setDescription(leaveMessage);
            }
            PlayerWebhookIntegration.sendPlayerEvent(player, new DiscordMessage(builder.build()), INSTANCE.getChannel(Configuration.instance().advanced.serverChannelID));
        } else {
            PlayerWebhookIntegration.sendPlayerEvent(player, leaveMessage, INSTANCE.getChannel(Configuration.instance().advanced.serverChannelID));
        }
    }
}
