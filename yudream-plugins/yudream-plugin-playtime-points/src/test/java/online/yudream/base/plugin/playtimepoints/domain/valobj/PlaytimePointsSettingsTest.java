package online.yudream.base.plugin.playtimepoints.domain.valobj;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子服规则的解析优先级、降级与旧设置文档兼容。 */
class PlaytimePointsSettingsTest {

    private static final String SERVER = "srv-1";

    private static PlaytimePointsSettings.ServerRule rule(String weight, boolean enabled) {
        return new PlaytimePointsSettings.ServerRule(weight, enabled);
    }

    @Test
    void subServerRuleWinsOverServerRuleAndDefault() {
        Map<String, PlaytimePointsSettings.ServerRule> subServers = new LinkedHashMap<>();
        subServers.put("srv-1::fabric", rule("0.5", false));
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of(SERVER, rule("2", true)), subServers);

        // 子服规则优先
        assertEquals(0, settings.weightFor(SERVER, "fabric").compareTo(new BigDecimal("0.5")));
        assertFalse(settings.enabledFor(SERVER, "fabric"));
        // 子服未配置 → 服务器规则
        assertEquals(0, settings.weightFor(SERVER, "paper").compareTo(new BigDecimal("2")));
        assertTrue(settings.enabledFor(SERVER, "paper"));
        // 服务器也没配置 → 默认权重 1、默认启用
        assertEquals(0, settings.weightFor("srv-2", "paper").compareTo(BigDecimal.ONE));
        assertTrue(settings.enabledFor("srv-2", "paper"));
    }

    @Test
    void serverRuleStillAppliesWhenSubServerRuleIsAbsent() {
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of(SERVER, rule("2", false)), Map.of(SERVER + "::fabric", rule("3", true)));

        assertEquals(0, settings.weightFor(SERVER).compareTo(new BigDecimal("2")));
        assertFalse(settings.enabledFor(SERVER));
        assertEquals(0, settings.weightFor(SERVER, "fabric").compareTo(new BigDecimal("3")));
        assertTrue(settings.enabledFor(SERVER, "fabric"));
        // 未被任何规则覆盖的子服仍受服务器级「移出结算」约束
        assertFalse(settings.enabledFor(SERVER, "paper"));
    }

    /** 改造之前的设置文档没有 subServers 字段，读出来是空表，行为必须与升级前逐位一致。 */
    @Test
    void settingsWithoutSubServersBehaveExactlyLikeBefore() {
        PlaytimePointsSettings legacy = new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of(SERVER, rule("2", false)));

        assertTrue(legacy.subServers().isEmpty());
        assertEquals(0, legacy.weightFor(SERVER, "fabric").compareTo(new BigDecimal("2")));
        assertFalse(legacy.enabledFor(SERVER, "fabric"));
        assertEquals(0, legacy.weightFor(SERVER).compareTo(new BigDecimal("2")));
        assertFalse(legacy.enabledFor(SERVER));
        assertEquals(0, legacy.weightFor("srv-2").compareTo(BigDecimal.ONE));
        assertTrue(legacy.enabledFor("srv-2"));
    }

    @Test
    void normalizedTrimsSubServerKeysAndDropsInvalidOnes() {
        Map<String, PlaytimePointsSettings.ServerRule> subServers = new LinkedHashMap<>();
        subServers.put("  srv-1 :: fabric  ", rule("2", true));
        subServers.put("srv-1", rule("9", true));
        subServers.put("::fabric", rule("9", true));
        subServers.put("srv-1::   ", rule("9", true));
        subServers.put("srv-1::paper", null);

        PlaytimePointsSettings normalized = new PlaytimePointsSettings(true, "point", 60, true,
                Map.of(), subServers).normalized();

        assertEquals("POINT", normalized.assetCode());
        assertEquals(1, normalized.subServers().size());
        assertTrue(normalized.subServers().containsKey("srv-1::fabric"));
        assertEquals(0, normalized.weightFor("srv-1", "fabric").compareTo(new BigDecimal("2")));
    }

    @Test
    void subServerRuleLookupToleratesBlankInput() {
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                Map.of(SERVER + "::fabric", rule("2", true)));

        assertNull(settings.subRuleFor(null, "fabric"));
        assertNull(settings.subRuleFor(" ", "fabric"));
        assertNull(settings.subRuleFor(SERVER, null));
        assertNull(settings.subRuleFor(SERVER, "  "));
        assertNull(settings.subRuleFor(SERVER, "paper"));
        assertEquals(0, settings.subRuleFor(SERVER, " fabric ").weightValue().compareTo(new BigDecimal("2")));
        // 子服名本身带分隔符时按第一个分隔符切分，键可以往返
        assertEquals("srv-1::a::b", PlaytimePointsSettings.normalizeSubKey(" srv-1 :: a::b "));
    }

    @Test
    void weightValueStaysLenientForSubServerRules() {
        Map<String, PlaytimePointsSettings.ServerRule> subServers = new LinkedHashMap<>();
        subServers.put(SERVER + "::broken", rule("abc", true));
        subServers.put(SERVER + "::negative", rule("-3", true));
        subServers.put(SERVER + "::blank", rule("  ", true));
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), subServers);

        assertEquals(0, settings.weightFor(SERVER, "broken").compareTo(BigDecimal.ONE));
        assertEquals(0, settings.weightFor(SERVER, "negative").compareTo(BigDecimal.ZERO));
        assertEquals(0, settings.weightFor(SERVER, "blank").compareTo(BigDecimal.ONE));
    }

    /* ---------- 打卡积分联动 ---------- */

    /** 旧构造器（改造前的调用方）默认关闭打卡积分，行为与升级前一致。 */
    @Test
    void legacyConstructorsKeepCheckInRewardsDisabled() {
        PlaytimePointsSettings legacy = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of());

        assertFalse(legacy.checkInRewardEnabled());
        assertFalse(legacy.checkInRewardRealtime());
        assertEquals("1", legacy.checkInRewardPoints());
        assertTrue(legacy.checkInRewardProjectPoints().isEmpty());
        assertTrue(legacy.normalized().checkInRewardProjectPoints().isEmpty());
        // 新增的「计算方式/时薪」在旧构造器下取默认值，等价于升级前的固定金额行为
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED, legacy.checkInRewardMode());
        assertFalse(legacy.checkInHourlyMode());
        assertEquals("1", legacy.checkInHourlyPoints());
        assertTrue(legacy.normalized().checkInHourlyPoints().equals("1"));
    }

    /* ---------- 按时薪折算 ---------- */

    /** 计算方式归一化：空/未知/大小写混写；空/未知一律回退固定金额（旧文档兼容）。 */
    @Test
    void checkInRewardModeNormalizesAndFallsBackToFixed() {
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED,
                PlaytimePointsSettings.normalizeMode(null));
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED,
                PlaytimePointsSettings.normalizeMode("   "));
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED,
                PlaytimePointsSettings.normalizeMode("PER_HOUR"));
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_HOURLY,
                PlaytimePointsSettings.normalizeMode(" hourly "));

        PlaytimePointsSettings unknown = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(), "WHATEVER", "3");
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED, unknown.checkInRewardMode());
        assertFalse(unknown.checkInHourlyMode());
    }

    /** 时薪：空/非法回退默认 1；项目覆盖在时薪模式下是「每小时积分」。 */
    @Test
    void hourlyPointsPreferProjectOverrideThenGlobal() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("proj-1", "20");
        overrides.put("proj-2", "abc");
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, overrides, PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10");

        assertTrue(settings.checkInHourlyMode());
        assertEquals(0, settings.checkInHourlyPointsFor("proj-1").compareTo(new BigDecimal("20")));
        assertEquals(0, settings.checkInHourlyPointsFor("proj-2").compareTo(new BigDecimal("10")),
                "非法覆盖回退全局时薪");
        assertEquals(0, settings.checkInHourlyPointsFor("proj-9").compareTo(new BigDecimal("10")));
        // 费率随模式切换：同一个覆盖值在固定模式下是每次金额
        assertEquals(0, settings.checkInRateFor("proj-1").compareTo(new BigDecimal("20")));
        assertEquals(0, settings.checkInRateFor("proj-9").compareTo(new BigDecimal("10")));

        PlaytimePointsSettings fixed = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, overrides, PlaytimePointsSettings.CHECK_IN_MODE_FIXED, "10");
        assertEquals(0, fixed.checkInRateFor("proj-1").compareTo(new BigDecimal("20")),
                "固定模式下率就是每次金额");
        assertEquals(0, fixed.checkInRateFor("proj-9").compareTo(new BigDecimal("2")));

        // 时薪留空 → 默认 1（不会因为空值把发放卡死）
        PlaytimePointsSettings blank = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "   ");
        assertEquals("1", blank.checkInHourlyPoints());
        assertEquals(0, blank.checkInHourlyPointsFor("proj-1").compareTo(BigDecimal.ONE));
    }

    /** 归一化保留计算方式与时薪，并同步规整项目覆盖。 */
    @Test
    void normalizedKeepsModeAndHourlyPoints() {
        PlaytimePointsSettings normalized = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of("  proj-1  ", " 5 "),
                " hourly ", " 3.5 ").normalized();

        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, normalized.checkInRewardMode());
        assertEquals("3.5", normalized.checkInHourlyPoints());
        assertEquals(Map.of("proj-1", "5"), normalized.checkInRewardProjectPoints());
        assertEquals(0, normalized.checkInRateFor("proj-1").compareTo(new BigDecimal("5")));
    }

    /** 打卡金额：项目覆盖 > 全局 > 默认 1；覆盖为空/非法时回退全局。 */
    @Test
    void checkInRewardPointsPreferProjectOverrideThenGlobalThenDefault() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("proj-1", "5");
        overrides.put("proj-2", "  ");
        overrides.put("proj-3", "abc");
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", true, overrides);

        assertEquals(0, settings.checkInRewardPointsFor("proj-1").compareTo(new BigDecimal("5")));
        assertEquals(0, settings.checkInRewardPointsFor("proj-2").compareTo(new BigDecimal("2")),
                "空覆盖被丢弃，回退全局");
        assertEquals(0, settings.checkInRewardPointsFor("proj-3").compareTo(new BigDecimal("2")),
                "非法覆盖回退全局");
        assertEquals(0, settings.checkInRewardPointsFor("proj-9").compareTo(new BigDecimal("2")));
        assertEquals(0, settings.checkInRewardPointsFor(null).compareTo(new BigDecimal("2")));
        assertTrue(settings.hasProjectOverride("proj-1"));
        assertFalse(settings.hasProjectOverride("proj-9"));

        // 全局金额为空 → 默认 1
        PlaytimePointsSettings blank = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "   ", false, Map.of());
        assertEquals("1", blank.checkInRewardPoints());
        assertEquals(0, blank.checkInRewardPointsFor("proj-1").compareTo(BigDecimal.ONE));
    }

    /** 归一化：项目覆盖去掉首尾空白、丢弃空项目键与空金额。 */
    @Test
    void normalizedTrimsCheckInRewardProjectOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("  proj-1  ", " 5 ");
        overrides.put("   ", "9");
        overrides.put("proj-2", "");
        PlaytimePointsSettings normalized = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, " 2 ", true, overrides).normalized();

        assertEquals(Map.of("proj-1", "5"), normalized.checkInRewardProjectPoints());
        assertEquals("2", normalized.checkInRewardPoints());
    }

    /* ---------- 非时长打卡积分（按时薪模式下没有时长的打卡的回退项） ---------- */

    /** 旧构造器与旧设置文档：非时长打卡积分默认 "0"（不发），行为与 1.3.0 一致；空白/非法/负数也按 0 处理。 */
    @Test
    void checkInFixedPointsDefaultToZeroAndStayLenient() {
        PlaytimePointsSettings legacy = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of());
        assertEquals(PlaytimePointsSettings.DEFAULT_CHECK_IN_FIXED_POINTS, legacy.checkInFixedPoints());
        assertEquals(0, legacy.checkInFixedPointsValue().compareTo(BigDecimal.ZERO));

        // 十二参构造（新增该字段之前的调用方）同样回退 0
        PlaytimePointsSettings twelve = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10");
        assertEquals("0", twelve.checkInFixedPoints());
        assertEquals(0, twelve.checkInFixedPointsValue().compareTo(BigDecimal.ZERO));

        // 空白归一成 "0"
        assertEquals("0", hourlyWithFixed("   ").checkInFixedPoints());
        // 非法与负数按 0 处理（不发钱比多发给安全）
        assertEquals(0, hourlyWithFixed("abc").checkInFixedPointsValue().compareTo(BigDecimal.ZERO));
        assertEquals(0, hourlyWithFixed("-1").checkInFixedPointsValue().compareTo(BigDecimal.ZERO));
        // 合法值原样保留（含小数）
        assertEquals(0, hourlyWithFixed(" 3.50 ").checkInFixedPointsValue().compareTo(new BigDecimal("3.50")));
        assertEquals("3.50", hourlyWithFixed(" 3.50 ").checkInFixedPoints());
    }

    /** 非时长打卡积分是全局值：不参与项目覆盖，项目覆盖仍然只作用于主口径（时薪）。 */
    @Test
    void checkInFixedPointsIgnoreProjectOverrides() {
        PlaytimePointsSettings settings = new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of("proj-1", "20"),
                PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10", "3");

        assertEquals(0, settings.checkInFixedPointsValue().compareTo(new BigDecimal("3")));
        assertEquals(0, settings.checkInRateFor("proj-1").compareTo(new BigDecimal("20")),
                "项目覆盖仍只作用于主口径：时薪模式是每小时积分");
        assertEquals(0, settings.checkInRateFor("proj-9").compareTo(new BigDecimal("10")));
        // 归一化保留该字段（并去掉首尾空白）
        PlaytimePointsSettings normalized = new PlaytimePointsSettings(true, "point", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10", " 5 ").normalized();
        assertEquals("5", normalized.checkInFixedPoints());
        assertEquals("POINT", normalized.assetCode());
    }

    /** 构造按时薪折算 + 指定非时长打卡积分的设置。 */
    private static PlaytimePointsSettings hourlyWithFixed(String checkInFixedPoints) {
        return new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10", checkInFixedPoints);
    }
}
