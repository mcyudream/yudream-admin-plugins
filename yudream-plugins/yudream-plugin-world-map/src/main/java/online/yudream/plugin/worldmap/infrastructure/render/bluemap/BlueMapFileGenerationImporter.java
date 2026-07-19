package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.plugin.worldmap.application.service.GenerationPublisher;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    public BlueMapImportSummary importStorage(Path storageRoot, MapGeneration generation,
                                               GenerationPublisher publisher) throws IOException {
        Path root = storageRoot.toAbsolutePath().normalize();
        Path tiles = root.resolve("tiles").normalize();
        if (!tiles.startsWith(root) || !Files.isDirectory(tiles)) {
            throw new IOException("BlueMap output does not contain a tile storage directory");
        }
        int hires = importHires(tiles.resolve("0"), generation, publisher);
        publisher.saveBlueMapTextures(generation, readTextures(root));
        publisher.saveBlueMapSettings(generation, readSettings(root));
        Map<Integer, List<TileCoordinate>> lowresIndex = new HashMap<>();
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
                List<TileCoordinate> imported = importLowres(level, lod, generation, publisher);
                lowres += imported.size();
                lowresIndex.put(lod, imported);
                assertTileLimit(hires + lowres);
            }
        }
        publisher.saveBlueMapLowresIndex(generation, JSON.writeValueAsBytes(lowresIndex(lowresIndex)));
        return new BlueMapImportSummary(hires, lowres);
    }

    private byte[] readTextures(Path root) throws IOException {
        Path plain = root.resolve("textures.json");
        Path gzip = root.resolve("textures.json.gz");
        if (Files.isRegularFile(plain)) return readLimited(Files.newInputStream(plain));
        if (Files.isRegularFile(gzip)) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(gzip))) {
                return readLimited(input);
            }
        }
        throw new IOException("BlueMap output does not contain textures.json");
    }

    private byte[] readSettings(Path root) throws IOException {
        Path settings = root.resolve("settings.json");
        if (!Files.isRegularFile(settings)) {
            throw new IOException("BlueMap output does not contain settings.json");
        }
        byte[] data = readLimited(Files.newInputStream(settings));
        validateSettings(data);
        return data;
    }

    private void validateSettings(byte[] data) throws IOException {
        JsonNode root = JSON.readTree(data);
        if (root == null || !root.isObject()) throw new IOException("BlueMap settings.json must be an object");
        int hiresX = vectorComponent(root, "hires", "tileSize", 0, "x");
        int hiresY = vectorComponent(root, "hires", "tileSize", 1, "y");
        int lowresX = vectorComponent(root, "lowres", "tileSize", 0, "x");
        int lowresY = vectorComponent(root, "lowres", "tileSize", 1, "y");
        if (hiresX != hiresY || lowresX != lowresY) {
            throw new IOException("BlueMap settings tiles must be square");
        }
        positive(root, "lowres", "lodCount");
        positive(root, "lowres", "lodFactor");
    }

    /** BlueMap v5 serializes Vector2i as [x, y]; object support keeps older imported output readable. */
    private int vectorComponent(JsonNode root, String section, String field, int index, String objectField) throws IOException {
        JsonNode vector = root.path(section).path(field);
        JsonNode value = vector.isArray() ? vector.path(index) : vector.path(objectField);
        if (!value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 4096) {
            throw new IOException("Invalid BlueMap settings value: " + section + "." + field + "." + objectField);
        }
        return value.intValue();
    }

    private int positive(JsonNode root, String... path) throws IOException {
        JsonNode value = root;
        for (String segment : path) value = value.path(segment);
        if (!value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 4096) {
            throw new IOException("Invalid BlueMap settings value: " + String.join(".", path));
        }
        return value.intValue();
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

    private List<TileCoordinate> importLowres(Path root, int lod, MapGeneration generation, GenerationPublisher publisher) throws IOException {
        List<TileCoordinate> imported = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = LOWRES.matcher(path.getFileName().toString());
                if (!matcher.matches()) continue;
                int x = Integer.parseInt(matcher.group(1));
                int z = Integer.parseInt(matcher.group(2));
                publisher.saveLowres(generation, lod, x, z,
                        readLimited(Files.newInputStream(path)));
                imported.add(new TileCoordinate(x, z));
                assertTileLimit(imported.size());
            }
        }
        return imported;
    }

    /** Encodes sparse BlueMap tile coverage as contiguous x-ranges per z row. */
    private Map<String, Object> lowresIndex(Map<Integer, List<TileCoordinate>> levels) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<Integer, List<TileCoordinate>> level : levels.entrySet()) {
            Map<Integer, List<Integer>> rows = new HashMap<>();
            for (TileCoordinate tile : level.getValue()) {
                rows.computeIfAbsent(tile.z(), ignored -> new ArrayList<>()).add(tile.x());
            }
            List<List<Object>> encodedRows = new ArrayList<>();
            rows.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
                List<Integer> xs = row.getValue().stream().distinct().sorted().toList();
                List<List<Integer>> ranges = new ArrayList<>();
                int start = xs.getFirst();
                int previous = start;
                for (int index = 1; index < xs.size(); index++) {
                    int value = xs.get(index);
                    if (value != previous + 1) {
                        ranges.add(List.of(start, previous));
                        start = value;
                    }
                    previous = value;
                }
                ranges.add(List.of(start, previous));
                encodedRows.add(List.of(row.getKey(), ranges));
            });
            result.put(Integer.toString(level.getKey()), encodedRows);
        }
        return Map.of("levels", result);
    }

    private record TileCoordinate(int x, int z) {
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
