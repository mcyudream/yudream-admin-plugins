# mc-assets 宿主 Graph SPI

`mc-assets` 通过宿主 `FrameworkServices.graph()` 获取 `PluginGraphService`。插件不依赖 Neo4j Java Driver，不读取 Neo4j 系统属性或环境变量，也不会创建、关闭或接触宿主的 Neo4j 连接与凭据。

从 Graph SPI 2.12.1 起，资源发布和星图查询分别使用无图表参数的 `replaceProjection(PluginGraphProjection)` 与 `readProjection(PluginGraphProjectionReadRequest)`。宿主仅在当前 `pluginCode` 存在唯一的 ACTIVE 且已授权逻辑图表时执行自动绑定；插件不会保存、选择或枚举 `tableCode`、连接或图表命名空间，也不再暴露图表设置接口。

插件只提交资源版本的结构化投影及分页读取参数，不执行 Cypher；宿主负责图表授权隔离、参数约束、执行与结果限制。未绑定或多绑定时，宿主返回 `TABLE_BINDING_NOT_FOUND` 或 `TABLE_BINDING_AMBIGUOUS`；SPI 不可用及其他调用失败同样会使功能显式降级为 `DEGRADED`。插件仍可加载，星图返回空结果，且不会伪造 `PUBLISHED`。
