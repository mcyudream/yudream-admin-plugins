package online.yudream.plugin.worldmap.infrastructure.resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资源包门面：从原版客户端 jar 构建 {@link BlockModelRegistry}。
 *
 * <p>加载流程：读取 jar 资产索引 → 解析全部 blockstate 定义 →
 * 预收集全部引用纹理并拼接贴图集 → 组装注册表（模型烘焙按需懒执行）。</p>
 */
public final class ResourcePacks {

    private ResourcePacks() {
    }

    /**
     * 从客户端 jar（1.20–1.21.x）加载渲染资产。
     *
     * @param clientJar 原版客户端 jar 路径
     * @return 方块模型注册表（含贴图集与群系染色）
     * @throws UncheckedIOException jar 读取失败时抛出
     */
    public static BlockModelRegistry load(Path clientJar) {
        final ResourcePackLoader.LoadedPack pack;
        try {
            pack = ResourcePackLoader.load(clientJar);
        } catch (IOException e) {
            throw new UncheckedIOException("客户端 jar 读取失败: " + clientJar, e);
        }

        // 1. 解析全部 blockstate 定义
        Map<String, BlockStateDefinition> definitions = new HashMap<>();
        pack.blockstates().forEach((name, raw) -> {
            BlockStateDefinition def = BlockStateDefinition.parse(ResourcePackLoader.parseJson(raw));
            if (def != null) {
                definitions.put(name, def);
            }
        });

        // 2. 预收集全部 blockstate 引用的纹理路径，保证贴图集完整
        ModelResolver resolver = new ModelResolver(pack.models());
        Set<String> neededTextures = collectTextures(definitions, resolver);
        // 内置液体模型使用的 still 贴图（原版仅被 particle 引用，不会被 face 收集到）
        neededTextures.add("block/water_still");
        neededTextures.add("block/lava_still");

        // 3. 拼接贴图集（含缺失纹理占位）
        TextureAtlas atlas = new TextureAtlas();
        atlas.add(TextureAtlas.MISSING_TEXTURE, TextureAtlas.missingTextureImage());
        for (String path : neededTextures) {
            byte[] png = pack.textures().get(path);
            if (png == null) {
                atlas.add(path, TextureAtlas.missingTextureImage());
                continue;
            }
            try {
                atlas.add(path, ResourcePackLoader.decodePng(png));
            } catch (UncheckedIOException e) {
                // 个别纹理解码失败不拖垮整体加载
                atlas.add(path, TextureAtlas.missingTextureImage());
            }
        }
        atlas.build();

        // 4. 组装注册表
        BiomeColors biomeColors = new BiomeColors(pack.grassColormap(), pack.foliageColormap());
        ModelBaker baker = new ModelBaker(resolver, atlas);
        return new DefaultBlockModelRegistry(definitions, resolver, baker, atlas, biomeColors);
    }

    /** 遍历全部 blockstate 定义，收集其模型树引用的纹理路径。 */
    private static Set<String> collectTextures(Map<String, BlockStateDefinition> definitions,
                                               ModelResolver resolver) {
        Set<String> paths = new HashSet<>();
        for (BlockStateDefinition def : definitions.values()) {
            for (String modelPath : def.allModelPaths()) {
                ResolvedModel model = resolver.resolve(modelPath);
                if (!model.found()) {
                    continue;
                }
                for (var element : model.elements()) {
                    for (var face : element.faces().values()) {
                        String texture = resolver.resolveFaceTexture(model, face.texture());
                        if (texture != null) {
                            paths.add(texture);
                        }
                    }
                }
            }
        }
        return paths;
    }
}
