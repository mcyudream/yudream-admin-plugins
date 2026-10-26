package online.yudream.base.plugin.ysm.application;

import online.yudream.base.plugin.ysm.domain.ModelRecord;
import online.yudream.base.plugin.ysm.infrastructure.*;
import online.yudream.ysm.model.*;
import online.yudream.ysm.protocol.Protocol;
import java.util.*;

public final class ModelService {
    private final ModelRepository repository;private final SkinPort skins;private final AssetLinks links;
    public ModelService(ModelRepository repository,SkinPort skins,AssetLinks links){this.repository=repository;this.skins=skins;this.links=links;}
    public Map<String,Object> own(String owner,int page,int size){List<ModelRecord> rows=repository.all().stream().filter(m->m.ownerId().equals(owner)).toList();return page(rows,page,size);}
    public Map<String,Object> catalog(int page,int size){return page(repository.all().stream().filter(m->m.enabled()&&m.serverEnabled()).map(m->Map.of("id",m.id(),"name",m.name(),"hash",m.hash(),"enabled",m.enabled(),"serverEnabled",m.serverEnabled(),"updatedAt",m.updatedAt())).toList(),page,size);}
    public Map<String,Object> admin(int page,int size){return page(repository.all(),page,size);}
    public Map<String,Object> players(String owner){return Map.of("records",skins.players(owner).stream().map(p->Map.of("uuid",p.uuid(),"name",p.name(),"outfit",repository.outfit(uuid(p.uuid())))).toList(),"bridgeConfigured",links.configured());}
    public ModelRecord upload(String owner,String existingId,String name,byte[] source){
        ModelRecord old=existingId==null?null:owned(owner,existingId);ModelAsset asset=new ModelCompiler().compile(source,name);
        repository.asset(asset);long now=System.currentTimeMillis();
        return repository.save(new ModelRecord(old==null?UUID.randomUUID().toString():old.id(),owner,asset.name(),asset.hash(),old!=null&&old.serverEnabled(),old==null||old.enabled(),old==null?now:old.createdAt(),now));
    }
    public ModelAsset preview(String owner,String id,boolean catalog){ModelRecord m=catalog?shared(id):owned(owner,id);return repository.asset(m.hash());}
    public ModelAsset adminPreview(String id){return repository.asset(require(id).hash());}
    public ModelRecord rename(String owner,String id,String name){ModelRecord m=owned(owner,id);String n=name==null?"":name.trim();if(n.isEmpty()||n.length()>100)throw new IllegalArgumentException("名称需要在 1–100 字符之间");return repository.save(new ModelRecord(m.id(),m.ownerId(),n,m.hash(),m.serverEnabled(),m.enabled(),m.createdAt(),System.currentTimeMillis()));}
    public ModelRecord manage(String id,boolean enabled,boolean serverEnabled){ModelRecord m=require(id);return repository.save(new ModelRecord(m.id(),m.ownerId(),m.name(),m.hash(),serverEnabled,enabled,m.createdAt(),System.currentTimeMillis()));}
    public void delete(String owner,String id,boolean admin){ModelRecord m=admin?require(id):owned(owner,id);repository.delete(m.id());}
    public Map<String,Object> equip(String owner,String playerUuid,String modelId,String texture){
        String id=uuid(playerUuid);if(skins.players(owner).stream().noneMatch(p->uuid(p.uuid()).equals(id)))throw new IllegalArgumentException("角色不存在");
        if(modelId==null||modelId.isBlank()){repository.outfit(id,owner,"","");return repository.outfit(id);}
        ModelRecord m=require(modelId);if(!m.enabled()||!m.ownerId().equals(owner)&&!m.serverEnabled())throw new IllegalArgumentException("模型不存在");
        ModelAsset asset=repository.asset(m.hash());String t=texture==null||texture.isBlank()?asset.textures().get(0).getAsJsonObject().get("name").getAsString():texture;
        if(asset.textures().asList().stream().noneMatch(e->e.getAsJsonObject().get("name").getAsString().equals(t)))throw new IllegalArgumentException("贴图不存在");
        repository.outfit(id,owner,m.id(),t);return repository.outfit(id);
    }
    public Protocol.State state(List<String> uuids){
        if(uuids.size()>128)throw new IllegalArgumentException("一次最多查询 128 个角色");List<Protocol.Outfit> outfits=new ArrayList<>();
        for(String player:uuids){String id=uuid(player);Map<String,Object> o=repository.outfit(id);String model=String.valueOf(o.getOrDefault("modelId",""));ModelRecord m=repository.find(model).orElse(null);
            if(m==null||!m.enabled()){outfits.add(new Protocol.Outfit(id,"","","",""));continue;}
            var profile=skins.player(id);if(profile.isEmpty()||!profile.get().ownerId().equals(o.get("ownerId"))||!m.serverEnabled()&&!m.ownerId().equals(profile.get().ownerId())){outfits.add(new Protocol.Outfit(id,"","","",""));continue;}
            ModelAsset asset = repository.asset(m.hash());
            String texture=String.valueOf(o.get("texture"));
            if (asset.textures().asList().stream().noneMatch(t->t.getAsJsonObject().get("name").getAsString().equals(String.valueOf(o.get("texture"))))) texture=asset.textures().get(0).getAsJsonObject().get("name").getAsString();
            outfits.add(new Protocol.Outfit(id,m.id(),m.hash(),texture,links.url("bundle",m.hash())));
        }return new Protocol.State(Protocol.MAJOR,List.copyOf(outfits));
    }
    public Protocol.Manifest manifest(String playerUuid){
        String owner=skins.player(uuid(playerUuid)).map(p->p.ownerId()).orElse("");
        List<ModelRecord> models=repository.all().stream().filter(m->m.enabled()&&(m.serverEnabled()||m.ownerId().equals(owner))).sorted(Comparator.comparing(ModelRecord::id)).toList();
        Map<String,Protocol.Asset> mapping=new LinkedHashMap<>();StringBuilder identity=new StringBuilder("schema=1\n");
        models.forEach(m->{mapping.put(m.id(),new Protocol.Asset(m.id(),m.hash(),links.url("bundle",m.hash())));identity.append(m.id()).append(':').append(m.hash()).append('\n');});
        return new Protocol.Manifest(ModelAsset.digest("SHA-256",identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Map.copyOf(mapping));
    }
    public byte[] download(String hash){if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("资源不存在");return repository.asset(hash).bytes();}
    private ModelRecord require(String id){return repository.find(id).orElseThrow(()->new IllegalArgumentException("模型不存在"));}
    private ModelRecord owned(String owner,String id){ModelRecord m=require(id);if(!m.ownerId().equals(owner))throw new IllegalArgumentException("模型不存在");return m;}
    private ModelRecord shared(String id){ModelRecord m=require(id);if(!m.enabled()||!m.serverEnabled())throw new IllegalArgumentException("模型不存在");return m;}
    private static Map<String,Object> page(List<?> rows,int page,int size){int n=Math.min(50,Math.max(1,size)),start=(Math.max(1,Math.min(page,100000))-1)*n;return Map.of("records",rows.subList(Math.min(start,rows.size()),Math.min(start+n,rows.size())),"total",rows.size());}
    public static String uuid(String value){String s=value.replace("-","");if(!s.matches("[a-fA-F0-9]{32}"))throw new IllegalArgumentException("角色 UUID 无效");return UUID.fromString(s.substring(0,8)+"-"+s.substring(8,12)+"-"+s.substring(12,16)+"-"+s.substring(16,20)+"-"+s.substring(20)).toString();}
}
