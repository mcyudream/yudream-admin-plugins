package online.yudream.base.plugin.ysm;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.system.storage.*;
import online.yudream.base.plugin.ysm.bootstrap.YsmModelsPlugin;
import online.yudream.base.plugin.ysm.domain.*;
import online.yudream.base.plugin.ysm.infrastructure.ModelRepository;
import online.yudream.base.plugin.ysm.interfaces.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelSettingsRepositoryTest {
    @Test void policyPersistsAcrossRepositoryInstancesAndLegacyModelsRemainApproved() {
        var documents=mock(PluginDocumentStore.class);var files=mock(PluginFileStore.class);
        Map<String,Map<String,Object>> settings=new HashMap<>();
        when(documents.findById(eq("settings"),anyString())).thenAnswer(i->Optional.ofNullable(settings.get(i.getArgument(1))));
        when(documents.save(eq("settings"),anyString(),anyMap())).thenAnswer(i->{settings.put(i.getArgument(1),i.getArgument(2));return i.getArgument(2);});
        var repository=new ModelRepository(documents,files);assertEquals(ModelSettings.defaults(),repository.settings());
        repository.settings(new ModelSettings(false,true));assertEquals(new ModelSettings(false,true),new ModelRepository(documents,files).settings());
        when(documents.findById("models","legacy")).thenReturn(Optional.of(Map.of("id","legacy","ownerId","owner","name","legacy","hash","hash","enabled",true,"serverEnabled",false,"createdAt",1,"updatedAt",2)));
        assertTrue(repository.find("legacy").orElseThrow().usable());
        assertEquals(ReviewStatus.PENDING,ReviewStatus.stored("unknown"));
    }
    @Test void configurationAndReviewEndpointsRequireAdministratorPermissions() throws Exception {
        var settings=ModelAdminController.class.getMethod("saveSettings",PluginHttpRequest.class).getAnnotation(PluginHttpEndpoint.class);
        assertEquals(YsmModelsPlugin.CONFIG,settings.permission());assertEquals("PUT",settings.method());assertEquals("/admin/settings",settings.path());
        var review=ModelAdminController.class.getMethod("review",PluginHttpRequest.class).getAnnotation(PluginHttpEndpoint.class);
        assertEquals(YsmModelsPlugin.MANAGE,review.permission());assertEquals("POST",review.method());
        for(var method:ModelUserController.class.getDeclaredMethods())assertFalse(method.getAnnotation(PluginHttpEndpoint.class).path().startsWith("/admin/"));
    }
}
