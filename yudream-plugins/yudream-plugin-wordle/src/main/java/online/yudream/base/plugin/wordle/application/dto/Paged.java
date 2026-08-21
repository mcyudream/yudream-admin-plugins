package online.yudream.base.plugin.wordle.application.dto;

import java.util.List;

public record Paged<T>(List<T> records, long total) {
}
