package net.mysterria.cosmos.command;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fallback trail for staff commands: one OBSERVED row per {@code /cosmos} admin invocation, whatever
 * the handler does afterwards. Players, console and RCON all pass through here ({@code
 * RemoteServerCommandEvent} extends {@code ServerCommandEvent}). It reads only the command line, then
 * queues the row; it adds no scheduler hop, lookup or IO. The sender's permission is not checked here
 * (the handlers enforce {@code cosmos.admin}), so refused attempts are recorded too.
 */
public class AdminCommandAuditListener implements Listener {

    private static final int MAX_ARGUMENTS_LENGTH = 512;
    // /cosmos balance, shop, leaderboard and the guide are open to players, so they are not staff commands.
    private static final Set<String> ADMIN_SUBCOMMANDS = Set.of("admin", "exclusion", "status", "help");

    private final CosmosIncursion plugin;

    public AdminCommandAuditListener(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        observe(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        observe(event.getSender(), event.getCommand());
    }

    private void observe(CommandSender sender, String line) {
        if (line == null) return;
        String text = line.strip();
        if (text.startsWith("/")) text = text.substring(1);

        String[] parts = text.split("\\s+", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        if (!label.equals("cosmos") || parts.length < 2) return;

        String arguments = parts[1].strip();
        String subcommand = arguments.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!ADMIN_SUBCOMMANDS.contains(subcommand)) return;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("command", "cosmos");
        row.put("subcommand", subcommand);
        row.put("arguments", arguments.length() > MAX_ARGUMENTS_LENGTH
                ? arguments.substring(0, MAX_ARGUMENTS_LENGTH) : arguments);
        MysterriaAuditEmitter.emitAdmin(plugin, "admin.command_observed", AuditOutcome.OBSERVED,
                AuditRisk.NORMAL, sender, null, null, row);
    }
}
