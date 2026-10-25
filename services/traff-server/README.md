# TraFF-Server (better-maps, AP4b)

Kleiner Verkehrsdatenserver in Go. Er liest Baustellen, Warnungen (Staus, Unfälle) und Sperrungen aus der
[Autobahn-API des Bundes](https://autobahn.api.bund.dev) und liefert sie im Format
[TraFF](https://gitlab.com/traffxml/traff) (Traffic Feed Format, Version 0.8) über die HTTP-Abo-Schnittstelle aus.
Gegenstelle ist die HTTP-TraFF-Quelle der App (`libs/traffxml/traff_source.cpp`, Branch `traffic`).

- Nur Standardbibliothek, keine externen Abhängigkeiten.
- Alles im Arbeitsspeicher: keine Datenbank, keine Dateien, Abos verschwinden beim Neustart.
- Weitere Quellen (DATEX II, Crowd-Daten) docken über das `Source`-Interface an (`internal/source`).

## Starten

Lokal mit Go 1.24:

```sh
go run ./cmd/traff-server                     # alle Autobahnen, Port 8080
go run ./cmd/traff-server -autobahn-roads A7,A9 -log-level debug
```

Als Container (Podman):

```sh
podman build -t traff-server -f Containerfile .
podman run --rm -p 8080:8080 -e TRAFF_AUTOBAHN_ROADS=A7,A9 traff-server
```

Das Image ist mehrstufig gebaut, enthält nur das statische Binary (ca. 11 MB, `distroless/static`) und läuft
als Benutzer 65532 (non-root).

Der erste vollständige Abruf aller Autobahnen dauert wegen der Drosselung etwa 1,5–2 Minuten
(rund 110 Autobahnen × 3 Dienste, mindestens 250 ms Abstand zwischen zwei Anfragen). Bis dahin sind die Feeds leer.

## Konfiguration

Jede Option gibt es als Flag und als Umgebungsvariable; das Flag hat Vorrang.

| Flag | Umgebungsvariable | Standard | Bedeutung |
|------|-------------------|----------|-----------|
| `-listen` | `TRAFF_LISTEN` | `:8080` | Adresse des HTTP-Servers |
| `-poll-interval` | `TRAFF_POLL_INTERVAL` | `5m` | Abrufintervall der Quellen (mind. 10 s) |
| `-autobahn` | `TRAFF_AUTOBAHN_ENABLED` | `true` | Autobahn-Quelle ein/aus |
| `-autobahn-url` | `TRAFF_AUTOBAHN_URL` | `https://verkehr.autobahn.de/o/autobahn` | Basis-URL der API |
| `-autobahn-roads` | `TRAFF_AUTOBAHN_ROADS` | leer = alle | Kommaliste, z. B. `A7,A9` |
| `-autobahn-request-interval` | `TRAFF_AUTOBAHN_REQUEST_INTERVAL` | `250ms` | Mindestabstand zwischen zwei API-Anfragen |
| `-user-agent` | `TRAFF_USER_AGENT` | `better-maps-traff-server/0.1 (+…)` | User-Agent für Anfragen an die API |
| `-subscription-ttl` | `TRAFF_SUBSCRIPTION_TTL` | `1h` | Abo verfällt nach dieser Zeit ohne Anfrage |
| `-message-lease` | `TRAFF_MESSAGE_LEASE` | `30m` | `expiration_time` einer Meldung, wird verlängert, solange die Quelle sie meldet |
| `-max-subscriptions` | `TRAFF_MAX_SUBSCRIPTIONS` | `10000` | Höchstzahl gleichzeitiger Abos (`SUBSCRIPTION_REJECTED`) |
| `-max-filters` | `TRAFF_MAX_FILTERS` | `128` | Höchstzahl Filter pro Anfrage |
| `-log-level` | `TRAFF_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

## Schnittstelle

Alle TraFF-Anfragen gehen per `POST` als XML an **eine** URL: `/` (Alias `/traff`). TraFF-Fehler werden mit
HTTP 200 und einem TraFF-Status gemeldet, wie es die Spezifikation (Abschnitt 6.1) verlangt.
`GET /healthz` liefert den Zustand als JSON (Anzahl Meldungen, Abos, letzter Abruf je Quelle).

Unterstützte Operationen: `SUBSCRIBE`, `SUBSCRIPTION_CHANGE`, `POLL` (mit Abo, oder ohne Abo als
„One-Shot“-Abfrage mit `filter_list`), `HEARTBEAT`, `UNSUBSCRIBE`, `GET_CAPABILITIES`.
`FEED` (Push vom Client) wird mit `PUSH_REJECTED` beantwortet.

### Beispiele mit curl

Abo für München und Umgebung anlegen (der erste Feed enthält alle passenden Meldungen):

```sh
curl -s -X POST -H 'Content-Type: application/xml' http://localhost:8080/ --data-binary '
<request operation="SUBSCRIBE">
  <filter_list>
    <filter bbox="47.9 11.2 48.4 11.9"/>
    <filter min_road_class="MOTORWAY" bbox="47.27 8.97 50.56 13.84"/>
  </filter_list>
</request>'
```

Antwort (gekürzt):

```xml
<response status="OK" subscription_id="3f0c…9a1e" timeout="3600">
  <feed>
    <message id="de.autobahn.warning:INRIX--vi-avl.2026-10-25_15-08-00-000_009.de0"
             receive_time="2026-10-25T16:00:00Z" update_time="2026-10-25T16:00:00Z"
             expiration_time="2026-10-25T16:30:00Z" start_time="2026-10-25T15:08:00Z"
             cancellation="false" forecast="false">
      <location country="DE" destination="Fulda" directionality="ONE_DIRECTION" origin="Würzburg"
                road_class="MOTORWAY" road_ref="A 7">
        <from>+50.302025 +9.848275</from>
        <via>+50.315713 +9.845064</via>
        <to>+50.319385 +9.838167</to>
      </location>
      <events>
        <event class="CONGESTION" type="CONGESTION_STATIONARY_TRAFFIC"></event>
        <event class="DELAY" type="DELAY_DELAY" q_duration="00:12"></event>
      </events>
    </message>
  </feed>
</response>
```

Neue und geänderte Meldungen abholen (Folgefeeds enthalten nur Änderungen und Aufhebungen):

```sh
curl -s -X POST http://localhost:8080/ --data '<request operation="POLL" subscription_id="3f0c…9a1e"/>'
```

Filter ändern, Abo am Leben halten, abbestellen, Fähigkeiten abfragen:

```sh
curl -s -X POST http://localhost:8080/ --data '<request operation="SUBSCRIPTION_CHANGE" subscription_id="3f0c…9a1e"><filter_list><filter bbox="53.3 9.7 53.7 10.3"/></filter_list></request>'
curl -s -X POST http://localhost:8080/ --data '<request operation="HEARTBEAT" subscription_id="3f0c…9a1e"/>'
curl -s -X POST http://localhost:8080/ --data '<request operation="UNSUBSCRIBE" subscription_id="3f0c…9a1e"/>'
curl -s -X POST http://localhost:8080/ --data '<request operation="GET_CAPABILITIES"/>'
curl -s http://localhost:8080/healthz
```

Einmalabfrage ohne Abo (z. B. zum Erzeugen von Testdaten für das TraFF-Assessment-Tool):

```sh
curl -s -X POST http://localhost:8080/ --data '<request operation="POLL"><filter_list><filter bbox="53.3 9.7 53.7 10.3"/></filter_list></request>'
```

## Verhalten im Detail

**Abos.** Die Subscription-ID ist eine zufällige 128-Bit-Zahl (crypto/rand, hex). Der Server merkt sich pro Abo,
welche Version welcher Meldung schon ausgeliefert wurde. Der erste Feed (Antwort auf `SUBSCRIBE`) ist vollständig,
jeder `POLL` liefert nur neue, geänderte und aufgehobene (`cancellation="true"`) Meldungen. Nach
`SUBSCRIPTION_CHANGE` kommen Meldungen hinzu, die nur der neue Filter trifft, sowie Änderungen; Meldungen, die nur
der alte Filter traf, werden nicht weiter verfolgt (TraFF 0.8, Abschnitt 2.4). Ein Abo verfällt nach
`subscription-ttl` ohne Anfrage; danach antwortet der Server mit `SUBSCRIPTION_UNKNOWN`.

**Filter.** Eine Meldung passt, wenn ihr Begrenzungsrechteck (alle Bezugspunkte, das sie je hatte) das
Filterrechteck berührt und ihre Straßenklasse mindestens `min_road_class` ist; Meldungen ohne Straßenklasse passen
immer. `bbox` ist `minLat minLon maxLat maxLon` wie im Client (`FiltersToXml`). Eine leere `filter_list` wird
akzeptiert und liefert nichts. Liegt kein Filterrechteck in Deutschland, lautet die Antwort `NOT_COVERED`, liegt
nur ein Teil darin, `PARTIALLY_COVERED` (der Client behandelt das wie `OK`).

**Gültigkeit.** `receive_time` ist der Zeitpunkt, an dem der Server die Meldung zuerst gesehen hat,
`update_time` die letzte Änderung. `expiration_time` ist eine Frist (Standard 30 min), die verlängert wird,
solange die Quelle die Meldung weiter meldet. Verschwindet eine Meldung aus der Quelle, verschickt der Server
eine Aufhebung. Fällt die Quelle aus, bleiben die letzten Daten bis zum Ablauf der Frist erhalten.

### Abbildung der Autobahn-Daten

| Autobahn-API | TraFF |
|--------------|-------|
| `display_type` `ROADWORKS`, `SHORT_TERM_ROADWORKS` | `CONSTRUCTION_ROADWORKS`, dazu `RESTRICTION_SPEED_LIMIT speed=…` („Max. 80 km/h“), `RESTRICTION_TEMP_MAX_WIDTH q_dimension=…`, je nach Fahrstreifen-Symbolen `RESTRICTION_LANE_CLOSED q_int=…`, `RESTRICTION_CONTRAFLOW` oder `RESTRICTION_CLOSED` |
| `CLOSURE` | `RESTRICTION_CLOSED` |
| `CLOSURE_ENTRY_EXIT` | `RESTRICTION_RAMP_CLOSED`, `ramps=ENTRY_RAMP/EXIT_RAMP/ALL_RAMPS` aus „Auffahrt“/„Abfahrt“ |
| `WEIGHT_LIMIT_35` | `RESTRICTION_TEMP_MAX_WEIGHT q_weight=3.5` |
| `WARNING` (INRIX) | `abnormalTrafficType` und Stichpunkte → `CONGESTION_STATIONARY_TRAFFIC` / `_QUEUE` / `_SLOW_TRAFFIC` / `_HEAVY_TRAFFIC` (die schwerste gewinnt, `averageSpeed` → `speed`), `delayTimeValue` → `DELAY_DELAY q_duration`, „Unfall“ → `INCIDENT_ACCIDENT`, „Personen auf der Fahrbahn“ → `HAZARD_PEOPLE_ON_ROAD`, „Fahrstreifen gesperrt“ → `RESTRICTION_LANE_CLOSED` usw.; ohne Angaben `CONGESTION_TRAFFIC_PROBLEM` |
| `isBlocked=true` | zusätzlich `RESTRICTION_CLOSED` |
| `geometry` (GeoJSON-Linie, in Fahrtrichtung) | `from` = erster, `to` = letzter, `via` = mittlerer Punkt; nur ein Punkt → `at` |
| Straßen-ID `A7` | `road_ref="A 7"`, `road_class="MOTORWAY"`, `country="DE"`, `directionality="ONE_DIRECTION"` |
| `subtitle` „Würzburg -> Fulda“ | `origin`, `destination` |
| Zeitangaben im Beschreibungstext | `start_time`/`end_time` des laufenden oder nächsten Zeitfensters (Beginn/Ende, „von … bis …“, „… bis zum …“, „Jeden Montag … zwischen dem … und dem …“, „Ende der Gesamtmaßnahme“); sonst `startTimestamp` |
| `identifier` | Meldungs-ID `de.autobahn.<dienst>:<identifier>` |

Abgelaufene Einträge und künftige Einträge ohne erkennbaren Beginn werden übersprungen.

### Höflichkeit gegenüber der API

Anfragen laufen nacheinander mit mindestens `autobahn-request-interval` Abstand, mit eigenem User-Agent und
`If-None-Match`/`If-Modified-Since` (die API liefert ETags und antwortet mit 304). Scheitert eine Anfrage für eine
Autobahn, werden deren letzte erfolgreiche Daten weiterverwendet; die übrigen Autobahnen sind nicht betroffen.

## Datenschutz

- Keine Protokollierung von IP-Adressen, User-Agents, Subscription-IDs oder Filtern. Auch Fehlermeldungen von
  `net/http`, die Client-Adressen enthalten können, werden vor der Ausgabe bereinigt (`internal/logging`).
- Abos liegen nur im Arbeitsspeicher und verfallen nach Inaktivität (`subscription-ttl`).
- Die Subscription-ID ist das einzige Merkmal eines Abos; sie ist zufällig und nicht erratbar.

## Tests

```sh
go vet ./... && go test -race -cover ./...       # oder: tools/better-maps/bm.sh go-test services/traff-server
go test ./internal/source/autobahn ./internal/server -update   # Golden-Files nach Prüfung neu schreiben
scripts/smoke.sh                                  # Container bauen, starten, Abo/Poll/Change/Unsubscribe per curl
SMOKE_OFFLINE=1 scripts/smoke.sh                  # dito ohne Netzzugriff auf die Autobahn-API
```

- `internal/source/autobahn/testdata/*.json` sind echte, am 25.10.2026 aufgezeichnete API-Antworten. Die Tests
  greifen nicht aufs Netz zu (die API wird mit `httptest` nachgestellt).
- `internal/clientcheck` bildet die Annahmeregeln des App-Parsers (`libs/traffxml/traff_model_xml.cpp`) in Go nach;
  die HTTP-Ende-zu-Ende-Tests prüfen damit jede Antwort und vergleichen sie mit Golden-Files
  (`internal/server/testdata`).

## Kompatibilität mit dem App-Client (Stand Branch `traffic`)

- Der Client kennt bisher nur Ereignistypen der Klassen `CONGESTION`, `DELAY` und (teilweise) `RESTRICTION`.
  Unbekannte Ereignisse überspringt er; eine Meldung ganz ohne bekanntes Ereignis verwirft er. Baustellen ohne
  Tempolimit oder Sperrung (nur `CONSTRUCTION_ROADWORKS`, `RESTRICTION_LANE_CLOSED`, …) kommen daher derzeit in der
  App nicht an – sie hätten im Client ohnehin keine Auswirkung auf Routing und Einfärbung.
- Der Client braucht mindestens zwei der Punkte `from`/`to`/`at`; Meldungen mit nur einem Punkt (`at`) ignoriert er.
- Der Client wertet `timeout` noch nicht aus und erneuert ein abgelaufenes Abo erst beim nächsten
  `SubscribeOrChangeSubscription()`.
