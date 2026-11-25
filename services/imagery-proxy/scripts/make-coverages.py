#!/usr/bin/env python3
"""Builds the coverage polygons of the state orthophoto sources (config/coverage/<id>.geojson)
from the map region borders of the app (data/borders/*.poly, Osmosis polygon format).

The regions of a state are merged, widened by ~30 m (closes slivers between the regions and
leaves no gap at the state borders; where two states overlap, the upper source in the MapProxy
cache wins) and simplified to ~20 m.

Needs shapely; scripts/make-coverages.sh runs it in the MapProxy image, which has it:
    scripts/make-coverages.sh
"""
import glob
import json
import os
import sys

from shapely.geometry import MultiPolygon, Polygon, mapping
from shapely.ops import unary_union

# Source id -> file name prefixes in data/borders. Keep in sync with config/sources.json and
# config/mapproxy.yaml.
STATES = {
    "nw": ["Germany_North Rhine-Westphalia_"],
    "rp": ["Germany_Rhineland-Palatinate_"],
    "he": ["Germany_Hesse_"],
    "ni": ["Germany_Lower Saxony_"],
    "by": ["Germany_Free State of Bavaria_"],
    "be": ["Germany_Berlin."],
    "bb": ["Germany_Brandenburg_"],
    "bw": ["Germany_Baden-Wurttemberg_"],
    "sl": ["Germany_Saarland."],
    "th": ["Germany_Thuringia."],
    "sn": ["Germany_Saxony_"],
    "sh": ["Germany_Schleswig-Holstein_"],
}

BUFFER_DEG = 0.0003
SIMPLIFY_DEG = 0.0002


def read_poly(path):
    """Returns the polygons of an Osmosis .poly file (sections starting with '!' are holes)."""
    outers, holes = [], []
    with open(path, encoding="utf-8") as f:
        lines = [l.strip() for l in f]
    i = 1  # line 0 is the name
    while i < len(lines):
        name = lines[i]
        i += 1
        if name == "END" or name == "":
            continue
        ring = []
        while i < len(lines) and lines[i] != "END":
            lon, lat = (float(v) for v in lines[i].split()[:2])
            ring.append((lon, lat))
            i += 1
        i += 1
        if len(ring) >= 3:
            (holes if name.startswith("!") else outers).append(Polygon(ring).buffer(0))
    shape = unary_union(outers)
    if holes:
        shape = shape.difference(unary_union(holes))
    return shape


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    borders = os.path.join(root, "data", "borders")
    out_dir = os.path.join(root, "services", "imagery-proxy", "config", "coverage")
    os.makedirs(out_dir, exist_ok=True)
    for sid, prefixes in STATES.items():
        files = sorted(f for p in prefixes for f in glob.glob(os.path.join(borders, p + "*")) if f.endswith(".poly"))
        if not files:
            sys.exit(f"{sid}: no border files for {prefixes}")
        shape = unary_union([read_poly(f) for f in files])
        shape = shape.buffer(BUFFER_DEG, join_style=2).simplify(SIMPLIFY_DEG, preserve_topology=True)
        if isinstance(shape, Polygon):
            shape = MultiPolygon([shape])
        geom = mapping(shape)
        geom["coordinates"] = [
            [[[round(x, 5), round(y, 5)] for x, y in ring] for ring in poly] for poly in geom["coordinates"]
        ]
        fc = {
            "type": "FeatureCollection",
            "features": [{"type": "Feature", "properties": {"id": sid, "regions": [os.path.basename(f)[:-5] for f in files]},
                          "geometry": geom}],
        }
        path = os.path.join(out_dir, sid + ".geojson")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(fc, f, separators=(",", ":"))
            f.write("\n")
        points = sum(len(r) for p in geom["coordinates"] for r in p)
        print(f"{sid}: {len(files)} regions -> {len(geom['coordinates'])} polygons, {points} points, "
              f"{os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
