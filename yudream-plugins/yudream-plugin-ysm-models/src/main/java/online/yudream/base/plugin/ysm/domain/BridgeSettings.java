package online.yudream.base.plugin.ysm.domain;

import java.net.URI;

/** Administrator-only connection settings. Never include this in personal upload policy responses. */
public record BridgeSettings(String publicBaseUrl, String bridgeToken) {
    public BridgeSettings {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        bridgeToken = bridgeToken == null ? "" : bridgeToken;
    }
    public BridgeSettings validated() {
        URI uri;
        try { uri = URI.create(publicBaseUrl); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("网站地址需要为有效的 HTTP(S) 根地址"); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || publicBaseUrl.length() > 1900)
            throw new IllegalArgumentException("网站地址需要为 HTTP(S) 根地址，不能包含账号、查询参数或片段");
        if (bridgeToken.length() < 32 || bridgeToken.length() > 256
                || bridgeToken.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("桥接密钥需要为 32–256 个可打印英文字符，不能包含空格");
        return this;
    }
    @Override public String toString() { return "BridgeSettings[publicBaseUrl=" + publicBaseUrl + ", bridgeToken=<redacted>]"; }
}
