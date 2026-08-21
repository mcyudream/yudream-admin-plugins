package online.yudream.base.plugin.pony.application.dto;

import java.util.List;

public record Paged<T>(List<T> records, long total) {
}
