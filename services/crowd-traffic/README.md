# Crowd-Traffic-Server (better-maps, AP5a)

Aggregations-Server für die datenschutzfreundliche Stauerkennung (Feature 5). Die App meldet anonym, wie schnell
sie Straßenabschnitte durchfahren hat; der Server nimmt die Meldungen über **Oblivious HTTP** an, zählt sie pro
Abschnitt und 15-Minuten-Fenster, entzerrt das lokale Rauschen und veröffentlicht Staus erst ab **k = 10**
Meldungen – als **TraFF-Feed** für `services/traff-server` und als JSON.

Design, Bedrohungsmodell und Parameter: [docs/CROWD_TRAFFIC_PRIVACY.md](../../docs/CROWD_TRAFFIC_PRIVACY.md)
(dort auch der verbindliche **Client-Vertrag** für AP5b).

| Komponente | Zweck |
|---|---|
| `cmd/crowd-traffic` | Server: OHTTP-Gateway, Aggregation im Arbeitsspeicher, Export, `/healthz` |
| `cmd/ohttp-relay` | OHTTP-Relay **nur für Entwicklung und Tests** (in Produktion betreibt ein Dritter das Relay) |
| `cmd/crowd-sim` | Simulator und Referenz-Client: Fahrten in Berlin mit Stau auf der A100, sendet per OHTTP |
| `internal/client` | Referenzimplementierung der Client-Regeln (Segmentierung, Trimmung, Rauschen, Versand) |

Abhängigkeit: nur `github.com/cloudflare/circl` (HPKE, BSD-3-Clause). OHTTP (RFC 9458) und Binary HTTP
(RFC 9292) sind eine dünne Schicht darüber in `internal/ohttp` und `internal/bhttp`, geprüft gegen das
Beispiel aus RFC 9458, Anhang A.

## Starten

```sh
export CROWD_OHTTP_MASTER_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
go run ./cmd/crowd-traffic                               # Port 8080, Fenster 15 min, k = 10
go run ./cmd/ohttp-relay -gateway http://127.0.0.1:8080  # Port 8090
```

Zum Ausprobieren mit kurzem Fenster (Auswertung nach gut einer Minute):

```sh
go run ./cmd/crowd-traffic -window 60s -grace 5s -rate-burst 2000 &
go run ./cmd/ohttp-relay -gateway http://127.0.0.1:8080 -per-hour 100000 -burst 100000 &
go run ./cmd/crowd-sim -relay http://127.0.0.1:8090/relay -server http://127.0.0.1:8080 -window 60s -grace 5s -check
curl -s http://127.0.0.1:8080/v1/traff
```

Als Container (Podman); das Image enthält alle drei Programme, läuft als Benutzer 65532 und ist ca. 23 MB groß:

```sh
podman build -t crowd-traffic -f Containerfile .
podman run --rm -p 8080:8080 -e CROWD_OHTTP_MASTER_KEY=… crowd-traffic
```

## Konfiguration

Jede Option gibt es als Flag und als Umgebungsvariable; das Flag hat Vorrang.

