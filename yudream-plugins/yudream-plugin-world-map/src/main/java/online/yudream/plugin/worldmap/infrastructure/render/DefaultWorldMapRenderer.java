package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BiomeColors;
import online.yudream.plugin.worldmap.infrastructure.resource.BlockModelRegistry;
import online.yudream.plugin.worldmap.infrastructure.resource.ResourcePacks;
import online.yudream.plugin.worldmap.infrastructure.world.WorldAccess;
import online.yudream.plugin.worldmap.infrastructure.world.WorldLoader;
import online.yudream.plugin.worldmap.domain.enumerate.RenderPhase;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link WorldMapRenderer} 默认实现（多线程）。
 *
 * <p>处理流程：加载客户端资产 → 输出贴图集 → 按 region 排序的 tile 列表分片，
 * 多个 worker 并行生成 hires（每 worker 独立 WorldAccess/StateOcclusion，
 * 共享只读的模型注册表与贴图集；共享宿主的 CPU 上限保守取 4 线程）→
 * 生成 lowres 金字塔。每 tile 检查中断，支持取消。</p>
 */
public final class DefaultWorldMapRenderer implements WorldMapRenderer {

    /** 并行 worker 数：共享宿主 JVM，保守取（核数-2，上限 4） */
    private static int workerCount() {
        return Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    }

