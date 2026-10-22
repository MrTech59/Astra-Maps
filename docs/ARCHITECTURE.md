# Architecture

Astra Maps is an offline-first cycling navigation app. The guiding constraint
is simple: **once a regional pack is installed, the app never needs the
network** to display a map, search for a place, calculate a route, guide the
rider, speak a voice hint, or reroute.

Everything below follows from that constraint.

---

## 1. Module layout

```
:app                Android application (com.astramaps.app)
:third_party:brouter  Vendored BRouter v1.7.10 runtime (MIT, plain Java)
```

`:third_party:brouter` is a `java-library`, not an Android library. BRouter is
pure Java with no Android dependencies, so keeping it plain means the routing
code can also be exercised by host-side JVM tests, which run far faster than
instrumented tests and are therefore part of the CI gate.

`:app` depends on `:third_party:brouter`; the dependency never goes the other
way.

---

## 2. Offline data model

| Need | Mechanism | Why not the obvious alternative |
|---|---|---|
| Map | PMTiles archive read via `pmtiles://file://` | MBTiles needs SQLite random access across many small files; PMTiles is a single seekable file, which is far kinder to flash storage and to app-private storage quotas. |
| Place search | SQLite + **FTS4** virtual table | FTS5 is the better search engine, but AOSP's SQLite is built **without** it. Shipping a custom SQLite build costs ~1.5 MB per ABI. FTS4 has been in AOSP since API 11. |
| Routing | BRouter `.rd5` cells + `.brf` profiles | The routing graph is a compiled binary format; reimplementing it would be absurd. |
| Routing updates | BRouter `.df5` deltas, applied on-device | A full cell re-download is 119.3 MB; the delta is 0.24–1.74 MB. |

### Why FTS4 and not FTS5

This was the single riskiest technical assumption in the design, so it is
**asserted on the device**, not assumed:

* `app/src/androidTest/.../Fts4AvailabilityTest.kt` creates an FTS4 virtual
  table on the real device SQLite and proves a `MATCH` query returns hits.
* The same test proves an FTS5 virtual table **fails** with
  `no such module: fts5`.

If AOSP ever ships FTS5, that test fails loudly and the decision can be
revisited.

### Why the routing payload is cell-scoped while the map is bbox-scoped

BRouter distributes its routing graph on a fixed 5°×5° global grid. There are
no finer cells — the format does not support them. So the smallest routable
unit a user can download is a 5°×5° cell, which for Berlin is 119.3 MB and
happens to cover eight cities.

This means a small city region still pulls in a large routing payload. That is
accepted deliberately: a routing graph that is too coarse produces bad routes,
and a good quiet-road route through Berlin needs the whole regional graph
anyway. The decoupling is explicit — the map payload follows the user's
bounding box, the routing payload follows BRouter's grid, and the manifest
lists them separately.

---

## 3. Dependency injection

Manual DI via `AppContainer` (`app/src/main/java/com/astramaps/app/di/`).

No Hilt, no Dagger, no Koin. The graph is a few objects deep; adding an
annotation processor to the build for it would cost build time and add a
failure mode for no benefit. `AppContainer` is created in `AstraApplication`
and lives for the process lifetime.

