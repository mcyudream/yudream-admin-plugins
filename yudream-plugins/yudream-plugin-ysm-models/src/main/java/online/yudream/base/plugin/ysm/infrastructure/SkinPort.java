package online.yudream.base.plugin.ysm.infrastructure;

import online.yudream.base.plugin.skin.api.*;
import online.yudream.base.plugin.spi.core.PluginContext;
import java.util.*;

public interface SkinPort {
    List<PluginSkinProfile> players(String owner);
    Optional<PluginSkinProfile> player(String uuid);
    static SkinPort create(PluginContext context){return new SkinPort(){
        private PluginSkinService service(){return context.service("yudream-skin",PluginSkinService.class).orElseThrow(()->new IllegalStateException("皮肤插件不可用"));}
        public List<PluginSkinProfile> players(String owner){return service().findProfilesByOwner(owner);}
        public Optional<PluginSkinProfile> player(String uuid){return service().findProfileByUuid(uuid);}
    };}
}
