#!/usr/bin/env python3
"""Checks a running MOTIS server (API /api/v6) for the better-maps app.

  smoke   Berlin Hbf -> Alexanderplatz (/api/v6/plan) and departures at Alexanderplatz (/api/v6/stoptimes):
          itineraries exist, legs carry lines, times are plausible. --expect-realtime additionally requires
          realtime data (realTime=true, delays) in departures of regional trains.
  compat  Sends exactly the requests of the app (OnlineTransitService and its unit tests) and validates the
          answers against the OpenAPI spec the app's client is generated from (android/sdk/motis_openapi.yaml):
          HTTP 200, required fields, types, enums; query parameters must exist in the spec.

Only the Python standard library is needed; PyYAML is needed for compat (to read the spec).
Exit code 0 = all checks passed.
"""

import argparse
import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "better-maps-motis-check/1.0 (self-hosted MOTIS test)"
TRANSIT_MODES = {"TRANSIT", "TRAM", "SUBWAY", "FERRY", "AIRPLANE", "BUS", "COACH", "RAIL", "HIGHSPEED_RAIL",
                 "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "SUBURBAN", "FUNICULAR",
                 "AERIAL_LIFT", "CABLE_CAR", "OTHER", "METRO"}

# Coordinates and IDs as in android/sdk/src/test/.../OnlineTransitServiceTest.java (Transitous dataset tag
# "de-VBB" is used by the self-hosted config as well).
HBF = "52.525000,13.369400"
ALEX = "52.521900,13.413200"
COTTBUS = "51.756300,14.332900"
POTSDAM = "52.391700,13.066100"
ALEX_STOP = "de-VBB_de:11000:900100003"
POTSDAM_HBF_STOP = "de-VBB_de:12054:900230999"


class Result:
    def __init__(self):
        self.failures = []
        self.passed = 0
        self.warnings = []

    def check(self, cond, msg):
        if cond:
            self.passed += 1
        else:
            self.failures.append(msg)
            print(f"  FAIL {msg}")
        return cond

    def warn(self, msg):
        self.warnings.append(msg)
        print(f"  WARN {msg}")


def get(base, path, params, timeout=60):
    """GET with params as list of (name, value); returns (status, json or text, seconds, url)."""
    query = urllib.parse.urlencode(params, safe=",:")
    url = f"{base}{path}?{query}" if query else f"{base}{path}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status, body = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read()
    seconds = time.monotonic() - start
    try:
        data = json.loads(body)
    except ValueError:
        data = body.decode("utf-8", "replace")
    return status, data, seconds, url


