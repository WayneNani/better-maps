# Bahnhöfe in anderen Städten: Vorher/Nachher

Anlass: „Nürnberg Hbf“, auf dem Handy in Bonn gesucht (ganz Deutschland heruntergeladen), zeigte U-Bahn-Eingänge
„Hauptbahnhof“ statt des Hauptbahnhofs, und die Suche dauerte „relativ lange“.

- **Vorher:** Branch `bm/search-stations` bei `ed01f1b09` (Stand `main` plus Werkzeug-Optionen), **Nachher:** Ende des
  Branches. Beide `search_quality_tool` mit derselben Werkzeug-Version (Hervorhebung wie in der App, Spalte
  `highlight`), Release-Build, ein Such-Thread.
- Bahnhofs-Set: `stations.tsv`, ausgeführt mit `run_stations.sh` (Karten Berlin, Mittelfranken, World, WorldCoasts;
  mit `all` alle 48 deutschen Karten, Version 261018). Jede Anfrage trägt den Ort, von dem aus gesucht wird
  (Position und Viewport `berlin`, `nuremberg`, `bonn`). Ergebnisse: `stations_before.tsv`, `stations_after.tsv`.
- Berlin-Set: `queries.tsv` wie bisher mit `run_eval.sh --detect_query_filters`, Nachher in
  `results_after_stations.tsv` (Vorher ist identisch mit `results_after.tsv`).
- Bewertung: `evaluate.py stations.tsv stations_before.tsv stations_after.tsv` bzw. `evaluate.py queries.tsv ...`.

## Ursachen

1. **Bewertung der Deutung [Stadt POI]:** „Nürnberg Hbf“ wird als Stadt „Nürnberg“ + POI „Hbf“ gedeutet. Nur „Hbf“
   wurde gegen den POI-Namen bewertet: Eingänge, Bushaltestellen, Taxistände namens „Hauptbahnhof“ waren volle
   Treffer, „Nürnberg Hauptbahnhof“ nur ein Teiltreffer (bzw. reiner Kategorie-Treffer, „Hbf“ ist ein Synonym für
   Bahnhof). Die Entfernung wird in dieser Deutung von der Stadtmitte gemessen.
2. **Deutung [POI POI]:** Als „beide Wörter im Namen“ war der Bahnhof ein voller Treffer, aber die Entfernung wurde vom
   Nutzer gemessen (379 km, −0,18 im linearen Modell gegenüber ~0 in der Stadt-Deutung). Der PreRanker behält von
   mehreren Deutungen desselben Objekts die mit den meisten Wörtern im POI, also diese. Ergebnis: Platz 57.
3. **Dubletten:** Bahnhof, Bahnhofsgebäude, U-Bahnhof, Bahnsteige und Eingänge sind eigene Objekte mit (fast) gleichem
   Namen; nur Bus-/Tram-Haltestellen wurden bisher zusammengefasst.
4. **Hamburg:** Die Karte mit der gefundenen Stadt wird über die Mitte des Stadtgrenzen-Rechtecks bestimmt. Hamburgs
   Grenze enthält die Insel Neuwerk, die Mitte liegt in Niedersachsen: die Hamburg-Karte wurde für „Hamburg Hbf“ nicht
   zuerst und bei genug anderen Treffern gar nicht durchsucht (Treffer: Bushaltestellen in Bremerhaven).
5. **Online-Ergänzung (App):** Sie startet, wenn weniger als 3 Offline-Treffer zu ≥ 75 % hervorgehoben sind. Synonyme
   wurden nicht hervorgehoben („Hauptbahnhof“ für „Hbf“), bei englischer Oberfläche auch die Stadt in der Adresse nicht
   („Nuremberg“). Vorher war für „Nürnberg Hbf“ kein Treffer voll → Online-Suche mit Lade-Zeile (600 ms Entprellung +
   Photon), was die Suche lang wirken ließ. Siehe „Dauer“.

## Zusammenfassung

Bahnhofs-Set (Karten Berlin + Mittelfranken):

| Art | Vorher | Nachher |
|---|---|---|
| station | Top-1 3/20, Top-5 6/20, MRR 0.21 | Top-1 20/20, Top-5 20/20, MRR 1.00 |
| counter | Top-1 6/9, Top-5 8/9, MRR 0.73 | Top-1 6/9, Top-5 8/9, MRR 0.75 |

Bahnhofs-Set mit allen 48 deutschen Karten (`run_stations.sh ... all`):

| Art | Vorher | Nachher |
|---|---|---|
| station | Top-1 3/20, Top-5 7/20, MRR 0.23 | Top-1 20/20, Top-5 20/20, MRR 1.00 |
| counter | Top-1 5/9, Top-5 8/9, MRR 0.66 | Top-1 5/9, Top-5 8/9, MRR 0.67 |

Berlin-Set (`queries.tsv`): keine Verschlechterung; Kategorien und Filter unverändert.

