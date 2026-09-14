package online.yudream.base.plugin.launcher.domain.valobj;

import java.util.Objects;

/**
 * 分发对账条目（协议 v2 §5.1）。发布时由 mrpack index + overrides 物化；
 * 客户端按 sha1 对账决定下载/删除。
 *
 * @param path   实例内相对路径（正斜杠），如 mods/jei-1.21.1.jar
 * @param sha1   内容 SHA-1 hex（小写）
 * @param size   字节数
 * @param tier   required（必装）| recommended（默认装）| optional（默认不装）
 * @param source 来源：managed（托管在 adapter，走 /v2/packs/{packId}/files/{sha1}）| url（直链）
 * @param env    环境语义同 mrpack index，缺省 client=required / server=optional
 */
public record LauncherEntry(
        String path,
        String sha1,
        long size,
        String tier,
        Source source,
        Env env
) {
    public static final String TIER_REQUIRED = "required";
    public static final String TIER_RECOMMENDED = "recommended";
    public static final String TIER_OPTIONAL = "optional";

    public LauncherEntry {
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("entry path 非法: " + path);
        }
        if (sha1 == null || !sha1.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("entry sha1 必须是 40 位小写十六进制: " + path);
        }
        if (tier == null || tier.isBlank()) {
            tier = TIER_REQUIRED;
        }
        if (source == null) {
            source = Source.managed();
        }
        if (env == null) {
            env = Env.defaults();
        }
    }

    public static LauncherEntry managed(String path, String sha1, long size) {
        return new LauncherEntry(path, sha1, size, TIER_REQUIRED, Source.managed(), Env.defaults());
    }

    public static LauncherEntry remote(String path, String sha1, long size, String url, Env env) {
        return new LauncherEntry(path, sha1, size, TIER_REQUIRED, new Source("url", url), env);
    }

    public record Source(String type, String url) {
        public Source {
            if (type == null || type.isBlank()) {
                type = "managed";
            }
            url = url == null ? "" : url;
        }

        public static Source managed() {
            return new Source("managed", "");
        }

        public boolean isManaged() {
            return "managed".equals(type);
        }
    }

    public record Env(String client, String server) {
        public Env {
            client = client == null || client.isBlank() ? "required" : client;
            server = server == null || server.isBlank() ? "optional" : server;
        }

        public static Env defaults() {
            return new Env("required", "optional");
        }
    }
}
