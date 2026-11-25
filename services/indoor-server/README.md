# Indoor-Server (better-maps, Innenraumkarten)

Eigener Server für Innenraumkarten großer Gebäude („Venues“): Flughafen-Terminals (z. B. Frankfurt), große Bahnhöfe
(z. B. Köln Hbf), Einkaufszentren, Universitäten. Grundlage sind die Innenraumdaten von OpenStreetMap nach dem
Schema **Simple Indoor Tagging** (`indoor=room|corridor|area|level|wall`, `level=*`, `repeat_on=*`, Türen, Treppen,
Aufzüge, Rolltreppen, Läden, Toiletten, Gates …). Der Server bereitet sie einmal pro Woche zu fertigen
Venue-Dokumenten auf; die App lädt die Liste der Venues im sichtbaren Ausschnitt und das Dokument einer Venue, wenn
sie es braucht, und zeichnet die Etagen selbst.

```
App ──HTTPS──► Reverse Proxy (TLS) ──► indoor-gateway (Go: Token, Ratenlimit, Daten im Speicher)
                                                 ▲ liest (nur lesend)
Geofabrik ──► fetch-data.sh ──► filter (osmium, ohne Netz) ──► import (indoor-import, ohne Netz) ──► data/
```

- **indoor-gateway** (`cmd/indoor-gateway`, nur Go-Standardbibliothek) ist der einzige veröffentlichte Dienst. Er
  lädt `data/index.json` und alle vorkomprimierten Venue-Dokumente in den Speicher und spricht mit niemandem sonst.
- **filter** (Debian-`osmium-tool`, Image `localhost/bettermaps-osmium`, dasselbe wie beim Routing-Server)
  filtert die Extrakte auf indoor-relevante Objekte, führt die Regionen zusammen und exportiert GeoJSONSeq.
- **indoor-import** (`cmd/indoor-import`, Paket `internal/venues`) baut daraus die Venues.

## Daten und Lizenz

Die Daten stammen aus OpenStreetMap und stehen unter der **Open Database License (ODbL 1.0)**. Jedes
Venue-Dokument trägt `"attribution": "© OpenStreetMap-Mitwirkende"` und `"license": "ODbL-1.0"`; die App zeigt den
Vermerk an, solange eine Innenraumkarte sichtbar ist. Die aufbereiteten Dokumente sind eine abgeleitete Datenbank im
Sinne der ODbL; wer sie öffentlich weitergibt, muss sie ebenfalls unter der ODbL anbieten (für den Eigenbetrieb mit
eigenen Geräten ohne Weitergabe entsteht keine Pflicht).

Quelle der Extrakte: Geofabrik (`download.geofabrik.de`), beliebige Regionen über `INDOOR_REGIONS`. User-Agent der
Downloads: `better-maps-indoor-fetch/1.0 (self-hosted indoor map server)` – ohne persönliche Daten.

## Sicherheit und Datenschutz

Der Server läuft im Heimnetz, ist aber von außen erreichbar. Deshalb wie beim Routing- und Luftbild-Server:

- **Token pro Gerät**: Jede Anfrage an `/v1/…` braucht `Authorization: Bearer <token>`. Der Server kennt nur
  SHA-256-Hashes (`~/.config/bettermaps-indoor/tokens`, Rechte 600), vergleicht in konstanter Zeit und antwortet
  ohne gültiges Token mit `401`, ohne den Rest der Anfrage anzusehen. Sperren durch Löschen der Zeile (wird nach
  ≤ 30 s bzw. bei `SIGHUP` neu gelesen).
- **Ratenlimit pro Token**: im Mittel 120 Anfragen/Minute, bis zu 60 auf einmal; darüber `429` mit `Retry-After`.
  Fehlversuche mit falschem Token global ≤ 5/s.
- **Grenzen**: bbox höchstens 1° × 1°, höchstens 100 Venues pro Liste, nur `GET`/`HEAD`, Venue-Ids nur
  `[A-Za-z0-9_-]{1,40}` (kein Pfadzugriff), Zeitlimits für Header (5 s), Anfrage (10 s) und Antwort (30 s).
