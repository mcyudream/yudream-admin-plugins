package online.yudream.plugin.worldmap.api;

import online.yudream.base.plugin.worldmap.api.PluginWorldMapLayerProvider;
import online.yudream.base.plugin.worldmap.api.PluginWorldMapMarker;
import online.yudream.base.plugin.worldmap.api.PluginWorldMapMarkerSet;
import online.yudream.plugin.worldmap.application.service.WorldMapLayerService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginWorldMapServiceTest {

    @Test
    void providersAreMergedInStableOrderAndCanBeRemoved() throws Exception {
        WorldMapLayerService service = new WorldMapLayerService();
        PluginWorldMapLayerProvider later = new PluginWorldMapLayerProvider() {
            @Override public String id() { return "z-later"; }
            @Override public List<PluginWorldMapMarkerSet> markerSets(String mapId) {
                return List.of(new PluginWorldMapMarkerSet("later", "Later", true,
                        List.of(PluginWorldMapMarker.point("spawn", "Spawn", 0, 64, 0))));
            }
        };
        PluginWorldMapLayerProvider first = new PluginWorldMapLayerProvider() {
            @Override public String id() { return "a-first"; }
            @Override public List<PluginWorldMapMarkerSet> markerSets(String mapId) {
                return List.of(new PluginWorldMapMarkerSet("first", "First", true, List.of()));
            }
        };

        try (AutoCloseable ignored = service.registerLayer(later);
             AutoCloseable ignored2 = service.registerLayer(first)) {
            assertEquals(List.of("first", "later"), service.markerSets("map-1").stream()
                    .map(PluginWorldMapMarkerSet::id).toList());
        }
        assertEquals(List.of(), service.markerSets("map-1"));
    }
}
