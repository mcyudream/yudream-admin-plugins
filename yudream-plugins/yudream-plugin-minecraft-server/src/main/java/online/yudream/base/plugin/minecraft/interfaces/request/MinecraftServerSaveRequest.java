package online.yudream.base.plugin.minecraft.interfaces.request;

import java.util.List;

public record MinecraftServerSaveRequest(
        String id,
        String name,
        String descriptionMarkdown,
        Boolean enabled,
        Integer sort,
        List<Endpoint> endpoints,
        List<Season> seasons
) {

    public record Endpoint(
            String id,
            String name,
            String host,
            Integer port,
            String edition,
            Boolean primaryLine,
            Boolean enabled,
            Integer sort
    ) {
    }

    public record Season(
            String id,
            String name,
            String description,
            Long startedAt,
            Long endedAt,
            Boolean current,
            Integer sort,
            ModpackBinding modpackBinding
    ) {
    }

    /**
     * modpack 绑定请求 DTO。三态字段不可同时填充。
     * <p>
     * 管理员填 NONE 时可缺省整体对象（视为 none），但 JSON 反序列化时绑 null 等价于不绑定。
     */
    public record ModpackBinding(
            String type,
            String gameVersion,
            String loader,
            String packId,
            String versionId
    ) {
        public static final String TYPE_NONE = "NONE";
        public static final String TYPE_VANILLA = "VANILLA";
        public static final String TYPE_MRPACK = "MRPACK";
    }
}