- **Logs**: nur Methode, Pfadklasse (`venues`/`venue`/`other`), Status, Dauer, Bytes, gzip und das **Label** des
  Tokens. Nie das Token, nie Koordinaten/bbox, nie Venue-Ids, nie Client-Adressen (auch die Fehlermeldungen von
  `net/http` werden bereinigt). Was ein Gerät ansieht, lässt sich aus den Logs also nicht ablesen.
- **Container**: alle ohne Capabilities, `no-new-privileges`, schreibgeschütztes Dateisystem, Speicher- und
  CPU-Limits. Das Gateway (distroless, ohne Shell) sieht nur die Token-Datei und `data/` (nur lesend). Die
  Import-Container haben **kein Netz** (`network_mode: none`), osmium läuft mit `nice -n 15`.
- **TLS** macht der Reverse Proxy; das Gateway lauscht standardmäßig nur auf `127.0.0.1:18300`.

### Reverse Proxy (Beispiel Caddy)

```caddyfile
indoor.example.org {
	@api path /v1/venues /v1/venues/*
	handle @api {
		reverse_proxy 127.0.0.1:18300
	}
	handle {
		respond 404
	}
	# Achtung: die bbox steht in der URL. Caddy schreibt Zugriffslogs nur mit "log"; dann die Query maskieren
	# oder kein Zugriffslog schreiben.
}
```

## Schnellstart

Voraussetzung: Podman mit `podman-compose` (in einer Distrobox wird der Host über `distrobox-host-exec`
angesprochen; Go wird nur für `new-token.sh` und die Tests gebraucht).

```sh
cd services/indoor-server
scripts/fetch-data.sh                 # Geofabrik-Extrakte, Standard: Hessen + NRW (~1,2 GB)
scripts/import.sh                     # filtern + Venues bauen (siehe Ressourcenbedarf), löscht danach die Extrakte
scripts/new-token.sh pixel            # Token für ein Gerät anlegen, wird einmal angezeigt
scripts/run.sh                        # Gateway starten (baut das Image beim ersten Mal)
INDOOR_TOKEN=... scripts/smoke.sh     # Test: 401, 400, Frankfurt Flughafen, Köln Hbf, gzip/ETag
scripts/stop.sh                       # Container stoppen, Daten bleiben
```

Andere Regionen: `INDOOR_REGIONS="europe/germany/berlin europe/germany/bayern" scripts/fetch-data.sh` usw. (alle
Skripte lesen dieselben Variablen). Ohne Container geht es auch direkt:
`go run ./cmd/indoor-gateway -data-dir ~/.cache/bettermaps-indoor/data -tokens-file ~/.config/bettermaps-indoor/tokens -listen 127.0.0.1:18300`.

Im Emulator erreicht man den Rechner unter `http://10.0.2.2:18300`.

## Konfiguration

Skripte (`scripts/common.sh`, alles über Umgebungsvariablen):

