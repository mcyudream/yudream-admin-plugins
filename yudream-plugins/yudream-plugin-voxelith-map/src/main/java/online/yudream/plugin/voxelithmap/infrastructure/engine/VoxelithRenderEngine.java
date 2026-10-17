package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 渲染引擎端口。
 *
 * <p>只描述「跑一次渲染并把产物落在哪儿」，不涉及对象存储与任务状态——
 * 那些属于编排层。这样换引擎（或加第二种引擎）时不用动编排。</p>
 */
public interface VoxelithRenderEngine {

    /**
     * 跑一次渲染。
     *
     * @param invocation  参数
     * @param taskDir     任务临时目录（work/、publish/、日志都在其下，任务结束由调用方删除）
     * @param canceled    取消探针
     * @param progress    进度回调（阶段 + 整体百分比 + 消息）
     * @param onLine      子进程输出逐行回调（用于任务日志尾巴）
     * @return 执行结果与产物根目录
     */
    RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                         Path taskDir,
                         java.util.function.BooleanSupplier canceled,
                         ProgressSink progress,
                         VoxelithProcessRunner.LineConsumer onLine);

    /**
     * 渲染调用的 {@code --worker-classpath}：引擎在渲染内就地采集模型时必需
     * （缺了只能静态解析）。缺省返回空表（引擎不可用时）。
     */
    default java.util.List<String> renderWorkerClasspath() {
        return java.util.List.of();
    }

    /**
     * 带堆覆盖的渲染：{@code heapMiBOverride > 0} 时替代配置堆（OOM 自动重试用）。
     */
    default RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                                 Path taskDir,
                                 java.util.function.BooleanSupplier canceled,
                                 ProgressSink progress,
                                 VoxelithProcessRunner.LineConsumer onLine,
                                 int heapMiBOverride) {
        return render(invocation, taskDir, canceled, progress, onLine);
    }

    /**
     * 带堆与超时覆盖的渲染。
     *
     * <p>编排层按窗口规模自适应时用：{@code timeout} 为 {@code null} 表示用配置值。
     * 大型窗口既吃堆也吃时间，两者必须一起放宽（见 {@link RenderBudget}）。</p>
     */
    default RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                                 Path taskDir,
                                 java.util.function.BooleanSupplier canceled,
                                 ProgressSink progress,
                                 VoxelithProcessRunner.LineConsumer onLine,
                                 int heapMiBOverride,
                                 Duration timeout) {
        return render(invocation, taskDir, canceled, progress, onLine, heapMiBOverride);
    }

    /**
     * 跑一次模型采集（把 {@code models.json.gz} 缓存到 {@code workDir}）。默认实现表示引擎不支持。
     *
     * @param workDir       采集工作目录（产物固定为 {@code <workDir>/models.json.gz}）
     * @param vanillaJar    原版 client jar（Voxelith 的 {@code --pack-dir}）
     * @param modJars       mod jar 列表（Voxelith 的 {@code --mod}，可重复）
     * @param mcVersion     采集版本；<b>必须显式给</b>——采集 CLI 自己不做自动判定
     *                      （{@code HarvestCliOptions} 默认写死 1.20.1），版本错了就是整片品红
     * @param loaderVersion Fabric loader 版本
     * @param provisionCache MC/Fabric 依赖下载缓存目录；跨版本共用一份，避免重复下载
     */
    default VoxelithProcessRunner.RunResult harvestModels(Path workDir, Path vanillaJar, List<Path> modJars,
                                                          String mcVersion, String loaderVersion,
                                                          Path provisionCache, Duration timeout,
                                                          VoxelithProcessRunner.LineConsumer onLine) {
        throw new UnsupportedOperationException("当前引擎不支持模型采集");
    }

    /**
     * 跑一次全景图烘焙（服务端把整张地图烘成一张透视全景图）。
     *
     * <p>输入是渲染留下的中间产物（工作目录里的 {@code surface.png}/{@code heightfield.bin}），
     * 不重跑 3D 管线。默认实现表示引擎不支持。</p>
     *
     * @param invocation 全景参数
     * @param workDir    日志与临时文件目录
     */
    default VoxelithProcessRunner.RunResult renderPanorama(VoxelithCliArguments.PanoramaInvocation invocation,
                                                           Path workDir, Duration timeout,
                                                           VoxelithProcessRunner.LineConsumer onLine) {
        throw new UnsupportedOperationException("当前引擎不支持全景图烘焙");
    }

    /**
     * 启动一个分片 worker 长驻进程（抢占共享队列里的 region 分片做增量重渲染）。
     *
     * <p>返回的句柄由调用方负责 {@code stop()}；插件卸载时必须停掉，否则留下孤儿 JVM。
     * 默认实现表示引擎不支持。</p>
     *
     * @param invocation worker 参数
     * @param workDir    日志与临时文件目录
     * @param heapMiB    堆上限（{@code 0} = 不传）
     */
    default VoxelithProcessRunner.ActiveProcess startShardWorker(
            VoxelithCliArguments.ShardWorkerInvocation invocation,
            Path workDir, int heapMiB,
            VoxelithProcessRunner.LineConsumer onLine) throws java.io.IOException {
        throw new UnsupportedOperationException("当前引擎不支持分片 worker");
    }

    /**
     * 一次渲染的结果。
     *
     * @param result      进程执行结果（退出码/超时/取消/日志）
     * @param publishRoot 管线发布的单张地图目录（{@code <taskDir>/publish/<mapId>}）；失败时可能不存在
     */
    record RenderOutcome(VoxelithProcessRunner.RunResult result, Path publishRoot) {

        public boolean succeeded() {
            return result.succeeded();
        }
    }

    /** 进度回调。 */
    @FunctionalInterface
    interface ProgressSink {
        /**
         * @param phase      阶段
         * @param percent    该阶段下的整体百分比
         * @param message    展示消息
         * @param tilesDone  该行携带的已完成瓦片计数；非计数行为 {@code null}
         * @param tilesTotal 该行携带的瓦片总数；非计数行为 {@code null}
         */
        void accept(RenderPhase phase, int percent, String message,
                    Integer tilesDone, Integer tilesTotal);

        /** 无操作实现（测试与不需要进度的场合）。 */
        static ProgressSink noop() {
            return (phase, percent, message, tilesDone, tilesTotal) -> {
            };
        }
    }

    /**
     * 把逐行输出收敛成进度事件的辅助器：识别不出阶段的行原样透传成日志。
     *
     * <p>可复用，避免每个引擎各写一遍正则。</p>
     */
    final class LineProgressRelay {

        private final ProgressSink sink;
        private final VoxelithProcessRunner.LineConsumer logSink;
        private final AtomicReference<RenderPhase> lastPhase = new AtomicReference<>(RenderPhase.SCAN);

        public LineProgressRelay(ProgressSink sink, VoxelithProcessRunner.LineConsumer logSink) {
            this.sink = sink == null ? ProgressSink.noop() : sink;
            this.logSink = logSink == null ? line -> {
            } : logSink;
        }

        /** 处理一行输出。 */
        public void accept(String line) {
            logSink.accept(line);
            if (line == null || line.isBlank()) {
                return;
            }
            VoxelithProgressParser.parse(line).ifPresent(event -> {
                lastPhase.set(event.phase());
                sink.accept(event.phase(), event.percent(), event.message(),
                        event.tilesDone(), event.tilesTotal());
            });
        }

        /** 当前阶段（导入阶段由编排层单独上报）。 */
        public RenderPhase currentPhase() {
            return lastPhase.get();
        }
    }
}
