package online.yudream.base.plugin.activityproof.application.cmd;

/** 高级自定义计分参数：变量名（key）由服务端按添加顺序分配，前端只需声明取值来源。 */
public record ActivityParamCmd(
        String type,
        String label,
        String serverId,
        String subServer,
        Boolean includeAfk,
        String formCode
) {
}
