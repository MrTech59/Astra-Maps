# Cycling attributes

The single most important decision in the tile schema: **carry the OSM tags a
rider actually cares about**, because the generic schemas throw them away.

OpenMapTiles and Protomaps both omit or flatten most of these. A rider
choosing between two roads needs to know whether one is a smooth asphalt
cycle track or a gravel forest track, whether bicycles are legally permitted,
and whether the road is physically rideable.

---

## The `roads` layer

27 attributes in total (`schemas/astra_schema.yml`, layer `roads`).

### 20 cycling-relevant attributes

These are the attributes the Phase 2 gate asserts are present on the `roads`
layer in the generated PMTiles.

#### Ride quality — what the surface is actually like

| Attribute | OSM source | Min zoom | Why a rider needs it |
|---|---|---|---|
| `surface` | `surface` | 12 | asphalt, concrete, paving_stones, cobblestone, gravel, dirt, sand, ground, grass, … The single biggest factor in whether a road is comfortable. |
| `smoothness` | `smoothness` | 13 | `excellent` … `very_poor`. Coarse surfaces deserve a warning even when the surface *type* looks rideable. |
| `tracktype` | `tracktype` | 13 | `grade1` … `grade5`. Distinguishes a maintained gravel track from a muddy forest trail. |

#### Legal access — is a bicycle allowed?

| Attribute | OSM source | Min zoom | Notes |
|---|---|---|---|
| `access` | `access` | 12 | General access. `private`, `no` → not usable. |
| `bicycle` | `bicycle` | 11 | Bicycle-specific access. `yes`, `no`, `designated`, `dismount`, `use_sidepath`. |
| `bicycle_access` | `bicycle:access` | 11 | More specific than `bicycle` when both are present. |
| `bicycle_fwd` | `bicycle:forward` | 12 | Directional bicycle permission. |
| `bicycle_bwd` | `bicycle:backward` | 12 | Directional bicycle permission. |
| `oneway` | `oneway` | 12 | General one-way-ness. |
| `oneway_bicycle` | `oneway:bicycle`, else `cycleway:oneway` | 12 | One-way **for bicycles** — often `no` where the road itself is one-way. The `coalesce` makes this resolve correctly rather than dropping the attribute. |

#### Infrastructure — is there a lane, and is it protected?

| Attribute | OSM source | Min zoom | Notes |
|---|---|---|---|
| `cycleway` | `cycleway` | 11 | `lane`, `track`, `buffer`, `separate`, `shared_lane`, `no`, `opposite_lane`, … |
| `segregated` | `segregated` | 12 | Whether the cycle track is separated from motor traffic. |
| `shoulder` | `shoulder` | 14 | `yes`, `no`, `paved`, `gravel`. Riding on a shoulder is often the only option. |
| `sidewalk` | `sidewalk` | 14 | `both`, `left`, `right`, `no`, `separate`. Sometimes the only legal space. |
| `width` | `width` | 14 | Physical width in metres. Narrow shared lanes are a real hazard. |
| `lit` | `lit` | 13 | Street lighting. Decisive for night riding. |

#### Terrain

| Attribute | OSM source | Min zoom | Notes |
|---|---|---|---|
| `incline` | `incline` | 13 | Gradient in percent. The single biggest factor in route difficulty. |
| `mtb_scale` | `mtb_scale` | 13 | 0–5 technical difficulty. Only meaningful for off-road riding. |

#### Network

| Attribute | OSM source | Min zoom | Notes |
|---|---|---|---|
| `network` | `network` | 11 | Which cycle network the way belongs to (`lcn`, `rcn`, `ncn`, …). Lets the renderer group a coherent network, and lets the router prefer it. |
| `service` | `service` | 13 | `parking_aisle`, `driveway`, `alley`. Predicts how much motor traffic to expect. |

### 7 general-navigation attributes

Not cycling-specific, but needed to render and label the way.

`class`, `name`, `name_en`, `ref`, `maxspeed`, `layer`, `brunnel`

---

## Layer-by-layer summary

The schema defines **11 layers** carrying **56 attribute fields** in total:

| Layer | Geometry | Purpose |
|---|---|---|
| `landcover` | polygon | Forest, farmland, residential areas, ice — the ground the map sits on. |
| `water` | polygon | Lakes and water bodies, with a coastline stroke. |
| `waterway` | line | Rivers, streams, canals. |
| `roads` | line | The road network — see above. |
| `cycle_network` | line | Cycle network relations, rendered as a distinct overlay. |
| `barriers` | line | Fences, walls, bollards — relevant when a route follows a path. |
| `bike` | line | Named cycle routes and track numbers. |
| `poi` | point | Points of interest. |
| `places` | point | Towns, villages, suburbs, neighbourhoods. |
| `transit` | line | Railways, and stops where a bike can be taken by train. |
| `boundaries` | line | Administrative boundaries. |

---

## Zoom strategy

The schema uses per-attribute `min_zoom` values so that the expensive,
high-cardinality attributes only appear when they can be resolved. A rider at
z6 sees a road hierarchy; the surface and smoothness detail only appears from
z12–z14.

Road geometry itself has a matching ramp: motorways appear at z6, primary at
z8, secondary at z9, tertiary at z10, and everything else at z12.

This is what keeps the Berlin pack at **30.2 MB** for **1,808,276 features**
across **862 tiles** at z6–14 — roughly 35 KB per tile, which is what makes a
full regional pack downloadable over a phone connection.

---

## Rendering rules derived from these attributes

The map style uses the attributes as follows (Phase 3):

| Attribute | Effect |
|---|---|
| `class` | Stroke width and colour from the road palette. |
| `surface` | Dash pattern or a subtle colour shift for unpaved surfaces. |
| `smoothness` | A warning tint for `bad` / `very_poor` when the route uses it. |
| `bicycle` = `dismount` | A distinct marker on the route line. |
| `bicycle` = `no` / `designated` / `use_sidepath` | Rendered differently from a plain legal road. |
| `incline` | Gradient indication on the route line and in the route detail list. |
| `lit` = `no` | Night-mode caution styling. |
| `cycleway` + `segregated` | Cycle-lane overlay colour: protected vs unprotected vs painted. |

The palette these colours come from is defined in
`app/src/main/assets/palette.json` and is covered by
`app/src/test/java/com/astramaps/app/ui/theme/PaletteContrastTest.kt`.
