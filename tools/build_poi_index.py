#!/usr/bin/env python3
"""Build the Astra Maps place index (``*.poi.db``) from an OpenStreetMap extract.

The output is a self-contained, read-only SQLite database with an FTS4
full-text index. It is shipped inside a regional pack and opened directly by
the app; it is *not* a Room database, so it carries no Room identity hash and
no Room schema version. See ``docs/PACKS.md`` section 8 for the format contract
and ``docs/ARCHITECTURE.md`` for why search uses FTS4 rather than FTS5.

Requirements: Python 3.8+, ``pyosmium``.

    pip install osmium

Typical use::

    python tools/build_poi_index.py \
        --pbf berlin.osm.pbf \
        --out astra-de-01.poi.db

Verify an existing index against the format contract::

    python tools/build_poi_index.py --verify astra-de-01.poi.db

Why the extraction happens here rather than on the device
--------------------------------------------------------
Parsing a PBF needs a native OSM library and tens of seconds of CPU. Shipping
the *result* means a pack install is a file copy, and the same bytes are
reproducible on a laptop, in CI, and by a contributor.

Why FTS4 with an external content table
---------------------------------------
The name columns are duplicated into the FTS index, but coordinates and ids
live only in the ordinary ``poi`` table. Putting ``lat``/``lon`` inside the FTS
table would make every coordinate a tokenised text column, which inflates the
index and answers no query: coordinates are queried with inequalities, not
with ``MATCH``.

``content='poi'`` makes the FTS table store only the index term positions; a
``MATCH`` query joins back to ``poi`` by rowid, so the caller gets coordinates
in the same statement.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import pathlib
import re
import sqlite3
import struct
import sys
import unicodedata

# Bumped whenever the on-disk format changes in a way the app must notice.
FORMAT_VERSION = 1

# Identifies an Astra Maps place index. ASCII "ASTR".
APPLICATION_ID = struct.unpack("<i", b"ASTR")[0]

# --------------------------------------------------------------------------
# What counts as a cycling-relevant POI
# --------------------------------------------------------------------------
#
# The tile pipeline carries every shop and amenity a cyclist might pass; this
# index is narrower on purpose. It answers two questions: "what is near me that
# helps me ride" and "where can I eat / refill / repair / park". A bench or a
# fire hydrant is noise in a search box.

POI_TAGS = {
    "amenity": set(
        "cafe restaurant fast_food pub bar drinking_water toilets bench "
        "picnic_site shelter pharmacy hospital atm bank post_office fuel "
        "waste_basket vending_machine place_of_worship university school "
        "library police fire_station townhall childcare public_bath "
        "bicycle_parking bicycle_rental bicycle_repair_station "
        "charging_station".split()
    ),
    "shop": set(
        "bicycle bicycle_repair convenience supermarket bakery butcher "
        "greengrocer alcohol hair_care laundry dry_cleaning books clothing "
        "hardware car_repair".split()
    ),
    "tourism": set(
        "hotel hostel guest_house attraction viewpoint information artwork "
        "museum gallery theme_park picnic_site".split()
    ),
    "natural": set("spring peak cave_entrance beach".split()),
    "historic": set("memorial monument castle ruins".split()),
    "emergency": set("fire_hydrant defibrillator phone".split()),
    "highway": set("bus_stop street_lamp".split()),
    "railway": set("station halt tram_stop subway_entrance".split()),
}

PLACE_VALUES = set(
    "city town village suburb neighbourhood hamlet isolated_dwelling "
    "city_block quarter borough locality".split()
)

# Kinds that survive into the shipped index. Deliberately drops bench,
# emergency, attraction, historic and generic shop: they are common, unnamed,
# and never what someone types into a search box.
SHIPPED_KINDS = {
    "parking",
    "food",
    "water",
    "toilets",
    "bike_shop",
    "repair",
    "rental",
    "charging",
    "transit",
    "lodging",
    "shelter",
    "natural",
}


def kind_of(tags) -> str | None:
    """Collapse OSM tags into a single search-facing kind."""
    a = tags.get("amenity")
    s = tags.get("shop")
    tu = tags.get("tourism")
    n = tags.get("natural")
    h = tags.get("historic")
    e = tags.get("emergency")
    r = tags.get("railway")

    if a in ("cafe", "restaurant", "fast_food", "pub", "bar"):
        return "food"
    if a == "drinking_water":
        return "water"
    if a == "toilets":
        return "toilets"
    if a == "bench":
        return "bench"
    if a == "picnic_site":
        return "picnic_site"
    if a == "shelter":
        return "shelter"
    if a == "bicycle_parking":
        return "parking"
    if a == "bicycle_rental":
        return "rental"
    if a == "bicycle_repair_station":
        return "repair"
    if a == "charging_station":
        return "charging"
    if s in ("bicycle", "bicycle_repair"):
        return "bike_shop"
    if s:
        return "shop"
    if tu in ("hotel", "hostel", "guest_house"):
        return "lodging"
    if tu:
        return "attraction"
    if n:
        return "natural"
    if h:
        return "historic"
    if e:
        return "emergency"
    if r:
        return "transit"
    return None


def _matched_kind(tags) -> str | None:
    for key, allowed in POI_TAGS.items():
        value = tags.get(key)
        if value is not None and value in allowed:
            return kind_of(tags)
    return None


def extract(pbf_path: str) -> tuple[list[dict], list[dict], list[dict]]:
    """Read the PBF and return (pois, places, streets)."""
    import osmium

    pois: list[dict] = []
    places: list[dict] = []
    streets: dict[str, dict] = {}
    stats = collections.Counter()

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            tags = n.tags
            if not tags:
                return
            stats["nodes"] += 1

            place = tags.get("place")
            if place in PLACE_VALUES:
                places.append(
                    {
                        "osm_type": "node",
                        "osm_id": n.id,
                        "kind": place,
                        "name": tags.get("name", "") or "",
                        "name_en": tags.get("name:en", "") or "",
                        "population": _int_or_none(tags.get("population")),
                        "lat": n.location.lat,
                        "lon": n.location.lon,
                    }
                )
                return

            kind = _matched_kind(tags)
            if kind is None or kind not in SHIPPED_KINDS:
                return
            stats["poi_" + kind] += 1
            pois.append(
                {
                    "osm_type": "node",
                    "osm_id": n.id,
                    "kind": kind,
                    "name": tags.get("name", "") or "",
                    "name_en": tags.get("name:en", "") or "",
                    "lat": n.location.lat,
                    "lon": n.location.lon,
                }
            )

        def way(self, w):
            tags = w.tags
            if not tags:
                return
            stats["ways"] += 1

            name = tags.get("name")
            highway = tags.get("highway")
            if name and highway:
                street = streets.setdefault(
                    name,
                    {
                        "name": name,
                        "class": highway,
                        "count": 0,
                        "minlat": 90.0,
                        "maxlat": -90.0,
                        "minlon": 180.0,
                        "maxlon": -180.0,
                    },
                )
                street["count"] += 1
                try:
                    box = w.bbox
                    if box.valid():
                        street["minlat"] = min(street["minlat"], box.bottom)
                        street["maxlat"] = max(street["maxlat"], box.top)
                        street["minlon"] = min(street["minlon"], box.left)
                        street["maxlon"] = max(street["maxlon"], box.right)
                        return
                except Exception:
                    pass
                for node in w.nodes:
                    if node.location.valid():
                        lat, lon = node.location.lat, node.location.lon
                        street["minlat"] = min(street["minlat"], lat)
                        street["maxlat"] = max(street["maxlat"], lat)
                        street["minlon"] = min(street["minlon"], lon)
                        street["maxlon"] = max(street["maxlon"], lon)
                return

            place = tags.get("place")
            if place in PLACE_VALUES:
                try:
                    centroid = w.centroid
                except Exception:
                    return
                places.append(
                    {
                        "osm_type": "way",
                        "osm_id": w.id,
                        "kind": place,
                        "name": name or "",
                        "name_en": tags.get("name:en", "") or "",
                        "population": _int_or_none(tags.get("population")),
                        "lat": centroid.lat,
                        "lon": centroid.lon,
                    }
                )
                return

            kind = _matched_kind(tags)
            if kind is None or kind not in SHIPPED_KINDS:
                return
            try:
                centroid = w.centroid
            except Exception:
                return
            stats["poi_" + kind] += 1
            pois.append(
                {
                    "osm_type": "way",
                    "osm_id": w.id,
                    "kind": kind,
                    "name": name or "",
                    "name_en": tags.get("name:en", "") or "",
                    "lat": centroid.lat,
                    "lon": centroid.lon,
                }
            )

    Handler().apply_file(pbf_path, locations=True)

    # Stable order so two runs over the same extract produce byte-identical
    # files apart from SQLite's internal layout.
    pois.sort(key=lambda p: (p["kind"], p["name"].lower(), p["lat"], p["lon"], p["osm_id"]))
    places.sort(key=lambda p: (p["kind"], p["name"].lower(), p["lat"], p["lon"], p["osm_id"]))
    return pois, places, sorted(streets.values(), key=lambda s: s["name"])


def _int_or_none(value):
    if value and str(value).isdigit():
        return int(value)
    return None


# --------------------------------------------------------------------------
# The format contract
# --------------------------------------------------------------------------
#
# docs/PACKS.md section 8 carries the same statements. verify() below compares
# the live database against these, so the document and the artifact cannot
# drift apart silently.

COUNTS_QUERIES = {
    "poi": "SELECT COUNT(*) FROM poi",
    "poi_fts": "SELECT COUNT(*) FROM poi_fts",
    "place": "SELECT COUNT(*) FROM place",
    "place_fts": "SELECT COUNT(*) FROM place_fts",
    "street": "SELECT COUNT(*) FROM street",
    "street_fts": "SELECT COUNT(*) FROM street_fts",
}

EXPECTED_COLUMNS = {
    "poi": [
        ("id", "INTEGER"),
        ("osm_type", "TEXT"),
        ("osm_id", "INTEGER"),
        ("kind", "TEXT"),
        ("name", "TEXT"),
        ("name_en", "TEXT"),
        ("lat", "REAL"),
        ("lon", "REAL"),
    ],
    "place": [
        ("id", "INTEGER"),
        ("osm_type", "TEXT"),
        ("osm_id", "INTEGER"),
        ("kind", "TEXT"),
        ("name", "TEXT"),
        ("name_en", "TEXT"),
        ("population", "INTEGER"),
        ("lat", "REAL"),
        ("lon", "REAL"),
    ],
    "street": [
        ("name", "TEXT"),
        ("class", "TEXT"),
        ("count", "INTEGER"),
        ("minlat", "REAL"),
        ("maxlat", "REAL"),
        ("minlon", "REAL"),
        ("maxlon", "REAL"),
    ],
}

EXPECTED_INDEXES = {
    "poi_kind",
    "poi_lat_lon",
}

EXPECTED_FTS = {
    "poi_fts": {"table": "poi", "columns": ["name", "name_en", "kind"]},
    "place_fts": {"table": "place", "columns": ["name", "name_en", "kind"]},
    "street_fts": {"table": "street", "columns": ["name"]},
}

SCHEMA_SQL = """
CREATE TABLE poi (
    id       INTEGER PRIMARY KEY,
    osm_type TEXT    NOT NULL,
    osm_id   INTEGER NOT NULL,
    kind     TEXT    NOT NULL,
    name     TEXT    NOT NULL,
    name_en  TEXT,
    lat      REAL    NOT NULL,
    lon      REAL    NOT NULL
);

