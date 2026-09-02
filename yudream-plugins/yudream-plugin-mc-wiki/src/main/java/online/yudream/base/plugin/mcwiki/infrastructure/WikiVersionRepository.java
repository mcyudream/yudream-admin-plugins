package online.yudream.base.plugin.mcwiki.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McVersionInfo;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class WikiVersionRepository {
    private final PluginDocumentStore documents;
    public WikiVersionRepository(PluginDocumentStore documents) { this.documents = documents; }
    public void replace(List<McVersionInfo> values) { for (McVersionInfo value : values) documents.save("versions", value.id(), map(value)); }
    public List<McVersionInfo> list(int page, int size) { return documents.findAll("versions", page, size).stream().map(this::read).toList(); }
    public long count() { return documents.count("versions"); }
    public Optional<McVersionInfo> find(String id) { return documents.findById("versions", id).map(this::read); }
    private Map<String,Object> map(McVersionInfo v) { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",v.id());m.put("type",v.type());m.put("releaseTime",v.releaseTime());m.put("url",v.url());m.put("latest",v.latest());return m; }
    private McVersionInfo read(Map<String,Object> m) { return new McVersionInfo(String.valueOf(m.get("id")),String.valueOf(m.get("type")),(String)m.get("releaseTime"),(String)m.get("url"),Boolean.parseBoolean(String.valueOf(m.getOrDefault("latest",false)))); }
}
