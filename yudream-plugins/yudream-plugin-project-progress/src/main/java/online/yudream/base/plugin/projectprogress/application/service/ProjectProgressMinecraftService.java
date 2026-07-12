package online.yudream.base.plugin.projectprogress.application.service;

import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftEvidence;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftPolicy;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftOnlineWindow;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

import java.util.List;
import java.util.Optional;

public class ProjectProgressMinecraftService {

    private static final String MINECRAFT_PLUGIN = "minecraft-server";
    private static final int SCAN_PAGE_SIZE = 200;

    private final FrameworkServices framework;
    private final PluginContext pluginContext;

    public ProjectProgressMinecraftService(FrameworkServices framework, PluginContext pluginContext) {
        this.framework = framework;
        this.pluginContext = pluginContext;
    }

    public boolean ready() {
        return minecraft().isPresent();
    }

    public ProjectMinecraftEvidence requireEvidence(ProjectMinecraftPolicy policy, String userId, long periodStart, long periodEnd) {
        if (policy == null || !policy.enabled()) {
            throw new IllegalArgumentException("该项目未启用 Minecraft 在线时长打卡");
        }
        PluginMinecraftPlayerActivity activity = matchActivity(policy.serverId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("未找到当前用户的 Minecraft 在线记录"));
        PluginMinecraftOnlineWindow window = minecraft()
                .flatMap(service -> service.minecraftOnlineWindow(policy.serverId(), activity.playerId(), periodStart, periodEnd))
                .orElseThrow(() -> new IllegalArgumentException("Minecraft activity events cannot calculate this check-in period"));
        long effective = policy.includeAfk() ? window.onlineMillis() : window.effectiveOnlineMillis();
        long requiredMillis = policy.requiredOnlineMinutes() * 60_000L;
        if (effective < requiredMillis) {
            throw new IllegalArgumentException("Minecraft 在线时长未达到自动打卡要求");
        }
        return new ProjectMinecraftEvidence(policy.serverId(), activity.playerId(), activity.playerName(),
                window.onlineMillis(), window.afkMillis(), effective, periodStart, periodEnd);
    }

    private Optional<PluginMinecraftPlayerActivity> matchActivity(String serverId, String userId) {
        PluginUserProfile user = userProfile(userId).orElse(null);
        String username = user == null ? "" : text(user.username());
        String nickname = user == null ? "" : text(user.nickname());
        return allActivities(serverId).stream()
                .filter(activity -> matches(activity, userId, username, nickname))
                .findFirst();
    }

    private boolean matches(PluginMinecraftPlayerActivity activity, String userId, String username, String nickname) {
        String playerId = text(activity.playerId());
        String playerName = text(activity.playerName());
        return playerId.equalsIgnoreCase(text(userId))
                || playerName.equalsIgnoreCase(text(userId))
                || (!username.isBlank() && playerName.equalsIgnoreCase(username))
                || (!nickname.isBlank() && playerName.equalsIgnoreCase(nickname));
    }

    private List<PluginMinecraftPlayerActivity> allActivities(String serverId) {
        PluginMinecraftService service = minecraft()
                .orElseThrow(() -> new IllegalArgumentException("Minecraft 服务插件未启用"));
        List<PluginMinecraftPlayerActivity> result = new java.util.ArrayList<>();
        int page = 1;
        while (true) {
            List<PluginMinecraftPlayerActivity> batch = service.minecraftPlayerActivities(serverId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private Optional<PluginMinecraftService> minecraft() {
        return pluginContext == null ? Optional.empty() : pluginContext.service(MINECRAFT_PLUGIN, PluginMinecraftService.class);
    }

    private Optional<PluginUserProfile> userProfile(String userId) {
        if (framework == null || framework.users() == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            return framework.users().findById(Long.parseLong(userId.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
