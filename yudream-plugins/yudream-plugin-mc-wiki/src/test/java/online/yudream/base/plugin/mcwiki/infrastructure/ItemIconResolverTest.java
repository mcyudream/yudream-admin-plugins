package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemIconResolverTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void flatItemResolvesLayer0() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,
                Map.of("apple","{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/apple\"}}","generated","{}"),
                Map.of());
        ItemIconResolver.Resolved resolved=resolver.resolve("minecraft:apple","item");
        assertEquals(List.of("item/apple.png"),resolved.layers());
        assertTrue(resolved.faces().isEmpty());
    }

    @Test void multiLayerItemCompositesBottomToTop() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,
                Map.of("potion","{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/potion\",\"layer1\":\"item/potion_overlay\"}}","generated","{}"),
                Map.of());
        assertEquals(List.of("item/potion.png","item/potion_overlay.png"),resolver.resolve("minecraft:potion","item").layers());
    }

    @Test void blockItemChainResolvesFacesAndRepresentativeIcon() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,
                Map.of("stone","{\"parent\":\"block/stone\"}"),
                Map.of("stone","{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"block/stone\"}}",
                        "cube_all","{\"parent\":\"block/block\",\"textures\":{\"particle\":\"#all\"}}",
                        "block","{}"));
        ItemIconResolver.Resolved resolved=resolver.resolve("minecraft:stone","item");
        assertEquals(List.of("block/stone.png"),resolved.layers());
        assertEquals("block/stone.png",resolved.faces().get("all"));
        assertEquals("block/stone.png",resolved.faces().get("particle"),"父模型里的 #all 引用必须解析到子模型定义的贴图");
    }

    @Test void blockWithoutItemModelFallsBackToBlockModel() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,Map.of(),
                Map.of("acacia_button","{\"parent\":\"block/button\",\"textures\":{\"texture\":\"block/acacia_planks\"}}","button","{\"parent\":\"block/block\"}","block","{}"));
        ItemIconResolver.Resolved resolved=resolver.resolve("minecraft:acacia_button","block");
        assertEquals(List.of("block/acacia_planks.png"),resolved.layers());
        assertEquals("block/acacia_planks.png",resolved.faces().get("texture"));
    }

    @Test void namespacedAndNamespacedlessValuesBothResolve() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,
                Map.of("apple","{\"textures\":{\"layer0\":\"minecraft:item/apple\"}}"),Map.of());
        assertEquals(List.of("item/apple.png"),resolver.resolve("apple","item").layers());
    }

    @Test void unknownModelYieldsEmpty() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,Map.of(),Map.of());
        ItemIconResolver.Resolved resolved=resolver.resolve("minecraft:missing","item");
        assertTrue(resolved.layers().isEmpty());
        assertTrue(resolved.faces().isEmpty());
    }

    @Test void cyclicParentsStopAtDepthLimit() {
        ItemIconResolver resolver=new ItemIconResolver(mapper,
                Map.of("a","{\"parent\":\"item/b\",\"textures\":{\"layer0\":\"item/a\"}}","b","{\"parent\":\"item/a\"}"),Map.of());
        assertEquals(List.of("item/a.png"),resolver.resolve("minecraft:a","item").layers());
    }
}
