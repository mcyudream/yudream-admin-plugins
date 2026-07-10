package online.yudream.base.plugin.minecraft.application.dto;

import java.util.List;

public record MinecraftPageDTO<T>(List<T> records, long total) {
}
