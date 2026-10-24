package online.yudream.base.plugin.cas.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.cas.application.dto.SsoSettingsDto;
import online.yudream.base.plugin.cas.application.service.AccessControlService;
import online.yudream.base.plugin.cas.application.service.SettingsService;
import online.yudream.base.plugin.cas.application.service.StudentInfoService;
import online.yudream.base.plugin.cas.domain.aggregate.AccessControl;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.cas.interfaces.request.AccessControlSaveRequest;
import online.yudream.base.plugin.cas.interfaces.request.OidcRegisterRequest;
import online.yudream.base.plugin.cas.interfaces.request.SettingsSaveRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CasSsoHttpFacade {

    private final SettingsService settings;
    private final StudentInfoService studentInfo;
    private final AccessControlService accessControl;

    public CasSsoHttpFacade(SettingsService settings, StudentInfoService studentInfo, AccessControlService accessControl) {
        this.settings = settings;
        this.studentInfo = studentInfo;
        this.accessControl = accessControl;
    }

    public PluginHttpResponse settings() {
        return PluginHttpResponse.ok(settings.view());
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        SettingsSaveRequest body = JsonSupport.read(request.body(), SettingsSaveRequest.class);
        SsoSettingsDto current = settings.view();
        SsoSettingsDto incoming = new SsoSettingsDto(
                body.enabled() == null ? current.enabled() : body.enabled(),
                firstNonBlank(body.protocol(), current.protocol()),
                firstNonBlank(body.displayName(), current.displayName()),
                firstNonBlank(body.icon(), current.icon()),
                firstNonBlank(body.casBaseUrl(), current.casBaseUrl()),
                firstNonBlank(body.loginPath(), current.loginPath()),
                firstNonBlank(body.validatePath(), current.validatePath()),
                firstNonBlank(body.oidcIssuer(), current.oidcIssuer()),
                firstNonBlank(body.oidcAuthorizePath(), current.oidcAuthorizePath()),
                firstNonBlank(body.oidcTokenPath(), current.oidcTokenPath()),
                firstNonBlank(body.oidcUserinfoPath(), current.oidcUserinfoPath()),
                firstNonBlank(body.oidcJwksPath(), current.oidcJwksPath()),
                firstNonBlank(body.oidcRegisterPath(), current.oidcRegisterPath()),
                body.clientId() == null ? current.clientId() : body.clientId().trim(),
                current.clientSecretConfigured(),
                firstNonBlank(body.scopes(), current.scopes()),
                firstNonBlank(body.callbackUrl(), current.callbackUrl()),
                body.loginWarmup() == null ? current.loginWarmup() : body.loginWarmup(),
                firstNonBlank(body.atrustWakeUrl(), current.atrustWakeUrl()),
                firstNonBlank(body.atrustGuideUrl(), current.atrustGuideUrl()),
                firstNonBlank(body.bridgeNotice(), current.bridgeNotice()),
                false
        );
        return PluginHttpResponse.ok(settings.save(incoming, body.clientSecret()));
    }

    public PluginHttpResponse test() {
        SsoProtocolClient.ConnectivityResult result = settings.test();
        return PluginHttpResponse.ok(Map.of("ok", result.ok(), "message", result.message()));
    }

    /**
     * 登录前预热页（公开、无权限注解）：点第三方登录后先落到本站这个页面。
     *
     * <p>部分前置网关对"首次、不带其会话 cookie（如 {@code route}）"的请求直接回 404，而同 URL 第二次访问
     * 就正常。这里先发一次跨站请求把该 cookie 种下来，再跳真正的认证地址；浏览器拦截第三方 cookie 时预热
     * 自然无效，页面仍会照常跳转（等价于原来的行为，用户再点一次即可）。
     *
     * <p>安全性：页面 URL 只带宿主签发的 {@code state}，真正的目标地址由服务端按当前配置重建，
     * 因此不存在可被外部利用的任意跳转目标。
     */
    public PluginHttpResponse warmup(PluginHttpRequest request) {
        String state = queryParam(request, "state");
        if (state == null) {
            return PluginHttpResponse.rawJson(400, Map.of("message", "缺少 state 参数"));
        }
        SsoSettings current = settings.current();
        String target = settings.client(current.protocol()).rawAuthorizationUrl(current, state);
        return new PluginHttpResponse(200,
                Map.of("Cache-Control", "no-store"),
                "text/html; charset=UTF-8",
                warmupPage(target, current.loginWarmup(), current.displayName(),
                        current.atrustWakeUrl(), current.atrustGuideUrl(), current.bridgeNotice()),
                false);
    }

    /**
     * 预热页 HTML：先跨站预热再 location.replace 到目标；任何异常都兜底跳转。
     *
     * <p>样式与站点保持一致：同一套中性色板（跟随系统深浅色）、居中卡片 + 环形进度，
     * 不引入任何外部资源（不依赖宿主的 CSS，也不会闪烁）。
     */
    static String warmupPage(String target, boolean warmup, String displayName,
                                     String atrustWakeUrl, String atrustGuideUrl, String bridgeNotice) {
        String preheat = warmup
                ? """
                  try {
                    var controller = new AbortController();
                    setTimeout(function () { controller.abort(); }, 1200);
                    fetch(target, { mode: 'no-cors', credentials: 'include', cache: 'no-store',
                      signal: controller.signal }).catch(function () {}).then(go);
                  } catch (e) { go(); }
                  """
                : "go();";
        String name = html(displayName);
        if (atrustGuideEnabled(atrustWakeUrl, bridgeNotice)) {
            return guidePage(target, preheat, name, atrustWakeUrl, atrustGuideUrl, bridgeNotice);
        }
        return autoPage(target, preheat, name);
    }

    private static boolean atrustGuideEnabled(String wakeUrl, String notice) {
        return (wakeUrl != null && !wakeUrl.isBlank()) || (notice != null && !notice.isBlank());
    }

    /** 默认预热页：自动预热并跳转（未配置 aTrust 唤醒链接时的行为，保持不变）。 */
    private static String autoPage(String target, String preheat, String name) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="referrer" content="no-referrer">
                  <meta name="color-scheme" content="light dark">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>正在前往@@NAME@@…</title>
                  <style>
                    :root {
                      color-scheme: light dark;
                      --yb-bg: #f2f3f5;
                      --yb-card: #ffffff;
                      --yb-text: #1d2129;
                      --yb-sub: #86909c;
                      --yb-border: #e5e6eb;
                      --yb-shadow: 0 4px 10px rgba(0, 0, 0, 0.06);
                    }
                    @media (prefers-color-scheme: dark) {
                      :root {
                        --yb-bg: #17171a;
                        --yb-card: #232324;
                        --yb-text: #f2f3f5;
                        --yb-sub: #86909c;
                        --yb-border: #333335;
                        --yb-shadow: 0 4px 10px rgba(0, 0, 0, 0.4);
                      }
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                      background: var(--yb-bg);
                      color: var(--yb-text);
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                        "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                      font-size: 14px;
                      line-height: 1.6;
                    }
                    .card {
                      width: 360px;
                      max-width: 100%;
                      padding: 28px 24px;
                      text-align: center;
                      background: var(--yb-card);
                      border: 1px solid var(--yb-border);
                      border-radius: 8px;
                      box-shadow: var(--yb-shadow);
                    }
                    .spinner {
                      width: 28px;
                      height: 28px;
                      margin: 0 auto 16px;
                      border: 2px solid var(--yb-border);
                      border-top-color: var(--yb-sub);
                      border-radius: 50%;
                      animation: yb-spin 0.9s linear infinite;
                    }
                    @keyframes yb-spin { to { transform: rotate(360deg); } }
                    @media (prefers-reduced-motion: reduce) {
                      .spinner { animation: none; }
                    }
                    .title { margin: 0 0 6px; font-size: 15px; font-weight: 600; }
                    .sub { margin: 0; color: var(--yb-sub); font-size: 13px; }
                    .fallback { margin: 18px 0 0; font-size: 12px; color: var(--yb-sub); }
                    .fallback a { color: inherit; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="spinner" role="status" aria-label="正在跳转"></div>
                    <p class="title">正在前往@@NAME@@</p>
                    <p class="sub">正在跳转到统一身份认证，请稍候…</p>
                    <p class="fallback">如果没有自动跳转，<a id="fallback" href="#">请点这里</a></p>
                  </div>
                  <script>
                  (function () {
                    var target = @@TARGET@@;
                    var done = false;
                    function go() {
                      if (done) { return; }
                      done = true;
                      location.replace(target);
                    }
                    var link = document.getElementById('fallback');
                    if (link) { link.setAttribute('href', target); }
                    @@PREHEAT@@
                    setTimeout(go, 1600);
                  })();
                  </script>
                </body>
                </html>
                """
                .replace("@@NAME@@", name)
                .replace("@@TARGET@@", jsString(target))
                .replace("@@PREHEAT@@", preheat);
    }

    /**
     * aTrust 引导页：配置了唤醒链接后，校外用户先唤醒/连接 aTrust，再手动继续登录。
     * 「继续登录」写入 localStorage 后回到自动跳转行为；跳转目标仍由服务端按 state 重建，无任意跳转参数。
     */
    private static String guidePage(String target, String preheat, String name,
                                            String atrustWakeUrl, String atrustGuideUrl, String bridgeNotice) {
        boolean hasGuideUrl = atrustGuideUrl != null && !atrustGuideUrl.isBlank();
        String guideLink = hasGuideUrl
                ? "<p class=\"fallback\"><a href=\"" + html(atrustGuideUrl)
                        + "\" target=\"_blank\" rel=\"noreferrer\">没有安装客户端？打开下载与帮助</a></p>"
                : "";
        // 自定义提示：整体 HTML 转义后按原文换行展示
        String notice = bridgeNotice == null || bridgeNotice.isBlank()
                ? ""
                : "<div class=\"notice\">" + html(bridgeNotice) + "</div>";
        // 深链协议直接以 <a href> 外露：唤起行为交给浏览器协议处理，比 JS location 更可靠，且地址可见可复制
        boolean hasWake = atrustWakeUrl != null && !atrustWakeUrl.isBlank();
        String wakeStep = hasWake
                ? "<div class=\"step\">"
                        + "<a id=\"wake\" class=\"btn\" href=\"" + html(atrustWakeUrl) + "\">① 唤醒认证客户端</a>"
                        + "<span class=\"hint\">若无反应，请先安装并登录校外认证客户端；链接无响应时可长按复制到客户端打开</span>"
                        + "</div>"
                : "";
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="referrer" content="no-referrer">
                  <meta name="color-scheme" content="light dark">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>正在前往@@NAME@@…</title>
                  <style>
                    :root {
                      color-scheme: light dark;
                      --yb-bg: #f2f3f5;
                      --yb-card: #ffffff;
                      --yb-text: #1d2129;
                      --yb-sub: #86909c;
                      --yb-border: #e5e6eb;
                      --yb-shadow: 0 4px 10px rgba(0, 0, 0, 0.06);
                      --yb-accent: #165dff;
                    }
                    @media (prefers-color-scheme: dark) {
                      :root {
                        --yb-bg: #17171a;
                        --yb-card: #232324;
                        --yb-text: #f2f3f5;
                        --yb-sub: #86909c;
                        --yb-border: #333335;
                        --yb-shadow: 0 4px 10px rgba(0, 0, 0, 0.4);
                        --yb-accent: #4080ff;
                      }
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                      background: var(--yb-bg);
                      color: var(--yb-text);
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                        "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                      font-size: 14px;
                      line-height: 1.6;
                    }
                    .card {
                      width: 380px;
                      max-width: 100%;
                      padding: 28px 24px;
                      text-align: center;
                      background: var(--yb-card);
                      border: 1px solid var(--yb-border);
                      border-radius: 8px;
                      box-shadow: var(--yb-shadow);
                    }
                    .title { margin: 0 0 6px; font-size: 15px; font-weight: 600; }
                    .sub { margin: 0 0 18px; color: var(--yb-sub); font-size: 13px; }
                    .step { display: grid; gap: 6px; margin: 0 0 14px; }
                    .step button {
                      width: 100%;
                      padding: 10px 12px;
                      font-size: 14px;
                      font-family: inherit;
                      border-radius: 6px;
                      border: 1px solid var(--yb-border);
                      background: var(--yb-card);
                      color: var(--yb-text);
                      cursor: pointer;
                    }
                    .step button.primary {
                      background: var(--yb-accent);
                      border-color: var(--yb-accent);
                      color: #fff;
                    }
                    .step button:hover, .step .btn:hover { filter: brightness(1.05); }
                    .step .hint { color: var(--yb-sub); font-size: 12px; }
                    .step .btn {
                      display: block;
                      padding: 10px 12px;
                      font-size: 14px;
                      text-align: center;
                      text-decoration: none;
                      border-radius: 6px;
                      border: 1px solid var(--yb-border);
                      background: var(--yb-card);
                      color: var(--yb-text);
                      word-break: break-all;
                    }
                    .notice {
                      margin: 0 0 14px;
                      padding: 10px 12px;
                      font-size: 13px;
                      line-height: 1.6;
                      text-align: left;
                      white-space: pre-wrap;
                      overflow-wrap: anywhere;
                      border: 1px solid var(--yb-border);
                      border-radius: 6px;
                      background: color-mix(in srgb, var(--yb-accent) 6%, var(--yb-card));
                    }
                    .fallback { margin: 6px 0 0; font-size: 12px; color: var(--yb-sub); }
                    .fallback a { color: inherit; }
                    @media (prefers-reduced-motion: reduce) {
                      .step button { transition: none; }
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <p class="title">正在前往@@NAME@@</p>
                    <p class="sub">校外访问需先连接校外认证客户端（如 aTrust）；已在校园网内可直接继续。</p>
                    @@NOTICE@@
                    @@WAKE_STEP@@
                    <div class="step">
                      <button id="go" type="button" class="primary">@@GO_LABEL@@</button>
                      <span class="hint">继续后本机下次不再显示此提示</span>
                    </div>
                    @@GUIDE_LINK@@
                  </div>
                  <script>
                  (function () {
                    var target = @@TARGET@@;
                    var KEY = 'cas-atrust-continue';
                    var done = false;
                    function go() {
                      if (done) { return; }
                      done = true;
                      location.replace(target);
                    }
                    var goBtn = document.getElementById('go');
                    if (goBtn) {
                      goBtn.addEventListener('click', function () {
                        try { localStorage.setItem(KEY, '1'); } catch (e) {}
                        go();
                      });
                    }
                    var skip = false;
                    try { skip = localStorage.getItem(KEY) === '1'; } catch (e) {}
                    if (skip) { @@PREHEAT@@ }
                  })();
                  </script>
                </body>
                </html>
                """
                .replace("@@NAME@@", name)
                .replace("@@TARGET@@", jsString(target))
                .replace("@@NOTICE@@", notice)
                .replace("@@WAKE_STEP@@", wakeStep)
                .replace("@@GO_LABEL@@", hasWake ? "② 我已连接，继续登录" : "我已连接，继续登录")
                .replace("@@GUIDE_LINK@@", guideLink)
                .replace("@@PREHEAT@@", preheat);
    }

    /** 把 URL 安全地放进 JS 字符串字面量（同时挡掉 </script> 之类的闭合注入）。 */
    private static String jsString(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026").replace("\n", "").replace("\r", "") + "\"";
    }

    /** 展示名称来自管理员配置，进 HTML 前做转义。 */
    private static String html(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public PluginHttpResponse registerOidc(PluginHttpRequest request) {
        OidcRegisterRequest body = JsonSupport.read(request.body(), OidcRegisterRequest.class);
        return PluginHttpResponse.ok(settings.registerOidcClient(body == null ? null : body.clientName()));
    }

    public PluginHttpResponse accessControl() {
        return PluginHttpResponse.ok(accessControlToMap(accessControl.current()));
    }

    public PluginHttpResponse saveAccessControl(PluginHttpRequest request) {
        AccessControlSaveRequest body = JsonSupport.read(request.body(), AccessControlSaveRequest.class);
        if (body == null) {
            throw new IllegalArgumentException("访问控制配置不能为空");
        }
        AccessControl current = accessControl.current();
        AccessControl incoming = body.requireBinding() == null
                ? current
                : current.withRequireBinding(body.requireBinding());
        return PluginHttpResponse.ok(accessControlToMap(accessControl.save(incoming)));
    }

    public PluginHttpResponse students(PluginHttpRequest request) {
        int page = intParam(request, "page", 1);
        int size = intParam(request, "size", 20);
        if (size < 1 || size > 100) {
            size = 20;
        }
        Map<String, Object> view = studentInfo.page(page, size, queryParam(request, "keyword"));
        Map<String, Object> result = new LinkedHashMap<>(view);
        // 前端据此提示「学院 / 班级」来源：学生档案插件未安装时只显示 —
        result.put("archiveAvailable", studentInfo.archiveAvailable());
        return PluginHttpResponse.ok(result);
    }

    public PluginHttpResponse studentDetail(PluginHttpRequest request) {
        String socialUid = queryParam(request, "socialUid");
        if (socialUid == null || socialUid.isBlank()) {
            throw new IllegalArgumentException("缺少学工号参数");
        }
        return studentInfo.detail(socialUid.trim())
                .map(PluginHttpResponse::ok)
                .orElseThrow(() -> new IllegalArgumentException("学生档案不存在: " + socialUid));
    }

    /**
     * 绑定门禁公开端点：前端全局挂件轮询。
     * requireBinding 仅在 CAS 登录入口启用时生效，避免配置半途把用户全部锁死。
     */
    public PluginHttpResponse gate() {
        boolean loginReady = settings.current().loginEnabled();
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("requireBinding", accessControl.requireBinding() && loginReady);
        gate.put("providerCode", "cas");
        gate.put("type", settings.current().protocol().typeCode());
        gate.put("displayName", settings.current().displayName());
        return PluginHttpResponse.ok(gate);
    }

    private static Map<String, Object> accessControlToMap(AccessControl control) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requireBinding", control.requireBinding());
        return view;
    }

    private static String queryParam(PluginHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int intParam(PluginHttpRequest request, String name, int fallback) {
        String raw = queryParam(request, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
