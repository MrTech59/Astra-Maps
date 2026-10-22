# Development phases

Astra Maps is built in eight phases. Each phase has **executable** exit
criteria — a command that must run and a result that must be observed. A
phase is not complete because the code looks right; it is complete because
the check was run and passed.

---

## Phase 1 — Repository and toolchain bootstrap ✅

**Goal:** an empty project that builds, tests and runs on a real device.

| Exit criterion | Status |
|---|---|
| Git repository initialised on `main`, remote `origin` → `MrTech59/Astra-Maps` | ✅ |
| `gradlew` wrapper present and pinned to Gradle 8.13 | ✅ |
| `./gradlew :app:assembleDebug` exits 0 | ✅ |
| `dependencyInsight` resolves `org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1` | ✅ |
| `dependencyInsight` resolves `com.squareup.okhttp3:okhttp:4.12.0` (matches MapLibre's own pin) | ✅ |
| Resolved `kotlin-stdlib` is a single version, 2.2.10 | ✅ |
| APK contains `libmaplibre.so` + `libmaplibre-opengl.so` for the target ABIs | ✅ |
| Vendored BRouter compiles as `:third_party:brouter` | ✅ |
| BRouter + MapLibre classes present in the dex | ✅ |
| `:app:assembleRelease` builds and R8 keeps the reflectively-loaded path models + `Rd5DiffTool` | ✅ (50.7 MB, 2 ABIs, keeps verified in the release dex) |
| App installs and launches on a physical device without crashing | ✅ |
| `./gradlew :app:testDebugUnitTest` passes | ✅ |
| `planetiler verify schemas/astra_schema.yml` → 7 passed / 0 failed | ✅ |
| CI workflow green on GitHub Actions | ✅ (`build`, `tile-schema`, `instrumented` all pass) |

---

## Phase 2 — Tile pipeline, local search, offline acceptance harness ✅

**Goal:** the data pipeline produces the assets the app consumes, and the
offline guarantees are provable.

| Exit criterion | Status |
|---|---|
| `planetiler verify schemas/astra_schema.yml` → 7 passed / 0 failed | ✅ (verified in Phase 1) |
| `planetiler generate-custom` over the Berlin bbox exits 0 | ✅ (862 tiles, 1,808,276 features) |
| Generated archive size within ±15 % of 31,676,842 B | ✅ (31,764,127 B, +0.28 %) |
| PMTiles metadata asserts 11 layers / 58 fields | ✅ (was 56 before the two tag fixes) |
| All 20 cycling attributes present on the `roads` layer | ✅ (20/20) |
| Instrumented test on API 35/36 proves `CREATE VIRTUAL TABLE … USING fts4` works | ✅ (SM-A175F/API 36 + emulator/API 35, 4/4 pass) |
| Instrumented test proves FTS5 is **not** available | ✅ (`no such module: fts5`, SQLite 3.44.5) |
| Prebuilt FTS4 POI index builds and matches the documented schema | ✅ (6,291,456 B Berlin index; `--verify` OK; `PrebuiltPoiIndexTest` 6/6 on API 35 + API 36) |
| Offline acceptance harness: device in airplane mode still serves tiles | ✅ (17/17 in airplane mode on API 35; strict mode fails 7/7 when online; 2.86 MB z6–14 archive, every tile read on device) |

---

## Phase 3 — Map rendering, style and palette contrast gate

| Exit criterion | Status |
|---|---|
| TileJSON resolves from `pmtiles://file://` | ⬜ |
| A z12 tile returns > 0 bytes from the local archive | ⬜ |
| Map renders on the physical device (OpenGL ES path — the device has no Vulkan) | ⬜ |
| Palette contrast regression test over `palette.json`: labels ≥ 4.5:1 | ⬜ |
| Same test: non-text elements ≥ 3:1 | ⬜ |
| Daylight theme renders and passes the same gate | ⬜ |

**Palette decisions resolved in Phase 1 (all measured, not estimated):**

* The approved plan specified a coastline alpha of 0.40, but that yields only
  **2.63:1** against the dark water fill — which fails the plan's own 3:1
  non-text gate. The alpha was raised to **0.55**, giving **3.73:1** against
  water and **4.90:1** against land. Revert by lowering `alpha` in
  `app/src/main/assets/palette.json`.
* The daylight theme gained an explicit coastline stroke (`#1A4E8A`, opaque):
  **7.17:1** against daylight land, 2.71:1 against the water it outlines.
* The dark land colour `#12141F` was **derived**, not assumed — it is the value
  that reproduces the plan's measured ratios (motorway 11.52, major 7.44,
  minor 4.72, residential 2.42, labels 12.30, water fill 1.31).

