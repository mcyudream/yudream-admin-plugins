package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityProofPageDTO<T>(List<T> records, long total) {
}
