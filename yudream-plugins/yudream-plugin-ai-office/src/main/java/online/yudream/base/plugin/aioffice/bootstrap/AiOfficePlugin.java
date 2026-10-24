package online.yudream.base.plugin.aioffice.bootstrap;

import online.yudream.base.plugin.aioffice.application.ConfigService;
import online.yudream.base.plugin.aioffice.application.FinanceService;
import online.yudream.base.plugin.aioffice.application.GenerationService;
import online.yudream.base.plugin.aioffice.application.PresetPackService;
import online.yudream.base.plugin.aioffice.infrastructure.repository.OfficeDocumentRepository;
import online.yudream.base.plugin.aioffice.interfaces.controller.OfficeAdminController;
import online.yudream.base.plugin.aioffice.interfaces.controller.OfficeUserController;
import online.yudream.base.plugin.aioffice.interfaces.http.OfficeHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

/**
 * AI 模板办公：数据模型 + 宿主 Word 模板映射 + AI 起草 + 财务结算。
 * 站点级单组织、零特异化：业务差异全部在 schema/文档类型/预设包数据里。
 */
@PluginSpec(
        code = AiOfficePlugin.CODE,
        name = "ai-office",
        version = "0.1.0",
        description = "数据模型与文档类型映射宿主 Word 模板，AI 起草活动总结/策划书/月度总结，财务凭证登记、审核与周期结算，月末材料打包导出。"
)
@PluginPermissions({
        @PluginPermission(code = AiOfficePlugin.VIEW_PERMISSION, name = "查看办公文档", module = "办公",
                description = "查看文档类型、生成任务并下载定稿产物；我的报销入口"),
        @PluginPermission(code = AiOfficePlugin.MANAGE_PERMISSION, name = "管理模板配置", module = "办公",
                description = "维护数据模型、文档类型与映射、提示词、类别字典、打包定义、数据集与站点设置"),
        @PluginPermission(code = AiOfficePlugin.GENERATE_PERMISSION, name = "生成文档", module = "办公",
                description = "发起映射预览、AI 起草、定稿渲染与打包导出"),
        @PluginPermission(code = AiOfficePlugin.FINANCE_PERMISSION, name = "经办财务", module = "办公",
                description = "登记收入/支出并上传发票、支付截图等凭证"),
        @PluginPermission(code = AiOfficePlugin.ACCEPT_PERMISSION, name = "审核财务", module = "办公",
                description = "审核财务记录（采纳/驳回并给出意见）"),
        @PluginPermission(code = AiOfficePlugin.SETTLE_PERMISSION, name = "周期结算", module = "办公",
                description = "查看结算批次、关账锁定当期记录并结转结余")
})
@PluginFrontend(
        moduleName = "aiOffice",
        menuTitle = "模板办公",
        menuIcon = "i-ri:file-text-line",
        menuSort = 52,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/office/templates",
                        name = "platform-plugin-office-templates",
                        title = "文档类型",
                        icon = "i-ri:file-list-3-line",
                        component = "ai-office/Templates",
                        permission = AiOfficePlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/prompts",
                        name = "platform-plugin-office-prompts",
                        title = "AI 提示词",
                        icon = "i-ri:quill-pen-line",
                        component = "ai-office/Prompts",
                        permission = AiOfficePlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/schemas",
                        name = "platform-plugin-office-schemas",
                        title = "数据模型",
                        icon = "i-ri:database-2-line",
                        component = "ai-office/Schemas",
                        permission = AiOfficePlugin.MANAGE_PERMISSION,
                        sort = 11
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/generate",
                        name = "platform-plugin-office-generate",
                        title = "生成与打包",
                        icon = "i-ri:magic-line",
                        component = "ai-office/Generate",
                        permission = AiOfficePlugin.GENERATE_PERMISSION,
                        sort = 12
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/finance",
                        name = "platform-plugin-office-finance",
                        title = "财务台账",
                        icon = "i-ri:wallet-3-line",
                        component = "ai-office/Finance",
                        permission = AiOfficePlugin.FINANCE_PERMISSION,
                        sort = 13
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/settlement",
                        name = "platform-plugin-office-settlement",
                        title = "周期结算",
                        icon = "i-ri:calendar-check-line",
                        component = "ai-office/Settlement",
                        permission = AiOfficePlugin.SETTLE_PERMISSION,
                        sort = 14
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/dataset",
                        name = "platform-plugin-office-dataset",
                        title = "数据集",
                        icon = "i-ri:table-2",
                        component = "ai-office/Dataset",
                        permission = AiOfficePlugin.MANAGE_PERMISSION,
                        sort = 15
                ),
                @PluginRoute(
                        path = "/platform/plugins/office/settings",
                        name = "platform-plugin-office-settings",
                        title = "办公设置",
                        icon = "i-ri:settings-3-line",
                        component = "ai-office/Settings",
                        permission = AiOfficePlugin.MANAGE_PERMISSION,
                        sort = 16
                ),
                @PluginRoute(
                        path = "/platform/office/my-finance",
                        name = "platform-plugin-office-my-finance",
                        title = "我的报销",
                        icon = "i-ri:receipt-line",
                        component = "ai-office/MyFinance",
                        permission = AiOfficePlugin.VIEW_PERMISSION,
                        sort = 20
                )
        }
)
public class AiOfficePlugin implements YuDreamPlugin {

    public static final String CODE = "ai-office";
    public static final String VIEW_PERMISSION = "plugin:ai-office:view";
    public static final String MANAGE_PERMISSION = "plugin:ai-office:manage";
    public static final String GENERATE_PERMISSION = "plugin:ai-office:generate";
    public static final String FINANCE_PERMISSION = "plugin:ai-office:finance";
    public static final String ACCEPT_PERMISSION = "plugin:ai-office:accept";
    public static final String SETTLE_PERMISSION = "plugin:ai-office:settle";

    @Override
    public void onEnable(PluginContext context) {
        OfficeDocumentRepository repository = new OfficeDocumentRepository(context.documents());
        ConfigService configService = new ConfigService(repository);
        FinanceService financeService = new FinanceService(repository, context.files(), context.framework());
        GenerationService generationService = new GenerationService(repository, financeService, context);
        PresetPackService presetService = new PresetPackService(repository, configService);
        OfficeHttpFacade http = new OfficeHttpFacade(configService, financeService, generationService,
                presetService, context);
        context.registerHttpController(new OfficeAdminController(http));
        context.registerHttpController(new OfficeUserController(http));
    }
}
