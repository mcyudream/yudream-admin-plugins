package online.yudream.base.plugin.mcpet.application.dto;

import online.yudream.base.plugin.mcpet.domain.aggregate.PetPreference;

/** 管理端用户宠物列表行。 */
public record PetAdminItemDTO(
        String userId,
        PetPreference preference,
        MyPetDTO effective
) {
}
