#!/usr/bin/env python3
"""Compares search results of the Berlin evaluation set (see queries.tsv and run_eval.sh).

Usage: evaluate.py queries.tsv before.tsv [after.tsv] > comparison.md

For every query the top 5 results are judged against the expectation from queries.tsv:
- category, filter, typo_category: the expectation is a regex for the result type.
- cuisine, cuisine_filter: the expectation is a regex for all types of the result, e.g. "cuisine-indian".
  For these kinds and the ones above reported are
  precision@5 (relevant types in the top 5), the mean distance of the top 5 to the user position and
  the opening state (open/closed/unknown) of the top 5 at the evaluation moment.
- place, address, street, typo, collision: the expectation is a regex for "name | address | type".
  Reported is the position of the first matching result (1..5, "-" if none).
"""

import csv
import re
import sys
from collections import defaultdict

TYPE_KINDS = {"category", "filter", "typo_category", "cuisine", "cuisine_filter"}
# Kinds judged against all types of a result (the "types" column), not only the main type.
ALL_TYPES_KINDS = {"cuisine", "cuisine_filter"}
TOP = 5


def read_queries(path):
    queries = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            qid, kind, query, expectation = line.rstrip("\n").split("\t")
            queries.append({"id": qid, "kind": kind, "query": query, "re": re.compile(expectation)})
    return queries


def read_results(path):
    results = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            results[row["query"]].append(row)
    return results


def judge(query, rows):
    rows = rows[:TOP]
    if query["kind"] in TYPE_KINDS:
        column = "types" if query["kind"] in ALL_TYPES_KINDS else "type"

        def matches(r):
            return query["re"].search(r.get(column) or r["type"])

        relevant = sum(1 for r in rows if matches(r))
        distances = [int(r["distance_m"]) for r in rows if r["distance_m"]]
        states = defaultdict(int)
        for r in rows:
            if matches(r):
                states[r["open"] or "unknown"] += 1
        return {
            "precision": relevant / TOP,
            "distance": sum(distances) / len(distances) if distances else None,
            "open": states["open"],
            "closed": states["closed"],
            "unknown": states["unknown"],
        }
    for i, r in enumerate(rows):
        if query["re"].search(f'{r["name"]} | {r["address"]} | {r["type"]}'):
            return {"rank": i + 1}
    return {"rank": None}


def fmt_judgement(query, j):
    if query["kind"] in TYPE_KINDS:
        dist = "-" if j["distance"] is None else f'{j["distance"]:.0f} m'
        return f'P@5 {j["precision"]:.1f}, Ø {dist}, open/closed/unknown {j["open"]}/{j["closed"]}/{j["unknown"]}'
    return f'Pos. {j["rank"] or "-"}'


def fmt_top(rows):
    items = []
    for r in rows[:TOP]:
        name = r["name"] or "(ohne Namen)"
        details = [r["type"]]
        cuisines = [t for t in (r.get("types") or "").split() if t.startswith("cuisine-")]
        if cuisines:
            details.append(" ".join(cuisines))
        if r["distance_m"]:
            details.append(f'{r["distance_m"]} m')
        if r["open"] and r["open"] != "unknown":
            details.append(r["open"])
        items.append(f'{name} ({", ".join(details)})'.replace("|", "\\|"))
    return "<br>".join(items) if items else "(keine)"


def summary(queries, judgements):
    by_kind = defaultdict(list)
    for q, j in zip(queries, judgements):
        by_kind[q["kind"]].append(j)
    lines = {}
    for kind, js in by_kind.items():
        if kind in TYPE_KINDS:
            precision = sum(j["precision"] for j in js) / len(js)
            dists = [j["distance"] for j in js if j["distance"] is not None]
            dist = sum(dists) / len(dists) if dists else 0
            lines[kind] = (
                f'P@5 {precision:.2f}, Ø Entfernung {dist:.0f} m, '
                f'geöffnet/geschlossen/unbekannt {sum(j["open"] for j in js)}/'
                f'{sum(j["closed"] for j in js)}/{sum(j["unknown"] for j in js)}'
            )
        else:
            top1 = sum(1 for j in js if j["rank"] == 1)
            top5 = sum(1 for j in js if j["rank"])
            mrr = sum(1 / j["rank"] for j in js if j["rank"]) / len(js)
            lines[kind] = f"Top-1 {top1}/{len(js)}, Top-5 {top5}/{len(js)}, MRR {mrr:.2f}"
    return lines


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    queries = read_queries(sys.argv[1])
    runs = [read_results(p) for p in sys.argv[2:]]
    names = ["Vorher", "Nachher"][: len(runs)]

    judgements = [[judge(q, run.get(q["query"], [])) for q in queries] for run in runs]

    print("## Zusammenfassung\n")
    print("| Art | " + " | ".join(names) + " |")
    print("|---|" + "---|" * len(names))
    summaries = [summary(queries, js) for js in judgements]
    for kind in summaries[0]:
        print(f"| {kind} | " + " | ".join(s[kind] for s in summaries) + " |")

    print("\n## Anfragen\n")
    print("| ID | Anfrage | Erwartung | " + " | ".join(f"{n}: Bewertung | {n}: Top 5" for n in names) + " |")
    print("|---|---|---|" + "---|---|" * len(names))
    for i, q in enumerate(queries):
        cells = [q["id"], q["query"], f'`{q["re"].pattern}`'.replace("|", "\\|")]
        for run, js in zip(runs, judgements):
            cells.append(fmt_judgement(q, js[i]))
            cells.append(fmt_top(run.get(q["query"], [])))
        print("| " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
