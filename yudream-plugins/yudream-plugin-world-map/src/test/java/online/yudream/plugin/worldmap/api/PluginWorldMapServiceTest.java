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
            assertEquals(List.of("7:a-first:first", "7:z-later:later"), service.markerSets("map-1").stream()
                    .map(PluginWorldMapMarkerSet::id).toList());
        }
        assertEquals(List.of(), service.markerSets("map-1"));
    }

    @Test
    void providerLocalLayerIdsCannotCollideInThePublicViewer() throws Exception {
        WorldMapLayerService service = new WorldMapLayerService();
        PluginWorldMapLayerProvider alpha = provider("alpha", "pois");
        PluginWorldMapLayerProvider beta = provider("beta", "pois");

        try (AutoCloseable ignored = service.registerLayer(alpha);
             AutoCloseable ignored2 = service.registerLayer(beta)) {
            assertEquals(List.of("5:alpha:pois", "4:beta:pois"), service.markerSets("map-1").stream()
                    .map(PluginWorldMapMarkerSet::id).toList());
        }
    }

    private static PluginWorldMapLayerProvider provider(String providerId, String layerId) {
        return new PluginWorldMapLayerProvider() {
            @Override public String id() { return providerId; }
            @Override public List<PluginWorldMapMarkerSet> markerSets(String mapId) {
                return List.of(new PluginWorldMapMarkerSet(layerId, layerId, true, List.of()));
            }
        };
    }
}
