package online.yudream.base.plugin.forum.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForumPluginTest {
    @Test
    void categoryPermissionsUseStableThreeSegmentCodes() {
        assertEquals("plugin:forum:category-abc-view", ForumPlugin.categoryViewPermission("abc"));
        assertEquals("plugin:forum:category-abc-post", ForumPlugin.categoryPostPermission("abc"));
    }
}
