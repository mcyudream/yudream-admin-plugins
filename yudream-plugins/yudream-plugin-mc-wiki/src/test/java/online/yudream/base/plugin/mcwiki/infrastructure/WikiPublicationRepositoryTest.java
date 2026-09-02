package online.yudream.base.plugin.mcwiki.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiPublicationRepositoryTest {
    private final Map<String,Map<String,Map<String,Object>>> collections=new ConcurrentHashMap<>();
    private final PluginDocumentStore documents=new PluginDocumentStore() {
        public Map<String,Object> save(String collection,String id,Map<String,Object> document){Map<String,Object> copy=new HashMap<>(document);copy.put("id",id);collections.computeIfAbsent(collection,k->new ConcurrentHashMap<>()).put(id,copy);return copy;}
        public Optional<Map<String,Object>> findById(String collection,String id){return Optional.ofNullable(collections.getOrDefault(collection,Map.of()).get(id));}
        public List<Map<String,Object>> findAll(String collection,int page,int size){List<Map<String,Object>> docs=collections.getOrDefault(collection,Map.of()).values().stream().sorted(Comparator.comparing(d->String.valueOf(d.get("id")))).toList();int from=Math.min((Math.max(page,1)-1)*Math.max(size,1),docs.size());return new ArrayList<>(docs.subList(from,Math.min(from+Math.max(size,1),docs.size())));}
        public List<Map<String,Object>> findByField(String collection,String field,Object value,int page,int size){return List.of();}
        public long count(String collection){return collections.getOrDefault(collection,Map.of()).size();}
        public void delete(String collection,String id){collections.getOrDefault(collection,Map.of()).remove(id);}
    };
    private final WikiPublicationRepository publication=new WikiPublicationRepository(documents);

    @Test void publishAccumulatesSetAndMovesCurrent() {
        publication.publish("1.20.4");
        publication.publish("1.21.1");
        assertEquals(List.of("1.20.4","1.21.1"),publication.published());
        assertEquals(Optional.of("1.21.1"),publication.current());
        publication.publish("1.20.4");
        assertEquals(List.of("1.21.1","1.20.4"),publication.published(),"重复发布应移到最新");
        assertEquals(Optional.of("1.20.4"),publication.current());
    }

    @Test void unpublishFallsBackAndEventuallyClears() {
        publication.publish("1.20.4");
        publication.publish("1.21.1");
        publication.unpublish("1.21.1");
        assertEquals(List.of("1.20.4"),publication.published());
        assertEquals(Optional.of("1.20.4"),publication.current(),"移除当前版本后回退到剩余版本");
        publication.unpublish("1.20.4");
        assertTrue(publication.published().isEmpty());
        assertTrue(publication.current().isEmpty(),"全部取消发布后文档清空");
    }

    @Test void legacySingleVersionDocumentStillReads() {
        documents.save("publication","current",Map.of("id","current","version","1.20.2","publishedAt",1L));
        assertEquals(List.of("1.20.2"),publication.published());
        assertEquals(Optional.of("1.20.2"),publication.current());
        publication.publish("1.21.1");
        assertEquals(List.of("1.20.2","1.21.1"),publication.published(),"旧文档的单版本应并入集合");
    }
}