---

## Phase 4 — Routing (BRouter)

| Exit criterion | Status |
|---|---|
| BRouter v1.7.10 vendored as `:third_party:brouter` | ✅ (vendored in Phase 1) |
| Release-build R8 check: `StdModel`, `KinematicModel`, `Rd5DiffTool` survive | ⬜ |
| Route smoke test Berlin → Potsdam > 50 km with an empty `getErrorMessage()` | ⬜ |
| `lookups.dat` version 11 / minor 2 matches the cell's `readVersion` 11 | ⬜ |
| Routing works with the network disabled | ⬜ |

**Note:** stock BRouter cycling profiles carry no `---model:` tag, so
`RoutingContext` resolves to `StdModel`. `RoutingContext.setModel(String)` is
**private** upstream, reached via `expctxGlobal._modelClass`. The plan prefers a
small documented patch — see `third_party/brouter/PATCHES.md`.

---

## Phase 5 — Navigation and voice guidance

| Exit criterion | Status |
|---|---|
| Foreground service with `foregroundServiceType="location"` | ⬜ |
| Turn-by-turn guidance follows the route geometry | ⬜ |
| All 18 `VoiceHint` codes spoken (C, TL, TSLL, TSHL, TR, TSLR, TSHR, KL, KR, TLU, TRU, OFFR, RNDB, RNLB, TU, BL, EL, ER) | ⬜ |
| Reroute on leaving the corridor, entirely offline | ⬜ |
| No `ACCESS_BACKGROUND_LOCATION` in the merged manifest | ⬜ |

---

## Phase 6 — Pack manager and updates

| Exit criterion | Status |
|---|---|
| Manifest fetch + SHA-256 verification | ⬜ |
| Download to `.part`, fsync, atomic rename | ⬜ |
| Pack install writes `installed_pack` / `installed_routing_cell` rows | ⬜ |
| `.df5` delta applied on-device via `Rd5DiffTool.recoverFromDelta` | ⬜ |
| Delta result MD5 verified before the file is put in place | ⬜ |
| Fallback to full download when the delta window has expired | ⬜ |
| Pack deletion removes files and rows | ⬜ |

---

## Phase 7 — Online enhancements

| Exit criterion | Status |
|---|---|
| Self-hosted Photon used as an *optional* search source | ⬜ |
| Local search remains the default and stays fully offline | ⬜ |
| Globe rendering evaluated on the `vulkan-opengl` artifact | ⬜ |
| Mercator style retained as the permanent fallback | ⬜ |

---

## Phase 8 — Release and the 12-row offline matrix

| Exit criterion | Status |
|---|---|
| Signed release AAB builds and R8 passes | ⬜ |
| Attribution screen shows every credit in `docs/ATTRIBUTION.md` | ⬜ |
| Dependency licence review complete | ⬜ |
| The 12-row offline matrix passes end-to-end | ⬜ |
| Play Store listing assets | ⬜ |

---

## The offline matrix

Twelve scenarios that must each pass with the device in airplane mode.

| # | Scenario | Phase |
|---|---|---|
| 1 | Map renders over a downloaded region | 3 |
| 2 | Map pans and zooms across a region boundary | 3 |
| 3 | Local place search returns results | 2 |
| 4 | Local street search returns results | 2 |
| 5 | Route is calculated between two offline points | 4 |
| 6 | Route avoids unpaved roads when the quiet profile is chosen | 4 |
| 7 | Turn-by-turn guidance advances along the route | 5 |
| 8 | Voice hints are spoken | 5 |
| 9 | Rerouting after a deliberate deviation | 5 |
| 10 | A pack is updated via a `.df5` delta | 6 |
| 11 | A pack is deleted and reinstalled | 6 |
| 12 | The app starts and works with no pack and no network | 6 |
