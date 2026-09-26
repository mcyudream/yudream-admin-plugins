package online.yudream.base.plugin.shop.application.service;

import java.util.List;

/** 内存过滤/排序后的分页结果（文档存储无服务端排序与复合查询）。 */
public record ShopPage<T>(List<T> records, long total) {

    public static <T> ShopPage<T> of(List<T> filteredSorted, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        long total = filteredSorted.size();
        int from = Math.min((int) ((long) (safePage - 1) * safeSize), filteredSorted.size());
        int to = Math.min(from + safeSize, filteredSorted.size());
        return new ShopPage<>(List.copyOf(filteredSorted.subList(from, to)), total);
    }
}
