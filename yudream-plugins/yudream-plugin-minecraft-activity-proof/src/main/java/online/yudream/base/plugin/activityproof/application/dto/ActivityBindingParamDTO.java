package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 高级自定义计分参数的展示视图：key 是计算式里的变量名，label/来源字段供参数映射表格展示；
 * serverId/formCode/includeAfk 保留原始取值，编辑活动时前端据此回显选择器。
 */
public record ActivityBindingParamDTO(
        String key,
        String label,
        String type,
        String serverId,
        String serverName,
        String subServer,
        boolean includeAfk,
        String formCode,
        String formName
) {
}
