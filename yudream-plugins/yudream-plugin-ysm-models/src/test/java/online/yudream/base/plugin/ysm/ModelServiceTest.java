package online.yudream.base.plugin.ysm;

import com.google.gson.*;
import online.yudream.base.plugin.ysm.application.ModelService;
import online.yudream.base.plugin.ysm.domain.ModelRecord;
import online.yudream.base.plugin.ysm.infrastructure.*;
import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.ysm.model.ModelAsset;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelServiceTest {
    private final ModelRepository repository=mock(ModelRepository.class);
    private final SkinPort skins=mock(SkinPort.class);
    private final AssetLinks links=new AssetLinks("https://models.example","test-credential-with-at-least-32-characters");
    private final ModelService service=new ModelService(repository,skins,links);
    private final String player="b5043965-cd42-443f-a1c7-8dd7ca8b5caa";
    private final ModelAsset asset=ModelAsset.of(JsonParser.parseString("{\"schema\":1,\"name\":\"test\",\"textures\":[{\"name\":\"blue\"}]}").getAsJsonObject());
    private ModelRecord row(String id,String owner,boolean shared,boolean enabled){return new ModelRecord(id,owner,id,asset.hash(),shared,enabled,1,2);}
    @BeforeEach void prepare(){
        when(skins.player(player)).thenReturn(Optional.of(new PluginSkinProfile(player,"Tester","owner-a",null,null,null)));
        when(skins.players("owner-a")).thenReturn(List.of(new PluginSkinProfile(player,"Tester","owner-a",null,null,null)));
        when(repository.asset(asset.hash())).thenReturn(asset);
    }
    @Test void manifestIncludesOnlyOwnAndEnabledServerModelsWithoutGameParameters(){
        when(repository.all()).thenReturn(List.of(row("own","owner-a",false,true),row("other","owner-b",false,true),row("shared","owner-b",true,true),row("off","owner-a",true,false)));
        var manifest=service.manifest(player);assertEquals(Set.of("own","shared"),manifest.models().keySet());
        assertEquals(manifest.revision(),service.manifest(player).revision());
        String wire=ModelAsset.JSON.toJson(manifest);
        assertFalse(wire.contains("minecraftVersion"));assertFalse(wire.contains("packFormat"));assertFalse(wire.contains("owner-a"));
    }
    @Test void usersAndAdministratorsUsingPersonalApiCannotAccessOtherOwners(){
        when(repository.find("other")).thenReturn(Optional.of(row("other","owner-b",false,true)));
        assertThrows(IllegalArgumentException.class,()->service.preview("owner-a","other",false));
        assertThrows(IllegalArgumentException.class,()->service.rename("owner-a","other","x"));
        assertThrows(IllegalArgumentException.class,()->service.delete("owner-a","other",false));
        assertThrows(IllegalArgumentException.class,()->service.equip("owner-a",player,"other","blue"));
        verify(repository,never()).save(any());
    }
    @Test void catalogDoesNotLeakOwnerIdentifiers(){
        when(repository.all()).thenReturn(List.of(row("shared","owner-b",true,true)));
        assertFalse(ModelAsset.JSON.toJson(service.catalog(1,10)).contains("owner-b"));
    }
    @Test void characterReassignmentInvalidatesPreviousOwnersOutfit(){
        when(repository.find("shared")).thenReturn(Optional.of(row("shared","owner-b",true,true)));
        when(repository.outfit(player)).thenReturn(Map.of("ownerId","previous-owner","modelId","shared","texture","blue"));
        assertEquals("",service.state(List.of(player)).outfits().getFirst().modelId());
    }
    @Test void missingTextureAfterNewVersionFallsBackAndDisabledModelClears(){
        when(repository.find("own")).thenReturn(Optional.of(row("own","owner-a",false,true)));
        when(repository.outfit(player)).thenReturn(Map.of("ownerId","owner-a","modelId","own","texture","removed"));
        assertEquals("blue",service.state(List.of(player)).outfits().getFirst().texture());
        when(repository.find("own")).thenReturn(Optional.of(row("own","owner-a",false,false)));
        assertEquals("",service.state(List.of(player)).outfits().getFirst().modelId());
    }
    @Test void shortLivedDownloadsDoNotRevealOrAcceptBridgeCredential(){
        String url=links.url("bundle",asset.hash());var uri=java.net.URI.create(url);
        var parts=uri.getQuery().split("&");long expires=Long.parseLong(parts[0].split("=")[1]);String sig=parts[1].split("=")[1];
        assertTrue(links.verify("bundle",asset.hash(),expires,sig));assertFalse(links.verify("bundle","0".repeat(64),expires,sig));
        assertFalse(links.bridge(sig));assertFalse(links.verify("bundle",asset.hash(),0,sig));
    }
}
