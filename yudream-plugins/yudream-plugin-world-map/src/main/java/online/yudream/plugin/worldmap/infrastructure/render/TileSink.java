package online.yudream.plugin.worldmap.infrastructure.render;

import java.io.IOException;

/**
 * tile 输出接收器：渲染层只负责产出字节，存储由上层（infrastructure/storage）实现。
 * 空 hires tile / 全透明 lowres tile 不会回调。
 */
public interface TileSink {

    /** 写出一个 hires tile（gzip 压缩的 JSON，格式见 CONTRACT §4）。 */
    void putHiresTile(int tx, int tz, byte[] gzipJson) throws IOException;

    /** 写出一个 lowres tile（512×512 PNG；lod0 = 1 方块/px）。 */
    void putLowresTile(int lod, int tx, int tz, byte[] png) throws IOException;

    /** 写出贴图集 PNG（渲染开始时调用一次）。 */
    void putAtlas(byte[] png) throws IOException;
}
