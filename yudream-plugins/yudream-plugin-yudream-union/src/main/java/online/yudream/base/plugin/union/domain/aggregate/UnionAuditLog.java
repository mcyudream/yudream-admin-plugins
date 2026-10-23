package online.yudream.base.plugin.union.domain.aggregate;

/**
 * 联合认证审计日志（enroll/信任启停/封禁增解/多站冲突全部落账）。
 *
 * @param id        文档主键（uuid hex）
 * @param actorType USER | ADMIN | SYSTEM | PEER
 * @param actor     操作者（站点用户 ID / siteCode）
 * @param action    动作码（enroll / peer.block / ban.add / ban.lift / join.conflict …）
 * @param target    目标（siteCode / 规范化角色名）
 * @param detail    详情（中文摘要）
 * @param at        时间（epoch 毫秒）
 */
public record UnionAuditLog(
        String id,
        String actorType,
        String actor,
        String action,
        String target,
        String detail,
        long at
) {
}
