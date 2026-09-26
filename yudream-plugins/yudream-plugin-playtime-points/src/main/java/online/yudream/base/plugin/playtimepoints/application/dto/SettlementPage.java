package online.yudream.base.plugin.playtimepoints.application.dto;

import java.util.List;

public record SettlementPage(List<SettlementView> records, long total) {
}
