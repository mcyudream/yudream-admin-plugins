package online.yudream.base.plugin.shop.interfaces.request;

/** 管理端排序请求：direction 取 UP / DOWN / TOP，作用于同一结算方式（玩家侧或官方侧）内的展示顺序。 */
public record ShopSortRequest(String direction) {
}
