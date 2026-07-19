package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.plugin.worldmap.application.service.GenerationPublisher;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Imports only validated file-storage tiles from a task-local BlueMap worker output. */
public final class BlueMapFileGenerationImporter {

    private static final Pattern TILE = Pattern.compile("x(-?\\d+)z(-?\\d+)\\.prbm(?:\\.gz)?");
    private static final Pattern LOWRES = Pattern.compile("x(-?\\d+)z(-?\\d+)\\.png");
    private static final int MAX_TILES = 1_000_000;
    private static final int MAX_ASSET_BYTES = 128 * 1024 * 1024;

    public BlueMapImportSummary importStorage(Path storageRoot, MapGeneration generation,
                                               GenerationPublisher publisher) throws IOException {
        Path root = storageRoot.toAbsolutePath().normalize();
        Path tiles = root.resolve("tiles").normalize();
        if (!tiles.startsWith(root) || !Files.isDirectory(tiles)) {
            throw new IOException("BlueMap output does not contain a tile storage directory");
        }
        int hires = importHires(tiles.resolve("0"), generation, publisher);
        int lowres = 0;
        try (Stream<Path> levels = Files.list(tiles)) {
            for (Path level : levels.filter(Files::isDirectory).toList()) {
                String name = level.getFileName().toString();
                if ("0".equals(name)) continue;
                int lod;
                try {
                    lod = Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (lod < 1 || lod > 16) continue;
                lowres += importLowres(level, lod, generation, publisher);
                assertTileLimit(hires + lowres);
            }
        }
        return new BlueMapImportSummary(hires, lowres);
    }

    private int importHires(Path root, MapGeneration generation, GenerationPublisher publisher) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = TILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                byte[] prbm = readHires(path);
                if (prbm.length < 8 || prbm[0] != 1) {
                    throw new IOException("Unsupported BlueMap PRBM tile: " + path.getFileName());
                }
                publisher.saveBlueMapHires(generation, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), prbm);
                assertTileLimit(++count);
            }
        }
        return count;
    }

    private int importLowres(Path root, int lod, MapGeneration generation, GenerationPublisher publisher) throws IOException {
        int count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = LOWRES.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                publisher.saveLowres(generation, lod, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        readLimited(Files.newInputStream(path)));
                assertTileLimit(++count);
            }
        }
        return count;
    }

    private byte[] readHires(Path path) throws IOException {
        try (InputStream input = path.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(Files.newInputStream(path)) : Files.newInputStream(path)) {
            return readLimited(input);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        try (input) {
            byte[] data = input.readNBytes(MAX_ASSET_BYTES + 1);
            if (data.length > MAX_ASSET_BYTES) throw new IOException("BlueMap tile exceeds import size limit");
            return data;
        }
    }

    private void assertTileLimit(int count) throws IOException {
        if (count > MAX_TILES) throw new IOException("BlueMap output exceeds tile import limit");
    }
}
