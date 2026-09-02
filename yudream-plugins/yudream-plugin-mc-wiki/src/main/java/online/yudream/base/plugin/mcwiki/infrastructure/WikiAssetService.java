package online.yudream.base.plugin.mcwiki.infrastructure;

import java.io.IOException;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

public final class WikiAssetService {
    private final PluginFileStore files;
    public WikiAssetService(PluginFileStore files){this.files=files;}
    public Optional<byte[]> read(String version,String kind,String path){if(!safe(version)||!safe(kind)||!safe(path))return Optional.empty();String key="textures/"+version+"/"+kind+"/"+path;try{return Optional.ofNullable(files.get(key)).map(file->{try{return file.inputStream().readAllBytes();}catch(IOException ex){return null;}});}catch(RuntimeException ex){return Optional.empty();}}
    private boolean safe(String value){return value!=null&&!value.isBlank()&&!value.contains("..")&&!value.contains("\\")&&!value.startsWith("/");}
}
