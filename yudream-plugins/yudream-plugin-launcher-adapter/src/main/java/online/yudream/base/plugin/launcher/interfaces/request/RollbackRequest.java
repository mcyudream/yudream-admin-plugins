package online.yudream.base.plugin.launcher.interfaces.request;

/**
 * 回滚到历史版本。仅调整 recommendedVersion 指针。
 */
public record RollbackRequest(
        String targetVersionId
) {
}
