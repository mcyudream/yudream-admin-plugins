package online.yudream.base.plugin.union.api;

import java.util.Optional;

/**
 * 本站 join 证据回供扩展点（B 侧断言数据源）。
 *
 * <p>join 记录归 authlib-injector 所有（文档库按插件隔离），而 /peer/joins/query
 * 端点由本插件承载。authlib-injector 依赖本插件 api（provided + softdepend），
 * 以本接口的实现注册扩展，回供 TTL 内的本地 join 记录与角色材质原文；本插件经
 * {@code context.extensions(UnionJoinEvidenceProvider.class)} 消费。扩展缺失时
 * 断言端点一律 204（联合层整体可选，本站行为不受影响）。</p>
 */
public interface UnionJoinEvidenceProvider {

    /**
     * 查找一条与 username（不区分大小写）+ serverId 精确匹配、且签发时间晚于
     * sinceMillis 的本地 join 记录。
     */
    Optional<JoinEvidence> findRecentJoin(String username, String serverId, long sinceMillis);

    /**
     * 本地 join 证据。
     *
     * @param profileId    角色 UUID（无连字符）
     * @param profileName  角色名（原始大小写）
     * @param textureValue 本站该角色 textures 属性 value（base64 原文）
     */
    record JoinEvidence(String profileId, String profileName, String textureValue) {
    }
}
