package online.yudream.plugin.worldmap.application.service;

import online.yudream.base.plugin.worldmap.api.PluginWorldMapLayerProvider;
import online.yudream.base.plugin.worldmap.api.PluginWorldMapMarkerSet;
import online.yudream.base.plugin.worldmap.api.PluginWorldMapService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Plugin-owned registry that isolates optional layer providers from the public map path. */
public final class WorldMapLayerService implements PluginWorldMapService {

    private final ConcurrentHashMap<String, PluginWorldMapLayerProvider> providers = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable registerLayer(PluginWorldMapLayerProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("Map layer provider id is required");
        }
        PluginWorldMapLayerProvider previous = providers.putIfAbsent(provider.id(), provider);
        if (previous != null && previous != provider) {
            throw new IllegalArgumentException("Map layer provider already registered: " + provider.id());
        }
        return () -> providers.remove(provider.id(), provider);
    }

    @Override
    public List<PluginWorldMapMarkerSet> markerSets(String mapId) {
        List<PluginWorldMapMarkerSet> result = new ArrayList<>();
        providers.values().stream()
                .sorted(Comparator.comparing(PluginWorldMapLayerProvider::id))
                .forEach(provider -> {
                    try {
                        List<PluginWorldMapMarkerSet> sets = provider.markerSets(mapId);
                        if (sets != null) {
                            sets.stream()
                                    .filter(set -> set != null && set.id() != null && !set.id().isBlank())
                                    .map(set -> scopedSet(provider.id(), set))
                                    .forEach(result::add);
                        }
                    } catch (RuntimeException ignored) {
                        // Optional integration failures must not make the public map unavailable.
                    }
                });
        return List.copyOf(result);
    }

    /** Length-prefixing is unambiguous even when a provider or a layer ID contains separators. */
    private static PluginWorldMapMarkerSet scopedSet(String providerId, PluginWorldMapMarkerSet set) {
        String id = providerId.length() + ":" + providerId + ":" + set.id();
        return new PluginWorldMapMarkerSet(id, set.label(), set.defaultVisible(), set.markers());
    }
}
