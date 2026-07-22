package online.yudream.plugin.worldmap.domain.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class MapInstanceTest {

    @Test
    void clearsThePreviousFailureWhenARenderIsResubmitted() {
        MapInstance map = new MapInstance("map-1", "Test", "overworld");
        map.markFailed("previous render failed");

        map.markRendering();

        assertNull(map.getMessage());
    }
}