| Art | Vorher | Nachher |
|---|---|---|
| place | Top-1 7/8, Top-5 8/8, MRR 0.94 | Top-1 8/8, Top-5 8/8, MRR 1.00 |
| street | Top-1 1/3, Top-5 3/3, MRR 0.56 | Top-1 1/3, Top-5 3/3, MRR 0.61 |
| typo | Top-1 2/3, Top-5 3/3, MRR 0.83 | Top-1 3/3, Top-5 3/3, MRR 1.00 |
| category, filter, address, typo_category, collision | unverändert | unverändert |

Geänderte Anfragen des Berlin-Sets (Rest identisch):
- p01/t01 „Alexanderplatz“/„Alexanderplatt“: vorher Bahnhofsgebäude, S-Bahnhof, U-Bahnhof auf 1–3, nachher ein Treffer
  „Alexanderplatz“ (S-Bahnhof), danach Kino, Polizei, Hotel.
- p02 „Hauptbahnhof“: vorher U-Bahnhof „Hauptbahnhof“, „Berlin Hauptbahnhof (tief)“, „Berlin Hauptbahnhof“, Gebäude,
  S-Bahnhof; nachher „Berlin Hauptbahnhof“ (ein Treffer), dann der Parkeisenbahn-Bahnhof „Hauptbahnhof“, Apotheke, Hotels.
- p03 „Brandenburger Tor“, p06 „Museumsinsel“, p08 „Rotes Rathaus“: U-/S-Bahnhof und Bahnsteige zusammengefasst, das
  Denkmal bzw. die Sehenswürdigkeit rückt auf.
- p07 „Potsdamer Platz“: Pos. 2 → 1 („Potsdamer Platz“, S-Bahnhof, statt „U Potsdamer Platz“ und S-Bahnhof).
- s02 „Sonnenallee“: das Gebäude „S-Bahnhof Sonnenallee“ gehört zum S-Bahnhof, die Straße rückt von 3 auf 2.

## Dauer

Suchzeit pro Anfrage, `search_quality_tool`, Release, ein Thread, alle 48 deutschen Karten, Median aus 3 Läufen,
vorher/nachher abwechselnd (Rechner ruhig, Last < 1). Die erste Anfrage jedes Laufs enthält das Öffnen der Karten.

| Anfrage | Berlin vorher | Berlin nachher | Nürnberg vorher | Nürnberg nachher | Bonn vorher | Bonn nachher |
|---|---|---|---|---|---|---|
| Nürnberg Hbf | 0.70 s | 0.81 s | 1.13 s | 1.38 s | 0.87 s | 0.84 s |
| Nürnberg Hauptbahnhof | 1.63 s | 1.64 s | 1.32 s | 1.40 s | 1.62 s | 1.47 s |
| Hbf Nürnberg | 0.52 s | 0.59 s | 0.94 s | 1.03 s | 0.62 s | 0.68 s |
| Fürth Hbf | 1.51 s | 1.36 s | 1.36 s | 1.42 s | 1.27 s | 1.25 s |
| Erlangen Bahnhof | 1.87 s | 1.63 s | 1.54 s | 1.37 s | 1.79 s | 1.80 s |
| Berlin Hbf | 1.18 s | 1.34 s | 1.07 s | 1.32 s | 1.04 s | 1.31 s |
| Köln Hbf | 0.61 s | 0.59 s | 0.58 s | 0.63 s | 0.65 s | 0.69 s |
| Hamburg Hbf | 0.65 s | 0.82 s | 1.51 s | 0.90 s | 1.24 s | 0.85 s |
| München Hbf | 0.68 s | 0.76 s | 0.82 s | 0.84 s | 0.77 s | 0.79 s |
| Hauptbahnhof | 0.29 s | 0.30 s | 0.45 s | 0.45 s | 0.50 s | 0.44 s |
| Bahnhofstraße | 0.94 s | 0.94 s | 0.97 s | 1.00 s | 1.08 s | 0.94 s |
| Hotel am Hauptbahnhof | 1.78 s | 1.75 s | 2.24 s | 2.24 s | 2.21 s | 2.20 s |
| Summe | 12.35 s | 12.54 s | 13.92 s | 13.98 s | 13.64 s | 13.24 s |

Die Änderungen kosten insgesamt nichts Messbares (±2 %); einzelne Anfragen schwanken um ±0,2 s.