    @Override
    public RenderSummary render(RenderJob job, TileSink sink, ProgressListener progress) throws IOException {
        long start = System.currentTimeMillis();
        if (progress != null) {
            progress.phase(RenderPhase.ASSETS, "Loading client assets");
        }
        BlockModelRegistry registry = ResourcePacks.load(job.clientJar());
        byte[] atlasPng = registry.atlas().png();
        sink.putAtlas(atlasPng);

        boolean nether = isNether(job.dimension());
        BiomeColors biomeColors = biomeColorsOf(registry);

        // hires tile 列表：按 region 文件（16×16 tile）分组排序，提高区块缓存命中
        List<int[]> tiles = new ArrayList<>();
        for (int tx = job.minTileX(); tx <= job.maxTileX(); tx++) {
            for (int tz = job.minTileZ(); tz <= job.maxTileZ(); tz++) {
                tiles.add(new int[]{tx, tz});
            }
        }
        tiles.sort(Comparator.comparingInt((int[] t) -> t[0] >> 4)
                .thenComparingInt(t -> t[1] >> 4)
                .thenComparingInt(t -> t[0])
                .thenComparingInt(t -> t[1]));

        int total = tiles.size();
        if (progress != null) {
            progress.phase(RenderPhase.HIRES, "Rendering detailed tiles");
        }
        AtomicInteger done = new AtomicInteger();
        AtomicInteger hiresCount = new AtomicInteger();
        TileSink safeSink = new SynchronizedTileSink(sink);

        int workers = Math.min(workerCount(), Math.max(1, total));
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "world-map-tile-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<List<int[]>> chunks = partition(tiles, workers);
            List<Future<?>> futures = new ArrayList<>();
            for (List<int[]> chunk : chunks) {
                futures.add(pool.submit(() -> renderHiresChunk(job, registry, biomeColors, chunk, safeSink, progress, done, total, hiresCount, nether)));
            }
            await(futures);

            // lowres：单线程（量小且按金字塔依赖）
            int lowresCount = 0;
            if (total > 0) {
                if (progress != null) {
                    progress.phase(RenderPhase.LOWRES, "Rendering overview tiles");
                }
                checkInterrupted();
                try (WorldAccess raw = WorldLoader.load(job.worldDir(), job.dimension())) {
                    RenderWorldView world = new RenderWorldView(raw, nether, nether && job.stripNetherCeiling());
                    LowresRenderer lowres = new LowresRenderer(world, registry, biomeColors);
                    lowresCount = lowres.render(
                            job.minTileX() * HiresTileRenderer.TILE_SIZE,
                            job.minTileZ() * HiresTileRenderer.TILE_SIZE,
                            (job.maxTileX() + 1) * HiresTileRenderer.TILE_SIZE,
                            (job.maxTileZ() + 1) * HiresTileRenderer.TILE_SIZE,
                            safeSink, progress, total);
                }
            }
            return new RenderSummary(hiresCount.get(), lowresCount, atlasPng.length,
                    System.currentTimeMillis() - start);
        } finally {
            pool.shutdownNow();
        }
    }

    private void renderHiresChunk(RenderJob job, BlockModelRegistry registry, BiomeColors biomeColors,
                                  List<int[]> chunk, TileSink sink, ProgressListener progress,
                                  AtomicInteger done, int total, AtomicInteger hiresCount, boolean nether) {
        try (WorldAccess raw = WorldLoader.load(job.worldDir(), job.dimension())) {
            RenderWorldView world = new RenderWorldView(raw, nether, nether && job.stripNetherCeiling());
            StateOcclusion occlusion = new StateOcclusion(registry);
            HiresTileRenderer hires = new HiresTileRenderer(world, registry, biomeColors, occlusion);
            for (int[] t : chunk) {
                checkInterrupted();
                byte[] gz = hires.renderTile(t[0], t[1]);
                if (gz != null) { // 空 tile 不产出
                    sink.putHiresTile(t[0], t[1], gz);
                    hiresCount.incrementAndGet();
                }
                int finished = done.incrementAndGet();
                if (progress != null) {
                    progress.progress(finished, total, "hires tile (" + t[0] + "," + t[1] + ")");
                }
            }
        } catch (InterruptedRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("hires 渲染失败: " + e.getMessage(), e);
        }
    }

    private static List<List<int[]>> partition(List<int[]> tiles, int chunks) {
        List<List<int[]>> result = new ArrayList<>();
        int size = tiles.size();
        int per = (size + chunks - 1) / chunks;
        for (int i = 0; i < size; i += per) {
            result.add(tiles.subList(i, Math.min(i + per, size)));
        }
        return result;
    }

    private static void await(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedRenderException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedRenderException interrupted) {
                    throw interrupted;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedRenderException();
        }
    }

    private static boolean isNether(String dimension) {
        String d = dimension == null ? "" : dimension.toLowerCase();
        if (d.startsWith("minecraft:")) {
            d = d.substring("minecraft:".length());
        }
        return d.equals("nether") || d.equals("the_nether");
    }

    /**
     * 取注册表实现类暴露的群系色表（包私有类，只能反射取用）；
     * 不可用时回退默认色（colormap 缺失 → 平原近似色）。
     */
    private static BiomeColors biomeColorsOf(BlockModelRegistry registry) {
        try {
            Method m = registry.getClass().getDeclaredMethod("biomeColors");
            m.setAccessible(true);
            if (m.invoke(registry) instanceof BiomeColors colors) {
                return colors;
            }
        } catch (ReflectiveOperationException | SecurityException | ClassCastException ignored) {
            // 实现类未暴露色表时回退默认
        }
        return new BiomeColors(null, null);
    }

    /** 渲染被取消（线程中断）。 */
    public static final class InterruptedRenderException extends RuntimeException {
        InterruptedRenderException() {
            super("渲染任务已取消", null, false, false);
        }
    }

    /** TileSink 线程安全包装（多 worker 共享）。 */
    private static final class SynchronizedTileSink implements TileSink {
        private final TileSink delegate;

        SynchronizedTileSink(TileSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void putHiresTile(int tx, int tz, byte[] gzipJson) throws IOException {
            delegate.putHiresTile(tx, tz, gzipJson);
        }

        @Override
        public synchronized void putLowresTile(int lod, int tx, int tz, byte[] png) throws IOException {
            delegate.putLowresTile(lod, tx, tz, png);
        }

        @Override
        public synchronized void putAtlas(byte[] png) throws IOException {
            delegate.putAtlas(png);
        }
    }
}
