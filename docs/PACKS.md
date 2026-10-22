# Regional packs

A **pack** is the unit of downloadable offline data. This document defines the
pack format, the manifest, the install rules, and the update rules.

---

## 1. Anatomy of a pack

A pack bundles four independent payloads plus a manifest.

| Payload | File | Purpose |
|---|---|---|
| Map | `*.pmtiles` | Vector tiles for the region |
| Places | `*.poi.db` | SQLite database with the on-device search index |
| Routing | `*.rd5` | BRouter routing graph cells (one or more) |
| Routing support | `lookups.dat` | BRouter cell lookup table + version |
| Routing profiles | `*.brf` | BRouter routing profiles |

The map and places payloads are **bbox-scoped** (the region the user chose).
The routing payload is **cell-scoped** (BRouter's fixed 5°×5° global grid),
so the two are deliberately decoupled: a small city region still pulls in the
whole routing cell that contains it.

### Pack IDs

`astra-<country>-<sequence>` — e.g. `astra-de-01`.

The ID is **stable** across versions. The manifest carries the version, so the
client can decide whether an installed pack needs replacing.

---

## 2. Manifest

One JSON document per pack, served from a stable URL. Every artifact is
content-addressed by SHA-256.

```jsonc
{
  "pack_id": "astra-de-01",
  "version": "1.0.0",
  "schema_version": "1.0.0",
  "osm_build_date": "2026-10-20",
  "bbox": [13.08283, 52.33446, 13.762245, 52.6783],
  "artifacts": [
    {
      "id": "map",
      "url": "https://github.com/MrTech59/Astra-Maps/releases/download/astra-de-01-1.0.0/astra-de-01-1.0.0.pmtiles",
      "size": 31676842,
      "sha256": "…"
    },
    {
      "id": "poi",
      "url": "…/astra-de-01-1.0.0.poi.db",
      "size": 4898816,
      "sha256": "…"
    },
    {
      "id": "rd5",
      "url": "…/E10_N50.rd5",
      "size": 125081401,
      "sha256": "…",
      "cells": ["E10_N50"],
      "read_version": 11
    },
    {
      "id": "lookups",
      "url": "…/lookups.dat",
      "size": 31604,
      "sha256": "…",
      "lookup_version": 11,
      "minor_version": 2
    },
    { "id": "brf", "url": "…/astra-quiet.brf", "size": 18000, "sha256": "…" }
  ]
}
```

---

## 3. Routing cell naming

BRouter stores routing data on a fixed 5°×5° global grid. The cell name is
derived from the coordinate:

```
lonCell = floor(lon / 5) * 5
latCell = floor(lat / 5) * 5
name    = (E|W)<abs(lonCell)>_(N|S)<abs(latCell)>
```

Examples:

| Coordinate | Cell |
|---|---|
| 13.08283, 52.33446 (Berlin) | `E10_N50` |
| 8.68, 50.11 (Frankfurt) | `E5_N50` |
| −48.5, −37.2 | `W50_S40` |
| −48.5, 37.2 | `W50_N35` |

A pack's routing payload is the set of cells that intersect the region's
bounding box. For the Berlin bbox that is exactly one cell, `E10_N50`
(125,081,401 bytes), which also covers Potsdam, Magdeburg, Leipzig, Dresden,
Chemnitz and Erfurt.

### Staleness

Each `.rd5` cell embeds a `readVersion`. `lookups.dat` declares
`---lookupversion` and `---minorversion`. If they disagree, the routing data is
out of date: the client must re-download the affected cell and surface
**"routing data out of date"**.

---

## 4. Installation rules

1. Download to `<name>.part` in the app's private storage.
2. Verify the SHA-256 from the manifest. On mismatch, delete and retry.
3. `fsync` the file and the containing directory.
4. Atomically rename `<name>.part` → `<name>`.
5. Only then record the artifact in `installed_pack` / `installed_routing_cell`.

**Why the `.part` + rename dance matters:** MapLibre reads PMTiles with
`pmtiles://file://`, which has *no* integrity checking. If it opened a
partially-written archive it would render a corrupt map with no error. The
rename is what makes the file either fully present or wholly absent.

A pack is **complete** only when every artifact in the manifest is present and
verified. The client must refuse to route against an incomplete routing
payload.

---

## 5. Update rules

### Map tiles and place index

PMTiles has no delta format, so an updated region is re-downloaded whole
(30.2 MB for the Berlin pack). The client avoids this by comparing the
manifest's SHA-256 for the installed version; a pack is only re-downloaded when
its hash actually changes. Automatic updates default to Wi-Fi only.

The place index is replaced wholesale (4.7 MB), which is cheap.

### Routing graph — delta updates

BRouter publishes per-cell deltas:

```
https://brouter.de/brouter/segments4/diff/<CELL>/<md5-of-installed-file>.df5
```

A `.df5` file transforms the installed cell into the current one. Measured
delta sizes for `E10_N50` are **0.24 MB – 1.74 MB**, against a 119.3 MB full
re-download — roughly a 100× saving.

The delta is applied with the vendored BRouter code:

```java
btools.mapaccess.Rd5DiffTool.recoverFromDelta(
    installedRd5, diffFile, outputRd5, progressListener);
```

The result's MD5 is then compared against the expected new MD5 before the file
is put into place.

**Limits:**

* BRouter keeps only **~9 days** of delta history. Beyond that window the
  client must perform a full re-download.
* A delta is only usable if the installed cell's MD5 matches the delta's
  filename. If the client has drifted (e.g. a partially applied delta), it
  falls back to a full download.
* If the new cell's `readVersion` no longer matches `lookups.dat`, the client
  downloads the matching `lookups.dat` alongside it.

---

## 6. Hosting

Packs are published as **GitHub Releases** assets.

| Constraint | Value |
|---|---|
| Assets per release | 1000 |
| Max file size | 2 GiB |
| Total size / bandwidth | unlimited |
| Range requests | supported (resumable downloads) |

The 119.3 MB `.rd5` fits comfortably; a 100 MB/file limit would not, which
rules out GitHub Pages for pack hosting. Pages is used only for the map style
and its glyph/sprite assets, which are small.

---

## 7. Tile schema

The tiles are generated by Planetiler from our own YAML profile,
[`schemas/astra_schema.yml`](../schemas/astra_schema.yml).

```bash
planetiler generate-custom \
  --schema=schemas/astra_schema.yml \
  --bounds=13.08283,52.33446,13.762245,52.6783 \
  --maxzoom=14 --render_maxzoom=14 \
  --output=astra-de-01.pmtiles
```

The profile is validated in CI:

```bash
planetiler verify schemas/astra_schema.yml
```

It yields **11 layers** carrying **56 attribute fields** in total, of which the
`roads` layer alone carries the 20 cycling attributes that the generic
OpenMapTiles and Protomaps schemas omit. See
[`docs/ATTRIBUTES.md`](ATTRIBUTES.md).
