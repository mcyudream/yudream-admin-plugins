package online.yudream.base.plugin.launcher.interfaces.request;

import java.util.List;

public record YmclChromeSaveRequest(
        String displayName,
        String logoUrl,
        String backgroundUrl,
        List<YmclNavNodeRequest> navTree
) {
    public record YmclNavNodeRequest(
            String code,
            String kind,
            String title,
            String icon,
            String pageCode,
            Boolean visible,
            List<YmclNavNodeRequest> children
    ) {
    }
}
