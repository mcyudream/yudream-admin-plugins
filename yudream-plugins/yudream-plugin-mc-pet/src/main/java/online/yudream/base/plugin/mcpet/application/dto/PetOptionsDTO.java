package online.yudream.base.plugin.mcpet.application.dto;

import java.util.List;

/** 「我的宠物」选择器数据：我的角色与我的衣柜条目。 */
public record PetOptionsDTO(
        boolean skinAvailable,
        List<PlayerOption> players,
        List<ClosetOption> closet
) {
    public record PlayerOption(String name, String uuid, String textureHash, String model) {
    }

    public record ClosetOption(String id, String textureHash, String itemName, Long createdAt) {
    }
}
