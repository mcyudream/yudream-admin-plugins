package online.yudream.plugin.voxelithmap.application.dto;

import java.util.List;
import java.util.Map;

/**
 * 引擎自检视图（管理页展示，用于排障「为什么渲染跑不起来」）。
 *
 * @param engineReady          内置引擎资源是否齐全
 * @param javaExecutable       java 可执行文件
 * @param runtimeRoot          插件运行根
 * @param heapMiB              渲染子进程堆
 * @param timeoutMinutes       单次渲染超时
 * @param maxConcurrentTasks   并发任务数
 * @param harvestCapable       是否具备采集能力（否则只能静态解析）
 * @param modelsCached         是否已缓存 models.json.gz
 * @param modelsFile           models 缓存路径
 * @param sidecarEnabled       是否启用边车
 * @param sidecarState         边车状态
 * @param sidecarPort          边车端口（0 = 未启动）
 * @param sidecarError         边车失败原因
 * @param sidecarRetryInSeconds 边车失败冷却剩余秒数
 * @param sidecarLaunchPlan    边车启动参数（排障展示）
 * @param bundledResources     内置资源清单与是否就位
 */
public record EngineStatusDTO(
        boolean engineReady,
        String javaExecutable,
        String runtimeRoot,
        int heapMiB,
        int timeoutMinutes,
        int maxConcurrentTasks,
        boolean harvestCapable,
        boolean modelsCached,
        String modelsFile,
        boolean sidecarEnabled,
        String sidecarState,
        int sidecarPort,
        String sidecarError,
        long sidecarRetryInSeconds,
        List<String> sidecarLaunchPlan,
        Map<String, Object> bundledResources) {
}