```kotlin
class AstraApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

The container owns the `OkHttpClient` (with the OSMF-compliant User-Agent
interceptor) and the Room database.

---

## 4. HTTP and the tile User-Agent

Every outbound request carries:

```
AstraMaps/1.0 (+https://github.com/MrTech59/Astra-Maps/issues)
```

This is set once, in `AppContainer`'s OkHttp interceptor, so it cannot be
forgotten on an individual call site. OSMF's tile usage policy requires a
descriptive User-Agent for any tile request.

Astra Maps does **not** point MapLibre at `vector.openstreetmap.org` or
`tile.openstreetmap.org` — the OSMF policy prohibits both. The compliant
online source is OpenFreeMap.

---

## 5. Permissions

The manifest requests the minimum needed:

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Position on the map and along the route. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Continuous navigation while the screen is off. |
| `POST_NOTIFICATIONS` | The turn-by-turn notifications. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Optional pack downloads and the opt-in online geocoder. |

**`ACCESS_BACKGROUND_LOCATION` is deliberately not requested.** A foreground
service with `foregroundServiceType="location"` shows a persistent, user-visible
notification and receives location updates in the background without it. The
background-location permission exists for *unattended* location collection,
which this app never does.

`android:usesCleartextTraffic="false"` is set, and no network security
config exempts anything. Offline PMTiles are read through `file://`, so no
cleartext exemption is needed.

---

## 6. Build configuration

| Setting | Value | Reason |
|---|---|---|
| `compileSdk` / `targetSdk` | 35 | Locked by the approved plan. |
| `minSdk` | 26 | API 26 ships notification channels and foreground service types, which the navigation service needs. |
| Java/Kotlin target | 17 | AGP 8.12 requirement for the Kotlin toolchain. |
| Compose | enabled via the `org.jetbrains.kotlin.plugin.compose` plugin | Required by Kotlin 2.x. |
| `androidx.activity` | **1.10.1** | 1.11.0 declares `minCompileSdk=36`; 1.10.1 is the newest release that still builds against the locked compileSdk 35. |

`gradle/libs.versions.toml` is the single source of truth for versions. The
resolved graph is verified with `dependencyInsight` in CI.

### Release build and R8

Release builds run R8 (`isMinifyEnabled` + `isShrinkResources`) and ship only
`arm64-v8a` and `x86_64` natives, which is where the 100 MB of debug native
libraries collapses to two ABIs. `:app:assembleRelease` was run and the output
inspected with `apkanalyzer dex packages`:

| Measure | Debug | Release |
|---|---|---|
| APK size | 104,098,014 B | 50,728,227 B |
| `classes*.dex` | 13 files | 1 file |
| Classes | 18,016 | 3,508 |
| `btools.*` classes | 116 | 14 |

The fourteen retained BRouter classes are exactly the ones the keep rules name,
plus the types their signatures mention. That is the *correct* result right
now: the app does not call BRouter yet, so R8 reaches those classes only
through `app/proguard-rules.pro`. `RoutingEngine`, `OsmTrack`, `OsmNodeNamed`
and `VoiceHint` are stripped today and reappear automatically once the Phase 4
`RouteProvider` implementation references them statically.

The keeps are load-bearing rather than defensive, because
`RoutingContext.setModel` instantiates the path model **by name**:

```java
if (className == null) {
  pm = new StdModel();                       // tag-less profiles take this branch
} else {
  Class<?> clazz = Class.forName(className); // reflective
  pm = (OsmPathModel) clazz.getDeclaredConstructor().newInstance();
}
```

A stock cycling `.brf` carries no `---model:` tag, so `_modelClass` is `null`
and `new StdModel()` runs directly. A profile that *does* name a model (BRouter
ships `KinematicModel` and friends for e-bike profiles) goes through
`Class.forName`, which R8 would defeat by renaming the class. The
`-keep class btools.router.<Model> { <init>(); }` rules preserve both the name
and the declared no-arg constructor, and both were confirmed present in the
release dex.

---

## 7. MapLibre

`org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1` (BSD 2-Clause).

The `vulkan-opengl` artifact carries both renderers and selects at runtime. This
matters because the reference device — a Samsung Galaxy A17 (SM-A175F) — reports
`vulkan_renderengine: false` and has only an OpenGL ES 3.2 Mali-G57 MC2 GPU.
MapLibre must fall back to the OpenGL renderer there; the Vulkan path is
exercised only on devices that support it.

API notes for 13.7.1 (verified against the shipped AAR):

* `org.maplibre.android.maps.MapLibreMap` — *not* `org.maplibre.android.MapLibreMap`
* `org.maplibre.android.maps.MapView`, `org.maplibre.android.maps.Style`
* `org.maplibre.android.offline.*` — the offline region API
* `org.maplibre.android.location.LocationComponent`

The 13.x artifact also pins `kotlin-stdlib` to 2.2.10 and `okhttp` to 4.12.0;
the version catalogue matches both so the graph resolves to a single copy of
each.

---

## 8. Routing

BRouter 1.7.10 is vendored and reached through a `RouteProvider` seam, so
the rest of the app never depends on BRouter's types.

The 1.7 API is materially different from the pre-1.7 API that most BRouter
documentation still describes:

* There are **no `Position` or `Waypoint` classes**. Waypoints are
  `OsmNodeNamed`; coordinates are passed as `int` pairs.
* `RoutingContext` replaces the old `BRouterContext`.
* `RoutingContext.setModel(String)` is **private** and reached through
  `expctxGlobal._modelClass`; `initModel()` is the public entry point.
* `RoutingContext.memoryclass` defaults to **64** — the peak heap in megabytes
  the routing graph may use. This is the first number to tune for a
  mid-range phone.
* The voice-hint codes live in `VoiceHint` as **package-private** constants.

The full, verified API inventory is in
[`third_party/brouter/PATCHES.md`](../third_party/brouter/PATCHES.md), and
`BrouterVendoringTest` asserts every shape we depend on, so a future re-sync
of the vendored source cannot silently break the integration.

Stock BRouter cycling profiles carry no `---model:` tag, so `RoutingContext`
resolves to `StdModel`; `KinematicModel` is kept alive by the ProGuard rules
in `app/proguard-rules.pro` so a release build can be verified to link.

---

## 9. Testing strategy

| Layer | Where | What it proves |
|---|---|---|
| Pure JVM | `app/src/test/` | Palette contrast maths, BRouter vendoring invariants, Room DAO behaviour (Robolectric). |
| Instrumented | `app/src/androidTest/` | What only a real device can prove: FTS4 vs FTS5, real SQLite behaviour, native library loading, and that a prebuilt pack still yields tiles with the radios off. |
| Release build | CI | R8 does not strip `btools.router.StdModel`, `btools.router.KinematicModel` or `btools.mapaccess.Rd5DiffTool`. |

CI runs `:app:assembleDebug`, `:app:testDebugUnitTest`, `:third_party:brouter:build`
and `:app:lintDebug`, and separately validates the Planetiler tile schema.

### Running the instrumented tests

```bash
./scripts/run-instrumented-tests.ps1                 # the only connected device
./scripts/run-instrumented-tests.ps1 -Serial 192.168.1.151:34013
./scripts/run-instrumented-tests.ps1 -AirplaneMode   # enforce the offline gate
```

The script exists because of a hard limitation in AGP 8.12, verified by reading
the AGP bytecode:

* **AGP 8.12 removed the legacy instrumentation runner.** The
  `DeviceProviderInstrumentTestTask` class contains the message *"Implicitly
  enabling Unified Test Platform because UTP is now the only test runner"*.
  The `android.experimental.androidTest.useUnifiedTestPlatform=false` flag is
  ignored, and AGP explicitly asks for it to be removed.
* **UTP cannot drive a TCP-attached device on Windows.** UTP writes a
  device-profile file whose name is derived from the raw device serial
  (`DeviceProviderProfileManager.Companion.targetFileForDevice`). A phone
  attached with `adb connect host:port` has a serial such as
  `192.168.1.151:34013`, and `:` is not a legal character in a Windows file
  name. The write fails with `FileNotFoundException: Invalid file path` and
  the run aborts with **0 tests discovered** — a failure that looks like
  "no tests found" rather than the infrastructure error it is.

`scripts/run-instrumented-tests.ps1` therefore bypasses the harness: it
installs the app and test APKs and drives `adb shell am instrument` directly.
That works over USB, over TCP and over wireless debugging, on any host OS.

`:app:connectedDebugAndroidTest` still works against the **emulator**, whose
serial (`emulator-5554`) is a legal file name, and is what CI uses.

### The offline acceptance harness

`OfflineTileServingTest` is the Phase 2 proof that the offline promise is real,
not aspirational. With the radios off it installs a real planetiler archive into
app-private storage, opens it, and reads **every** tile it advertises.

The archive is `app/src/androidTest/assets/berlin_mitte.pmtiles` — 2.86 MB,
20 tiles across z6–14, cut from the same profile and the same Berlin extract as
a production pack:

```bash
planetiler generate-custom --schema=schemas/astra_schema.yml \
  --bounds=13.38,52.50,13.42,52.53 --maxzoom=14 --render_maxzoom=14 \
  --output=app/src/androidTest/assets/berlin_mitte.pmtiles
```

Three things make it a gate rather than a demo:

* **The device must actually be offline.** Airplane mode cannot be enabled from
  inside the app, and enabling it over wireless ADB would drop the connection
  carrying the results. So the *host* enables it and the test checks
  `ConnectivityManager.activeNetwork` is null. If a network is up the test
  either skips (developer default) or fails (`-e astra.requireOffline true`),
  which is what CI and `-AirplaneMode` pass.
* **CI runs the whole suite in airplane mode**, not just this test, so no
  instrumented test can quietly depend on a network.
* **Reading a tile does not use MapLibre.** `PmtilesArchive` is a small
  read-only PMTiles v3 reader (header, varint directories, gzip payloads). It
  is not on the rendering path — MapLibre reads `pmtiles://` natively — but it
  lets the app validate a pack *before* marking it installed, since PMTiles
  carries no checksums of its own. The test also asserts the tile-id Hilbert
  arithmetic is a bijection over every entry, which is what catches a
  transcription error that a single lookup would hide.

PMTiles v3 stores a directory **column-wise**: entry count, then all tile-id
deltas, then all run lengths, then all lengths, then all offsets (with `0`
meaning "contiguous with the previous entry"). Parsing it row-wise produces
plausible garbage, so the reader mirrors the reference decoder.

---

## 10. Licensing posture

The app is Apache-2.0. Vendored and bundled components keep their own
licences; `docs/ATTRIBUTION.md` records every credit that must be displayed in
the app, and `third_party/brouter/PATCHES.md` records every change made to the
vendored BRouter source.
