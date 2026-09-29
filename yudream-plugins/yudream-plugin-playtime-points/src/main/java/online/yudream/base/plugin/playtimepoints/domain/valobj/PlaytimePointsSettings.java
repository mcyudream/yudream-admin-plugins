package online.yudream.base.plugin.playtimepoints.domain.valobj;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 结算设置。servers 为按服务器 ID 的覆盖规则，subServers 为按子服的覆盖规则（键为
 * {@code serverId::subServer}）；未配置的按上一级规则参与结算（服务器规则 → 默认权重 1、默认启用）。
 * weight 以字符串保存以保留十进制精度。
 *
 * <p>旧设置文档没有 subServers 字段，读出来就是空表，此时按子服取权重会退化成服务器规则，
 * 行为与改造前完全一致。</p>
 *
 * <h2>打卡积分联动（project-progress 软依赖）</h2>
 * <p>六个字段都是后加的可选能力，默认全部关闭/缺省，因此旧设置文档读出来仍然是「不发放打卡积分」，
 * 既有在线时长结算链路零变化：</p>
 * <ul>
 *   <li>{@code checkInRewardEnabled}：总开关，默认 false；关闭时不做任何拉取、也不注册实时回调；</li>
 *   <li>{@code checkInRewardPoints}：每次打卡的积分数，十进制字符串（默认 {@value #DEFAULT_CHECK_IN_REWARD_POINTS}）；</li>
 *   <li>{@code checkInRewardRealtime}：是否额外注册验收通过实时回调（默认 false，关闭时只靠定时拉取）；</li>
 *   <li>{@code checkInRewardProjectPoints}：按项目 ID 的金额覆盖，留空或未配置的项目回退全局金额；</li>
 *   <li>{@code checkInRewardMode}：计算方式，{@value #CHECK_IN_MODE_FIXED}（每次固定积分，默认）或
 *       {@value #CHECK_IN_MODE_HOURLY}（按时薪折算：有效在线小时数 × 每小时积分）；
 *       旧设置文档没有该键，读出来就是 {@value #CHECK_IN_MODE_FIXED}，行为与升级前完全一致；</li>
 *   <li>{@code checkInHourlyPoints}：按时薪折算时每小时的积分数，十进制字符串
 *       （默认 {@value #DEFAULT_CHECK_IN_HOURLY_POINTS}）；</li>
 *   <li>{@code checkInFixedPoints}：**没有时长的打卡**（图片/文件/定位等非 MC 打卡，打卡证据里
 *       {@code effectiveMillis = 0}）每次发放的积分数，十进制字符串
 *       （默认 {@value #DEFAULT_CHECK_IN_FIXED_POINTS}，即不发）。只在按时薪模式（{@value #CHECK_IN_MODE_HOURLY}）
 *       下作为「时长为 0」时的回退生效；固定模式下所有打卡都按 {@code checkInRewardPoints} 发放，
 *       该值不参与。旧设置文档没有该键，读出来就是 {@value #DEFAULT_CHECK_IN_FIXED_POINTS}，
 *       行为与 1.3.0 逐位一致。</li>
 * </ul>
 *
 * <p>{@code checkInRewardProjectPoints} 的语义随 {@code checkInRewardMode} 变化：固定模式是「每次金额」，
 * 时薪模式是「每小时积分」；留空/非法一律回退全局。项目覆盖只作用于**主口径**（FIXED 的每次金额 /
 * HOURLY 的每小时时薪），{@code checkInFixedPoints} 是全局值，不参与项目覆盖。</p>
 */
public record PlaytimePointsSettings(
        boolean enabled,
        String assetCode,
        long minutesPerPoint,
        boolean subtractAfk,
        Map<String, ServerRule> servers,
        Map<String, ServerRule> subServers,
        boolean checkInRewardEnabled,
        String checkInRewardPoints,
        boolean checkInRewardRealtime,
        Map<String, String> checkInRewardProjectPoints,
        String checkInRewardMode,
        String checkInHourlyPoints,
        String checkInFixedPoints) {

    /** 子服规则的键分隔符：{@code serverId::subServer}。 */
    public static final String SUB_SERVER_SEPARATOR = "::";

    /** 打卡积分默认金额：每次打卡 1 分。 */
    public static final String DEFAULT_CHECK_IN_REWARD_POINTS = "1";

    /** 计算方式：每次固定积分（默认，与升级前一致）。 */
    public static final String CHECK_IN_MODE_FIXED = "FIXED";
    /** 计算方式：按时薪折算（积分 = 有效在线小时数 × 每小时积分）。 */
    public static final String CHECK_IN_MODE_HOURLY = "HOURLY";
    /** 按时薪折算的默认时薪：每小时 1 分。 */
    public static final String DEFAULT_CHECK_IN_HOURLY_POINTS = "1";
    /**
     * 非时长打卡（图片/文件/定位等没有打卡时长的打卡）每次发放的默认积分：0，即不发。
     *
     * <p>默认 0 而不是 1 是刻意的：该字段是按时薪模式下的回退项，旧设置文档读出来必须是 0，
     * 才能让「旧文档 + 按时薪模式」的行为与 1.3.0 完全一致（时长为 0 的打卡不发分）。</p>
     */
    public static final String DEFAULT_CHECK_IN_FIXED_POINTS = "0";

    public record ServerRule(String weight, boolean enabled) {

        /** 宽松解析：空/非法回退默认权重 1，负数按 0 处理（管理端保存时会先做严格校验）。 */
        public BigDecimal weightValue() {
            if (weight == null || weight.isBlank()) {
                return BigDecimal.ONE;
            }
            try {
                BigDecimal value = new BigDecimal(weight.trim());
                return value.signum() < 0 ? BigDecimal.ZERO : value;
            } catch (NumberFormatException e) {
                return BigDecimal.ONE;
            }
        }
    }

    public PlaytimePointsSettings {
        servers = copyRules(servers);
        subServers = copyRules(subServers);
        checkInRewardPoints = checkInRewardPoints == null || checkInRewardPoints.isBlank()
                ? DEFAULT_CHECK_IN_REWARD_POINTS
                : checkInRewardPoints.trim();
        checkInRewardProjectPoints = copyProjectPoints(checkInRewardProjectPoints);
        checkInRewardMode = normalizeMode(checkInRewardMode);
        checkInHourlyPoints = checkInHourlyPoints == null || checkInHourlyPoints.isBlank()
                ? DEFAULT_CHECK_IN_HOURLY_POINTS
                : checkInHourlyPoints.trim();
        // 非时长打卡积分默认 0（不发）；旧文档缺键读成空串，这里归一成 "0"。
        checkInFixedPoints = checkInFixedPoints == null || checkInFixedPoints.isBlank()
                ? DEFAULT_CHECK_IN_FIXED_POINTS
                : checkInFixedPoints.trim();
    }

    /** 计算方式归一化：空/未知一律回退 {@value #CHECK_IN_MODE_FIXED}（旧文档兼容）。 */
    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return CHECK_IN_MODE_FIXED;
        }
        String trimmed = mode.trim().toUpperCase(Locale.ROOT);
        return CHECK_IN_MODE_HOURLY.equals(trimmed) ? CHECK_IN_MODE_HOURLY : CHECK_IN_MODE_FIXED;
    }

    /** 只读副本：保留写入顺序并丢弃空规则，便于文档落库结果稳定。 */
    private static Map<String, ServerRule> copyRules(Map<String, ServerRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        Map<String, ServerRule> copy = new LinkedHashMap<>();
        rules.forEach((key, rule) -> {
            if (key != null && rule != null) {
                copy.put(key, rule);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /** 只读副本：丢弃空项目键与空金额，值去掉首尾空白。 */
    private static Map<String, String> copyProjectPoints(Map<String, String> points) {
        if (points == null || points.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        points.forEach((projectId, amount) -> {
            if (projectId == null || projectId.isBlank() || amount == null || amount.isBlank()) {
                return;
            }
            copy.put(projectId.trim(), amount.trim());
        });
        return Collections.unmodifiableMap(copy);
    }

    /** 兼容旧调用方的六参构造：没有子服规则，打卡积分联动关闭。 */
    public PlaytimePointsSettings(boolean enabled, String assetCode, long minutesPerPoint,
                                  boolean subtractAfk, Map<String, ServerRule> servers,
                                  Map<String, ServerRule> subServers) {
        this(enabled, assetCode, minutesPerPoint, subtractAfk, servers, subServers,
                false, DEFAULT_CHECK_IN_REWARD_POINTS, false, Map.of());
    }

    /**
     * 兼容新增「计算方式/时薪」之前的十参调用：等价于 {@value #CHECK_IN_MODE_FIXED} + 默认时薪
     * {@value #DEFAULT_CHECK_IN_HOURLY_POINTS}，即行为与升级前逐位一致。
     */
    public PlaytimePointsSettings(boolean enabled, String assetCode, long minutesPerPoint,
                                  boolean subtractAfk, Map<String, ServerRule> servers,
                                  Map<String, ServerRule> subServers, boolean checkInRewardEnabled,
                                  String checkInRewardPoints, boolean checkInRewardRealtime,
                                  Map<String, String> checkInRewardProjectPoints) {
        this(enabled, assetCode, minutesPerPoint, subtractAfk, servers, subServers, checkInRewardEnabled,
                checkInRewardPoints, checkInRewardRealtime, checkInRewardProjectPoints,
                CHECK_IN_MODE_FIXED, DEFAULT_CHECK_IN_HOURLY_POINTS);
    }

    /**
     * 兼容新增「非时长打卡积分」之前的十二参调用：非时长打卡积分取默认
     * {@value #DEFAULT_CHECK_IN_FIXED_POINTS}（即不发），行为与 1.3.0 逐位一致。
     */
    public PlaytimePointsSettings(boolean enabled, String assetCode, long minutesPerPoint,
                                  boolean subtractAfk, Map<String, ServerRule> servers,
                                  Map<String, ServerRule> subServers, boolean checkInRewardEnabled,
                                  String checkInRewardPoints, boolean checkInRewardRealtime,
                                  Map<String, String> checkInRewardProjectPoints, String checkInRewardMode,
                                  String checkInHourlyPoints) {
        this(enabled, assetCode, minutesPerPoint, subtractAfk, servers, subServers, checkInRewardEnabled,
                checkInRewardPoints, checkInRewardRealtime, checkInRewardProjectPoints, checkInRewardMode,
                checkInHourlyPoints, DEFAULT_CHECK_IN_FIXED_POINTS);
    }

    /** 兼容旧调用方的五参构造：没有子服规则。 */
    public PlaytimePointsSettings(boolean enabled, String assetCode, long minutesPerPoint,
                                  boolean subtractAfk, Map<String, ServerRule> servers) {
        this(enabled, assetCode, minutesPerPoint, subtractAfk, servers, Map.of());
    }

    public static PlaytimePointsSettings defaults() {
        return new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of());
    }

    public ServerRule ruleFor(String serverId) {
        return serverId == null ? null : servers.get(serverId);
    }

    /** 子服规则；未配置该子服时为 null（调用方再退化成服务器规则）。 */
    public ServerRule subRuleFor(String serverId, String subServer) {
        if (serverId == null || serverId.isBlank() || subServer == null || subServer.isBlank()) {
            return null;
        }
        return subServers.get(subServerKey(serverId.trim(), subServer.trim()));
    }

    public BigDecimal weightFor(String serverId) {
        ServerRule rule = ruleFor(serverId);
        return rule == null ? BigDecimal.ONE : rule.weightValue();
    }

    /** 子服权重：子服规则 > 服务器规则 > 默认 1。 */
    public BigDecimal weightFor(String serverId, String subServer) {
        ServerRule rule = subRuleFor(serverId, subServer);
        return rule == null ? weightFor(serverId) : rule.weightValue();
    }

    public boolean enabledFor(String serverId) {
        ServerRule rule = ruleFor(serverId);
        return rule == null || rule.enabled();
    }

    /** 子服是否参与结算：子服规则 > 服务器规则 > 默认启用。 */
    public boolean enabledFor(String serverId, String subServer) {
        ServerRule rule = subRuleFor(serverId, subServer);
        return rule == null ? enabledFor(serverId) : rule.enabled();
    }

    /** 归一化：货币代码转大写、去掉空白服务器键与非法子服键；打卡积分金额与项目覆盖一并规整。 */
    public PlaytimePointsSettings normalized() {
        String code = assetCode == null ? "" : assetCode.trim().toUpperCase();
        Map<String, ServerRule> normalized = new LinkedHashMap<>();
        servers.forEach((serverId, rule) -> {
            if (serverId != null && !serverId.isBlank() && rule != null) {
                normalized.put(serverId.trim(), rule);
            }
        });
        Map<String, ServerRule> normalizedSubs = new LinkedHashMap<>();
        subServers.forEach((key, rule) -> {
            String canonical = normalizeSubKey(key);
            if (canonical != null && rule != null) {
                normalizedSubs.put(canonical, rule);
            }
        });
        Map<String, String> normalizedProjectPoints = new LinkedHashMap<>();
        checkInRewardProjectPoints.forEach((projectId, amount) -> {
            if (projectId != null && !projectId.isBlank() && amount != null && !amount.isBlank()) {
                normalizedProjectPoints.put(projectId.trim(), amount.trim());
            }
        });
        return new PlaytimePointsSettings(enabled, code, minutesPerPoint, subtractAfk, normalized, normalizedSubs,
                checkInRewardEnabled, checkInRewardPoints, checkInRewardRealtime, normalizedProjectPoints,
                checkInRewardMode, checkInHourlyPoints, checkInFixedPoints);
    }

    /* ---------- 打卡积分 ---------- */

    /** 是否按时薪折算（{@value #CHECK_IN_MODE_HOURLY}）；其余一律按每次固定金额。 */
    public boolean checkInHourlyMode() {
        return CHECK_IN_MODE_HOURLY.equals(checkInRewardMode);
    }

    /** 全局打卡金额；空/非法时回退默认 1（管理端保存前会做严格校验，这里只兜底）。 */
    public BigDecimal checkInRewardPointsValue() {
        BigDecimal parsed = parsePoints(checkInRewardPoints);
        return parsed == null ? new BigDecimal(DEFAULT_CHECK_IN_REWARD_POINTS) : parsed;
    }

    /**
     * 某项目的打卡单次金额：**项目覆盖 > 全局**；项目覆盖为空/非法时回退全局。
     *
     * <p>返回 {@link BigDecimal#ZERO} 表示这条打卡不该发钱（金额为 0），调用方据此跳过钱包调用。</p>
     */
    public BigDecimal checkInRewardPointsFor(String projectId) {
        BigDecimal override = projectOverride(projectId);
        return override != null ? override : checkInRewardPointsValue();
    }

    /** 全局时薪（每小时积分）；空/非法时回退默认 1。 */
    public BigDecimal checkInHourlyPointsValue() {
        BigDecimal parsed = parsePoints(checkInHourlyPoints);
        return parsed == null ? new BigDecimal(DEFAULT_CHECK_IN_HOURLY_POINTS) : parsed;
    }

    /**
     * 某项目的时薪（每小时积分）：**项目覆盖 > 全局**；项目覆盖为空/非法时回退全局。
     *
     * <p>复用同一份 {@code checkInRewardProjectPoints} 覆盖表，语义随计算方式变化：
     * 固定模式是「每次金额」，时薪模式是「每小时积分」。</p>
     */
    public BigDecimal checkInHourlyPointsFor(String projectId) {
        BigDecimal override = projectOverride(projectId);
        return override != null ? override : checkInHourlyPointsValue();
    }

    /**
     * 本次生效的**费率**，语义随 {@link #checkInRewardMode()}：固定模式是每次打卡金额，
     * 时薪模式是每小时积分。项目覆盖在两种模式下都生效。
     *
     * <p>注意：这里只覆盖**主口径**。按时薪模式下「没有时长的打卡」用的
     * {@link #checkInFixedPointsValue()} 是全局值，不参与项目覆盖。</p>
     */
    public BigDecimal checkInRateFor(String projectId) {
        return checkInHourlyMode() ? checkInHourlyPointsFor(projectId) : checkInRewardPointsFor(projectId);
    }

    /**
     * 「没有时长的打卡」（图片/文件/定位等非 MC 打卡）每次发放的固定积分：**只有全局值**，
     * 不参与 {@code checkInRewardProjectPoints} 项目覆盖。
     *
     * <p>返回 {@link BigDecimal#ZERO} 表示这类打卡不发积分（配置为 0 或值非法）。
     * 空/非法值时回退 0 而不是 1：多发钱不可逆，少发只是留在流水里可排查。</p>
     */
    public BigDecimal checkInFixedPointsValue() {
        BigDecimal parsed = parsePoints(checkInFixedPoints);
        return parsed == null || parsed.signum() < 0 ? BigDecimal.ZERO : parsed;
    }

    /** 项目覆盖值；未配置/空白/非法时返回 null（调用方回退全局）。 */
    private BigDecimal projectOverride(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        return parsePoints(checkInRewardProjectPoints.get(projectId.trim()));
    }

    /** 是否有按项目的金额覆盖（供管理端展示）。 */
    public boolean hasProjectOverride(String projectId) {
        return projectId != null && !projectId.isBlank() && checkInRewardProjectPoints.containsKey(projectId.trim());
    }

    /** 宽松解析十进制金额：空/非法返回 null（调用方决定回退策略）。 */
    public static BigDecimal parsePoints(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 子服规则键：{@code serverId::subServer}。 */
    public static String subServerKey(String serverId, String subServer) {
        String left = serverId == null ? "" : serverId.trim();
        String right = subServer == null ? "" : subServer.trim();
        return left + SUB_SERVER_SEPARATOR + right;
    }

    /** {@code serverId::subServer} → 规范化键；缺少分隔符或任一段为空白时返回 null。 */
    public static String normalizeSubKey(String key) {
        if (key == null) {
            return null;
        }
        int at = key.indexOf(SUB_SERVER_SEPARATOR);
        if (at < 0) {
            return null;
        }
        String serverId = key.substring(0, at);
        String subServer = key.substring(at + SUB_SERVER_SEPARATOR.length());
        if (serverId.isBlank() || subServer.isBlank()) {
            return null;
        }
        return subServerKey(serverId, subServer);
    }
}