def parse_time(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------------------------- smoke

def check_plan(r, data, now, label, max_duration_s=2 * 3600, require_short=None):
    its = data.get("itineraries") or []
    r.check(len(its) >= 1, f"{label}: at least one itinerary")
    with_transit = 0
    shortest = None
    for i, it in enumerate(its):
        p = f"{label} itinerary {i}"
        start, end = parse_time(it["startTime"]), parse_time(it["endTime"])
        dur = (end - start).total_seconds()
        r.check(0 < dur <= max_duration_s, f"{p}: duration {dur:.0f} s plausible")
        r.check(abs(it["duration"] - dur) <= 60, f"{p}: duration field matches start/end")
        r.check(now - dt.timedelta(hours=1) <= start <= now + dt.timedelta(hours=24),
                f"{p}: start {it['startTime']} near the request time")
        legs = it.get("legs") or []
        r.check(len(legs) >= 1, f"{p}: has legs")
        prev_end = None
        transit_legs = 0
        for j, leg in enumerate(legs):
            q = f"{p} leg {j}"
            ls, le = parse_time(leg["startTime"]), parse_time(leg["endTime"])
            r.check(ls <= le, f"{q}: start <= end")
            if prev_end is not None:
                r.check(ls >= prev_end - dt.timedelta(seconds=60), f"{q}: does not start before previous leg ends")
            prev_end = le
            r.check(bool((leg.get("legGeometry") or {}).get("points")), f"{q}: has geometry")
            if leg["mode"] in TRANSIT_MODES:
                transit_legs += 1
                line = leg.get("routeShortName") or leg.get("displayName")
                r.check(bool(line), f"{q}: transit leg has a line name")
                r.check(bool(leg.get("tripId")), f"{q}: transit leg has a tripId")
                r.check(bool(leg.get("headsign") or leg.get("tripTo")), f"{q}: transit leg has a direction")
                r.check(bool(leg["from"].get("stopId")) and bool(leg["to"].get("stopId")),
                        f"{q}: transit leg has stop IDs")
                r.check(isinstance(leg.get("realTime"), bool), f"{q}: realTime flag present")
        if transit_legs:
            with_transit += 1
            shortest = dur if shortest is None else min(shortest, dur)
        lines = [l.get("routeShortName") or l["mode"] for l in legs]
        print(f"    {it['startTime']} -> {it['endTime']} ({dur / 60:.0f} min, {it.get('transfers')} transfers): "
              + " | ".join(lines))
    r.check(with_transit >= 1, f"{label}: at least one itinerary uses public transport")
    if require_short is not None and shortest is not None:
        r.check(shortest <= require_short, f"{label}: fastest transit itinerary {shortest / 60:.0f} min "
                f"<= {require_short / 60:.0f} min")
    r.check(bool(data.get("nextPageCursor")), f"{label}: nextPageCursor present")


def check_stoptimes(r, data, now, label, min_count=1):
    sts = data.get("stopTimes") or []
    r.check(len(sts) >= min_count, f"{label}: at least {min_count} departures ({len(sts)})")
    prev = None
    for i, st in enumerate(sts):
        q = f"{label} stopTime {i}"
        place = st.get("place") or {}
        dep = place.get("departure") or place.get("arrival")
        r.check(bool(dep), f"{q}: has departure time")
        if not dep:
            continue
        t = parse_time(dep)
        r.check(now - dt.timedelta(hours=1) <= t <= now + dt.timedelta(hours=24), f"{q}: time {dep} plausible")
        # MOTIS sorts by the actual (realtime) time.
        if prev is not None:
            r.check(t >= prev - dt.timedelta(seconds=1), f"{q}: sorted by time")
        prev = t
        r.check(bool(st.get("routeShortName") or st.get("displayName")), f"{q}: has a line name")
        r.check(bool(st.get("headsign")), f"{q}: has a headsign")
        r.check(st.get("mode") in TRANSIT_MODES, f"{q}: transit mode ({st.get('mode')})")
        r.check(isinstance(st.get("realTime"), bool), f"{q}: realTime flag present")
    for st in sts[:8]:
        p = st["place"]
        print(f"    {p.get('departure')}  {st.get('routeShortName') or st.get('displayName'):>6}  -> {st.get('headsign')}"
              f"{'  (realtime)' if st.get('realTime') else ''}")


def realtime_stats(stop_times):
    rt = [st for st in stop_times if st.get("realTime")]
    delays = []
    for st in rt:
        p = st["place"]
        if p.get("departure") and p.get("scheduledDeparture"):
            delays.append((parse_time(p["departure"]) - parse_time(p["scheduledDeparture"])).total_seconds() / 60)
    return rt, delays


def smoke(args):
    r = Result()
    now = dt.datetime.now(dt.timezone.utc)
    base = args.base

    print("health")
    status, data, s, _ = get(base, "/api/v1/health", [])
    r.check(status == 200, f"/api/v1/health returns 200 ({status})")

    print("plan Berlin Hbf -> Alexanderplatz")
    status, data, s, url = get(base, "/api/v6/plan", [("fromPlace", HBF), ("toPlace", ALEX), ("time", iso(now))])
    print(f"  {url}  ({s:.2f} s)")
    if r.check(status == 200, f"plan returns 200 ({status}: {str(data)[:200] if status != 200 else ''})"):
        check_plan(r, data, now, "plan", require_short=30 * 60)

    print(f"stoptimes {ALEX_STOP}")
    status, data, s, url = get(base, "/api/v6/stoptimes", [("stopId", ALEX_STOP), ("n", "20"), ("time", iso(now))])
    print(f"  {url}  ({s:.2f} s)")
    if r.check(status == 200, f"stoptimes returns 200 ({status}: {str(data)[:200] if status != 200 else ''})"):
        r.check("Alexanderplatz" in (data.get("place") or {}).get("name", ""), "stoptimes: place is Alexanderplatz")
        check_stoptimes(r, data, now, "stoptimes", min_count=10)

    if args.expect_realtime:
        print("realtime (departures around Berlin Hbf and Alexanderplatz)")
        all_sts = []
        status, matches, _, _ = get(base, "/api/v1/reverse-geocode", [("place", HBF), ("type", "STOP")])
        stop_ids = [m["id"] for m in matches if isinstance(m, dict) and m.get("id")][:6] if status == 200 else []
        for stop_id in stop_ids + [ALEX_STOP]:
            status, data, _, _ = get(base, "/api/v6/stoptimes", [("stopId", stop_id), ("n", "100"), ("time", iso(now))])
            if status == 200:
                all_sts += data.get("stopTimes") or []
        rt, delays = realtime_stats(all_sts)
        modes = {}
        for st in all_sts:
            m = modes.setdefault(st.get("mode"), [0, 0])
            m[0] += 1
            m[1] += 1 if st.get("realTime") else 0
        print("  realtime share by mode: " + ", ".join(f"{k} {v[1]}/{v[0]}" for k, v in sorted(modes.items())))
        print(f"  {len(rt)} of {len(all_sts)} departures with realtime data, "
              f"{sum(1 for d in delays if d >= 1)} delayed by >= 1 min; "
              f"delays (min): {sorted(round(d) for d in delays)[-10:]}")
        r.check(len(rt) >= 1, "realtime: at least one departure with realTime=true")
        r.check(len(delays) > 0, "realtime: delays computable (departure - scheduledDeparture)")
        status, data, _, _ = get(base, "/api/v6/plan", [("fromPlace", HBF), ("toPlace", POTSDAM_HBF_STOP),
                                                        ("transitModes", "REGIONAL_RAIL,REGIONAL_FAST_RAIL,SUBURBAN"),
                                                        ("time", iso(now))])
        if status == 200:
            legs = [l for it in data.get("itineraries", []) for l in it["legs"] if l["mode"] in TRANSIT_MODES]
            rt_legs = [l for l in legs if l.get("realTime")]
            print(f"  plan Hbf -> Potsdam (regional rail): {len(rt_legs)} of {len(legs)} transit legs realtime")
            for l in rt_legs[:5]:
                d = (parse_time(l["startTime"]) - parse_time(l["scheduledStartTime"])).total_seconds() / 60
                print(f"    {l.get('routeShortName') or l.get('displayName')} {l['scheduledStartTime']} delay {d:+.0f} min")

    return report(r)


# ---------------------------------------------------------------------------------------------- compat

class Spec:
    def __init__(self, path):
        import yaml  # PyYAML
        with open(path, encoding="utf-8") as f:
            self.doc = yaml.safe_load(f)

    def resolve(self, schema):
        while isinstance(schema, dict) and "$ref" in schema:
            node = self.doc
            for part in schema["$ref"].lstrip("#/").split("/"):
                node = node[part]
            schema = node
        return schema

    def operation(self, path):
        return self.doc["paths"][path]["get"]

    def response_schema(self, path):
        return self.operation(path)["responses"]["200"]["content"]["application/json"]["schema"]

    def validate(self, inst, schema, where, errors, warnings):
        schema = self.resolve(schema)
        if not isinstance(schema, dict):
            return
        for sub in schema.get("allOf", []):
            self.validate(inst, sub, where, errors, warnings)
        for key in ("oneOf", "anyOf"):
            if key in schema:
                if not any(self._valid(inst, sub) for sub in schema[key]):
                    errors.append(f"{where}: matches none of {key}")
        types = schema.get("type")
        types = types if isinstance(types, list) else [types] if types else []
        if inst is None:
            if types and "null" not in types and not schema.get("nullable"):
                errors.append(f"{where}: null not allowed")
            return
        if types and not any(self._type_ok(inst, t) for t in types):
            errors.append(f"{where}: expected {types}, got {type(inst).__name__}")
            return
        if "enum" in schema and inst not in schema["enum"]:
            # The app's client maps unknown enum values to UNKNOWN_DEFAULT_OPEN_API, so only warn.
            warnings.append(f"{where}: value {inst!r} not in spec enum")
        if isinstance(inst, str) and schema.get("format") == "date-time":
            try:
                parse_time(inst)
            except ValueError:
                errors.append(f"{where}: invalid date-time {inst!r}")
        if isinstance(inst, dict):
            for req in schema.get("required", []):
                if req not in inst:
                    errors.append(f"{where}: required field '{req}' missing")
            for name, sub in (schema.get("properties") or {}).items():
                if name in inst:
                    self.validate(inst[name], sub, f"{where}.{name}", errors, warnings)
        if isinstance(inst, list) and "items" in schema:
            for i, item in enumerate(inst):
                self.validate(item, schema["items"], f"{where}[{i}]", errors, warnings)

    def _valid(self, inst, schema):
        errors = []
        self.validate(inst, schema, "", errors, [])
        return not errors

    @staticmethod
    def _type_ok(inst, t):
        return {
            "object": isinstance(inst, dict),
            "array": isinstance(inst, list),
            "string": isinstance(inst, str),
            "integer": isinstance(inst, int) and not isinstance(inst, bool),
            "number": isinstance(inst, (int, float)) and not isinstance(inst, bool),
            "boolean": isinstance(inst, bool),
            "null": inst is None,
        }.get(t, True)

    def check_params(self, path, params, r, label):
        known = {p.get("name"): self.resolve(p) for p in self.operation(path).get("parameters", [])}
        for name, value in params:
            if not r.check(name in known, f"{label}: query parameter '{name}' exists in spec"):
                continue
            schema = self.resolve(known[name].get("schema", {}))
            enum = (self.resolve(schema.get("items", {})) if schema.get("type") == "array" else schema).get("enum")
            if enum:
                for v in value.split(","):
                    r.check(v in enum, f"{label}: value {v!r} of '{name}' allowed by spec")


def compat(args):
    r = Result()
    spec = Spec(args.spec)
    base = args.base
    now = dt.datetime.now(dt.timezone.utc)
    next_hour = (now + dt.timedelta(hours=1)).replace(minute=0, second=0, microsecond=0)

    def call(label, path, params, expect_results=None):
        print(label)
        spec.check_params(path, params, r, label)
        status, data, s, url = get(base, path, params)
        print(f"  {url}  ({s:.2f} s)")
        if not r.check(status == 200, f"{label}: HTTP 200 (got {status}: {str(data)[:300] if status != 200 else ''})"):
            return None
        errors, warnings = [], []
        spec.validate(data, spec.response_schema(path), "response", errors, warnings)
        for e in errors[:20]:
            print(f"    schema: {e}")
        r.check(not errors, f"{label}: response matches spec ({len(errors)} violations)")
        for w in sorted(set(warnings))[:5]:
            r.warn(f"{label}: {w}")
        if expect_results is not None:
            n = expect_results(data)
            r.check(n > 0, f"{label}: non-empty result ({n})")
        return data

    n_its = lambda d: len(d.get("itineraries") or [])
    default_plan = [("fromPlace", HBF), ("toPlace", ALEX), ("numItineraries", "5"),
                    ("useRoutedTransfers", "true"), ("detailedTransfers", "true")]

    # OnlineTransitServiceTest.planWithDefaultOptionsSendsOnlyRequiredAndDefaultParameters
    call("plan, default options", "/api/v6/plan", default_plan, n_its)

    # OnlineTransitServiceTest.planWithAllOptions (destination Cottbus, outside the Berlin OSM extract)
    all_options = [("fromPlace", HBF), ("toPlace", COTTBUS), ("numItineraries", "3"),
                   ("useRoutedTransfers", "false"), ("detailedTransfers", "false"), ("maxTransfers", "2"),
                   ("minTransferTime", "3"), ("additionalTransferTime", "2"), ("transferTimeFactor", "1.5"),
                   ("pedestrianProfile", "WHEELCHAIR"), ("pedestrianSpeed", "1.0"),
                   ("requireBikeTransport", "true"), ("transitModes", "TRAM,BUS,REGIONAL_RAIL"),
                   ("preTransitModes", "WALK,BIKE"), ("postTransitModes", "WALK"), ("maxPreTransitTime", "1200"),
                   ("maxPostTransitTime", "900"), ("searchWindow", "3600"), ("realtimeMode", "OFF")]
    d = call("plan, all options (to Cottbus)", "/api/v6/plan", all_options)
    if d is not None and not n_its(d):
        r.warn("plan to Cottbus with all options: no itinerary (restrictive options, or outside the OSM extract)")
    # Same options inside Berlin, where results are expected.
    inside = [(k, ALEX if k == "toPlace" else v) for k, v in all_options]
    call("plan, all options (to Alexanderplatz)", "/api/v6/plan", inside, n_its)

    # OnlineTransitServiceTest.planWithStopIdTimeArriveByAndCursor
    arrive = [("fromPlace", ALEX_STOP), ("toPlace", POTSDAM), ("time", iso(next_hour)), ("arriveBy", "true"),
              ("numItineraries", "5"), ("useRoutedTransfers", "true"), ("detailedTransfers", "true")]
    d = call("plan, stop ID + arriveBy + time (to Potsdam)", "/api/v6/plan", arrive)
    if d is not None and not n_its(d):
        r.warn("plan to Potsdam: no itinerary (destination outside the OSM extract?)")
    # Paging as in OnlineTransitServiceTest.pagingRepeatsTheRequestWithCursor
    if d is not None:
        for cursor_field in ("nextPageCursor", "previousPageCursor"):
            cursor = d.get(cursor_field)
            if r.check(bool(cursor), f"plan arriveBy: {cursor_field} present"):
                call(f"plan, page with {cursor_field}", "/api/v6/plan", arrive + [("pageCursor", cursor)])
    d = call("plan, stop ID + arriveBy + time (to Alexanderplatz)", "/api/v6/plan",
             [("fromPlace", "de-VBB_de:11000:900003201"), ("toPlace", ALEX), ("time", iso(next_hour)),
              ("arriveBy", "true"), ("numItineraries", "5"), ("useRoutedTransfers", "true"),
              ("detailedTransfers", "true")], n_its)
    if d is not None:
        for it in d.get("itineraries", []):
            r.check(parse_time(it["endTime"]) <= next_hour + dt.timedelta(minutes=1),
                    f"arriveBy: arrival {it['endTime']} not after {iso(next_hour)}")

    # OnlineTransitServiceTest.realtimeModes
    for mode in ("REALTIME", "REALTIME_ANNOTATION_ONLY", "OFF"):
        call(f"plan, realtimeMode={mode}", "/api/v6/plan", default_plan + [("realtimeMode", mode)], n_its)

    # OnlineTransitServiceTest.reverseGeocodeStops
    matches = call("reverse-geocode, type=STOP", "/api/v1/reverse-geocode", [("place", ALEX), ("type", "STOP")],
                   lambda d: len(d) if isinstance(d, list) else 0)
    if matches:
        r.check(all(m.get("type") == "STOP" for m in matches), "reverse-geocode: only stops")
        r.check(any(m.get("id", "").startswith("de-VBB_") for m in matches), "reverse-geocode: stop IDs with de-VBB_")

    # OnlineTransitServiceTest.stopTimes / closestStopWithTimes
    st = call("stoptimes, n=20", "/api/v6/stoptimes", [("stopId", ALEX_STOP), ("n", "20")],
              lambda d: len(d.get("stopTimes") or []))
    if st is not None:
        r.check(st.get("place", {}).get("stopId") == ALEX_STOP or "Alexanderplatz" in st["place"].get("name", ""),
                "stoptimes: place matches the requested stop")
    if matches:
        # The app takes the first match. Depending on the dataset that can be a platform without departures
        # (e.g. a VBB tram platform that only DELFI serves on Transitous), which is a valid, empty answer.
        d = call("closestStopWithTimes: stoptimes of nearest stop, n=3", "/api/v6/stoptimes",
                 [("stopId", matches[0]["id"]), ("n", "3")])
        if d is not None and not d.get("stopTimes"):
            r.warn(f"closestStopWithTimes: nearest stop {matches[0]['id']} has no departures in this dataset")
            served = [m for m in matches[1:6] if m.get("id")]
            counts = []
            for m in served:
                status, data, _, _ = get(base, "/api/v6/stoptimes", [("stopId", m["id"]), ("n", "3")])
                counts.append(len(data.get("stopTimes") or []) if status == 200 else 0)
            r.check(any(counts), f"closestStopWithTimes: one of the next stops has departures ({counts})")

    return report(r)


def report(r):
    print(f"\n{r.passed} checks passed, {len(r.failures)} failed, {len(r.warnings)} warnings")
    for f in r.failures:
        print(f"FAILED: {f}")
    return 0 if not r.failures else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["smoke", "compat"])
    ap.add_argument("--base", default="http://127.0.0.1:18090", help="server root, without /api")
    ap.add_argument("--spec", help="OpenAPI spec of the app's client (compat)")
    ap.add_argument("--expect-realtime", action="store_true", help="smoke: require realtime data")
    args = ap.parse_args()
    args.base = args.base.rstrip("/")
    if args.command == "compat" and not args.spec:
        ap.error("compat needs --spec")
    sys.exit(smoke(args) if args.command == "smoke" else compat(args))


if __name__ == "__main__":
    main()
