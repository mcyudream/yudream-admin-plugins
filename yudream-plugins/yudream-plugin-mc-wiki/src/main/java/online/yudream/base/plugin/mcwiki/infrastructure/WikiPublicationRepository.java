package online.yudream.base.plugin.mcwiki.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/** 发布状态：当前默认版本 + 全部已发布版本集合，公开页可在已发布版本间切换。 */
public final class WikiPublicationRepository {
    private final PluginDocumentStore documents;
    public WikiPublicationRepository(PluginDocumentStore documents){this.documents=documents;}
    /** 发布版本：加入已发布集合并设为当前默认版本。 */
    public void publish(String version){List<String> published=new ArrayList<>(published());published.remove(version);published.add(version);save(version,published);}
    /** 取消发布：移出集合；若移除的是当前默认版本，则回退到最近发布的其余版本，全部移除后清空。 */
    public void unpublish(String version){List<String> published=new ArrayList<>(published());if(!published.remove(version))return;if(published.isEmpty()){clear();return;}save(published.getLast(),published);}
    public Optional<String> current(){return documents.findById("publication","current").map(m->String.valueOf(m.get("version")));}
    /** 全部已发布版本（按发布先后排序）；兼容旧版仅含单一 version 的文档。 */
    @SuppressWarnings("unchecked") public List<String> published(){return documents.findById("publication","current").map(m->{Object versions=m.get("versions");if(versions instanceof List<?> list&&!list.isEmpty())return list.stream().map(String::valueOf).toList();Object single=m.get("version");return single==null?List.<String>of():List.of(String.valueOf(single));}).orElse(List.of());}
    public void clear(){documents.delete("publication","current");}
    private void save(String current,List<String> published){Map<String,Object> m=new LinkedHashMap<>();m.put("id","current");m.put("version",current);m.put("versions",List.copyOf(published));m.put("publishedAt",System.currentTimeMillis());documents.save("publication","current",m);}
}
