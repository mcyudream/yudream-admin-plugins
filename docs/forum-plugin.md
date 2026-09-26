# Forum Plugin

论坛插件提供分类化帖子、Markdown 图片上传、自由标签、评论、点赞、收藏、人工/AI 审核策略、精华与置顶，以及公开用户资料页。

## 权限

插件基础权限为 `plugin:forum:view`、`plugin:forum:use`、`plugin:forum:manage`。管理员创建分类时会动态注册 `plugin:forum:category-{categoryId}-view` 和 `plugin:forum:category-{categoryId}-post` 两个权限；分类改名不会改变权限码，分类删除前必须归档其帖子。

## 审核

全局设置支持关闭、人工和 AI 三种策略，分类可以覆盖全局策略。AI 不可用、超时或返回不确定结果时回退人工审核；AI 标签失败不阻断发帖。当前持久化设置端点为 `/admin/settings`，审核与运营动作写入 `/admin/audit`。

## YMCL

论坛以 `YmclContributionProvider` 注册 `forum.home`、`forum.post`、`forum.profile` 页面，并通过 module bundle 提供最新、最热、精华列表以及点赞/收藏动作。`ymcl-adapter` 0.10.0 起把当前主体权限集合透传到 `YmclDataContext`，论坛据此裁剪动态分类内容；旧四参 provider 构造方式仍兼容。

启动器仍使用现有 `module` renderer、`server:*` 动作和 `client:*` 通用动作，不新增启动器业务渲染器。Markdown 正文在 module 页面中只做安全文本展示，后台详情使用 Markdown 预览组件。