-- No unique index on (osm_type, osm_id): the extract yields each node and way
-- at most once, so uniqueness holds by construction, and the app never looks a
-- POI up by OSM id. That index cost 0.76 MB and answered no query.
CREATE INDEX poi_kind    ON poi (kind);
CREATE INDEX poi_lat_lon ON poi (lat, lon);

-- External content: the FTS table stores index terms only, and a MATCH joins
-- back to `poi` by rowid so coordinates come from the same query.
CREATE VIRTUAL TABLE poi_fts USING fts4(
    name, name_en, kind,
    content='poi',
    tokenize=unicode61 "remove_diacritics=2"
);

CREATE TABLE place (
    id         INTEGER PRIMARY KEY,
    osm_type   TEXT    NOT NULL,
    osm_id     INTEGER NOT NULL,
    kind       TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    name_en    TEXT,
    population INTEGER,
    lat        REAL    NOT NULL,
    lon        REAL    NOT NULL
);

CREATE VIRTUAL TABLE place_fts USING fts4(
    name, name_en, kind,
    content='place',
    tokenize=unicode61 "remove_diacritics=2"
);

CREATE TABLE street (
    name   TEXT PRIMARY KEY,
    class  TEXT,
    count  INTEGER,
    minlat REAL,
    maxlat REAL,
    minlon REAL,
    maxlon REAL
);

