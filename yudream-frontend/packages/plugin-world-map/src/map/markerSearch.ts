import type { MapMarker, MapMarkerSet } from '../types'

export interface MarkerSearchResult {
  setId: string
  setLabel: string
  marker: MapMarker
  label: string
}

export const MAX_MARKER_SEARCH_RESULTS = 12

/** Searches extension-provided marker layers without exposing renderer internals to providers. */
export function findMarkers(
  markerSets: readonly MapMarkerSet[],
  query: string,
  limit = MAX_MARKER_SEARCH_RESULTS,
): MarkerSearchResult[] {
  const normalized = query.trim().toLocaleLowerCase()
  if (!normalized || limit < 1) return []

  const matches: MarkerSearchResult[] = []
  for (const set of markerSets) {
    const setId = set.id ?? ''
    const setLabel = set.label || setId || 'Marker layer'
    const setSearch = `${setId} ${setLabel}`.toLocaleLowerCase()
    for (const marker of set.markers ?? []) {
      const label = marker.label || marker.id || 'Marker'
      const markerSearch = `${marker.id ?? ''} ${label}`.toLocaleLowerCase()
      if (!setSearch.includes(normalized) && !markerSearch.includes(normalized)) continue
      matches.push({ setId, setLabel, marker, label })
      if (matches.length >= limit) return matches
    }
  }
  return matches
}
