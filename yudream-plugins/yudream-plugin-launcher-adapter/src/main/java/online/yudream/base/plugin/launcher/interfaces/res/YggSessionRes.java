package online.yudream.base.plugin.launcher.interfaces.res;

import online.yudream.base.plugin.authlib.api.PluginAuthProfile;

import java.util.List;
import java.util.Map;

public record YggSessionRes(
        String userId,
        String username,
        String profileId,
        String accessToken,
        String clientToken,
        List<PluginAuthProfile> availableProfiles,
        Map<String, String> selectedProfile
) {
}
