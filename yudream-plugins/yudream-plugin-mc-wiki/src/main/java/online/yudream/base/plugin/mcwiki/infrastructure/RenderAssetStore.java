package online.yudream.base.plugin.mcwiki.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import online.yudream.base.plugin.mcwiki.application.JobService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

/**
 * 共享渲染资产库：从 Owen1212055/mc-assets 拉取官方风格的 256x256 背包渲染图，
 * 所有导入版本共用同一份渲染图（版本导入不再各自下载贴图）。键空间：
 * renders/item/<NAME>.png、renders/entity/{flat,isometric}/<NAME>.png，NAME 为现代原版 id 大写。
 */
public final class RenderAssetStore {
    /** 伪版本号：JobService 以该值派发“一键更新渲染资产”任务，区别于真实 MC 版本导入。 */
    public static final String JOB_VERSION = "__renders__";
    public static final String SOURCE_URL = "https://codeload.github.com/Owen1212055/mc-assets/zip/refs/heads/main";
    private static final String COLLECTION = "renders_meta";
    private static final String META_ID = "current";
    private static final Pattern GAME_VERSION = Pattern.compile("Last updated:\\s*Minecraft\\s*<!--MC TOKEN-->(.*?)<!--MC TOKEN-->");
    /** 旧版本（1.12.2 及更早）物品 id → 现代渲染名，覆盖视觉主体；未覆盖的旧 id 自然回退到缺图。 */
    private static final Map<String,String> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("planks","OAK_PLANKS"), Map.entry("log","OAK_LOG"), Map.entry("log2","ACACIA_LOG"),
            Map.entry("leaves","OAK_LEAVES"), Map.entry("leaves2","ACACIA_LEAVES"), Map.entry("sapling","OAK_SAPLING"),
            Map.entry("grass","GRASS_BLOCK"), Map.entry("tallgrass","SHORT_GRASS"), Map.entry("double_plant","SUNFLOWER"),
            Map.entry("red_flower","POPPY"), Map.entry("yellow_flower","DANDELION"),
            Map.entry("wool","WHITE_WOOL"), Map.entry("carpet","WHITE_CARPET"), Map.entry("banner","WHITE_BANNER"), Map.entry("bed","RED_BED"),
            Map.entry("concrete","WHITE_CONCRETE"), Map.entry("concrete_powder","WHITE_CONCRETE_POWDER"),
            Map.entry("stained_glass","WHITE_STAINED_GLASS"), Map.entry("stained_glass_pane","WHITE_STAINED_GLASS_PANE"),
            Map.entry("stained_hardened_clay","WHITE_TERRACOTTA"), Map.entry("hardened_clay","TERRACOTTA"),
            Map.entry("silver_shulker_box","LIGHT_GRAY_SHULKER_BOX"), Map.entry("silver_glazed_terracotta","LIGHT_GRAY_GLAZED_TERRACOTTA"),
            Map.entry("stonebrick","STONE_BRICKS"), Map.entry("stone_slab","STONE_SLAB"), Map.entry("wooden_slab","OAK_SLAB"),
            Map.entry("double_stone_slab","SMOOTH_STONE_SLAB"), Map.entry("double_wooden_slab","OAK_SLAB"),
            Map.entry("fence","OAK_FENCE"), Map.entry("fence_gate","OAK_FENCE_GATE"), Map.entry("trapdoor","OAK_TRAPDOOR"),
            Map.entry("wooden_door","OAK_DOOR"), Map.entry("sign","OAK_SIGN"), Map.entry("boat","OAK_BOAT"),
            Map.entry("wooden_pressure_plate","OAK_PRESSURE_PLATE"), Map.entry("wooden_button","OAK_BUTTON"),
            Map.entry("workbench","CRAFTING_TABLE"), Map.entry("enchantment_table","ENCHANTING_TABLE"),
            Map.entry("snow_layer","SNOW"), Map.entry("snow","SNOW_BLOCK"), Map.entry("web","COBWEB"), Map.entry("waterlily","LILY_PAD"),
            Map.entry("noteblock","NOTE_BLOCK"), Map.entry("quartz_ore","NETHER_QUARTZ_ORE"), Map.entry("nether_brick","NETHER_BRICKS"),
            Map.entry("netherbrick","NETHER_BRICK"), Map.entry("end_bricks","END_STONE_BRICKS"), Map.entry("slime","SLIME_BLOCK"),
            Map.entry("magma","MAGMA_BLOCK"), Map.entry("monster_egg","INFESTED_STONE"), Map.entry("skull","SKELETON_SKULL"),
            Map.entry("golden_rail","POWERED_RAIL"), Map.entry("reeds","SUGAR_CANE"), Map.entry("dye","INK_SAC"),
            Map.entry("fish","COD"), Map.entry("cooked_fish","COOKED_COD"), Map.entry("clownfish","TROPICAL_FISH"),
            Map.entry("speckled_melon","GLISTERING_MELON_SLICE"), Map.entry("fireworks","FIREWORK_ROCKET"), Map.entry("firework_charge","FIREWORK_STAR"),
            Map.entry("record_13","MUSIC_DISC_13"), Map.entry("record_cat","MUSIC_DISC_CAT"), Map.entry("record_blocks","MUSIC_DISC_BLOCKS"),
            Map.entry("record_chirp","MUSIC_DISC_CHIRP"), Map.entry("record_far","MUSIC_DISC_FAR"), Map.entry("record_mall","MUSIC_DISC_MALL"),
            Map.entry("record_mellohi","MUSIC_DISC_MELLOHI"), Map.entry("record_stal","MUSIC_DISC_STAL"), Map.entry("record_strad","MUSIC_DISC_STRAD"),
            Map.entry("record_ward","MUSIC_DISC_WARD"), Map.entry("record_11","MUSIC_DISC_11"), Map.entry("record_wait","MUSIC_DISC_WAIT"),
            // 方块形态没有独立渲染图的 id：映射到对应物品形态
            Map.entry("redstone_wire","REDSTONE"), Map.entry("tripwire","STRING"),
            // 新版本重命名：chain 在更新版本改名为 iron_chain，渲染资产库跟随最新版命名
            Map.entry("chain","IRON_CHAIN"));

    public record Meta(String commit,String gameVersion,int items,int entitiesFlat,int entitiesIsometric,long updatedAt){}

    private final PluginFileStore files; private final PluginDocumentStore documents; private final Function<String,byte[]> downloader;
    public RenderAssetStore(PluginFileStore files,PluginDocumentStore documents,Function<String,byte[]> downloader){this.files=files;this.documents=documents;this.downloader=downloader;}

    /** 当前渲染资产元信息；未更新过时为空。 */
    public Optional<Meta> meta(){return documents.findById(COLLECTION,META_ID).map(m->new Meta(String.valueOf(m.getOrDefault("commit","")),String.valueOf(m.getOrDefault("gameVersion","")),number(m.get("items")),number(m.get("entitiesFlat")),number(m.get("entitiesIsometric")),longNumber(m.get("updatedAt"))));}

    /** namespacedId → 渲染文件名（不含 .png）；旧 id 经别名映射到现代名。 */
    public String renderName(String namespacedId){
        String path=namespacedId==null?"":namespacedId.substring(namespacedId.indexOf(':')+1);
        String alias=LEGACY_ALIASES.get(path);
        return alias!=null?alias:path.toUpperCase(Locale.ROOT);
    }

    /** 按 id 与类别取渲染图：生物优先等轴渲染、回退平面贴图；物品/方块取背包渲染图。 */
    public Optional<byte[]> render(String namespacedId,String kind){
        String name=renderName(namespacedId);
        if("entity".equals(kind)){Optional<byte[]> isometric=read("renders/entity/isometric/"+name+".png");if(isometric.isPresent())return isometric;return read("renders/entity/flat/"+name+".png");}
        Optional<byte[]> item=read("renders/item/"+name+".png");
        if(item.isPresent())return item;
        // 墙上/盆栽变种不是独立物品，渲染资产库没有对应图，回退到本体物品（WALL_HANGING_SIGN→HANGING_SIGN、POTTED_SAPLING→SAPLING）
        if(name.startsWith("WALL_"))return read("renders/item/"+name.substring(5)+".png");
        if(name.startsWith("POTTED_"))return read("renders/item/"+name.substring(7)+".png");
        return Optional.empty();
    }

    /**
     * 渲染覆盖判断，走元信息里名称清单的内存索引（适合批量构建目录，不产生文件 IO）；
     * 渲染资产尚未一键更新过时恒为 false。回退规则与 render 保持一致。
     */
    public boolean hasRender(String namespacedId,String kind){
        String name=renderName(namespacedId);
        NameIndex index=names();
        if("entity".equals(kind))return index.iso().contains(name)||index.flat().contains(name);
        if(index.items().contains(name))return true;
        if(name.startsWith("WALL_"))return index.items().contains(name.substring(5));
        if(name.startsWith("POTTED_"))return index.items().contains(name.substring(7));
        return false;
    }

    private record NameIndex(Set<String> items, Set<String> flat, Set<String> iso) {}
    private volatile NameIndex nameIndex;
    private NameIndex names(){
        NameIndex index=nameIndex;
        if(index==null)synchronized(this){
            index=nameIndex;
            if(index==null){
                Map<String,Object> meta=documents.findById(COLLECTION,META_ID).orElse(Map.of());
                index=new NameIndex(Set.copyOf(strings(meta.get("itemNames"))),Set.copyOf(strings(meta.get("flatNames"))),Set.copyOf(strings(meta.get("isoNames"))));
                nameIndex=index;
            }
        }
        return index;
    }

    /** 一键更新：下载 zipball → 写入文件库 → 清理下架渲染 → 更新元信息。 */
    public void update(JobService.Control control){
        control.progress("DOWNLOAD",0,4,"正在下载 mc-assets 渲染资产包");
        byte[] zip=downloader.apply(SOURCE_URL); control.checkpoint();
        control.progress("WRITE",1,4,"正在写入渲染图");
        String commit=""; String gameVersion="";
        int items=0, flat=0, iso=0, written=0;
        Set<String> itemNames=new LinkedHashSet<>(), flatNames=new LinkedHashSet<>(), isoNames=new LinkedHashSet<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip))){
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null){
                if(entry.isDirectory())continue;
                String name=entry.getName(); int slash=name.indexOf('/');
                if(slash<0)continue;
                String path=name.substring(slash+1);
                if(commit.isEmpty())commit=name.substring(0,slash).replace("Owen1212055-mc-assets-","").replace("mc-assets-","");
                if("README.md".equals(path)){Matcher m=GAME_VERSION.matcher(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));if(m.find())gameVersion=m.group(1).trim();continue;}
                String key=null;
                if(path.startsWith("item-assets/")&&path.endsWith(".png"))key="renders/item/"+path.substring("item-assets/".length());
                else if(path.startsWith("entity-assets/flat/")&&path.endsWith(".png"))key="renders/entity/flat/"+path.substring("entity-assets/flat/".length());
                else if(path.startsWith("entity-assets/isometric/")&&path.endsWith(".png"))key="renders/entity/isometric/"+path.substring("entity-assets/isometric/".length());
                if(key==null)continue;
                byte[] bytes=in.readAllBytes(); files.put(key,new ByteArrayInputStream(bytes),bytes.length,"image/png");
                String base=path.substring(path.lastIndexOf('/')+1,path.length()-4);
                if(key.startsWith("renders/item/")){itemNames.add(base);items++;}else if(key.contains("/flat/")){flatNames.add(base);flat++;}else{isoNames.add(base);iso++;}
                if(++written%100==0){control.checkpoint();control.progress("WRITE",1,4,"已写入 "+written+" 个渲染图");}
            }
        }catch(IOException ex){throw new IllegalStateException("mc-assets 资产包解包失败",ex);}
        control.checkpoint(); control.progress("CLEANUP",2,4,"正在清理已下架的渲染图");
        cleanup(itemNames,flatNames,isoNames,control);
        control.checkpoint(); control.progress("META",3,4,"正在更新渲染资产信息");
        Map<String,Object> meta=new LinkedHashMap<>();
        meta.put("id",META_ID); meta.put("commit",commit); meta.put("gameVersion",gameVersion);
        meta.put("items",items); meta.put("entitiesFlat",flat); meta.put("entitiesIsometric",iso); meta.put("updatedAt",System.currentTimeMillis());
        meta.put("itemNames",List.copyOf(itemNames)); meta.put("flatNames",List.copyOf(flatNames)); meta.put("isoNames",List.copyOf(isoNames));
        documents.save(COLLECTION,META_ID,meta);
        nameIndex=new NameIndex(Set.copyOf(itemNames),Set.copyOf(flatNames),Set.copyOf(isoNames));
        control.log("INFO","渲染资产更新完成：物品 "+items+"、生物平面 "+flat+"、生物等轴 "+iso+"（源 "+commit+"，Minecraft "+gameVersion+"）");
    }

    /** 删除上一次更新存在、本次已下架的渲染文件。 */
    private void cleanup(Set<String> itemNames,Set<String> flatNames,Set<String> isoNames,JobService.Control control){
        Optional<Map<String,Object>> previous=documents.findById(COLLECTION,META_ID);
        if(previous.isEmpty())return;
        Map<String,Object> old=previous.get(); int removed=0;
        for(String name:strings(old.get("itemNames")))if(!itemNames.contains(name)){delete("renders/item/"+name+".png");removed++;}
        for(String name:strings(old.get("flatNames")))if(!flatNames.contains(name)){delete("renders/entity/flat/"+name+".png");removed++;}
        for(String name:strings(old.get("isoNames")))if(!isoNames.contains(name)){delete("renders/entity/isometric/"+name+".png");removed++;}
        if(removed>0)control.log("INFO","清理已下架渲染图 "+removed+" 个");
    }

    private Optional<byte[]> read(String key){try{return Optional.ofNullable(files.get(key)).map(file->{try{return file.inputStream().readAllBytes();}catch(IOException ex){return null;}});}catch(RuntimeException ex){return Optional.empty();}}
    private void delete(String key){try{files.delete(key);}catch(RuntimeException ignored){}}
    private List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();List<String> out=new ArrayList<>();for(Object v:list)out.add(String.valueOf(v));return out;}
    private int number(Object value){return value instanceof Number n?n.intValue():0;}
    private long longNumber(Object value){return value instanceof Number n?n.longValue():0L;}
}
