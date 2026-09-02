package online.yudream.base.plugin.mcwiki.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.infrastructure.MojangClient;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiVersionRepository;

public final class WikiVersionService {
    // 宿主 MongoPluginDocumentStore 单页上限 200（MAX_PAGE_SIZE），请求更大会被静默截断导致误判读完
    private static final int SCAN_PAGE = 200;
    private final MojangClient client;
    private final WikiVersionRepository repository;
    public WikiVersionService(MojangClient client, WikiVersionRepository repository) { this.client=client; this.repository=repository; }
    public List<McWikiApi.McVersionInfo> refresh() { List<McWikiApi.McVersionInfo> values=client.manifest(); repository.replace(values); return values; }
    public McWikiApi.Page<McWikiApi.McVersionInfo> list(int page,int size) { return list(page,size,null,null); }
    /** 版本清单按发布时间倒序返回，支持按 id 关键字与类型（release/snapshot 等）过滤。 */
    public McWikiApi.McVersionInfo require(String id) { return repository.find(id).orElseThrow(() -> new IllegalArgumentException("Minecraft 版本不存在: " + id)); }
    public McWikiApi.Page<McWikiApi.McVersionInfo> list(int page,int size,String keyword,String type) {
        int p=Math.max(page,1), s=Math.min(Math.max(size,1),200);
        List<McWikiApi.McVersionInfo> filtered=listFiltered(keyword,type);
        int from=Math.min((p-1)*s,filtered.size()); return new McWikiApi.Page<>(filtered.stream().skip(from).limit(s).toList(),filtered.size(),p,s);
    }
    /** 按关键字与类型过滤后的全量版本清单（按发布时间倒序），供调用方叠加导入/发布状态过滤后再分页。 */
    public List<McWikiApi.McVersionInfo> listFiltered(String keyword,String type) {
        List<McWikiApi.McVersionInfo> all=new ArrayList<>(); int cursor=1; while(true){List<McWikiApi.McVersionInfo> chunk=repository.list(cursor,SCAN_PAGE);all.addAll(chunk);if(chunk.size()<SCAN_PAGE)break;cursor++;}
        String q=keyword==null?null:keyword.toLowerCase(Locale.ROOT); String t=type==null||type.isBlank()?null:type;
        return all.stream().filter(v->(q==null||v.id().toLowerCase(Locale.ROOT).contains(q))&&(t==null||t.equals(v.type()))).sorted(Comparator.comparing(McWikiApi.McVersionInfo::releaseTime,Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }
}
