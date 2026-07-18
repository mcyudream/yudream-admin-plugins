package online.yudream.base.plugin.worldmap.api;

import java.util.List;

/** Renderer-neutral geometry for a map annotation. */
public record PluginWorldMapMarker(String id, String type, String label, Position position,
                                   List<Position> points, String color) {

    public record Position(double x, double y, double z) {
    }

    public static PluginWorldMapMarker point(String id, String label, double x, double y, double z) {
        return new PluginWorldMapMarker(id, "POINT", label, new Position(x, y, z), List.of(), null);
    }
}
