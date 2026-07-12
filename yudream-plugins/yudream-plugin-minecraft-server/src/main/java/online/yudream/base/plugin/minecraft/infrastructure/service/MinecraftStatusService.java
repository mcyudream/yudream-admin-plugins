package online.yudream.base.plugin.minecraft.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.lenni0451.mcping.MCPing;
import net.lenni0451.mcping.responses.BedrockPingResponse;
import net.lenni0451.mcping.responses.MCPingResponse;
import online.yudream.base.plugin.minecraft.domain.enumerate.MinecraftEdition;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftEndpointStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.Optional;

public class MinecraftStatusService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public MinecraftEndpointStatus ping(MinecraftServerEndpoint endpoint) {
        if (!endpoint.enabled()) {
            return MinecraftEndpointStatus.offline(endpoint.id(), "线路已停用");
        }
        try {
            if (endpoint.edition() == MinecraftEdition.BEDROCK) {
                BedrockPingResponse response = MCPing.pingBedrock()
                        .address(endpoint.host(), endpoint.port())
                        .timeout(3000, 3000)
                        .getSync();
                return MinecraftEndpointStatus.online(endpoint.id(), response.getOnlinePlayers(), response.getMaxPlayers(),
                        response.getVersionName(), response.getProtocolId(), response.getPing(), plainMotd(response.getMotd()), null);
            }
            ResolvedAddress address = resolveJavaAddress(endpoint);
            MCPingResponse response = MCPing.pingModern()
                    .address(address.host(), address.port())
                    .timeout(3000, 3000)
                    .getSync();
            return MinecraftEndpointStatus.online(endpoint.id(), response.getOnlinePlayers(), response.getMaxPlayers(),
                    response.getVersionName(), response.getProtocolId(), response.getPing(), plainMotd(response.getMotd()),
                    favicon(response.getFavicon()));
        } catch (RuntimeException e) {
            return MinecraftEndpointStatus.offline(endpoint.id(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String favicon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.regionMatches(true, 0, "data:image/png;base64,", 0, "data:image/png;base64,".length())
                ? normalized : null;
    }

    public static String plainMotd(String motd) {
        if (motd == null || motd.isBlank()) {
            return motd;
        }
        String value = motd.trim();
        if (value.startsWith("{") || value.startsWith("[")) {
            try {
                StringBuilder text = new StringBuilder();
                appendComponentText(JSON.readTree(value), text);
                if (!text.isEmpty()) {
                    value = text.toString();
                }
            } catch (Exception ignored) {
                // Some servers return malformed components; retain their original MOTD.
            }
        }
        return value.replace("\\u00a7", "§")
                .replaceAll("(?i)(?:§|&)[0-9A-FK-ORX]", "")
                .trim();
    }

    private static void appendComponentText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            text.append(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> appendComponentText(item, text));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode content = node.get("text");
        if (content != null && content.isValueNode()) {
            text.append(content.asText());
        }
        JsonNode extra = node.get("extra");
        if (extra != null) {
            appendComponentText(extra, text);
        }
    }

    private ResolvedAddress resolveJavaAddress(MinecraftServerEndpoint endpoint) {
        if (!endpoint.automaticPort()) {
            return new ResolvedAddress(endpoint.host(), endpoint.port());
        }
        return resolveSrv(endpoint.host()).orElseGet(() -> new ResolvedAddress(endpoint.host(), MinecraftEdition.JAVA.defaultPort()));
    }

    private Optional<ResolvedAddress> resolveSrv(String host) {
        if (host == null || host.isBlank() || isIpLiteral(host)) {
            return Optional.empty();
        }
        try {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            environment.put("com.sun.jndi.dns.timeout.initial", "1500");
            environment.put("com.sun.jndi.dns.timeout.retries", "1");
            Attributes attributes;
            InitialDirContext context = new InitialDirContext(environment);
            try {
                attributes = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            } finally {
                try {
                    context.close();
                } catch (NamingException ignored) {
                }
            }
            Attribute srv = attributes.get("SRV");
            if (srv == null || srv.size() == 0) {
                return Optional.empty();
            }
            SrvRecord selected = null;
            for (int i = 0; i < srv.size(); i++) {
                SrvRecord record = SrvRecord.parse(String.valueOf(srv.get(i)));
                if (record != null && better(record, selected)) {
                    selected = record;
                }
            }
            return selected == null ? Optional.empty() : Optional.of(new ResolvedAddress(selected.target(), selected.port()));
        } catch (NamingException e) {
            return Optional.empty();
        }
    }

    private boolean better(SrvRecord record, SrvRecord selected) {
        return selected == null
                || record.priority() < selected.priority()
                || record.priority() == selected.priority() && record.weight() > selected.weight();
    }

    private boolean isIpLiteral(String host) {
        String value = host.trim();
        return value.indexOf(':') >= 0 || value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private record ResolvedAddress(String host, int port) {
    }

    private record SrvRecord(int priority, int weight, int port, String target) {

        private static SrvRecord parse(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.trim().split("\\s+");
            if (parts.length < 4) {
                return null;
            }
            try {
                if (".".equals(parts[3])) {
                    return null;
                }
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new SrvRecord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), target);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
