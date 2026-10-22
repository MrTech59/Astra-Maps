# Attribution

Astra Maps is built on the work of others. This document records every
attribution we are legally required to display, and where it must appear.

The in-app **About / Attribution** screen is the canonical surface; it is
reachable from the settings screen and must be visible without a network
connection.

---

## Data

### OpenStreetMap

All map, place and routing data derives from OpenStreetMap contributors.

> © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/)

Required display: a visible "© OpenStreetMap contributors" link to
<https://www.openstreetmap.org/copyright>, shown on the map surface and in the
attribution screen.

Source extracts are obtained from [Geofabrik](https://download.geofabrik.de/).

### Committed test fixtures

Two binary fixtures are committed so the instrumented tests are reproducible
without a build-time download:

| File | Derived from | Licence |
|---|---|---|
| `app/src/androidTest/assets/berlin_mitte.pmtiles` | Berlin extract via our own planetiler profile | ODbL 1.0 — a Derivative Database of OpenStreetMap |
| `app/src/androidTest/assets/astra_poi_fixture.db` | Same extract via `tools/build_poi_index.py` | ODbL 1.0 — a Derivative Database of OpenStreetMap |

They are test data, not shipped app content, but they are still OSM derivatives
and are offered under the same ODbL terms as the source data.

### Protomaps

The PMTiles archive format and tooling ecosystem originate with Protomaps.

> Protomaps — [BSD 3-Clause](https://github.com/protomaps/protomaps/blob/main/LICENSE) / ODbL for the underlying OSM data.

Protomaps is *not* a data source for Astra Maps' tiles — those come from our own
planetiler profile — but the PMTiles tooling and the `pmtiles://` URL scheme
are theirs and are credited accordingly.

### OpenMapTiles / MapTiler

The tile-pipeline methodology and the OpenMapTiles schema family informed our
own schema; parts of the upstream OpenMapTiles schema were evaluated as a
baseline and rejected.

> OpenMapTiles / MapTiler — [CC BY 4.0](https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md) for schema and tooling.

### CartoDB / Stamen / Paul Norman

Referenced as the origin of basemap styling conventions consulted during the
palette design.

> © [CARTO](https://carto.com/) © [Stamen Design](http://stamen.com/) © [Paul Norman](https://paulnorman.com/) — [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)

Where the Astra palette is *adapted* from these sources, the attribution screen
must say **"adapted from"** rather than merely crediting them.

---

## Software

| Component | Licence | Where |
|---|---|---|
| [MapLibre Native for Android](https://github.com/maplibre/maplibre-native) | BSD 2-Clause | Bundled in the APK (`org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1`) |
| [BRouter](https://github.com/abrensch/brouter) | MIT | Vendored in `third_party/brouter/` — see its `LICENSE` |
| [Planetiler](https://github.com/onthegomap/planetiler) | MIT | Build-time only; not shipped in the APK |
| [Photon](https://github.com/komoot/photon) | Apache 2.0 | Optional hosted service — not shipped in the APK |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 | Bundled |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache 2.0 | Bundled |
| AndroidX (Room, Compose, Lifecycle, Activity) | Apache 2.0 | Bundled |
| Kotlin standard library | Apache 2.0 | Bundled |

### Fonts

The bundled map style uses **Noto Sans**.

> Noto Sans — SIL Open Font License 1.1

### Map icons

> Maki — [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (part of the Mapbox Maki icon set, by Mapbox)

---

## Tile request User-Agent

Every outbound HTTP request made by Astra Maps carries:

```
AstraMaps/1.0 (+https://github.com/MrTech59/Astra-Maps/issues)
```

This is set centrally in `AppContainer` via an OkHttp interceptor and applies to
manifest fetches, pack downloads, delta downloads and optional geocoder calls.

---

## Remote services

| Service | Purpose | Required disclosure |
|---|---|---|
| `download1.graphhopper.com` | Photon geocoder database dumps (build/ops only) | none in-app |
| Photon instance (self-hosted) | Optional online place search | "Place search by Photon" + link |
| GitHub Releases | Pack artifact distribution | "Packs hosted on GitHub Releases" |

Astra Maps does **not** point MapLibre at `vector.openstreetmap.org` or
`tile.openstreetmap.org`. The OSMF tile usage policy prohibits both for this
kind of use.
