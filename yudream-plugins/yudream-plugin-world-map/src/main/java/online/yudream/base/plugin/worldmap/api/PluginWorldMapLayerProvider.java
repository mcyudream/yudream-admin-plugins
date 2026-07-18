package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** Supplies a deterministic marker layer for one or more published map instances. */
public interface PluginWorldMapLayerProvider {

    String id();

    List<PluginWorldMapMarkerSet> markerSets(String mapId);
}
