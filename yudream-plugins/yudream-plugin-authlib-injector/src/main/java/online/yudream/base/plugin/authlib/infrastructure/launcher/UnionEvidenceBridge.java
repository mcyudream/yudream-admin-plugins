package online.yudream.base.plugin.authlib.infrastructure.launcher;

import online.yudream.base.plugin.authlib.application.service.AuthlibAppService;
import online.yudream.base.plugin.authlib.application.service.AuthlibInfoService;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;
import online.yudream.base.plugin.union.api.UnionJoinEvidenceProvider;

import java.util.Optional;

/**
 * B 侧 join 证据回供（YD 联合认证协议 v1 §3.2 的数据源）：
 * join 记录与角色材质都归 authlib-injector 所有，经本扩展点回供给
 * yudream-union 的 /peer/joins/query 断言端点。类型引用（UnionJoinEvidenceProvider）
 * 仅在 yudream-union 可用时被实例化（bootstrap 中 guarded 注册）。
 */
public class UnionEvidenceBridge implements UnionJoinEvidenceProvider {

    private final AuthlibRepository repository;
    private final AuthlibAppService appService;
    private final AuthlibInfoService infoService;

    public UnionEvidenceBridge(AuthlibRepository repository, AuthlibAppService appService,
                               AuthlibInfoService infoService) {
        this.repository = repository;
        this.appService = appService;
        this.infoService = infoService;
    }

    @Override
    public Optional<JoinEvidence> findRecentJoin(String username, String serverId, long sinceMillis) {
        return repository.findJoinsByServer(serverId).stream()
                // 缺 issuedAt 的旧 join 文档不能安全地证明近期登录，联合断言直接跳过。
                .filter(join -> join.issuedAt() != null && join.issuedAt() >= sinceMillis)
                .filter(join -> join.expiresAt() != null && join.expiresAt() > System.currentTimeMillis())
                .filter(join -> username.equalsIgnoreCase(join.username()))
                .findFirst()
                .flatMap(join -> appService.textureValueFor(join.profileId(), textureBaseUrl())
                        .map(textureValue -> new JoinEvidence(join.profileId(), join.username(), textureValue)));
    }

    private String textureBaseUrl() {
        return infoService.configuredOrigin().orElse("http://localhost:8080")
                + "/api/plugins/yudream-skin/textures";
    }
}
