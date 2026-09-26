package online.yudream.base.plugin.shop.interfaces.request;

import java.util.List;

/**
 * 发货凭证请求：voucher 为文本说明（可为空），proofs 为平台上传的凭证图片地址（最多 6 张，可为空），
 * 两者不能同时为空。
 */
public record ShopVoucherRequest(String voucher, List<String> proofs) {
}
