package net.mysterria.cosmos.toolkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends Discord webhook notifications (embeds + optional role ping) for incursion events.
 * Supports multiple simultaneous webhooks (e.g. one channel per language).
 */
public class DiscordToolkit {

    private final CosmosIncursion plugin;
    private final HttpClient httpClient;

    public DiscordToolkit(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Sends the "incursion starting" notification to every enabled webhook, substituting
     * %countdown% and %zones% placeholders in each webhook's configured title/description.
     * Called exactly once per event start (from EventManager.onEnterStarting).
     */
    public void sendEventStarting(int countdownSeconds, int zoneCount) {
        CosmosConfig config = plugin.getConfigLoader().getConfig();

        if (!config.isDiscordEnabled()) {
            return;
        }

        for (CosmosConfig.DiscordWebhookConfig webhook : config.getDiscordWebhooks()) {
            if (!webhook.enabled()) {
                continue;
            }
            if (webhook.webhookUrl() == null || webhook.webhookUrl().isBlank()) {
                plugin.log("Discord webhook '" + webhook.name() + "' is enabled but has no webhook-url, skipping");
                continue;
            }

            String title = replacePlaceholders(webhook.title(), countdownSeconds, zoneCount);
            String description = replacePlaceholders(webhook.description(), countdownSeconds, zoneCount);
            int color = parseColor(webhook.color());

            String payload = buildPayload(title, description, webhook.imageUrl(), webhook.pingRoleId(), color);
            send(webhook.name(), webhook.webhookUrl(), payload);
        }
    }

    private String replacePlaceholders(String text, int countdownSeconds, int zoneCount) {
        if (text == null) {
            return "";
        }
        return text
                .replace("%countdown%", String.valueOf(countdownSeconds))
                .replace("%zones%", String.valueOf(zoneCount));
    }

    private int parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return 0xFF0000;
        }
        try {
            return Integer.parseInt(hex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return 0xFF0000;
        }
    }

    private String buildPayload(String title, String description, String imageUrl, String pingRoleId, int color) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);

        if (imageUrl != null && !imageUrl.isBlank()) {
            JsonObject image = new JsonObject();
            image.addProperty("url", imageUrl);
            embed.add("image", image);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject root = new JsonObject();
        if (pingRoleId != null && !pingRoleId.isBlank()) {
            root.addProperty("content", "<@&" + pingRoleId + ">");

            JsonObject allowedMentions = new JsonObject();
            JsonArray roles = new JsonArray();
            roles.add(pingRoleId);
            allowedMentions.add("roles", roles);
            root.add("allowed_mentions", allowedMentions);
        }
        root.add("embeds", embeds);

        return root.toString();
    }

    private void send(String webhookName, String webhookUrl, String payload) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
        } catch (IllegalArgumentException e) {
            plugin.log("Discord webhook '" + webhookName + "' has an invalid URL: " + e.getMessage());
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        plugin.log("Discord webhook '" + webhookName + "' request failed: " + throwable.getMessage());
                        return;
                    }
                    if (response.statusCode() >= 300) {
                        plugin.log("Discord webhook '" + webhookName + "' returned status " + response.statusCode() + ": " + response.body());
                    }
                });
    }

}
