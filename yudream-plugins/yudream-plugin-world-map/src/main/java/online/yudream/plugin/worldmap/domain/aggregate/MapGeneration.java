package online.yudream.plugin.worldmap.domain.aggregate;

import java.util.Objects;

/** Immutable namespace for one completed render attempt. */
public record MapGeneration(String mapId, String id) {

    public MapGeneration {
        Objects.requireNonNull(mapId, "mapId");
        Objects.requireNonNull(id, "id");
        if (mapId.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("Generation identifiers must not be blank");
        }
    }
}
