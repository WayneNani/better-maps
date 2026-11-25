#!/usr/bin/env python3
# Generates services/indoor-server/testdata/export-fixture.osm (hand-designed small indoor venue).
import sys
from xml.sax.saxutils import quoteattr

nodes = []  # (id, lat, lon, tags)
ways = []   # (id, [node ids], tags)
rels = []   # (id, [(type, ref, role)], tags)
nid = [1000]


def node(lon, lat, tags=None, id_=None):
    if id_ is None:
        nid[0] += 1
        id_ = nid[0]
    nodes.append((id_, lat, lon, tags or {}))
    return id_


def ring(x0, y0, x1, y1):
    """Rectangle lon x0..x1, lat y0..y1 as closed node list."""
    a = node(x0, y0)
    b = node(x1, y0)
    c = node(x1, y1)
    d = node(x0, y1)
    return [a, b, c, d, a]


def way(id_, nds, tags):
    ways.append((id_, nds, tags))


LON, LAT = 8.5600, 50.0500
DX, DY = 0.0001, 0.0001  # ~7.2 m, ~11.1 m

# Terminal building (100 m x 67 m) and the level outline of level 0.
way(1, ring(LON, LAT, LON + 14 * DX, LAT + 6 * DY), {"aeroway": "terminal", "building": "yes", "name": "Terminal 9"})
way(2, ring(LON, LAT, LON + 14 * DX, LAT + 6 * DY), {"indoor": "level", "level": "0", "level:ref": "EG", "name": "Erdgeschoss"})
way(3, ring(LON, LAT, LON + 14 * DX, LAT + 6 * DY), {"indoor": "level", "level": "1", "name": "Abflug"})
# Rooms on level 0 along the south side (each ~14 m x 22 m).
room0 = ring(LON, LAT, LON + 2 * DX, LAT + 2 * DY)
way(10, room0, {"indoor": "room", "level": "0", "shop": "kiosk", "name": "Relay", "opening_hours": "Mo-Su 05:00-23:00"})
way(11, ring(LON + 2 * DX, LAT, LON + 4 * DX, LAT + 2 * DY), {"indoor": "room", "level": "0", "amenity": "toilets", "wheelchair": "yes"})
way(12, ring(LON + 4 * DX, LAT, LON + 6 * DX, LAT + 2 * DY), {"indoor": "room", "level": "0", "access": "private"})
way(13, ring(LON + 6 * DX, LAT, LON + 8 * DX, LAT + 2 * DY), {"indoor": "room", "level": "0;1", "room": "stairs"})
# Rooms on level 1.
way(14, ring(LON, LAT + 4 * DY, LON + 2 * DX, LAT + 6 * DY), {"indoor": "room", "level": "1", "amenity": "restaurant", "name": "Bistro"})
way(15, ring(LON + 2 * DX, LAT + 4 * DY, LON + 4 * DX, LAT + 6 * DY), {"indoor": "room", "level": "1", "office": "airline"})
way(16, ring(LON + 4 * DX, LAT + 4 * DY, LON + 6 * DX, LAT + 6 * DY), {"indoor": "area", "level": "1"})
# Multipolygon room with a hole on level 1.
way(17, ring(LON + 8 * DX, LAT + 4 * DY, LON + 12 * DX, LAT + 6 * DY), {})
way(18, ring(LON + 9 * DX, LAT + 4.5 * DY, LON + 10 * DX, LAT + 5.5 * DY), {})
rels.append((1, [("way", 17, "outer"), ("way", 18, "inner")], {"type": "multipolygon", "indoor": "room", "level": "1", "shop": "duty_free", "name": "Heinemann"}))
# Corridor on level 0 (centre strip).
way(20, ring(LON, LAT + 2 * DY, LON + 14 * DX, LAT + 4 * DY), {"indoor": "corridor", "level": "0"})
# Gate waiting area with ref.
way(21, ring(LON + 10 * DX, LAT, LON + 14 * DX, LAT + 2 * DY), {"indoor": "area", "aeroway": "gate", "ref": "B12", "level": "0"})
# Lines.
way(30, [node(LON + 8 * DX, LAT), node(LON + 8 * DX, LAT + 2 * DY), node(LON + 8.5 * DX, LAT + 2 * DY)], {"indoor": "wall", "level": "0"})
way(31, ring(LON + 7 * DX, LAT + 3 * DY, LON + 7.1 * DX, LAT + 3.1 * DY), {"indoor": "wall", "level": "0"})  # pillar
way(40, [node(LON + 6.5 * DX, LAT + 1 * DY), node(LON + 7.5 * DX, LAT + 1 * DY)], {"highway": "steps", "level": "0;1", "indoor": "yes"})
way(41, [node(LON + 9 * DX, LAT + 3 * DY), node(LON + 9 * DX, LAT + 5 * DY)], {"highway": "steps", "conveying": "forward", "level": "0-1"})
way(42, ring(LON + 1 * DX, LAT + 4.5 * DY, LON + 3 * DX, LAT + 5.5 * DY), {"highway": "footway", "indoor": "yes", "level": "1"})
# A closed footway without level and not indoor: not exported as a feature.
way(43, ring(LON + 20 * DX, LAT, LON + 21 * DX, LAT + 1 * DY), {"highway": "footway"})
# Platform (closed way) on level -1, pedestrian area with area=yes.
way(50, ring(LON, LAT - 2 * DY, LON + 14 * DX, LAT - 1 * DY), {"railway": "platform", "public_transport": "platform", "level": "-1", "ref": "3"})
way(51, ring(LON + 12 * DX, LAT + 2 * DY, LON + 14 * DX, LAT + 4 * DY), {"highway": "pedestrian", "area": "yes", "level": "0"})
# Named building far away (candidate container, not a venue).
way(60, ring(LON + 0.01, LAT + 0.01, LON + 0.0102, LAT + 0.0102), {"building": "yes", "name": "Faraway"})