| Variable | Standard | Bedeutung |
|----------|----------|-----------|
| `INDOOR_REGIONS` | `europe/germany/hessen europe/germany/nordrhein-westfalen` | Geofabrik-Pfade ohne `-latest.osm.pbf`, durch Leerzeichen getrennt (Dateinamen = letzter Teil, müssen verschieden sein) |
| `INDOOR_CACHE` | `~/.cache/bettermaps-indoor` | `input/` (Downloads), `work/` (Zwischendateien), `data/` (ausgelieferte Daten) |
| `INDOOR_KEEP_PBF` | `0` | `1` behält die Extrakte nach einem erfolgreichen Import |
| `INDOOR_IMPORT_MEMORY` / `INDOOR_IMPORT_CPUS` | `3g` / `2` | Limits der Import-Container |
| `INDOOR_BIND` / `INDOOR_PORT` | `127.0.0.1` / `18300` | Veröffentlichter Port des Gateways |
| `INDOOR_MEMORY` | `128m` | Speicherlimit des Gateways (gemessen: 12 MB) |
| `INDOOR_TOKENS_FILE` | `~/.config/bettermaps-indoor/tokens` | Token-Hashes |
| `INDOOR_RATE_PER_MINUTE` / `INDOOR_RATE_BURST` | `120` / `60` | Ratenlimit pro Token |
| `INDOOR_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `INDOOR_MAX_FILESIZE` | `4G` | größter erlaubter Download |

Gateway (Flag oder Umgebungsvariable, das Flag hat Vorrang): `-listen`/`INDOOR_LISTEN` (`:8080`),
`-data-dir`/`INDOOR_DATA_DIR`, `-tokens-file`/`INDOOR_TOKENS_FILE`, `-reload-tokens`/`INDOOR_RELOAD_TOKENS` (`30s`),
`-reload-data`/`INDOOR_RELOAD_DATA` (`1m`: prüft die Änderungszeit von `index.json`), `-rate-per-minute`/
`INDOOR_RATE_PER_MINUTE`, `-rate-burst`/`INDOOR_RATE_BURST`, `-log-level`/`INDOOR_LOG_LEVEL`. `SIGHUP` lädt Tokens
und Daten sofort neu. Ein kaputtes `data/` oder eine kaputte Token-Datei ersetzt nie den bisherigen Stand.

`indoor-gateway token <label>` erzeugt ein Token (256 Bit) und die Zeile für die Token-Datei
(`<label> sha256:<hex>`); `scripts/new-token.sh` erledigt beides.

`indoor-import -input <geojsonseq> -out <dir> [-timestamps <datei>] [-regions <dir mit .poly>] [-min-venues 1]`
schreibt `index.json`, `venues/<id>.json.gz` und `import-stats.txt`; mit weniger als `-min-venues` Venues bricht es
ab (schützt die ausgelieferten Daten vor einem kaputten Export).

## API

```http
GET /v1/venues?bbox=8.50,50.00,8.65,50.08
Authorization: Bearer <token>
```

```json
{"data_timestamp": "2026-11-24T21:21:02Z",
 "venues": [{"id": "w38212384", "name": "Terminal 1", "kind": "airport", "bbox": [8.5612, 50.0459, 8.5779, 50.0532],
             "levels": [{"level": -1, "label": "-1"}, {"level": 0, "label": "0"}], "default_level": 0,
             "features": 4211, "etag": "\"3f9a1c0d2b…\""}]}
```

- `bbox` = `minLon,minLat,maxLon,maxLat` (WGS84), höchstens 1° × 1°, sonst `400`. Liefert die Venues, deren bbox
  den Ausschnitt schneidet, höchstens 100, die größten (meiste Features) zuerst.
- `Cache-Control: private, max-age=3600`, `ETag` (Hash der Antwort), `304` bei `If-None-Match`, gzip wenn angeboten.
- `etag` ist das ETag, mit dem das Venue-Dokument ausgeliefert wird.

```http
GET /v1/venues/{id}
```

Das Venue-Dokument: vorkomprimiert (`Content-Encoding: gzip`, `Vary: Accept-Encoding`), wenn der Client gzip
anbietet, sonst entpackt; starkes ETag (Hash des unkomprimierten JSON), `304` bei `If-None-Match`,
`Cache-Control: private, max-age=86400`. Unbekannte Ids: `404`.

```json
{"id": "w38212384", "name": "Terminal 1", "kind": "airport", "bbox": [8.5612, 50.0459, 8.5779, 50.0532],
 "levels": [{"level": 0, "label": "0"}], "default_level": 0, "data_timestamp": "2026-11-24T21:21:02Z",
 "attribution": "© OpenStreetMap-Mitwirkende", "license": "ODbL-1.0",
 "features": [
   {"id": "w123", "t": "a", "k": "shop", "n": "Relay", "r": "B12", "lv": [0], "g": [["<ring>", "<loch>"]], "tg": {"shop": "kiosk"}},
   {"id": "w124", "t": "l", "k": "steps", "lv": [0, 1], "g": ["<linie>"]},
   {"id": "n125", "t": "p", "k": "toilets", "lv": [1], "g": "<punkt>", "tg": {"amenity": "toilets", "wheelchair": "yes"}}]}