| Flag | Umgebungsvariable | Standard | Bedeutung |
|---|---|---|---|
| `-listen` | `CROWD_LISTEN` | `:8080` | Adresse des HTTP-Servers |
| – | `CROWD_OHTTP_MASTER_KEY` | zufällig | 32 Byte hex: Master-Geheimnis, aus dem die OHTTP-Schlüssel abgeleitet werden. Ohne Angabe ändern sich die Schlüssel bei jedem Neustart (Warnung im Log). |
| `-ohttp-master-key-file` | `CROWD_OHTTP_MASTER_KEY_FILE` | – | dasselbe aus einer Datei (z. B. Podman-Secret) |
| `-key-period` | `CROWD_KEY_PERIOD` | `168h` | Rotationsperiode der OHTTP-Schlüssel |
| `-window` | `CROWD_WINDOW` | `15m` | Fensterlänge; **nur für Tests ändern**, die App rechnet fest mit 15 min |
| `-grace` | `CROWD_GRACE` | `5m` | Karenzzeit nach Fensterende bis zur Auswertung |
| `-k` | `CROWD_K` | `10` | Mindestzahl Meldungen pro Abschnitt und Fenster |
| `-coverage` | `CROWD_COVERAGE` | `47.2,5.8,55.1,15.1` | angenommenes Gebiet (`minLat,minLon,maxLat,maxLon` oder `world`) |
| `-date-skew` | `CROWD_DATE_SKEW` | `5m` | erlaubte Abweichung des `Date`-Headers (Replay-Schutz) |
| `-rate-limit`, `-rate-burst` | `CROWD_RATE_LIMIT`, `CROWD_RATE_BURST` | `200`, `400` | globales Limit des Gateways (Anfragen/s); pro Client begrenzt das Relay |
| `-tick` | `CROWD_TICK` | `5s` | Intervall der Fensterauswertung |
| `-summary-interval` | `CROWD_SUMMARY_INTERVAL` | `15m` | Zähler-Zusammenfassung im Log (`0` = aus) |
| `-log-level` | `CROWD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

`ohttp-relay`: `-listen` (`RELAY_LISTEN`, `:8090`), `-gateway` (`RELAY_GATEWAY`), `-per-hour` (30), `-burst` (10).

## Schnittstellen

### Annahme (nur über OHTTP)

| Endpunkt | Inhalt |
|---|---|
| `GET /ohttp-keys` | `application/ohttp-keys`: Schlüssel der laufenden und der nächsten Epoche (DHKEM X25519; HKDF-SHA256 mit AES-128-GCM oder ChaCha20-Poly1305) |
| `POST /gateway` | `message/ohttp-req` → `message/ohttp-res`. Einziges Ziel in der Kapsel: `POST /v1/reports` mit JSON-Bündel und `Date`-Header. Innen 204 = angenommen, 400 = abgelehnt (Problem-JSON mit Grund) |

Es gibt keinen Endpunkt, der Meldungen ohne OHTTP annimmt. Wire-Format und Fehlercodes:
Design-Dokument, Abschnitt 13.

### Export (stabil, Version 1)

**`GET /v1/traff[?bbox=minLat,minLon,maxLat,maxLon]`** – TraFF-0.8-`<feed>` mit allen aktuell
gemeldeten Staus (nur `confident` und nicht frei). Für die künftige Quelle in `services/traff-server`:

- Den Feed periodisch (z. B. jede Minute; neue Daten gibt es höchstens alle 15 min) abrufen und als
  **vollständigen Schnappschuss** behandeln (`Source.Fetch`). Meldungen, die fehlen, sind aufgehoben.
- Meldungs-IDs: `org.bettermaps.crowd:17/<x>/<y>/<Einfahrt>-<Ausfahrt>/<Straßenklasse>`, stabil pro Abschnitt.
- `receive_time` = Beginn der Stau-Episode, `update_time` = letzte Auswertung, `expiration_time` = 20 min
  nach der Auswertung.
- `location`: `from`/`to` auf den Kachelkanten (auf der Straße), `road_class`, `directionality="ONE_DIRECTION"`;
  ein Ereignis `CONGESTION_HEAVY_TRAFFIC`, `_SLOW_TRAFFIC`, `_QUEUE` oder `_STATIONARY_TRAFFIC` mit `speed`
  (geschätzter Median, km/h). Die Meldungen erfüllen die Regeln von `traff-server/internal/clientcheck`
  (Kopie in `internal/clientcheck`, von den Tests verwendet).

```xml
<feed>
  <message id="org.bettermaps.crowd:17/70420/43010/W-E/MOTORWAY" receive_time="2026-10-26T08:05:00Z"
           update_time="2026-10-26T08:20:00Z" expiration_time="2026-10-26T08:40:00Z" cancellation="false" forecast="false">
    <location directionality="ONE_DIRECTION" road_class="MOTORWAY">
      <from>+52.478778 +13.414307</from>
      <to>+52.478647 +13.417053</to>
    </location>
    <events>
      <event class="CONGESTION" type="CONGESTION_STATIONARY_TRAFFIC" speed="6"></event>
    </events>
  </message>