# Points. The door is a vertex of room 10 (no level, gets the room's level from its outline).
nodes[:] = [(i, la, lo, ({"door": "hinged"} if i == room0[1] else t)) for (i, la, lo, t) in nodes]
node(LON + 1 * DX, LAT + 5 * DY, {"amenity": "toilets", "level": "1", "wheelchair": "yes", "changing_table": "yes"})
node(LON + 12 * DX, LAT + 1 * DY, {"aeroway": "gate", "ref": "A5", "level": "0"})
node(LON + 7 * DX, LAT + 3.5 * DY, {"highway": "elevator", "level": "-1;0;1"})
node(LON + 3 * DX, LAT + 3 * DY, {"shop": "newsagent", "name": "Presse", "level": "0", "opening_hours": "24/7"})
node(LON + 5 * DX, LAT + 5 * DY, {"amenity": "cafe", "name": "Kaffee", "level": "1", "cuisine": "coffee_shop"})
node(LON + 6 * DX, LAT + 3 * DY, {"shop": "gift", "level": "EG"})  # unparseable level: dropped
node(LON + 11 * DX, LAT + 3 * DY, {"tourism": "information", "level": "0"})
node(LON + 13 * DX, LAT + 3 * DY, {"amenity": "atm", "level": "0", "operator": "Bank"})
node(LON + 14 * DX, LAT + 3 * DY, {"entrance": "main", "level": "0"})
node(LON + 7 * DX, LAT + 1 * DY, {"railway": "station", "public_transport": "station", "name": "Teststation"})
node(LON + 0.02, LAT + 0.02, {"shop": "bakery", "level": "0"})  # outside every venue: ignored

out = sys.stdout
out.write("<?xml version='1.0' encoding='UTF-8'?>\n<osm version=\"0.6\" generator=\"genfixture\">\n")
for i, la, lo, t in sorted(nodes):
    if t:
        out.write(f'  <node id="{i}" version="1" lat="{la:.7f}" lon="{lo:.7f}">\n')
        for k, v in t.items():
            out.write(f"    <tag k={quoteattr(k)} v={quoteattr(v)}/>\n")
        out.write("  </node>\n")
    else:
        out.write(f'  <node id="{i}" version="1" lat="{la:.7f}" lon="{lo:.7f}"/>\n')
for i, nds, t in ways:
    out.write(f'  <way id="{i}" version="1">\n')
    for n in nds:
        out.write(f'    <nd ref="{n}"/>\n')
    for k, v in t.items():
        out.write(f"    <tag k={quoteattr(k)} v={quoteattr(v)}/>\n")
    out.write("  </way>\n")
for i, mem, t in rels:
    out.write(f'  <relation id="{i}" version="1">\n')
    for ty, ref, role in mem:
        out.write(f'    <member type="{ty}" ref="{ref}" role="{role}"/>\n')
    for k, v in t.items():
        out.write(f"    <tag k={quoteattr(k)} v={quoteattr(v)}/>\n")
    out.write("  </relation>\n")
out.write("</osm>\n")
