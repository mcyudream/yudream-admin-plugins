package online.yudream.base.plugin.ysm;

import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.base.plugin.ysm.application.ModelService;
import online.yudream.base.plugin.ysm.domain.*;
import online.yudream.base.plugin.ysm.infrastructure.*;
import online.yudream.ysm.model.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelModerationTest {
    private final ModelRepository repository=mock(ModelRepository.class);
    private final SkinPort skins=mock(SkinPort.class);
    private final AssetLinks links=new AssetLinks("https://models.example","test-credential-with-at-least-32-characters");
    private final ModelService service=new ModelService(repository,skins,links);
    private final AtomicReference<ModelSettings> policy=new AtomicReference<>(ModelSettings.defaults());
    private final Map<String,ModelRecord> rows=new ConcurrentHashMap<>();
    private final Map<String,ModelAsset> assets=new ConcurrentHashMap<>();
    private final String player="b5043965-cd42-443f-a1c7-8dd7ca8b5caa";
    private byte[] zip;
    private Map<String,byte[]> files;

    @BeforeEach void prepare() throws Exception {
        files=new LinkedHashMap<>();
        files.put("models/main.json","{\"minecraft:geometry\":[{\"description\":{\"texture_width\":16,\"texture_height\":16},\"bones\":[{\"name\":\"Root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4],\"uv\":[0,0]}]}]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",png);files.put("textures/blue.png",png.toByteArray());
        var output=new ByteArrayOutputStream();try(var z=new ZipOutputStream(output)){for(var entry:files.entrySet()){z.putNextEntry(new ZipEntry(entry.getKey()));z.write(entry.getValue());z.closeEntry();}}zip=output.toByteArray();
        when(repository.settings()).thenAnswer(i->policy.get());
        when(repository.settings(any())).thenAnswer(i->{policy.set(i.getArgument(0));return policy.get();});
        when(repository.find(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.all()).thenAnswer(i->List.copyOf(rows.values()));
        when(repository.save(any())).thenAnswer(i->{ModelRecord row=i.getArgument(0);rows.put(row.id(),row);return row;});
        doAnswer(i->{ModelAsset asset=i.getArgument(0);assets.put(asset.hash(),asset);return null;}).when(repository).asset(any(ModelAsset.class));
        when(repository.asset(anyString())).thenAnswer(i->assets.get(i.getArgument(0)));
        var profile=new PluginSkinProfile(player,"Tester","owner",null,null,null);
        when(skins.players("owner")).thenReturn(List.of(profile));when(skins.player(player)).thenReturn(Optional.of(profile));
    }
    @Test void closedUploadRejectsNewAndReplacementBeforeParsing() {
        service.settings(new ModelSettings(false,true));
        assertThrows(IllegalArgumentException.class,()->service.upload("owner",null,"x",new byte[0]));
        assertThrows(IllegalArgumentException.class,()->service.upload("owner","existing","x",new byte[0]));
        verify(repository,never()).asset(any(ModelAsset.class));verify(repository,never()).save(any());
    }
    @Test void approvalIsRequiredAcrossEquipmentCatalogManifestStateAndDownload() {
        service.settings(new ModelSettings(true,true));var row=service.upload("owner",null,"pending",zip);
        assertEquals(ReviewStatus.PENDING,row.reviewStatus());
        when(repository.outfit(player)).thenReturn(Map.of("ownerId","owner","modelId",row.id(),"texture","blue"));
        assertThrows(IllegalArgumentException.class,()->service.equip("owner",player,row.id(),"blue"));
        assertThrows(IllegalArgumentException.class,()->service.manage(row.id(),true,true));
        assertEquals(0,service.catalog(1,12).get("total"));assertTrue(service.manifest(player).models().isEmpty());
        assertEquals("",service.state(List.of(player)).outfits().getFirst().modelId());
        assertThrows(IllegalArgumentException.class,()->service.download(row.hash()));
        assertEquals(row.hash(),service.preview("owner",row.id(),false).hash());
        service.review(row.id(),row.hash(),true,"");service.manage(row.id(),true,true);
        assertDoesNotThrow(()->service.equip("owner",player,row.id(),"blue"));
        assertEquals(1,service.catalog(1,12).get("total"));assertTrue(service.manifest(player).models().containsKey(row.id()));
        assertEquals(row.id(),service.state(List.of(player)).outfits().getFirst().modelId());assertArrayEquals(assets.get(row.hash()).bytes(),service.download(row.hash()));
    }
    @Test void rejectionNeedsReasonAndNewVersionRequiresFreshApproval() {
        service.settings(new ModelSettings(true,true));var first=service.upload("owner",null,"pending",zip);
        assertThrows(IllegalArgumentException.class,()->service.review(first.id(),first.hash(),false," "));
        var rejected=service.review(first.id(),first.hash(),false," 请修正贴图 ");assertEquals("请修正贴图",rejected.reviewNote());
        assertThrows(IllegalArgumentException.class,()->service.equip("owner",player,first.id(),"blue"));
        var second=service.upload("owner",first.id(),"updated",zip);assertEquals(ReviewStatus.PENDING,second.reviewStatus());assertEquals("",second.reviewNote());
        assertThrows(IllegalArgumentException.class,()->service.review(second.id(),first.hash(),true,""));
        service.review(second.id(),second.hash(),true,"");assertThrows(IllegalArgumentException.class,()->service.review(second.id(),second.hash(),false,"stale"));
    }
    @Test void togglingReviewOnlyChangesFutureUploadsAndNeverApprovesPendingRows() {
        var approved=service.upload("owner",null,"existing",zip);service.settings(new ModelSettings(true,true));
        assertEquals(ReviewStatus.APPROVED,rows.get(approved.id()).reviewStatus());
        var pending=service.upload("owner",approved.id(),"replacement",zip);assertEquals(ReviewStatus.PENDING,pending.reviewStatus());
        service.settings(new ModelSettings(true,false));assertEquals(ReviewStatus.PENDING,rows.get(pending.id()).reviewStatus());
        assertEquals(ReviewStatus.APPROVED,service.upload("owner",null,"auto",zip).reviewStatus());
    }
    @Test void renameAndEnableCannotBypassReviewAndFilteringStaysOnServer() {
        service.settings(new ModelSettings(true,true));var row=service.upload("owner",null,"pending",zip);
        assertEquals(ReviewStatus.PENDING,service.rename("owner",row.id(),"renamed").reviewStatus());
        assertEquals(ReviewStatus.PENDING,service.manage(row.id(),true,false).reviewStatus());
        assertEquals(1,service.admin(1,12,"PENDING").get("total"));assertEquals(0,service.admin(1,12,"APPROVED").get("total"));
        assertThrows(IllegalArgumentException.class,()->service.admin(1,12,"invalid"));
        assertThrows(IllegalArgumentException.class,()->service.preview("other",row.id(),false));
        assertThrows(IllegalArgumentException.class,()->service.upload("other",row.id(),"replacement",zip));
    }
    @Test void closingUploadsDuringConversionPreventsTheFinalSave() throws Exception {
        var decoder=mock(YsmDecoder.class);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(decoder.decode(any())).thenAnswer(i->{entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return files;});
        var converting=new ModelService(repository,skins,links,decoder);
        try(var executor=Executors.newSingleThreadExecutor()){
            var task=executor.submit(()->converting.upload("owner",null,"racing",new byte[]{1}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));converting.settings(new ModelSettings(false,false));finish.countDown();
            var failure=assertThrows(ExecutionException.class,()->task.get(5,TimeUnit.SECONDS));assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            verify(repository,never()).save(any());
        }finally{finish.countDown();}
    }
}
