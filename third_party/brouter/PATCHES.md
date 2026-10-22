# Vendored BRouter

## Provenance

| | |
|---|---|
| Upstream | https://github.com/abrensch/brouter |
| Tag | `v1.7.10` |
| Licence | MIT (see `LICENSE` in this directory) |
| Vendored modules | `brouter-core`, `brouter-util`, `brouter-codec`, `brouter-expressions`, `brouter-mapaccess` |
| Vendored files | 101 Java files, 599,336 bytes |
| External dependencies | **none** — only `java.*` and `btools.*` |
| Resources | **none** |

The five runtime modules are flattened into a single source tree
(`third_party/brouter/src/main/java/btools/...`). The upstream package
structure is preserved, so the files are byte-identical to upstream and can be
re-synced by overwriting the directory.

Deliberately **not** vendored: `brouter-map-creator` (build-time tool),
`brouter-server`, `brouter-routing-app`, `brouter-web`.

## Why a `java-library` and not an Android library

BRouter is pure Java with no Android dependencies. Keeping it as a plain
`java-library` allows the routing code to be exercised by host-side JVM tests
(`./gradlew :third_party:brouter:test`) in addition to on-device tests. The
module targets Java 11 bytecode (`options.release.set(11)`); the `:app` module
targets Java 17 and consumes it without issue.

## R8 / ProGuard

Stock BRouter cycling profiles carry **no `---model:` tag**, so
`RoutingContext` resolves to `StdModel` directly and no custom model class is
ever instantiated reflectively. The keep rules in `app/proguard-rules.pro` are
therefore belt-and-braces; they exist so that a R8 build cannot silently strip
`KinematicModel` before we have verified the release APK.

## BRouter 1.7 API notes

Verified by reading the vendored v1.7.10 source (not assumed):

| Fact | Detail |
|---|---|
| **No `Position` / `Waypoint` classes** | The pre-1.7 API is gone. `Class.forName("btools.router.Waypoint")` fails. The whole upstream tree contains neither file. |
| Waypoints are `OsmNodeNamed` | `RoutingContext.setWaypoint(OsmNodeNamed wp, boolean endpoint)` and the three-argument overload with a pending endpoint. |
| Coordinates are `int` pairs | `RoutingContext.calcDistance(int lon1, int lat1, int lon2, int lat2)` — integers, not doubles. BRouter's convention is **microdegrees** (1e-6°); confirm the exact scale in `OsmNodeNamed` before Phase 4 wires it up. |
| `RoutingContext` replaces `BRouterContext` | `btools.router.BRouterContext` does not exist in 1.7.10. `btools.router.RoutingContextBridge` does not exist either — both were dropped in the rewrite. |
| The model hook is private | `private void setModel(String className)`, invoked as `setModel(expctxGlobal._modelClass)`. `RoutingContext` has a public `initModel()`. |
| Default memory class | `public int memoryclass = 64;` — the peak heap BRouter may use for the routing graph, in megabytes. This is the value that must be tuned for a mid-range phone. |
| Engine entry point | `RoutingEngine.run()` / `doRun(long maxRunningTime)`, with `getFoundTrack(): OsmTrack`, `getErrorMessage(): String`, `getDistance()`, `getAscend()`, `getTime()`. |
| Voice hints | `btools.router.VoiceHint` holds **19** `static final int` constants: 18 navigation codes (`C`=1 … `ER`=18) plus `END`=100. They are **package-private**, so they must be read reflectively — there are no public accessors. |
| Progress listener | `btools.util.ProgressListener` — a **top-level** interface, not nested in `Rd5DiffTool` or `Rd5DiffManager`. |
| Delta application | `Rd5DiffTool.recoverFromDelta(File installed, File delta, File out, ProgressListener)` — `public static void`, throws `IOException`. |

`app/src/test/java/com/astramaps/app/thirdparty/BrouterVendoringTest.kt` asserts
every one of these shapes, so a future re-sync of the vendored source cannot
silently break the routing integration.

## Planned patch: `RoutingContext.setModel`

The approved plan prefers a small patch over vendored `RoutingContext` to allow
selecting a routing model programmatically, rather than relying on the
`---model:` tag which only appears in `car-vario*.brf` upstream.

**Status: not yet applied.** When it is, record it here in the format below.

```
### <date> — <one-line summary>

- File: brouter-core/src/main/java/btools/router/RoutingContext.java
- Upstream commit / issue:
- Rationale:
- Behaviour change:
- Upstreamability: (is this accepted upstream? link)
```

MIT permits modification provided the copyright notice and licence are
retained — the vendored `LICENSE` file satisfies that requirement, and this
file documents every change.

## Delta updates

`btools.mapaccess.Rd5DiffTool.recoverFromDelta(...)` is used by the pack
updater (Phase 6) to apply BRouter `.df5` deltas on-device. It lives in
`brouter-mapaccess`, imports only `java.io` + `btools.codec` + `btools.util`,
and contains no AWT / `javax.imageio` references — it is Android-safe.
