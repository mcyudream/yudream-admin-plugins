package online.yudream.base.plugin.eduverify.domain.aggregate;

/** 审计日志。actorType：USER / ADMIN / SYSTEM。 */
public record AuditLog(
        String id,
        long at,
        String actorType,
        String actorId,
        String action,
        String channel,
        String emailLower,
        String userId,
        String detail
) {
}
