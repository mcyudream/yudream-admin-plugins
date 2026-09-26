package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户数据范围与配额（M7 骨架，M2 起接入实例访问判断）。
 * 成员关系经 PluginUserService 实时解析（部门/角色绑定），不复制清单。
 */
public class TenantScopeService {

    /** 成员解析端口：bootstrap 注入（宿主 PluginUserService 适配）。 */
    public interface MembershipResolver {
        List<String> departmentIdsOf(long userId);

        List<String> roleIdsOf(long userId);
    }

    private final SettingsService settings;
    private final MembershipResolver membership;
    private final Map<String, List<String>> tenantCache = new ConcurrentHashMap<>();

    public TenantScopeService(SettingsService settings, MembershipResolver membership) {
        this.settings = settings;
        this.membership = membership;
    }

    public boolean enabled() {
        return settings.load().tenancy() != null && settings.load().tenancy().enabled();
    }

    /** 平台管理员：持设置中任一平台角色即不受租户范围约束。 */
    public boolean isPlatformAdmin(long userId) {
        List<String> platform = settings.load().tenancy() == null
                ? List.of() : settings.load().tenancy().platformRoleIds();
        if (platform.isEmpty()) {
            return true;
        }
        return membership.roleIdsOf(userId).stream().anyMatch(platform::contains);
    }

    /** 用户可见租户集合（platformAdmin = null 表示全部）。 */
    public List<String> visibleTenants(long userId) {
        if (!enabled() || isPlatformAdmin(userId)) {
            return List.of("*");
        }
        return tenantCache.computeIfAbsent(String.valueOf(userId), key -> resolve(key));
    }

    private List<String> resolve(String userIdKey) {
        long userId = Long.parseLong(userIdKey);
        List<String> tenants = new java.util.ArrayList<>();
        for (Map<String, Object> tenant : allTenants()) {
            String bindType = String.valueOf(tenant.get("bindType"));
            String bindId = String.valueOf(tenant.get("bindId"));
            if ("dept".equals(bindType) && membership.departmentIdsOf(userId).contains(bindId)) {
                tenants.add(String.valueOf(tenant.get("id")));
            } else if ("role".equals(bindType) && membership.roleIdsOf(userId).contains(bindId)) {
                tenants.add(String.valueOf(tenant.get("id")));
            }
            Object admins = tenant.get("adminUserIds");
            if (admins instanceof List<?> list && list.stream().anyMatch(a -> String.valueOf(a).equals(userIdKey))) {
                tenants.add(String.valueOf(tenant.get("id")));
            }
        }
        return tenants;
    }

    private List<Map<String, Object>> allTenants() {
        // 租户实体数量有界（<100），直接读全量集合。
        return TenantStore.listAll();
    }

    /** 静态租户存储（bootstrap 注入文档适配）。 */
    public static final class TenantStore {

        private static List<Map<String, Object>> tenants = List.of();

        private TenantStore() {
        }

        public static void replace(List<Map<String, Object>> next) {
            tenants = List.copyOf(Objects.requireNonNull(next));
        }

        static List<Map<String, Object>> listAll() {
            return tenants;
        }
    }

    public boolean canAccessInstance(long userId, String instanceTenantId) {
        if (!enabled() || isPlatformAdmin(userId)) {
            return true;
        }
        if (instanceTenantId == null || instanceTenantId.isBlank()) {
            return false;
        }
        return visibleTenants(userId).contains(instanceTenantId);
    }

    /** 配额校验：创建/启动前调用；返回剩余额度描述（不足抛业务异常）。 */
    public void assertQuota(String tenantId, long deltaInstances, long deltaCpuMillis, long deltaMemoryMb) {
        if (!enabled() || tenantId == null || tenantId.isBlank()) {
            return;
        }
        Map<String, Object> quota = allTenants().stream()
                .filter(t -> String.valueOf(t.get("id")).equals(tenantId))
                .findFirst().map(t -> (Map<String, Object>) t.get("quota"))
                .orElse(null);
        if (quota == null) {
            return;
        }
        Long maxInstances = asLong(quota.get("maxInstances"));
        Long maxCpu = asLong(quota.get("maxCpuMillis"));
        Long maxMem = asLong(quota.get("maxMemoryMb"));
        Object expiresAt = quota.get("expiresAt");
        if (expiresAt instanceof Number expiry && expiry.longValue() > 0
                && expiry.longValue() < System.currentTimeMillis()) {
            throw new McpanelBusinessException("quota.expired", 409, "租户配额已过期，实例可停不可启");
        }
        QuotaUsage usage = UsageStore.usageOf(tenantId);
        if (maxInstances != null && usage.instances() + deltaInstances > maxInstances) {
            throw new McpanelBusinessException("quota.instances", 409, "实例数超出租户配额（上限 " + maxInstances + "）");
        }
        if (maxCpu != null && usage.cpuMillis() + deltaCpuMillis > maxCpu) {
            throw new McpanelBusinessException("quota.cpu", 409, "CPU 超出租户配额");
        }
        if (maxMem != null && usage.memoryMb() + deltaMemoryMb > maxMem) {
            throw new McpanelBusinessException("quota.memory", 409, "内存超出租户配额");
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public record QuotaUsage(long instances, long cpuMillis, long memoryMb) {
    }

    /** 用量由实例聚合实时求和（bootstrap 注入计算器）。 */
    public static final class UsageStore {

        private static java.util.function.Function<String, QuotaUsage> calculator = tenant -> new QuotaUsage(0, 0, 0);

        private UsageStore() {
        }

        public static void bind(java.util.function.Function<String, QuotaUsage> fn) {
            calculator = java.util.Objects.requireNonNull(fn);
        }

        static QuotaUsage usageOf(String tenantId) {
            return calculator.apply(tenantId);
        }
    }
}
