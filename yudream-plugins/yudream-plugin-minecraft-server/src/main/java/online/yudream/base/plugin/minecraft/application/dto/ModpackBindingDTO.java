package online.yudream.base.plugin.minecraft.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * modpack 绑定抽象在 DTO 层的扁平表示。
 * <p>
 * 结构对应 {@code ModpackBinding}（Domain 值对象），三态字段不可同时填充：
 * <ul>
 *   <li>NONE：全部字段为 null</li>
 *   <li>VANILLA：仅 gameVersion/loader 有值</li>
 *   <li>MRPACK：仅 packId/versionId 有值（versionId 可空）</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModpackBindingDTO(
        String type,
        String gameVersion,
        String loader,
        String packId,
        String versionId
) {
    public static final String TYPE_NONE = "NONE";
    public static final String TYPE_VANILLA = "VANILLA";
    public static final String TYPE_MRPACK = "MRPACK";

    public static ModpackBindingDTO none() {
        return new ModpackBindingDTO(TYPE_NONE, null, null, null, null);
    }
}
