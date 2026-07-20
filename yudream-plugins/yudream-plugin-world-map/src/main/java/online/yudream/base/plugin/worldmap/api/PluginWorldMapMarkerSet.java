package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** A named, independently visible layer of marker geometry. The ID only needs to be unique per provider. */
public record PluginWorldMapMarkerSet(String id, String label, boolean defaultVisible,
                                      List<PluginWorldMapMarker> markers) {
}