</feed>
```

**`GET /v1/segments[?bbox=…]`** – JSON mit allen veröffentlichten Abschnitten (auch freie Fahrt):

```json
{
  "version": 1, "generated": "2026-10-26T08:20:00Z", "window_seconds": 900, "k": 10, "epsilon": 3,
  "segments": [{
    "id": "org.bettermaps.crowd:17/70420/43010/W-E/MOTORWAY", "tile": [17, 70420, 43010],
    "entry": "W", "exit": "E", "road_class": "MOTORWAY",
    "from": {"lat": 52.478778, "lon": 13.414307}, "to": {"lat": 52.478647, "lon": 13.417053},
    "window_start": "2026-10-26T08:00:00Z", "window_end": "2026-10-26T08:15:00Z", "reports": 17,
    "speed_kmh": 6.4, "ref_speed_kmh": 100, "ref_source": "road_class_default", "ratio": 0.064,
    "slow_share": 0.9, "slow_share_se": 0.07, "state": "STATIONARY_TRAFFIC", "confident": true,
    "congested": true, "traff_event": "CONGESTION_STATIONARY_TRAFFIC",
    "first_seen": "2026-10-26T08:05:00Z", "updated": "2026-10-26T08:20:00Z", "expires": "2026-10-26T08:40:00Z"
  }]
}
```

`reports` ist immer ≥ k; Abschnitte unter k erscheinen nirgends.

**`GET /healthz`** – nur aggregierte Zähler: Anfragen, angenommene/abgelehnte Bündel (nach Grund), Replays,
Datumsfehler, angenommene Meldungen, ausgewertete Fenster, Abschnitte über/unter k, Speicherbelegung
(offene Fenster, Referenzen, Veröffentlichungen), aktuelle Schlüssel-ID.

## Datenschutz im Betrieb

- Keine Protokollierung von Anfragen, IP-Adressen oder User-Agents; `net/http`-Fehlermeldungen werden von
  Adressen bereinigt (`internal/logging`). Im Log stehen nur Start/Stopp und Zähler.
- Einzelmeldungen werden nie gespeichert: Der entschlüsselte Puffer wird nach der Anfrage mit Nullen
  überschrieben, die Aggregation erhöht nur Zähler. Zähler offener Fenster leben ≤ 20 min; Abschnitte unter k
  werden danach spurlos gelöscht. Veröffentlichungen leben 20 min, Freifluss-Referenzen 14 Tage ohne neue
  Daten. Alles liegt im Arbeitsspeicher; ein Neustart löscht es.
- Relay und Gateway müssen von **unabhängigen** Parteien betrieben werden. `cmd/ohttp-relay` ist nur für
  Entwicklung und Tests.

## Tests

```sh
tools/better-maps/bm.sh go-test services/crowd-traffic    # go vet + go test -race -cover ./...
go test -coverprofile=c.out ./... && go tool cover -func=c.out | tail -1
scripts/smoke.sh                                          # Container: Gateway + Relay im Pod, Simulator, Prüfungen
go run ./cmd/crowd-sim -dry-run                           # Statistik und Beispiel-Bündel des Simulators
```

Wichtige Tests:

- `internal/ohttp`: RFC-9458-Testvektor, Roundtrip beider Suites, falscher Schlüssel, unbekannte Key-ID,
  manipulierte Header/Chiffrate, Key-Rotation, Replay-Cache.
- `internal/ldp`: Verteilung der randomisierten Antwort, Erwartungstreue und Standardfehler des Schätzers,
  Fehlerschranke bei n = k, Norm-Sub/Base-Cut.
- `internal/aggregate`: k-Schwelle (unter k keine Spur), Fenster/Karenz/TTL, Falsch-positiv- und
  Erkennungsraten mit Rauschen, Ausreißer-Robustheit, Referenz und Episoden.
- `internal/protocol`, `internal/server`: Validierung bösartiger und inkonsistenter Bündel, Datum, Replay,
  Ratenlimit, Klartext wird überschrieben, keine personenbezogenen Daten in Log und `/healthz`.
- `internal/sim`: Ende-zu-Ende Simulator → Referenz-Client → Relay → Gateway → Export; Stau nur in der
  simulierten Stauzone, nichts unter k, TraFF-Feed vom Client-Parser akzeptiert.

## Offene Punkte

- Erledigt (AP5c): `services/traff-server` holt `/v1/segments` als Quelle `crowd` ab und fasst benachbarte
  Stau-Kacheln zu einer TraFF-Meldung zusammen (siehe README dort, Abschnitt „Crowd-Daten“).
- Tageslimits (96 Meldungen, 2 pro Abschnitt) muss die App über Fahrten hinweg führen; der Referenz-Client
  verarbeitet einzelne Fahrten (AP5b).
- Betreiber für das Relay, optional Privacy-Pass-Tokens gegen Sybil-Angriffe, optional DAP/Janus.
