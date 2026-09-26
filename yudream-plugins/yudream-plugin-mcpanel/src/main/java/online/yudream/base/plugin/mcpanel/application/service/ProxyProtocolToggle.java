package online.yudream.base.plugin.mcpanel.application.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROXY protocol 一键开关（单端口入口配套）：入口（mc-router）转发时会把真实玩家 IP 以
 * HAProxy PROXY protocol 头传下来，实例侧必须显式允许接收，否则玩家 IP 会显示为入口机地址。
 *
 * <p>策略是<b>只翻转已存在的键</b>：不新建文件、不凭空拼键名/缩进——配置文件的键由服务端版本
 * 自己生成（Paper 首次启动产出 config/paper-global.yml），面板只改那一行的值，注释与格式原样保留。
 * 键不存在时给出明确提示（先启动一次生成默认配置，或手动配置）。
 *
 * <p>各端键位：
 * <ul>
 *   <li>Paper 系（paper/purpur/folia）：{@code config/paper-global.yml} → {@code proxies.proxy-protocol}</li>
 *   <li>Velocity：{@code velocity.toml} → {@code haproxy-protocol}（TOML 等号语法）</li>
 *   <li>BungeeCord：{@code config.yml} → {@code proxy_protocol}（listener 内）</li>
 * </ul>
 */
public final class ProxyProtocolToggle {

    /** 配置目标：文件路径 + 键名（section 非空时要求键位于该顶层段内）。 */
    public record Target(String path, String key, String section) {
    }

    private ProxyProtocolToggle() {
    }

    /** 按实例类型取目标；返回 null = 该类型不支持（原版/模组端/基岩等无需 PROXY protocol）。 */
    public static Target targetOf(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase();
        return switch (normalized) {
            case "paper", "purpur", "folia" -> new Target("config/paper-global.yml", "proxy-protocol", "proxies");
            case "velocity" -> new Target("velocity.toml", "haproxy-protocol", null);
            case "bungee" -> new Target("config.yml", "proxy_protocol", null);
            default -> null;
        };
    }

    public static boolean supported(String kind) {
        return targetOf(kind) != null;
    }

    /** 读取当前开关值；null = 配置里没有该键（尚未生成或版本不支持）。 */
    public static Boolean current(String content, Target target) {
        Matcher matcher = locate(content, target);
        if (matcher == null) {
            return null;
        }
        String value = matcher.group(2).trim().toLowerCase();
        if (value.startsWith("true") || value.startsWith("\"true\"") || value.startsWith("'true'")) {
            return true;
        }
        if (value.startsWith("false") || value.startsWith("\"false\"") || value.startsWith("'false'")) {
            return false;
        }
        return null;
    }

    /**
     * 翻转开关值：只替换值本身，保留缩进、行尾注释与文件其余内容。
     *
     * @return 修改后的文本；已是目标值时原样返回
     * @throws IllegalArgumentException 配置里找不到该键（附带可操作提示）
     */
    public static String apply(String content, Target target, boolean enabled) {
        Matcher matcher = locate(content, target);
        if (matcher == null) {
            throw new IllegalArgumentException("配置 " + target.path() + " 里没有 " + target.key()
                    + " 键：请先启动一次实例生成默认配置（或在该文件里手动添加后重试）");
        }
        Boolean value = current(content, target);
        if (Boolean.valueOf(enabled).equals(value)) {
            return content;
        }
        return content.substring(0, matcher.start())
                + matcher.group(1) + (enabled ? "true" : "false") + matcher.group(3)
                + content.substring(matcher.end());
    }

    /**
     * 定位键所在行：{@code group(1)} 保留「缩进 + 键名 + 冒号 + 空格」，{@code group(2)} 原值，
     * {@code group(3)} 行尾（注释等）。指定 section 时只在该顶层段内部查找。
     */
    private static Matcher locate(String content, Target target) {
        if (content == null || content.isBlank()) {
            return null;
        }
        // 行首缩进 + 键名 + 分隔符（YAML 冒号 / TOML 等号）+ 值 + 行尾（保留注释）
        Pattern pattern = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(target.key())
                + "[ \\t]*(?::|=)[ \\t]*)(\\S+)([^\\r\\n]*)$");
        if (target.section() == null || target.section().isBlank()) {
            Matcher matcher = pattern.matcher(content);
            return matcher.find() ? matcher : null;
        }
        // 顶层段：YAML `proxies:` 或 TOML `[proxies]`，到下一个顶层键（行首非空白）为止
        Pattern sectionStart = Pattern.compile("(?m)^(?:[ \\t]*" + Pattern.quote(target.section())
                + "[ \\t]*:|\\[[ \\t]*" + Pattern.quote(target.section()) + "[ \\t]*\\])[ \\t]*$");
        Matcher start = sectionStart.matcher(content);
        if (!start.find()) {
            return null;
        }
        int from = start.end();
        Pattern nextTopLevel = Pattern.compile("(?m)^\\S");
        Matcher next = nextTopLevel.matcher(content);
        int to = content.length();
        if (next.find(from)) {
            to = next.start();
        }
        // 区域限制在段内查找：start()/end() 仍是全文坐标，替换时可直接用
        Matcher matcher = pattern.matcher(content);
        matcher.region(from, to);
        return matcher.find() ? matcher : null;
    }
}
