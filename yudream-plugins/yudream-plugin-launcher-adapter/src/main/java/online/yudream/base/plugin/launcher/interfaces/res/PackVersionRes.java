package online.yudream.base.plugin.launcher.interfaces.res;

import java.util.List;

public record PackVersionRes(
        String packId,
        String versionId,
        String indexHash,
        String overridesObjectKey,
        List<String> downloads,
        String changelog,
        String publishedAt,
        String publisherUserId,
        String status
) {
}
