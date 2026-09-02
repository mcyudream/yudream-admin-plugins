package online.yudream.base.plugin.mcwiki.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import online.yudream.base.plugin.mcwiki.application.JobService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderAssetStoreTest {
    private final Map<String,byte[]> storage=new HashMap<>();
    private final PluginFileStore files=new PluginFileStore() {
        @Override public String put(String key,InputStream in,long length,String contentType){try{storage.put(key,in.readAllBytes());}catch(Exception ex){throw new RuntimeException(ex);}return key;}
        @Override public PluginStoredFile get(String key){byte[] data=storage.get(key);return data==null?null:new PluginStoredFile(key,"image/png",(long)data.length,new ByteArrayInputStream(data));}
        @Override public void delete(String key){storage.remove(key);}
    };
    private final Map<String,Map<String,Map<String,Object>>> collections=new ConcurrentHashMap<>();
    private final PluginDocumentStore documents=new PluginDocumentStore() {
        public Map<String,Object> save(String collection,String id,Map<String,Object> document){Map<String,Object> copy=new HashMap<>(document);copy.put("id",id);collections.computeIfAbsent(collection,k->new ConcurrentHashMap<>()).put(id,copy);return copy;}
        public Optional<Map<String,Object>> findById(String collection,String id){return Optional.ofNullable(collections.getOrDefault(collection,Map.of()).get(id));}
        public List<Map<String,Object>> findAll(String collection,int page,int size){List<Map<String,Object>> docs=collections.getOrDefault(collection,Map.of()).values().stream().sorted(Comparator.comparing(d->String.valueOf(d.get("id")))).toList();int from=Math.min((Math.max(page,1)-1)*Math.max(size,1),docs.size());return new ArrayList<>(docs.subList(from,Math.min(from+Math.max(size,1),docs.size())));}
        public List<Map<String,Object>> findByField(String collection,String field,Object value,int page,int size){return List.of();}
        public long count(String collection){return collections.getOrDefault(collection,Map.of()).size();}
        public void delete(String collection,String id){collections.getOrDefault(collection,Map.of()).remove(id);}
    };

    @Test void updateStoresRendersAndMetaFromZipball() throws Exception {
        byte[] zip=zipball("abc1234",Map.of("item-assets/APPLE.png",new byte[]{1},"entity-assets/flat/ZOMBIE.png",new byte[]{2},"entity-assets/isometric/ZOMBIE.png",new byte[]{3}),
                "# Renders\nLast updated: Minecraft <!--MC TOKEN-->26.3 Snapshot 7<!--MC TOKEN-->\n");
        RenderAssetStore store=new RenderAssetStore(files,documents,url->zip);
        new JobService(documents,Runnable::run,(v,c)->store.update(c)).create(RenderAssetStore.JOB_VERSION);
        assertArrayEquals(new byte[]{1},store.render("minecraft:apple","item").orElseThrow());
        assertArrayEquals(new byte[]{3},store.render("minecraft:zombie","entity").orElseThrow(),"生物必须优先等轴渲染");
        RenderAssetStore.Meta meta=store.meta().orElseThrow();
        assertEquals("abc1234",meta.commit());
        assertEquals("26.3 Snapshot 7",meta.gameVersion());
        assertEquals(1,meta.items()); assertEquals(1,meta.entitiesFlat()); assertEquals(1,meta.entitiesIsometric());
    }

    @Test void entityFallsBackToFlatWhenIsometricMissing() throws Exception {
        byte[] zip=zipball("def5678",Map.of("entity-assets/flat/COW.png",new byte[]{9}),"readme");
        RenderAssetStore store=new RenderAssetStore(files,documents,url->zip);
        new JobService(documents,Runnable::run,(v,c)->store.update(c)).create(RenderAssetStore.JOB_VERSION);
        assertArrayEquals(new byte[]{9},store.render("minecraft:cow","entity").orElseThrow());
    }

    @Test void updateCleansUpDelistedRenders() throws Exception {
        byte[] first=zipball("aaa0001",Map.of("item-assets/APPLE.png",new byte[]{1},"item-assets/STONE.png",new byte[]{2}),"readme");
        RenderAssetStore store=new RenderAssetStore(files,documents,url->first);
        new JobService(documents,Runnable::run,(v,c)->store.update(c)).create(RenderAssetStore.JOB_VERSION);
        assertTrue(store.render("minecraft:stone","item").isPresent());
        byte[] secondZip=zipball("bbb0002",Map.of("item-assets/APPLE.png",new byte[]{1}),"readme");
        RenderAssetStore second=new RenderAssetStore(files,documents,url->secondZip);
        new JobService(documents,Runnable::run,(v,c)->second.update(c)).create(RenderAssetStore.JOB_VERSION);
        assertTrue(second.render("minecraft:apple","item").isPresent());
        assertTrue(second.render("minecraft:stone","item").isEmpty(),"下架的渲染图必须被清理");
    }

    @Test void renderNameMapsIdsAndLegacyAliases() {
        RenderAssetStore store=new RenderAssetStore(files,documents,url->new byte[0]);
        assertEquals("ACACIA_BOAT",store.renderName("minecraft:acacia_boat"));
        assertEquals("SHORT_GRASS",store.renderName("minecraft:short_grass"));
        assertEquals("STONE",store.renderName("stone"),"无命名空间 id 也要可用");
        assertEquals("WHITE_WOOL",store.renderName("minecraft:wool"),"1.12.2 旧 id 走别名映射");
        assertEquals("CRAFTING_TABLE",store.renderName("minecraft:workbench"));
        assertEquals("OAK_BOAT",store.renderName("minecraft:boat"));
        assertEquals("IRON_CHAIN",store.renderName("minecraft:chain"),"新版本把 chain 重命名为 iron_chain，渲染库跟随最新命名");
        assertEquals("REDSTONE",store.renderName("minecraft:redstone_wire"),"方块形态回退到物品形态");
    }

    private static byte[] zipball(String sha,Map<String,byte[]> entries,String readme) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry("Owen1212055-mc-assets-"+sha+"/README.md"));zip.write(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry("Owen1212055-mc-assets-"+sha+"/"+entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return out.toByteArray();
    }
}
