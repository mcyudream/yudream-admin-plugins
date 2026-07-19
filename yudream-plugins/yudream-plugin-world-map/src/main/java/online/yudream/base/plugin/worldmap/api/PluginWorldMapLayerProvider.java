package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** Supplies deterministic, provider-local marker layers for one or more published map instances. */
public interface PluginWorldMapLayerProvider {

    /** Stable provider namespace used to make returned layer IDs globally unique. */
    String id();

    List<PluginWorldMapMarkerSet> markerSets(String mapId);
}
