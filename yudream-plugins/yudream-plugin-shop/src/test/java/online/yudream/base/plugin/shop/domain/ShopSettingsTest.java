package online.yudream.base.plugin.shop.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import org.junit.jupiter.api.Test;

/**
 * 商店设置规范化：官方展示名与官方头像的清洗规则（留空即隐藏 / 必须是平台上传文件）。
 */
class ShopSettingsTest {

    @Test
    void defaultsShowOfficialOwnerWithDefaultNameAndNoAvatar() {
        ShopSettings settings = ShopSettings.defaults();

        assertEquals("官方", settings.platformOwnerLabel());
        assertNull(settings.platformOwnerAvatar());
    }

    @Test
    void blankOwnerNameHidesTheOwnerRowAndBlankAvatarIsCleared() {
        ShopSettings settings = new ShopSettings(true, null, BigDecimal.ZERO, List.of(), "  ", "   ").normalized();

        assertNull(settings.platformOwnerLabel(), "展示名留空表示界面不显示归属行");
        assertNull(settings.platformOwnerAvatar());
    }

    @Test
    void avatarMustBeAPlatformUploadedFile() {
        assertThrows(IllegalArgumentException.class, () -> new ShopSettings(true, null, BigDecimal.ZERO, List.of(),
                "官方", "https://evil.example/x.png").normalized());

        ShopSettings settings = new ShopSettings(true, null, BigDecimal.ZERO, List.of(), " 胡杨方块社 ",
                " /api/files/avatar.png ").normalized();
        assertEquals("胡杨方块社", settings.platformOwnerLabel());
        assertEquals("/api/files/avatar.png", settings.platformOwnerAvatar());
    }

    @Test
    void overlyLongOwnerNameAndAvatarAreTruncated() {
        ShopSettings settings = new ShopSettings(true, null, BigDecimal.ZERO, List.of(),
                "名".repeat(30), "/api/files/" + "a".repeat(600)).normalized();

        assertEquals(ShopSettings.MAX_PLATFORM_OWNER_NAME_LENGTH, settings.platformOwnerName().length());
        assertEquals(ShopSettings.MAX_PLATFORM_OWNER_AVATAR_LENGTH, settings.platformOwnerAvatar().length());
    }
}
