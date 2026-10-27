package online.yudream.ysm.folia;

import com.example.handbookcore.api.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** Player instructions published through HandbookCore's public contribution API. */
final class ModelsGuide {
    private final String website;
    private final long cooldown;

    private ModelsGuide(String website, long cooldown) {
        this.website = safeWebsite(website);
        this.cooldown = Math.max(0, cooldown);
    }

    static HandbookContribution build(String website, long cooldown) {
        return new ModelsGuide(website, cooldown).build();
    }

    private HandbookContribution build() {
        var category = new HandbookCategory(ModelsHandbook.CATEGORY, "模型指南", "上传、审核与角色换装",
                HandbookIcon.vanilla(Material.LEATHER_CHESTPLATE), 60, "ysm.use");
        return new HandbookContribution(List.of(category), List.of(
                entry("quickstart", "开始换装", "从网站选模型，到游戏查看外观", List.of("新手", "入门", "换装", "皮肤", "YSM"), 10,
                        page("准备账号", "登录服务器提供的皮肤站，先创建或确认本人的游戏角色。装备绑定本人角色，请确认角色与进服身份一致。", web("打开网站", ""), link("角色换装", "equip")),
                        page("选择模型", "可在“我的模型”上传原始 .ysm 或模型 ZIP，也可从“服务器模型”选择已有的共享模型。", web("共享模型", "/catalog"), link("上传模型", "upload")),
                        page("进入游戏", "模型可用后，选择贴图和角色并装备。进服后点击“查看状态”确认模式；原版玩家需接受服务器资源包。", command("查看状态", "status"), link("客户端", "client"), link("原版快照", "vanilla"))),
                entry("upload", "上传模型", "支持原始 .ysm 与模型 ZIP，查看转换结果", List.of("上传", "ysm", "zip", "文件", "版本", "权限"), 20,
                        page("上传文件", "在网站“我的模型”点击上传，选择原始 .ysm 或明文模型 ZIP，单次文件上限 16 MiB。ZIP 请保留模型的几何、动画、贴图和配置目录。", web("我的模型", "")),
                        page("确认预览", "上传后会转换并生成预览。检查贴图、动作和兼容说明；三维预览不可用时可查看二维预览。转换失败时按页面提示修正文件。", link("常见问题", "faq")),
                        page("上传限制", "管理员可关闭普通上传。关闭后不能新增模型或上传新版本，已有可用模型仍可装备。持有受限上传权限的账号可继续提交，但仍须遵守审核规则。", link("等待审核", "review")),
                        page("更新版本", "在自己的模型详情上传新版本。新版本会替换旧版本；启用审核时须重新审核，原来已装备的角色在审核期间暂时恢复皮肤。", link("等待审核", "review"))),
                entry("review", "等待审核", "待审与驳回的处理，以及版本更新的影响", List.of("审核", "待审核", "驳回", "通过", "新版本"), 30,
                        page("模型状态", "服务器启用审核后，新模型和新版本会进入待审核。你仍可预览自己的模型，通过审核后才可装备、共享、下载和同步到游戏。"),
                        page("审核驳回", "被驳回时，在模型详情查看原因，修正文件后上传新版本并等待重新审核。拥有上传权限也不会跳过审核。", web("我的模型", "")),
                        page("版本更新", "新版本替换旧版本。审核期间，已装备角色暂时显示皮肤；审核通过且模型可用后，装备关系恢复生效。原版观看者可能需要刷新资源快照。", link("原版快照", "vanilla")),
                        page("设置变化", "开启审核不会追溯改变已有模型的状态；关闭审核也不会自动通过待审或驳回模型。仍在待审时请等待管理员处理。")),
                entry("equip", "角色换装", "选择模型、贴图与本人角色，或恢复皮肤", List.of("装备", "角色", "贴图", "换肤", "共享", "取消", "恢复"), 40,
                        page("装备模型", "打开自己的可用模型，或服务器模型库中的共享模型。选择贴图和本人的皮肤站角色，再点击装备；不能替其他账号的角色装备。", web("我的模型", ""), web("共享模型", "/catalog")),
                        page("查看外观", "用对应角色身份进服，点击“查看状态”确认模式和资源结果。原版观看者若未加载该模型或新版本，可到“原版快照”查看刷新方法。", command("查看状态", "status"), link("原版快照", "vanilla")),
                        page("恢复皮肤", "在网站取消该角色的模型装备，恢复皮肤外观。不需要重新上传皮肤，也不要求刷新资源包。模型被停用、删除或进入审核时也会回退皮肤。", web("打开网站", ""))),
                entry("client", "客户端", "原版资源包与 Fabric 动态更新的选择", List.of("Fabric", "Mod", "客户端", "安装", "Java", "热更新"), 50,
                        page("原版游玩", "未安装模型 Mod 也可通过服务器资源包查看模型。请允许服务器资源包；网站更新后需手动刷新。刷新方法见“原版快照”。", link("原版快照", "vanilla")),
                        page("安装模组", "游戏与运行环境：\nMinecraft\n26.3\nJava 25\nFabric Loader\n0.20.0 或更高版本。"),
                        page("放入模组", "模型客户端名称：\nYudreamModels\n客户端版本 0.2.0。\n把它与适配 26.3 的 Fabric API 放入同一游戏实例的 mods 文件夹。"),
                        page("版本匹配", "本次配套 Fabric API 版本：\n0.165.0+26.3\n客户端文件向服务器管理员获取，不需要额外安装原始 YSM Mod，也不需要填写网站地址或服务器密钥。"),
                        page("动态更新", "装好配套 Mod 后进服，点击“查看状态”应显示 Fabric 动态更新。装备变化会按需下载并切换；下载失败时保留旧外观。该路径无需全局资源重载。", command("查看状态", "status"))),
                entry("vanilla", "原版快照", "接受资源包、手动刷新和本人替身预览", List.of("原版", "资源包", "刷新", "冷却", "第三人称", "自身"), 60,
                        page("接受资源", "首次进服请接受服务器资源包。原版按已加载的模型快照显示外观；网站更新不会自动发送新资源包，快照中没有的模型会回退皮肤。"),
                        page("手动刷新", "网站换装或模型更新后，点击“刷新资源”检查资源。当前刷新冷却为 " + cooldown + " 秒；资源无变化、正在加载或冷却中时不会重复发送。", command("刷新资源", "refresh")),
                        page("资源重载", "刷新会触发资源重载，可能出现短暂停顿。新包加载成功后才移除本插件旧包，因此可能重载两次；失败时保留旧外观，可稍后重试。"),
                        page("本人替身", "原版本人替身默认关闭。点击“开启替身”后切换第三人称查看；第一人称遮挡时点击“关闭替身”。此开关不控制 Fabric 原生渲染的第三人称模型。", command("开启替身", "self on"), command("关闭替身", "self off"))),
                entry("commands", "常用命令", "查看指南、检查状态、刷新资源与切换替身", List.of("命令", "指令", "model", "guide", "status", "refresh", "self"), 70,
                        page("阅读指南", "以下命令在聊天中输入时，先加 /。也可直接点击书页按钮。\nmodel guide\n打开模型指南，也可从手册目录或搜索进入。", command("模型指南", "guide")),
                        page("查看状态", "model status\n查看当前模式、加载结果、快照模型数和资源包发送次数。", command("查看状态", "status")),
                        page("刷新资源", "model refresh\n手动检查原版资源快照。需要刷新权限，默认所有玩家可用；冷却由服务器设置。", command("刷新资源", "refresh"), link("原版快照", "vanilla")),
                        page("本人替身", "model self on\n开启原版本人替身。\n关闭时改用 off。\n也可点击下方按钮。\n模型、贴图与角色装备在网站选择，也可在网站恢复皮肤。", command("开启替身", "self on"), command("关闭替身", "self off"), link("角色换装", "equip"))),
                entry("faq", "常见问题", "无法上传、没有角色、外观未更新与兼容说明", List.of("问题", "失败", "看不到", "上传关闭", "缺少文件", "预览", "兼容", "物理", "没有角色"), 80,
                        page("无法上传", "先确认文件为 .ysm 或模型 ZIP，且未超过 16 MiB。上传入口关闭时，请使用已有模型或联系管理员；转换报缺少文件时检查 ZIP 目录与模型配置引用的文件。", link("上传模型", "upload")),
                        page("没有角色", "装备列表为空时，先在皮肤站建立本人角色。模型待审、被驳回或已停用时不能装备；待审可预览，驳回请先查看原因。", link("等待审核", "review")),
                        page("外观未变", "确认网站装备的角色与进服身份一致，再点击“查看状态”检查资源加载。原版接受资源包后可尝试刷新，方法见“原版快照”；下载失败请联系管理员。", command("查看状态", "status"), link("原版快照", "vanilla")),
                        page("兼容范围", "支持原始 .ysm、模型 ZIP、逐面 UV 和受限动画表达式。复杂控制器、专用物理和外观菜单没有完整复现；转换后的兼容说明可帮助判断差异。", web("我的模型", "")),
                        page("预览异常", "浏览器三维预览不可用时可使用二维预览。游戏外观有问题时先恢复皮肤，再把报错、模型版本和“查看状态”的结果提供给管理员。", link("角色换装", "equip")))));
    }

    private static HandbookEntry entry(String id, String title, String summary, List<String> keywords, int order, HandbookPage... pages) {
        return new HandbookEntry(key(id), ModelsHandbook.CATEGORY, title, summary, keywords, order, "ysm.use", List.of(pages));
    }
    private static HandbookPage page(String title, String text, HandbookBlock... actions) {
        var blocks = new ArrayList<HandbookBlock>();
        blocks.add(HandbookBlock.text(text));
        for (var action : actions) if (action != null) blocks.add(action);
        return new HandbookPage(title, blocks);
    }
    private static HandbookBlock link(String label, String id) { return new HandbookBlock.Link(label, key(id)); }
    private static HandbookBlock command(String label, String args) { return new HandbookBlock.Command(label, "model " + args); }
    private HandbookBlock web(String label, String path) {
        return website.isEmpty() ? null : new HandbookBlock.Url(label, website + "/platform/plugins/ysm-models" + path);
    }
    private static NamespacedKey key(String id) { return new NamespacedKey("ysm", id); }
    private static String safeWebsite(String value) {
        try {
            String base = value.strip().replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || base.length() > 1900) return "";
            return base;
        } catch (IllegalArgumentException | NullPointerException error) { return ""; }
    }
}
