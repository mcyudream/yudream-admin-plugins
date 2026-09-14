package online.yudream.base.plugin.launcher.interfaces.res;

import java.util.List;

public record PackRes(
        String id,
        String name,
        String description,
        String icon,
        String recommendedVersionId,
        List<String> retainedVersionIds,
        String createdAt,
        String updatedAt
) {
}
