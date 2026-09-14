package online.yudream.base.plugin.launcher.interfaces.request;

import java.util.List;

/**
 * 推送新版本。
 * {@code indexJson} 为 modrinth.index.json 原文；启动器按 {@code indexHash} 做增量对比。
 */
public record PublishPackVersionRequest(
        String versionId,
        String indexJson,
        String indexHash,
        String overridesObjectKey,
        List<String> downloads,
        String changelog
) {
}
