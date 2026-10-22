# Astra Maps

**Offline-first cycling navigation for Android.**

Astra Maps is a navigation app built for bicycle riders — road cycling, quiet-road
touring and off-road. Once a regional pack is downloaded, **map display, local
search, route calculation, navigation, voice guidance and rerouting all work with
no internet connection**.

---

## Why it exists

Most cycling navigation apps are thin clients around a hosted routing service.
That breaks down exactly where cyclists need it most: in the mountains, abroad,
on a phone with no data plan, or when you simply want the route to keep working
while you focus on the road.

Astra Maps inverts that. The heavy data — the map, the searchable places, the
routing graph — lives on the device. The network is an enhancement, never a
dependency.

## Design pillars

| Pillar | How it is achieved |
|---|---|
| **Works offline** | Self-hosted PMTiles vector tiles, an on-device SQLite/FTS4 place index, and the BRouter routing graph stored as local `.rd5` cells. |
| **Built for bicycles** | A purpose-made tile schema carries the OSM tags that actually matter to a rider — `surface`, `smoothness`, `tracktype`, `cycleway`, `segregated`, `bicycle:forward/backward`, `oneway:bicycle`, `incline`, `lit` — none of which survive the generic OpenMapTiles/Protomaps schemas. |
| **Calm, legible cartography** | A measured colour palette; every label pair clears WCAG AA, and area fills are backed by a mandatory coastline stroke. |
| **No surprise data use** | Location stays on the device. The optional online geocoder is an explicit user toggle. |

## Toolchain

| Component | Version |
|---|---|
| Gradle | 8.13 |
| Android Gradle Plugin | 8.12.0 |
| Kotlin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| JDK | 21 (Android Studio JBR) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| MapLibre Native for Android | `org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1` |
| BRouter | v1.7.10, vendored (MIT) |
| Room | 2.8.5 |
| OkHttp | 4.12.0 (pinned to match MapLibre's own dependency) |
| kotlinx.serialization | 1.9.0 |

## Repository layout

```
AstraMaps/
├── app/                      # The Android application
│   └── src/main/assets/palette.json   # Authoritative colour palette
├── schemas/
│   └── astra_schema.yml      # Planetiler tile schema (11 layers)
├── third_party/brouter/      # Vendored BRouter v1.7.10 runtime modules (MIT)
│   └── PATCHES.md            # Every change we make to the vendored source
├── docs/
│   ├── ARCHITECTURE.md       # Design, module layout, offline data model
│   ├── PACKS.md              # Pack format, manifest, install and update rules
│   ├── ATTRIBUTES.md         # The 20 cycling attributes the tiles carry
│   ├── ATTRIBUTION.md        # Every legally required credit
│   └── PHASES.md             # The eight phases and their exit criteria
└── .github/workflows/ci.yml
```

## Regional packs

A *pack* is the unit of downloadable data. The first pack, `astra-de-01`
(Berlin), comprises:

| Component | Size | Notes |
|---|---|---|
| Map tiles (PMTiles, z6–14) | 30.2 MB | 11 layers, 27 attributes on the `roads` layer |
| Local place index (SQLite + FTS4) | 4.7 MB | 39,530 POIs, 761 places, 13,012 streets |
| Routing graph (BRouter `.rd5`) | 119.3 MB | 5°×5° cell `E10_N50` |
| Lookup table (`lookups.dat`) | 0.03 MB | version 11 / minor 2 |
| Routing profiles (3 × BRF) | 0.05 MB | quiet / touring / off-road |
| **Total** | **≈ 154 MB** | |

BRouter ships routing data on a fixed 5°×5° global grid, so a pack's routing
component is never user-granular: 119.3 MB buys Berlin *and* Potsdam,
Magdeburg, Leipzig, Dresden, Chemnitz and Erfurt.

See [`docs/PACKS.md`](docs/PACKS.md) for the full format.

## Building

```bash
git clone https://github.com/MrTech59/Astra-Maps.git
cd Astra-Maps

# Point at your Android SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (device attached)
```

The tile schema can be validated standalone:

```bash
java -jar planetiler.jar verify schemas/astra_schema.yml
```

## Attribution

Astra Maps is built on the work of others. See
[`docs/ATTRIBUTION.md`](docs/ATTRIBUTION.md) for the full, legally required
credits.

## Licence

Astra Maps is licensed under the [Apache License 2.0](LICENSE). Vendored and
bundled third-party components keep their own licences; see
[`docs/ATTRIBUTION.md`](docs/ATTRIBUTION.md) and
[`third_party/brouter/LICENSE`](third_party/brouter/LICENSE).