**Viele Karten:** Dieselben Anfragen (nachher, aus Berlin) mit 4 Karten (Berlin, Mittelfranken, World, WorldCoasts)
zusammen 5,2 s, mit allen 48 deutschen Karten 12,6 s (Median aus 2 Läufen), z. B. „Fürth Hbf“ 0,30 → 1,36 s,
„Erlangen Bahnhof“ 0,51 → 1,73 s, „Köln Hbf“ 0,11 → 0,63 s. Ursache (gemessen mit Zeitstempeln pro Karte im Geocoder):
Der Geocoder durchsucht zuerst alle Karten mit einer gefundenen Stadt. Ortsnamen werden mit Tippfehlern gesucht, so
dass „Nürnberg“ auch Nürburg u. ä. findet und „Fürth“ mehrere Fürth: für „Nürnberg Hbf“ aus Bonn sind das 7 Karten
(Köln, Koblenz, Aachen, Arnsberg, Düsseldorf, Darmstadt, Mittelfranken) mit 0,03–0,2 s je Karte, Mittelfranken selbst
0,6–0,75 s (alle Bahnhöfe der Stadt über die Kategorie „Hbf“ plus Namen). Allgemeine Anfragen ohne Stadt („Hotel am
Hauptbahnhof“) gehen alle 48 Karten durch, weil nie genug Treffer zusammenkommen. Auf dem Handy (langsamere Kerne,
kalte Dateien) ist mit dem 2- bis 4-Fachen zu rechnen.

**Online-Ergänzung:** Nachbildung der App-Regel auf den 30 Offline-Treffern (alle deutschen Karten; Adressen im Werkzeug
englisch, wie bei englischer App-Sprache):

| Anfrage | vorher: volle Treffer, Online? | nachher: volle Treffer, erster voll?, Online? |
|---|---|---|
| Bonn: Nürnberg Hbf | 0, **online** | 9, ja, offline |
| Berlin: Nürnberg Hbf | 0, **online** | 9, ja, offline |
| Nürnberg: Nürnberg Hbf | 2, **online** | 11, ja, offline |
| Bonn/Berlin: Fürth Hbf | 0/2, **online** | 22/15, ja, offline |
| Berlin: Hbf Nürnberg | 0, **online** | 10, ja, offline |
| Berlin: Nürnberg Frankenstadion | 3, offline | 1, ja, offline (alte Regel: online) |
| Berlin: Nürnberg Bahnhof | 1, online | 0, nein, online (Englisch; mit deutscher Adresse „Nürnberg“ voll) |

Vorher startete für alle „<Stadt> Hbf“-Fernsuchen die Online-Suche. Bei erteilter Zustimmung zeigt die App dann eine
Lade-Zeile, wartet 600 ms und fragt Photon an; das ist die wahrscheinlichste Ursache für „dauert relativ lange“
neben der Offline-Suchzeit über alle Karten. Nachher werden Synonyme hervorgehoben („**Hauptbahnhof**“ für „Hbf“), und
die App sucht nicht online, wenn der erste Offline-Treffer die Anfrage voll abdeckt.

**Emulator** (Debug-APK, x86_64, 2 Kerne, Karten Berlin + Mittelfranken, Position Berlin, Suche per
`cm://search?query=…`): „Nürnberg Hbf“, „Hbf Nürnberg“ → 1. „Nürnberg Hauptbahnhof“ (Train Station), „Hauptbahnhof“
hervorgehoben; „Fürth Hbf“ → „Fürth (Bayern) Hauptbahnhof“; „Erlangen Bahnhof“ → „Bahnhof Erlangen“ (Gebäude, nach
den Suchvorschlägen). Geocoding-Zeit laut Log 1,7–3,7 s; das ist ein unoptimierter Debug-Build, die Werte sind nicht
mit dem Handy (Release) vergleichbar. Vorher-Messung im Emulator gibt es nicht (nur die neue APK installiert).

## Offen

- Stadtgrenzen werden als Rechteck verwendet: Bremerhavener Haltestellen „Hauptbahnhof“ gelten als „in Hamburg“
  (Neuwerk), Fürther Haltestellen als „in Nürnberg“. Seit der Änderung gewinnt der echte Bahnhof, das Rechteck bleibt.
- „Hbf“ ist (upstream) ein Kategorie-Synonym für alle Bahnhöfe. „Hbf“ allein findet daher die nächsten Bahnhöfe, nicht
  den Hauptbahnhof (wie früher „Hauptbahnhof“, das AP2a als Synonym entfernt hat). Es bleibt, weil es „Erlangen Hbf“ den
  Bahnhof „Erlangen“ finden lässt.
- Geocoder-Laufzeit mit vielen Karten: Tippfehler-Treffer bei Städten ziehen weitere Karten in die erste Runde; Anfragen
  ohne Stadt durchsuchen alle Karten. Beides ist Upstream-Verhalten und nicht geändert.
- Zusammenfassung über Batches hinweg: Teile eines Bahnhofs, die erst in einem späteren Batch (andere Karte) ankommen,
  werden nicht mehr mit einem schon ausgegebenen Bahnhof zusammengefasst (nur an Kartengrenzen denkbar).

## Anfragen (Karten Berlin + Mittelfranken)

