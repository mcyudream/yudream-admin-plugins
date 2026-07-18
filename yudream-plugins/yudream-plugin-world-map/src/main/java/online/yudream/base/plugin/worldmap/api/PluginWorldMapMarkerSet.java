package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** A named, independently visible layer of marker geometry. */
public record PluginWorldMapMarkerSet(String id, String label, boolean defaultVisible,
                                      List<PluginWorldMapMarker> markers) {
}
