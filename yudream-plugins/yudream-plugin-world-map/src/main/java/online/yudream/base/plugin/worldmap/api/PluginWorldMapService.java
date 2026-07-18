package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** Stable cross-plugin map extension API. It deliberately exposes no renderer or storage types. */
public interface PluginWorldMapService {

    AutoCloseable registerLayer(PluginWorldMapLayerProvider provider);

    List<PluginWorldMapMarkerSet> markerSets(String mapId);
}
