package online.yudream.base.plugin.shop.domain.enumerate;

public enum ShopProductStatus {
    ON_SHELF("上架中"),
    OFF_SHELF("已下架");

    private final String label;

    ShopProductStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