| ID | Anfrage | Erwartung | Vorher: Bewertung | Vorher: Top 5 | Nachher: Bewertung | Nachher: Top 5 |
|---|---|---|---|---|---|---|
| s01 | berlin: Nürnberg Hbf | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof (highway-bus_stop, 379196 m)<br>Hauptbahnhof (railway-subway_entrance, 378740 m)<br>Hauptbahnhof (railway-subway_entrance, 378871 m)<br>Hauptbahnhof (railway-subway_entrance, 378832 m)<br>City Apartment am Hauptbahnhof (tourism-apartment, 379406 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 378962 m)<br>Nürnberg-Steinbühl (railway-station, 379632 m)<br>Nürnberg-Dürrenhof (railway-station, 378217 m)<br>Nürnberg Nordost (railway-station, 375333 m)<br>Nürnberg-Schweinau (railway-station, 381143 m) |
| s02 | berlin: Nürnberg Hauptbahnhof | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof Fürth (building-train_station, 379276 m, open)<br>Fürth Hauptbahnhof (railway-station-subway, 379217 m)<br>Fürth (Bayern) Hauptbahnhof (railway-station, 379315 m)<br>Hauptbahnhof Westausgang - mobilpunkt (amenity-car_sharing, 378961 m, open)<br>Hauptbahnhof (highway-bus_stop, 379196 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 378962 m)<br>Fürth (Bayern) Hauptbahnhof (railway-station, 379315 m)<br>Hauptbahnhof Westausgang - mobilpunkt (amenity-car_sharing, 378961 m, open)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 378763 m)<br>Nürnberg Hauptbahnhof (railway-tram_stop, 378715 m) |
| s03 | berlin: Nürnberg Bahnhof | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Bahnhof (amenity-car_sharing, 379156 m, open)<br>Bahnhof (building, 376940 m)<br>Bahnhofstraße (highway-secondary, 378727 m)<br>Bahnhofstraße (highway-secondary, 383546 m)<br>Bahnhofplatz (highway-secondary, 379238 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 378962 m)<br>Nürnberg-Steinbühl (railway-station, 379632 m)<br>Nürnberg-Dürrenhof (railway-station, 378217 m)<br>Nürnberg Nordost (railway-station, 375333 m)<br>Nürnberg-Schweinau (railway-station, 381143 m) |
| s04 | berlin: Hbf Nürnberg | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof (highway-bus_stop, 379196 m)<br>Hauptbahnhof (railway-subway_entrance, 378704 m)<br>Hauptbahnhof (railway-subway_entrance, 378730 m)<br>Hauptbahnhof (railway-subway_entrance, 378789 m)<br>Hauptbahnhof (railway-subway_entrance, 378832 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 378962 m)<br>Nürnberg-Steinbühl (railway-station, 379632 m)<br>Nürnberg-Dürrenhof (railway-station, 378217 m)<br>Nürnberg Nordost (railway-station, 375333 m)<br>Nürnberg-Schweinau (railway-station, 381143 m) |
| s05 | berlin: Fürth Hbf | `^Fürth (\(Bayern\) )?Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof (highway-bus_stop, 379232 m)<br>(ohne Namen) (building-train_station, 380831 m)<br>Alte Veste (railway-halt, 381415 m)<br>Zirndorf (railway-station, 382895 m)<br>Bahnhofstraße, 25 (building-train_station, 382925 m) | Pos. 1 | Fürth (Bayern) Hauptbahnhof (railway-station, 379315 m)<br>Fürth-Burgfarrnbach (railway-station, 379290 m)<br>Fürth Hauptbahnhof (amenity-taxi, 379248 m)<br>Fürth Hauptbahnhof (amenity-bench, 379212 m)<br>Fürth Hauptbahnhof (U1) (railway-subway_entrance, 379215 m) |
| s06 | berlin: Fürth Hauptbahnhof | `^Fürth (\(Bayern\) )?Hauptbahnhof \\|.*\\| railway-station` | Pos. 2 | Hauptbahnhof (highway-bus_stop, 379211 m)<br>Fürth Hauptbahnhof (railway-station-subway, 379217 m)<br>Fürth (Bayern) Hauptbahnhof (railway-station, 379315 m)<br>Fürth Hauptbahnhof (amenity-taxi, 379248 m)<br>Fürth Hauptbahnhof (railway-subway_entrance, 379196 m) | Pos. 1 | Fürth (Bayern) Hauptbahnhof (railway-station, 379315 m)<br>Hauptbahnhof (highway-bus_stop, 379211 m)<br>Fürth Hauptbahnhof (amenity-taxi, 379248 m)<br>Fürth Hauptbahnhof (amenity-bench, 379212 m)<br>Fürth Hauptbahnhof (U1) (railway-subway_entrance, 379215 m) |
| s07 | berlin: Erlangen Bahnhof | `^(Bahnhof )?Erlangen \\|.*\\| (railway-station\|building-train_station)` | Pos. - | Bahnhofplatz (highway-residential, 366264 m)<br>Am Bahnhof (highway-secondary, 362657 m)<br>Am Brucker Bahnhof (highway-residential, 368761 m)<br>Bruck Bahnhof (highway-bus_stop, 368937 m)<br>Frauenaurach/Bahnhof (highway-bus_stop, 370795 m) | Pos. 1 | Erlangen (railway-station, 366305 m)<br>Bahnhofplatz (highway-residential, 366264 m)<br>Erlangen Bahnhof (Parkplatzstraße) (highway-bus_stop, 366296 m)<br>Erlangen Paul-Gossen-Straße (railway-halt, 368051 m)<br>Erlangen-Bruck (railway-halt, 369069 m) |
| s08 | berlin: Erlangen Hbf | `^(Bahnhof )?Erlangen \\|.*\\| (railway-station\|building-train_station)` | Pos. - | Am Brucker Bahnhof, 21 (building-train_station, 369038 m)<br>Bahnstraße, 8 (building-train_station, 370684 m)<br>Eltersdorf (railway-station, 370837 m)<br>Erlangen Hauptbahnhof B (highway-bus_stop, 366260 m)<br>Erlangen Hauptbahnhof C (highway-bus_stop, 366267 m) | Pos. 1 | Erlangen (railway-station, 366305 m)<br>Hauptbahnhof (amenity-car_sharing, 366390 m, open)<br>Erlangen Hauptbahnhof B (highway-bus_stop, 366260 m)<br>Erlangen Hauptbahnhof A (highway-bus_stop, 366272 m)<br>Erlangen Hauptbahnhof C (highway-bus_stop, 366267 m) |
| s09 | berlin: Nürnberg Frankenstadion | `^Nürnberg Frankenstadion \\|.*\\| railway-station` | Pos. 2 | Kiosk am Frankenstadion (shop-kiosk, 378840 m)<br>Nürnberg Frankenstadion (railway-station, 378997 m)<br>Nürnberg Frankenstadion (railway-platform, 378960 m)<br>Nürnberg-Frankenstadion (railway-platform, 378991 m)<br>Frankenstadion (highway-bus_stop, 379026 m) | Pos. 1 | Nürnberg Frankenstadion (railway-station, 378997 m)<br>Kiosk am Frankenstadion (shop-kiosk, 378840 m)<br>Frankenstadion (highway-bus_stop, 379026 m)<br>Frankenstadion (highway-bus_stop, 379160 m)<br>Nürnberg–Regensburg (railway-rail-main, 378932 m) |
| s10 | berlin: Fürth Rathaus | `^Fürth Rathaus \\|.*\\| railway-station` | Pos. - | Rathaus (highway-bus_stop, 378545 m)<br>Rathaus (amenity-shelter, 378544 m)<br>Rathaus (railway-subway_entrance, 378614 m)<br>Rathaus (railway-subway_entrance, 378554 m)<br>Rathausplatz (highway-residential, 383197 m) | Pos. 1 | Fürth Rathaus (railway-station-subway, 378594 m)<br>Rathaus Shop Fürth (shop-second_hand, 378574 m, closed)<br>Fürth Rathaus (amenity-bench, 378587 m)<br>Rathaus (highway-bus_stop, 378545 m)<br>Rathaus (amenity-shelter, 378544 m) |
| s11 | nuremberg: Berlin Hbf | `^Berlin Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof Washingtonplatz (amenity-taxi, 377796 m)<br>Hauptbahnhof Nachrückhalteplatz Washingtonplatz (amenity-taxi, 377762 m)<br>Hauptbahnhof (railway-subway_entrance-berlin, 377822 m)<br>Hauptbahnhof (railway-platform, 376616 m)<br>Berlin Hovedbanegård (railway-station-subway-berlin, 377914 m) | Pos. 1 | Berlin Hauptbahnhof (railway-station, 377867 m)<br>Berlin Friedrichstraße (railway-station, 377908 m)<br>Berlin Ostbahnhof (railway-station, 378383 m)<br>Berlin Gesundbrunnen (railway-station, 380848 m)<br>Berlin Zoo Bhf. (building-train_station, 375043 m) |
| s12 | nuremberg: Berlin Hauptbahnhof | `^Berlin Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof (railway-station-subway-berlin, 377914 m)<br>Hauptbahnhof (railway-station-light_rail-berlin, 377882 m)<br>Hauptbahnhof (railway-station, 376613 m)<br>Hauptbahnhof der Parkeisenbahn Wuhlheide (building-train_station, 376628 m)<br>Hauptbahnhof Nachrückhalteplatz Washingtonplatz (amenity-taxi, 377762 m) | Pos. 1 | Berlin Hauptbahnhof (railway-station, 377867 m)<br>Hauptbahnhof (railway-station, 376613 m)<br>Hauptbahnhof Nachrückhalteplatz Washingtonplatz (amenity-taxi, 377762 m)<br>DB Casino Hauptbahnhof Berlin (amenity-fast_food, 377900 m)<br>Ibis Berlin Hauptbahnhof (tourism-hotel, 378096 m) |
| s13 | nuremberg: Hbf Berlin | `^Berlin Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof Washingtonplatz (amenity-taxi, 377796 m)<br>Hauptbahnhof Nachrückhalteplatz Washingtonplatz (amenity-taxi, 377762 m)<br>Hauptbahnhof (railway-subway_entrance-berlin, 377822 m)<br>Hauptbahnhof (railway-platform, 377915 m)<br>Hauptbahnhof (railway-platform, 377880 m) | Pos. 1 | Berlin Hauptbahnhof (railway-station, 377867 m)<br>Berlin Friedrichstraße (railway-station, 377908 m)<br>Berlin Ostbahnhof (railway-station, 378383 m)<br>Berlin Gesundbrunnen (railway-station, 380848 m)<br>Berlin Zoo Bhf. (building-train_station, 375043 m) |
| s14 | nuremberg: Nürnberg Hbf | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. 1 | Nürnberg Hauptbahnhof (railway-station-subway, 691 m)<br>Nürnberg Hauptbahnhof (building-train_station, 756 m)<br>Nürnberg Hauptbahnhof (railway-station, 868 m)<br>ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 599 m)<br>B&B Hotel Nürnberg-Hbf (tourism-hotel, 762 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 868 m)<br>Nürnberg-Steinbühl (railway-station, 1208 m)<br>Nürnberg-Dürrenhof (railway-station, 1582 m)<br>Nürnberg Nordost (railway-station, 3234 m)<br>Nürnberg-Schweinau (railway-station, 2966 m) |
| s15 | nuremberg: Hauptbahnhof | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. 1 | Nürnberg Hauptbahnhof (railway-station-subway, 691 m)<br>Nürnberg Hauptbahnhof (building-train_station, 756 m)<br>Nürnberg Hauptbahnhof (railway-station, 868 m)<br>Hauptbahnhof Fürth (building-train_station, 6565 m, open)<br>ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 599 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 868 m)<br>Fürth (Bayern) Hauptbahnhof (railway-station, 6577 m)<br>ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 599 m)<br>City Apartment am Hauptbahnhof (tourism-apartment, 1160 m)<br>Hauptbahnhof Westausgang - mobilpunkt (amenity-car_sharing, 747 m, open) |
| s16 | nuremberg: Fürth Hbf | `^Fürth (\(Bayern\) )?Hauptbahnhof \\|.*\\| railway-station` | Pos. 1 | Fürth Hauptbahnhof (railway-station-subway, 6502 m)<br>Fürth (Bayern) Hauptbahnhof (railway-station, 6577 m)<br>Fürth Hauptbahnhof (amenity-taxi, 6554 m)<br>Fürth Hauptbahnhof (amenity-bench, 6497 m)<br>Fürth Hauptbahnhof (railway-subway_entrance, 6543 m) | Pos. 1 | Fürth (Bayern) Hauptbahnhof (railway-station, 6577 m)<br>Fürth-Burgfarrnbach (railway-station, 11819 m)<br>Fürth Hauptbahnhof (amenity-taxi, 6554 m)<br>Fürth Hauptbahnhof (amenity-bench, 6497 m)<br>Fürth Hauptbahnhof (U1) (railway-subway_entrance, 6558 m) |
| s17 | nuremberg: Erlangen Hbf | `^(Bahnhof )?Erlangen \\|.*\\| (railway-station\|building-train_station)` | Pos. 4 | Erlangen Hauptbahnhof C (highway-bus_stop, 16873 m)<br>Erlangen Hauptbahnhof A (highway-bus_stop, 16902 m)<br>Erlangen Hauptbahnhof B (highway-bus_stop, 16906 m)<br>Erlangen (railway-station, 16920 m)<br>VAG_Rad-Station Erlangen - Hauptbahnhof (amenity-bicycle_rental, 16902 m) | Pos. 1 | Erlangen (railway-station, 16920 m)<br>Hauptbahnhof (amenity-car_sharing, 16888 m, open)<br>Erlangen Hauptbahnhof B (highway-bus_stop, 16906 m)<br>Erlangen Hauptbahnhof A (highway-bus_stop, 16902 m)<br>Erlangen Hauptbahnhof C (highway-bus_stop, 16873 m) |
| s18 | bonn: Nürnberg Hbf | `^Nürnberg Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof Westausgang - mobilpunkt (amenity-car_sharing, 318608 m, open)<br>Hauptbahnhof (highway-bus_stop, 311648 m)<br>Hauptbahnhof (railway-subway_entrance, 318703 m)<br>Hauptbahnhof (railway-subway_entrance, 318701 m)<br>City Apartment am Hauptbahnhof (tourism-apartment, 318741 m) | Pos. 1 | Nürnberg Hauptbahnhof (railway-station, 318768 m)<br>Nürnberg-Steinbühl (railway-station, 317983 m)<br>Nürnberg-Dürrenhof (railway-station, 319597 m)<br>Nürnberg Nordost (railway-station, 318789 m)<br>Nürnberg-Schweinau (railway-station, 317022 m) |
| s19 | bonn: Fürth Hbf | `^Fürth (\(Bayern\) )?Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof (highway-bus_stop, 311549 m)<br>(ohne Namen) (building-train_station, 310812 m)<br>Alte Veste (railway-halt, 311296 m)<br>Zirndorf (railway-station, 310991 m)<br>Bahnhofstraße, 25 (building-train_station, 310951 m) | Pos. 1 | Fürth (Bayern) Hauptbahnhof (railway-station, 311588 m)<br>Fürth-Burgfarrnbach (railway-station, 306316 m)<br>Fürth Hauptbahnhof (amenity-taxi, 311600 m)<br>Fürth Hauptbahnhof (amenity-bench, 311650 m)<br>Fürth Hauptbahnhof (U1) (railway-subway_entrance, 311589 m) |
| s20 | bonn: Berlin Hbf | `^Berlin Hauptbahnhof \\|.*\\| railway-station` | Pos. - | Hauptbahnhof Nachrückhalteplatz Washingtonplatz (amenity-taxi, 476539 m)<br>Hauptbahnhof (railway-platform, 476671 m)<br>Hauptbahnhof (railway-subway_entrance-berlin, 476675 m)<br>Hauptbahnhof (railway-platform, 476619 m)<br>Hauptbahnhof (railway-subway_entrance-berlin, 476691 m) | Pos. 1 | Berlin Hauptbahnhof (railway-station, 476627 m)<br>Berlin Friedrichstraße (railway-station, 477514 m)<br>Berlin Ostbahnhof (railway-station, 480119 m)<br>Berlin Gesundbrunnen (railway-station, 478915 m)<br>Berlin Zoo Bhf. (building-train_station, 473579 m) |
| c01 | berlin: Bahnhofstraße | `^Bahnhofstraße \\|.*\\| highway-` | Pos. 1 | Bahnhofstraße (highway-secondary, 8275 m)<br>Neue Bahnhofstraße (highway-bus_stop, 4587 m)<br>Bahnhofstraße (highway-service, 6794 m)<br>Bahnhofstraße (highway-residential, 6896 m)<br>Bahnhofstraße (highway-residential, 7077 m) | Pos. 1 | Bahnhofstraße (highway-secondary, 8275 m)<br>Neue Bahnhofstraße (highway-bus_stop, 4587 m)<br>Bahnhofstraße (highway-service, 6794 m)<br>Bahnhofstraße (highway-residential, 6896 m)<br>Bahnhofstraße (highway-residential, 7077 m) |
| c02 | nuremberg: Bahnhofstraße | `^Bahnhofstraße \\|.*\\| highway-` | Pos. 1 | Bahnhofstraße (highway-secondary, 631 m)<br>Bahnhofstraße (highway-secondary, 5533 m)<br>Bahnhofstraße (highway-secondary, 8080 m)<br>Bahnhofstraße (highway-service, 7683 m)<br>Bahnhofstraße (highway-cycleway, 670 m) | Pos. 1 | Bahnhofstraße (highway-secondary, 631 m)<br>Bahnhofstraße (highway-secondary, 5533 m)<br>Bahnhofstraße (highway-secondary, 8080 m)<br>Bahnhofstraße (highway-service, 7683 m)<br>Bahnhofstraße (highway-cycleway, 670 m) |
| c03 | berlin: Hotel Nürnberg Hauptbahnhof | `^ibis Hotel Nürnberg Hauptbahnhof \\|` | Pos. 1 | ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 378600 m)<br>Le Méridien Grand Hotel Nürnberg (tourism-hotel, 378682 m)<br>Hotel Keiml (tourism-hotel, 378767 m, open)<br>ibis-Hotel Altstadt (tourism-hotel, 378725 m, open)<br>art & business Hotel (tourism-hotel, 378596 m) | Pos. 1 | ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 378600 m)<br>Hotel Keiml (tourism-hotel, 378767 m, open)<br>ibis-Hotel Altstadt (tourism-hotel, 378725 m, open)<br>Le Méridien Grand Hotel Nürnberg (tourism-hotel, 378682 m)<br>art & business Hotel (tourism-hotel, 378596 m) |
| c04 | nuremberg: ibis Nürnberg Hauptbahnhof | `^ibis Hotel Nürnberg Hauptbahnhof \\|` | Pos. 1 | ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 599 m)<br>ibis-Hotel Altstadt (tourism-hotel, 529 m, open)<br>Hotel Ibis (amenity-parking_entrance, 595 m)<br>Nürnberg Hauptbahnhof (highway-pedestrian-area, 659 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 664 m) | Pos. 1 | ibis Hotel Nürnberg Hauptbahnhof (tourism-hotel, 599 m)<br>ibis-Hotel Altstadt (tourism-hotel, 529 m, open)<br>Hotel Ibis (amenity-parking_entrance, 595 m)<br>Nürnberg Hauptbahnhof (highway-pedestrian-area, 659 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 664 m) |
| c05 | berlin: Hotel am Hauptbahnhof | `^(City Apartment\|Hotel) am Hauptbahnhof \\|` | Pos. - | Steigenberger Hotel Am Kanzleramt (tourism-hotel, 2570 m)<br>IntercityHotel Berlin Ostbahnhof (tourism-hotel, 2207 m)<br>S+U Hauptbahnhof (Invalidenstraße) (highway-pedestrian, 2524 m)<br>S+U Hauptbahnhof (highway-pedestrian-area, 2560 m)<br>S+U Hauptbahnhof (highway-bus_stop, 2570 m) | Pos. - | Steigenberger Hotel Am Kanzleramt (tourism-hotel, 2570 m)<br>IntercityHotel Berlin Ostbahnhof (tourism-hotel, 2207 m)<br>S+U Hauptbahnhof (Invalidenstraße) (highway-pedestrian, 2524 m)<br>S+U Hauptbahnhof (highway-pedestrian-area, 2560 m)<br>S+U Hauptbahnhof (highway-bus_stop, 2570 m) |
| c06 | nuremberg: Hotel am Hauptbahnhof | `^(City Apartment\|Hotel) am Hauptbahnhof \\|` | Pos. 1 | City Apartment am Hauptbahnhof (tourism-apartment, 1160 m)<br>Hotel am Stadtpark (tourism-hotel, 6384 m)<br>Nürnberg Hauptbahnhof (highway-pedestrian-area, 659 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 664 m)<br>Am Plärrer (highway-secondary, 825 m) | Pos. 1 | City Apartment am Hauptbahnhof (tourism-apartment, 1160 m)<br>Hotel am Stadtpark (tourism-hotel, 6384 m)<br>Nürnberg Hauptbahnhof (highway-pedestrian-area, 659 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 664 m)<br>Am Plärrer (highway-secondary, 825 m) |
| c07 | nuremberg: Nürnberg Bahnhofsplatz | `^Bahnhofsplatz \\|.*\\| highway-` | Pos. 3 | Nürnberg Hauptbahnhof (railway-station-subway, 691 m)<br>Nürnberg Hauptbahnhof (building-train_station, 756 m)<br>Bahnhofsplatz (highway-secondary, 616 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 656 m)<br>Nürnberg Hauptbahnhof (railway-tram_stop, 678 m) | Pos. 2 | Nürnberg Hauptbahnhof (railway-station-subway, 691 m)<br>Bahnhofsplatz (highway-secondary, 616 m)<br>Nürnberg Hauptbahnhof (highway-bus_stop, 659 m)<br>Nürnberg Hauptbahnhof (railway-tram_stop, 678 m)<br>Bahnhofsplatz (highway-cycleway, 634 m) |
| c08 | nuremberg: Nürnberg Hauptbahnhof Apotheke | `\\| amenity-pharmacy` | Pos. 1 | Merian Apotheke (amenity-pharmacy, 778 m, closed)<br>St.-Pauls-Apotheke (amenity-pharmacy, 6445 m, closed)<br>ABF Apotheke (amenity-pharmacy, 6472 m, closed)<br>Apotheke im Forum (amenity-pharmacy, 6475 m, closed)<br>Friedrichs-Apotheke (amenity-pharmacy, 6678 m, closed) | Pos. 1 | Merian Apotheke (amenity-pharmacy, 778 m, closed)<br>St.-Pauls-Apotheke (amenity-pharmacy, 6445 m, closed)<br>ABF Apotheke (amenity-pharmacy, 6472 m, closed)<br>Apotheke im Forum (amenity-pharmacy, 6475 m, closed)<br>Friedrichs-Apotheke (amenity-pharmacy, 6678 m, closed) |
| c09 | berlin: Nürnberg Gostenhof | `^Gostenhof \\|.*\\| place-suburb` | Pos. 4 | Gostenhof (railway-station-subway, 379192 m)<br>Gostenhof Cafe Bar (amenity-cafe, 379289 m, open)<br>Gostenhof-Apotheke zu Sankt Anton (amenity-pharmacy, 379349 m, closed)<br>Gostenhof (place-suburb, 379440 m)<br>Gostenhofener Buch Cafe (amenity-cafe, 379322 m) | Pos. 4 | Gostenhof (railway-station-subway, 379192 m)<br>Gostenhof Cafe Bar (amenity-cafe, 379289 m, open)<br>Gostenhof-Apotheke zu Sankt Anton (amenity-pharmacy, 379349 m, closed)<br>Gostenhof (place-suburb, 379440 m)<br>Gostenhofener Buch Cafe (amenity-cafe, 379322 m) |
