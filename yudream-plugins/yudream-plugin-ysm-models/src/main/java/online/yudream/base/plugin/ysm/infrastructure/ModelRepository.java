package online.yudream.base.plugin.ysm.infrastructure;

import online.yudream.base.plugin.ysm.domain.ModelRecord;
import online.yudream.base.plugin.spi.system.storage.*;
import online.yudream.ysm.model.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;

public final class ModelRepository {
    private final PluginDocumentStore documents;private final PluginFileStore files;
    public ModelRepository(PluginDocumentStore documents,PluginFileStore files){this.documents=documents;this.files=files;}
    public ModelRecord save(ModelRecord m){documents.save("models",m.id(),Map.of("ownerId",m.ownerId(),"name",m.name(),"hash",m.hash(),"serverEnabled",m.serverEnabled(),"enabled",m.enabled(),"createdAt",m.createdAt(),"updatedAt",m.updatedAt()));return m;}
    public Optional<ModelRecord> find(String id){return documents.findById("models",id).map(this::record);}
    public void delete(String id){documents.delete("models",id);}
    public List<ModelRecord> list(String owner,int page,int size){return (owner==null?documents.findAll("models",page,size):documents.findByField("models","ownerId",owner,page,size)).stream().map(this::record).toList();}
    public List<ModelRecord> all(){List<ModelRecord> out=new ArrayList<>();for(int p=1;;p++){var rows=list(null,p,200);out.addAll(rows);if(rows.size()<200)return out;}}
    public void asset(ModelAsset asset){put("bundles/"+asset.hash()+".json",asset.bytes(),"application/json");}
    public ModelAsset asset(String hash){byte[] b=read("bundles/"+hash+".json");ModelAsset a=ModelAsset.of(JsonParser.parseString(new String(b,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());if(!a.hash().equals(hash))throw new IllegalStateException("模型文件校验失败");return a;}
    public Map<String,Object> outfit(String uuid){return documents.findById("outfits",uuid).orElse(Map.of("playerUuid",uuid,"modelId","","texture",""));}
    public void outfit(String uuid,String owner,String model,String texture){documents.save("outfits",uuid,Map.of("playerUuid",uuid,"ownerId",owner,"modelId",model,"texture",texture));}
    private void put(String key,byte[] bytes,String type){files.put(key,new ByteArrayInputStream(bytes),bytes.length,type);}
    private byte[] read(String key){PluginStoredFile f=files.get(key);if(f==null)throw new IllegalArgumentException("资源不存在");try(var in=f.inputStream()){return in.readNBytes(64*1024*1024);}catch(IOException e){throw new UncheckedIOException(e);}}
    private ModelRecord record(Map<String,Object> d){return new ModelRecord(String.valueOf(d.get("id")),String.valueOf(d.get("ownerId")),String.valueOf(d.get("name")),String.valueOf(d.get("hash")),Boolean.TRUE.equals(d.get("serverEnabled")),Boolean.TRUE.equals(d.get("enabled")),((Number)d.get("createdAt")).longValue(),((Number)d.get("updatedAt")).longValue());}
}
