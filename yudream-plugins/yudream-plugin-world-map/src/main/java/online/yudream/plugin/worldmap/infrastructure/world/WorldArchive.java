package online.yudream.plugin.worldmap.infrastructure.world;

import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtReader;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 存档压缩包工具：解包、定位世界根目录、读取出生点、推导 tile 渲染范围。
 */
public final class WorldArchive {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private WorldArchive() {
    }

    /**
     * 解压存档 zip 到目标目录（已存在则先清空）。
     */
    public static Path extract(Path zipFile, Path targetDir) throws IOException {
        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);
        try (InputStream input = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..") || name.startsWith("/")) {
                    continue;
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        return targetDir;
    }

    /**
     * 定位世界根目录（包含 region/*.mca 的目录，最多向下找两层）。
     */
    public static Path resolveWorldRoot(Path extractedDir) throws IOException {
        try (Stream<Path> stream = Files.walk(extractedDir, 3)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().equals("region"))
                    .filter(WorldArchive::containsMca)
                    .map(Path::getParent)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("存档中未找到 region 数据（未识别的世界目录结构）"));
        }
    }

    /**
     * 读取 level.dat 出生点，缺失时返回默认 (0, 64, 0)。
     */
    public static int[] spawn(Path worldRoot) {
        Path levelDat = worldRoot.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return new int[]{0, 64, 0};
        }
        try (InputStream input = Files.newInputStream(levelDat)) {
            NbtTag root = NbtReader.read(input);
            NbtTag data = root.get("Data");
            if (data == null) {
                return new int[]{0, 64, 0};
            }
            return new int[]{
                    data.getInt("SpawnX", 0),
                    data.getInt("SpawnY", 64),
                    data.getInt("SpawnZ", 0)
            };
        } catch (Exception e) {
            return new int[]{0, 64, 0};
        }
    }

    /**
     * 按维度 region 目录中的文件名推导 hires tile 范围 [minTx, minTz, maxTx, maxTz]。
     * 一个 region 覆盖 512×512 方块 = 16×16 tile。
     */
    public static int[] tileRange(Path worldRoot, String dimension) throws IOException {
        Path regionDir = dimensionRegionDir(worldRoot, dimension);
        if (!Files.isDirectory(regionDir)) {
            throw new IllegalArgumentException("存档缺少维度目录：" + regionDir);
        }
        int minTx = Integer.MAX_VALUE, minTz = Integer.MAX_VALUE;
        int maxTx = Integer.MIN_VALUE, maxTz = Integer.MIN_VALUE;
        try (Stream<Path> stream = Files.list(regionDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int rx = Integer.parseInt(matcher.group(1));
                int rz = Integer.parseInt(matcher.group(2));
                minTx = Math.min(minTx, rx * 16);
                minTz = Math.min(minTz, rz * 16);
                maxTx = Math.max(maxTx, rx * 16 + 15);
                maxTz = Math.max(maxTz, rz * 16 + 15);
            }
        }
        if (minTx > maxTx) {
            throw new IllegalArgumentException("维度 " + dimension + " 中没有可用的 region 文件");
        }
        return new int[]{minTx, minTz, maxTx, maxTz};
    }

    private static Path dimensionRegionDir(Path worldRoot, String dimension) {
        return switch (dimension == null ? "overworld" : dimension) {
            case "nether" -> worldRoot.resolve("DIM-1").resolve("region");
            case "the_end" -> worldRoot.resolve("DIM1").resolve("region");
            default -> worldRoot.resolve("region");
        };
    }

    private static boolean containsMca(Path regionDir) {
        try (Stream<Path> stream = Files.list(regionDir)) {
            return stream.anyMatch(path -> REGION_FILE.matcher(path.getFileName().toString()).matches());
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