```

- `id` OSM-Id (`n`/`w`/`r` + Nummer), `t` Geometrie (`a` Fläche, `l` Linie, `p` Punkt), `k` Art (Zeichenklasse),
  `n` Name, `r` ref, `lv` Etagen (sortiert), `g` Geometrie als Google Encoded Polyline mit Präzision 6 (Breite
  zuerst; Punkt: ein String, Linie: Liste von Strings, Fläche: Liste von Polygonen aus Ringen, erster = außen, Ringe
  ohne wiederholten Endpunkt), `tg` Auswahl an Tags (`amenity`, `shop`, `cuisine`, `opening_hours`, `wheelchair`,
  `operator`, `brand`, `website`, `aeroway`, `highway`, `door`, `access`, `toilets:wheelchair`, `changing_table`,
  `fee`, `description`; Werte höchstens 200 Zeichen). Leere Felder fehlen.
- Arten: Flächen `level`, `corridor`, `area`, `platform`, `room`, `restricted`, `service`, `shop`, `food`, `toilets`,
  `stairs`, `elevator`, `gate`, `wall`; Linien `footway`, `steps`, `escalator`, `elevator`, `wall`; Punkte `door`,
  `entrance`, `toilets`, `elevator`, `stairs`, `escalator`, `gate`, `ticket`, `info`, `luggage`, `atm`, `pharmacy`,
  `food`, `shop`, `poi`.
- Reihenfolge = Zeichenreihenfolge: erst Flächen (Etagenumriss, Gänge/Bereiche/Bahnsteige, Räume, Läden, …, Wände),
  dann Linien, dann Punkte; innerhalb einer Art nach unterster Etage und Id.

Fehler als `{"error": "<code>"}`: `400` (`bad_request`), `401` (`unauthorized`), `404` (`not_found`), `405`
(`method_not_allowed`), `429` (`rate_limited`, mit `Retry-After`). `GET /healthz` antwortet `ok` (ohne Token).

## Datenaufbereitung

### Filter und Export (osmium)

Pro Region `osmium tags-filter` auf `indoor`, `level`, `repeat_on`, `level:ref`, Türen und Eingänge,
`highway=elevator|steps`, `conveying`, `aeroway=aerodrome|terminal|gate`, `railway=station|halt|platform`,
`public_transport=station|platform`, `building=train_station|transportation|terminal`, `shop=mall|department_store`
(jeweils mit allen referenzierten Objekten) sowie – über zwei hintereinander geschaltete Filter – **benannte
Gebäude** (`building` UND `name`). Die gefilterten Regionen werden mit `osmium merge` zusammengeführt (eine Venue
an einer Regionsgrenze erscheint einmal) und mit `osmium export -f geojsonseq --add-unique-id=type_id` und
`config/osmium-export.json` exportiert. Die Konfiguration macht geschlossene Wege mit `indoor=room|corridor|area|
level|wall`, `room`, `building`, `shop`, `amenity`, `aeroway`, Bahnsteigen, Stationen oder `area=yes` zu Flächen
und geschlossene `highway=*`-Wege (z. B. Fußwegschleifen) zu Linien; offene Wege sind immer Linien. Geprüft mit dem
handgeschriebenen Beispiel `testdata/export-fixture.osm` (`scripts/check-export.sh`, Ergebnis
`testdata/export-fixture.geojsonseq`, das die Go-Tests lesen). Osmium vergibt Flächen eigene Ids (`a` + 2 × Weg-Id
bzw. 2 × Relations-Id + 1); der Import rechnet sie auf `w…`/`r…` zurück.

### Etagen (`level`, `repeat_on`)

| Wert | Etagen |
|------|--------|
| `0`, `-1`, `0.5`, `1.0` | eine Etage (Dezimalpunkt) |
| `0;1`, `-1;0;1`, `0;` | Liste mit `;` (leere Einträge werden ignoriert) |
| `0-2`, `-2--1`, `-1-1`, `2-0` | Bereich in ganzen Schritten (auch umgekehrt) |
| `0.5-2` | `0.5, 1, 2` (Enden bleiben, dazwischen ganze Zahlen) |
| `1,2`, `-1,0,1` | Liste mit Komma **nur**, wenn alle Einträge ganze, aufeinanderfolgende Zahlen sind |
| `0,5`, `1,3`, `EG`, `1a`, `roof` | Fehler: Feature wird verworfen (`0,5` ist eher ein Dezimalkomma) |

Höchstens 20 Etagen pro Wert, Beträge ≤ 100. `level` und `repeat_on` werden vereinigt; ist einer von beiden
unlesbar, fällt das Feature weg (gezählt als `bad_level`). Innenraum-Features (`indoor=*`) ohne Etage liegen auf
`0`; andere Features ohne Etage gehören nicht zur Innenraumkarte. **Ausnahme**: Türen und Eingänge ohne Etage, die
auf dem Umriss (≤ 0,5 m) eines Innenraums liegen (meist ein Knoten des Raum-Wegs), bekommen dessen Etagen; Umrisse
ganzer Etagen (`indoor=level`) zählen nur, wenn kein Raum passt.

### Venues

1. **Cluster**: Innenraumflächen (`indoor=room|corridor|area|level`) mit höchstens 30 m Abstand (zwischen den
   Bounding-Boxen) bilden eine Venue (Union-Find über ein Gitter).
2. **Teilen**: Ein Cluster, das breiter/höher als 3 km ist oder mehr als 40 000 Innenraumflächen hat, wird nach den
   umgebenden Terminals/Bahnhofsgebäuden/Malls/benannten Gebäuden geteilt (jede Fläche zum äußersten Objekt der
   wichtigsten Klasse, das ihren Mittelpunkt enthält; Flächen außerhalb zum nächsten Teil bis 50 m, sonst eigene
   Cluster). Ebenso, wenn mindestens zwei Terminals je ≥ 10 % der Flächen enthalten (Flughäfen mit verbundenen
   Terminals).
3. **Anhängen**: Wände, Linien, Punkte und Flächen mit Etage (Läden, Bahnsteige, …), deren Mittelpunkt höchstens
   20 m von den Innenraumflächen einer Venue entfernt liegt, gehören zu ihr (zur nächsten, wenn mehrere in Frage
   kommen). Was weiter weg liegt, aber in (oder ≤ 20 m neben, z. B. Gates am Fluggastbrückenende) einem
   **Terminal, Bahnhofsgebäude oder einer Mall** (benannt), gehört zu der Venue, die dieses Gebäude „beansprucht“
   (≥ 25 % ihrer Innenraumflächen liegen darin), sonst zu einer neuen **Gebäude-Venue** aus genau diesem Gebäude
   (das kleinste, wenn sie verschachtelt sind). Viele Terminals und Bahnhöfe haben Läden, Gates und Toiletten mit
   `level`, aber kaum `indoor=room` (Frankfurt Terminal 1). Alles andere wird ignoriert.
4. **Schwellen** (Konstanten in `internal/venues/build.go`): Eine Venue bleibt, wenn sie mindestens **8
   Innenraumflächen** mit zusammen mindestens **1500 m²** Raum/Gang/Bereich hat (Etagenumrisse zählen nicht zur
   Fläche) **oder** mindestens **2 Etagen mit je 4 Features** (für Gebäude-Venues gilt nur diese Regel). Sonst
   wird sie übersprungen (`skipped_small`).
5. **Name und Art**: das Objekt, das mehr als die Hälfte der Innenraumflächen enthält, nach Vorrang
   `aeroway=terminal` (airport) > `building=train_station|transportation` bzw. Bahnhofsfläche (station) >
   `shop=mall|department_store` (mall) > anderes benanntes Gebäude (building) > `aeroway=aerodrome` (airport),
   innerhalb einer Klasse das kleinste. Sonst ein Bahnhofsknoten (`railway=station`, `public_transport=station`)
   in der Venue (station), sonst der häufigste Name ihrer `indoor=level`-Flächen (other), sonst ohne Namen (other).
6. **Id**: die OSM-Id des namengebenden Objekts (`w…`, `r…`, `n…`), sonst `c` + kleinste Id einer Innenraumfläche
   (z. B. `cw12345`). Teilen sich zwei Venues ein namengebendes Objekt (z. B. den Flughafen), bekommt die größere
   dessen Id und die andere die `c…`-Id. Die Ids bleiben über Importe stabil, solange sich die OSM-Objekte nicht
   ändern.
7. **Umriss**: Hat eine Venue keine `indoor=level`-Fläche, aber ein Terminal/Bahnhofsgebäude/eine Mall, aus dem sie
   entstanden ist oder nach dem sie benannt ist, bekommt sie dessen Umriss als `level`-Fläche auf allen ihren
   Etagen (Feature-Id = Id des Gebäudes).
8. **Etagen**: alle Etagen der Features, `label` aus `level:ref`, `level_ref` oder `name` der `indoor=level`-Fläche
   genau dieser Etage, wenn höchstens 8 Zeichen, sonst die Zahl. `default_level`: 0, falls vorhanden, sonst die
   Etage mit den meisten Features (bei Gleichstand die nähere an 0, dann die obere).
9. **Geometrie**: Douglas-Peucker mit 0,15 m, Ringe mit weniger als 3 Punkten fallen weg (Polygon ohne Außenring
   ganz), Koordinaten auf 1e-6° gerundet.

## Ressourcenbedarf

Siehe `import-stats.txt` und `import-history.txt` im Cache. Gemessen am 25.11.2026 (Geofabrik-Stand 24.11.2026
21:21 UTC, Hessen + NRW, Rechner mit 16 Threads / 31 GB, Limits 3 GB / 2 CPUs):

| | Hessen + NRW |
|---|---|
| Download (`input/`) | 333 MB + 876 MB, nach dem Import gelöscht |
| filter (je Region 3 × `tags-filter`, `merge`, `export`) | 128 s; Spitze laut cgroup 2,9 GB (überwiegend Seitencache) |
| Zwischendateien (`work/`, danach gelöscht) | zusammengeführt 45 MB PBF, Export 230 MB GeoJSONSeq (765 336 Objekte) |
| indoor-import | 6,6 s, Spitze 688 MB |
| Import gesamt (`import.sh`) | 137 s |
| Ergebnis (`data/`) | 2,8 MB: 579 Venues (Hessen 180, NRW 399), 35 176 Features, 1,1 MB gzip / 4,5 MB JSON, `index.json` 133 KB |
| Gateway | 12 MB Speicher, < 2 ms je Anfrage lokal |
| Liste Frankfurt Flughafen (9 Venues) / Köln Hbf (8) | 3,1 KB / 2,7 KB, gzip 0,8 KB / 0,7 KB |
| Dokument Köln Hbf (428 Features) | 60 KB, gzip 13 KB |
| Dokument Terminal 1 Flugsteig B (100 Features) | 15 KB, gzip 3,5 KB |
| größte Dokumente | b-it Bonn 1119 Features 119 KB / 18 KB gzip; The Squaire (Frankfurt Flughafen Fernbahnhof) 1115 Features 130 KB / 28 KB gzip |

Übersprungen wurden 627 Cluster unter den Schwellen (zusammen 2860 Features), 138 Features mit unlesbarer Etage und
2591 Türen ohne Etage abseits von Raumumrissen; 109 Venues sind Gebäude-Venues. Für größere Regionen (ganz
Deutschland) ist der Bedarf nicht gemessen; der osmium-Schritt braucht dann mehr als 3 GB
(`INDOOR_IMPORT_MEMORY` erhöhen), der Rest wächst mit der Zahl der Innenraum-Objekte.

## Wöchentliches Update

```sh
mkdir -p ~/.config/systemd/user
cp systemd/bettermaps-indoor-update.* ~/.config/systemd/user/
# Pfad in ExecStart= und ggf. INDOOR_REGIONS anpassen, dann:
systemctl --user daemon-reload
systemctl --user enable --now bettermaps-indoor-update.timer
```

`scripts/update.sh` lädt die Extrakte (ETag/Last-Modified, MD5-Prüfung), importiert in `data.next` und tauscht erst
nach Erfolg; ein laufendes Gateway wird mit den neuen Daten neu gestartet. Weil `import.sh` die Extrakte nach dem
Import löscht (`INDOOR_KEEP_PBF=1` behält sie), lädt jedes Update sie neu (Hessen + NRW ≈ 1,2 GB); Geofabrik
veröffentlicht ohnehin täglich neue Extrakte, so dass wöchentlich fast immer etwas Neues da ist.

### Dauerbetrieb

`systemd/bettermaps-indoor.service` startet das Gateway beim Anmelden (`systemctl --user enable --now
bettermaps-indoor.service`; für den Start ohne Anmeldung `loginctl enable-linger`).

## Tests

```sh
tools/better-maps/bm.sh go-test services/indoor-server     # go vet + go test -race -cover
scripts/check-export.sh                                    # osmium-Export des Beispiels unverändert?
```

Abgedeckt: Etagen-Werte (Listen, Bereiche, Kommas, Fehler), Klassifizierung, Cluster, Teilen, Anhängen,
Türetagen, Schwellen, Namen und Ids, Kodierung (Polyline, Vereinfachung, entartete Geometrie), Import-Kommando,
Token-Datei und -Prüfung, Ratenlimits, bbox-Prüfung, gzip/ETag/304/HEAD, Methoden, Neuladen der Daten, Logs ohne
Token/Koordinaten/Ids.

## Bekannte Grenzen

- **Datenlage**: Die Venues sind so gut wie die OSM-Daten. Frankfurt Terminal 1 hat kaum `indoor=room`, aber
  viele Läden, Gates und Toiletten mit `level`; es erscheint als mehrere Venues („Terminal 1 Flugsteig A“, „…
  Flugsteig B“ und ein nach dem Flughafen benannter Teil um Flugsteig A-Plus), Terminal 2 fehlt mangels Daten.
  Etagen-Labels sind meist Zahlen (`level:ref` ist selten), Zwischenetagen wie `0.3` kommen aus den Daten.
- Gates und andere Objekte mehr als 20 m außerhalb eines Terminals werden ignoriert; ebenso alles mit `level`
  außerhalb von Venues (`unattached`, z. B. Läden in gewöhnlichen Gebäuden).
- Venues können sich überlappen (z. B. benachbarte Terminals); die App zeigt dann mehrere an derselben Stelle.
- Die Ids sind stabil, solange das namengebende OSM-Objekt bzw. die kleinste Innenraumfläche bleibt; sonst ändert
  sich die Id beim nächsten Import (die App lädt das Dokument dann neu).
- gzip- und unkomprimierte Antwort tragen dasselbe starke ETag (so vereinbart, damit das ETag der Liste für beide
  passt); `Vary: Accept-Encoding` hält Zwischenspeicher auseinander.
- Die bbox steht in der URL: Zugriffslogs eines Reverse Proxys müssen sie maskieren oder abgeschaltet sein.
- Regionen werden nur zusammengeführt; derselbe Dateiname in zwei Pfaden (`…/a/x` und `…/b/x`) geht nicht.