-- No separate index on street(name): a TEXT PRIMARY KEY is already backed by a
-- unique index, so an explicit one only duplicated it (0.31 MB).
CREATE VIRTUAL TABLE street_fts USING fts4(
    name,
    content='street',
    tokenize=unicode61 "remove_diacritics=2"
);
"""


def build(pois: list[dict], places: list[dict], streets: list[dict], out_path: str) -> int:
    if os.path.exists(out_path):
        os.remove(out_path)

    con = sqlite3.connect(out_path)
    try:
        cur = con.cursor()
        cur.execute("PRAGMA page_size=4096")
        cur.execute("PRAGMA journal_mode=OFF")
        cur.execute("PRAGMA synchronous=OFF")
        cur.executescript(SCHEMA_SQL)

        cur.executemany(
            "INSERT INTO poi (osm_type, osm_id, kind, name, name_en, lat, lon) "
            "VALUES (?,?,?,?,?,?,?)",
            [
                (
                    p["osm_type"],
                    p["osm_id"],
                    p["kind"],
                    p["name"],
                    p["name_en"] or None,
                    p["lat"],
                    p["lon"],
                )
                for p in pois
            ],
        )
        cur.executemany(
            "INSERT INTO place (osm_type, osm_id, kind, name, name_en, population, lat, lon) "
            "VALUES (?,?,?,?,?,?,?,?)",
            [
                (
                    p["osm_type"],
                    p["osm_id"],
                    p["kind"],
                    p["name"],
                    p["name_en"] or None,
                    p["population"],
                    p["lat"],
                    p["lon"],
                )
                for p in places
            ],
        )
        cur.executemany(
            "INSERT INTO street (name, class, count, minlat, maxlat, minlon, maxlon) "
            "VALUES (?,?,?,?,?,?,?)",
            [
                (
                    s["name"],
                    s["class"],
                    s["count"],
                    s["minlat"],
                    s["maxlat"],
                    s["minlon"],
                    s["maxlon"],
                )
                for s in streets
            ],
        )

        # Populate the three indexes from their content tables.
        for fts in EXPECTED_FTS:
            cur.execute(f"INSERT INTO {fts}({fts}) VALUES('rebuild')")

        cur.execute(f"PRAGMA application_id={APPLICATION_ID}")
        cur.execute(f"PRAGMA user_version={FORMAT_VERSION}")
        con.commit()
        cur.execute("VACUUM")
        con.commit()
    finally:
        con.close()

    return os.path.getsize(out_path)


# --------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------


def _sqlite_master_sql(cur, name: str) -> str:
    row = cur.execute(
        "SELECT sql FROM sqlite_master WHERE name = ?", (name,)
    ).fetchone()
    return (row[0] or "") if row else ""


def _stratified(items: list[dict], limit: int) -> list[dict]:
    """Take `limit` items spread round-robin across kinds.

    A plain slice would keep only the alphabetically-first kinds (the fixture
    would contain bike shops and charging stations and nothing else), so a
    truncated index would not resemble a real one.
    """
    buckets: dict[str, list[dict]] = {}
    for item in items:
        buckets.setdefault(item["kind"], []).append(item)

    order = sorted(buckets)
    position = {kind: 0 for kind in order}
    picked: list[dict] = []
    while len(picked) < limit:
        progressed = False
        for kind in order:
            index = position[kind]
            if index < len(buckets[kind]):
                picked.append(buckets[kind][index])
                position[kind] = index + 1
                progressed = True
                if len(picked) == limit:
                    break
        if not progressed:
            break

    # Re-apply the global ordering so the output stays deterministic and the
    # fixture's row order still matches a full build's.
    picked.sort(key=lambda p: (p["kind"], p["name"].lower(), p["lat"], p["lon"], p["osm_id"]))
    return picked


def _first_letter_run(text: str, minimum: int = 4) -> str | None:
    """Return the first run of letters at least `minimum` long, or None.

    Used to build a MATCH probe from real data rather than a hard-coded name,
    so the same verifier works for a full region and for a test fixture.
    """
    for word in re.findall(r"[^\W\d_]+", text, re.UNICODE):
        if len(word) >= minimum:
            return word
    return None


def verify(path: str) -> bool:
    # A file: URI is the portable way to ask for read-only mode. Build it with
    # pathlib so a Windows drive letter does not confuse SQLite's URI parser.
    uri = pathlib.Path(path).resolve().as_uri() + "?mode=ro"
    con = sqlite3.connect(uri, uri=True)
    ok = True
    try:
        cur = con.cursor()

        app_id = cur.execute("PRAGMA application_id").fetchone()[0]
        user_version = cur.execute("PRAGMA user_version").fetchone()[0]
        if app_id != APPLICATION_ID:
            print(f"  FAIL application_id: {app_id} != {APPLICATION_ID}")
            ok = False
        if user_version != FORMAT_VERSION:
            print(f"  FAIL user_version: {user_version} != {FORMAT_VERSION}")
            ok = False

        for table, expected in EXPECTED_COLUMNS.items():
            actual = [
                (row[1], (row[2] or "").upper())
                for row in cur.execute(f"PRAGMA table_info({table})").fetchall()
            ]
            if actual != expected:
                print(f"  FAIL {table} columns")
                print(f"        expected {expected}")
                print(f"        actual   {actual}")
                ok = False

        indexes = {
            row[0]
            for row in cur.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"
            ).fetchall()
        }
        missing = EXPECTED_INDEXES - indexes
        if missing:
            print(f"  FAIL missing indexes: {sorted(missing)}")
            ok = False

        for fts, spec in EXPECTED_FTS.items():
            sql = " ".join(_sqlite_master_sql(cur, fts).split())
            if f"content='{spec['table']}'" not in sql.replace('"', "'"):
                print(f"  FAIL {fts} is not external-content over {spec['table']}")
                print(f"        {sql}")
                ok = False
            if "unicode61" not in sql:
                print(f"  FAIL {fts} does not use the unicode61 tokenizer")
                ok = False

        counts = {}
        for name, query in COUNTS_QUERIES.items():
            counts[name] = cur.execute(query).fetchone()[0]

        if counts["poi_fts"] != counts["poi"]:
            print(
                f"  FAIL poi_fts has {counts['poi_fts']} rows but poi has {counts['poi']}"
            )
            ok = False
        if counts["place_fts"] != counts["place"]:
            print(
                f"  FAIL place_fts has {counts['place_fts']} rows but place has {counts['place']}"
            )
            ok = False
        if counts["street_fts"] != counts["street"]:
            print(
                f"  FAIL street_fts has {counts['street_fts']} rows but street has {counts['street']}"
            )
            ok = False

        # Real queries, with the probe tokens taken from the data, so a
        # structurally valid but empty index cannot pass and a fixture with
        # different contents still verifies.
        sample = cur.execute(
            "SELECT name FROM poi WHERE name <> '' ORDER BY id LIMIT 200"
        ).fetchone()
        probe = None
        for (poi_name,) in cur.execute(
            "SELECT name FROM poi WHERE name <> '' ORDER BY id LIMIT 200"
        ):
            probe = _first_letter_run(poi_name)
            if probe:
                break
        if not sample:
            print("  FAIL no named POI to probe with")
            ok = False
        elif probe is None:
            print("  FAIL could not find a probe token in the first 200 named POIs")
            ok = False
        else:
            hits = cur.execute(
                "SELECT p.name, p.kind, p.lat, p.lon FROM poi_fts f "
                "JOIN poi p ON p.id = f.rowid "
                "WHERE poi_fts MATCH ? ORDER BY p.id LIMIT 5",
                (probe.lower() + "*",),
            ).fetchall()
            print(
                f"  MATCH {probe.lower() + '*'!r}: {len(hits)} hit(s), "
                f"first={hits[0][:2] if hits else None}"
            )
            if not hits:
                print(f"  FAIL MATCH for {probe!r} returned no POIs")
                ok = False
            elif not all(
                isinstance(h[2], float) and isinstance(h[3], float) for h in hits
            ):
                print("  FAIL MATCH results did not carry float coordinates")
                ok = False

        # Diacritic folding must work, or German and Polish names are
        # unsearchable by anyone typing on an ASCII keyboard.
        checked = False
        for (place_name,) in cur.execute("SELECT name FROM place ORDER BY id"):
            for word in re.findall(r"[^\W\d_]{4,}", place_name, re.UNICODE):
                folded = "".join(
                    ch
                    for ch in unicodedata.normalize("NFKD", word)
                    if not unicodedata.combining(ch)
                )
                if folded.lower() == word.lower():
                    continue
                hits = cur.execute(
                    "SELECT COUNT(*) FROM place_fts WHERE place_fts MATCH ?",
                    (folded.lower() + "*",),
                ).fetchone()[0]
                print(f"  diacritic probe {word!r} -> {folded.lower()!r}*: {hits} place(s)")
                if hits == 0:
                    print(f"  FAIL diacritic folding: {folded!r}* did not match {word!r}")
                    ok = False
                checked = True
                break
            if checked:
                break
        if not checked:
            print("  note: no diacritic-bearing place name available to probe")

        print("  rows: " + ", ".join(f"{k}={v:,}" for k, v in counts.items()))
        print(f"  application_id=0x{app_id:08X} user_version={user_version}")
    finally:
        con.close()

    return ok


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pbf", help="OSM extract to read (.osm.pbf)")
    parser.add_argument("--out", help="place index to write (*.poi.db)")
    parser.add_argument(
        "--verify",
        metavar="DB",
        help="verify an existing index against the documented format",
    )
    parser.add_argument(
        "--limit",
        type=int,
        metavar="N",
        help=(
            "keep only N rows per table, chosen round-robin across kinds. "
            "Intended for building a small, deterministic fixture for "
            "instrumented tests; never use it for a shipped pack."
        ),
    )
    args = parser.parse_args(argv)

    if args.verify:
        print(f"Verifying {args.verify}")
        ok = verify(args.verify)
        print("OK" if ok else "FAILED")
        return 0 if ok else 1

    if not args.pbf or not args.out:
        parser.error("--pbf and --out are required unless --verify is given")
    if not os.path.exists(args.pbf):
        parser.error(f"no such extract: {args.pbf}")

    print(f"Reading {args.pbf}")
    pois, places, streets = extract(args.pbf)

    if args.limit:
        # Round-robin across kinds so a truncated index still contains every
        # kind a real one does, instead of only the alphabetically-first ones.
        pois = _stratified(pois, args.limit)
        places = _stratified(places, args.limit)
        streets = streets[: args.limit]
        print(
            f"  truncated to {args.limit} rows per table (fixture mode, "
            f"stratified by kind)"
        )

    by_kind = collections.Counter(p["kind"] for p in pois)
    print(f"  {len(pois):,} POIs, {len(places):,} places, {len(streets):,} streets")
    for kind, count in sorted(by_kind.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"    {kind:12s} {count:7,d}")

    size = build(pois, places, streets, args.out)
    print(f"Wrote {args.out}: {size:,} bytes ({size / 1048576:.2f} MB)")

    print("Verifying written index")
    ok = verify(args.out)
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
