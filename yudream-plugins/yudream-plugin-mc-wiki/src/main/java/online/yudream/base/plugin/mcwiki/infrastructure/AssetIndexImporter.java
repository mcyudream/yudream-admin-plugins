package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.mcwiki.application.JobService;

/**
 * 从版本 assetIndex 中只取中文语言文件（客户端 JAR 不含 zh_cn）。
 * 物品渲染图改由共享渲染资产库提供，不再按版本下载贴图。
 */
public final class AssetIndexImporter {
    private static final String OBJECT_BASE = "https://resources.download.minecraft.net/";
    private final ObjectMapper mapper; private final MojangClient mojang;
    public AssetIndexImporter(ObjectMapper mapper,MojangClient mojang){this.mapper=mapper;this.mojang=mojang;}
    /** 返回 zh_cn 语言文件原始内容（JSON 或 .lang 文本）；清单缺失或下载失败返回 null。 */
    public byte[] importZhLang(String indexUrl,JobService.Control control){
        try {
            JsonNode objects=mapper.readTree(mojang.download(indexUrl)).path("objects"); List<String> hashes=new ArrayList<>();
            objects.fields().forEachRemaining(entry->{String key=entry.getKey();if(key.startsWith("minecraft/lang/zh_cn.")){String hash=entry.getValue().path("hash").asText();if(hash.matches("[a-f0-9]{40}"))hashes.add(hash);}});
            if(hashes.isEmpty()){control.log("INFO","assetIndex 中没有中文语言文件");return null;}
            String hash=hashes.getFirst(); control.log("INFO","正在下载中文语言文件 "+hash);
            return mojang.download(OBJECT_BASE+hash.substring(0,2)+"/"+hash);
        } catch(Exception ex){control.log("WARN","中文语言文件下载失败: "+ex.getClass().getSimpleName());return null;}
    }
}
