# TraFF-Server (better-maps, AP4b/AP4c/AP5c)

Kleiner Verkehrsdatenserver in Go. Er liest Baustellen, Warnungen (Staus, Unfälle) und Sperrungen aus der
[Autobahn-API des Bundes](https://autobahn.api.bund.dev) und Verkehrsmeldungen im Format
[DATEX II](https://docs.datex2.eu) (v2 und v3) von Landesmeldestellen, Straßenbauverwaltungen und der
[Mobilithek](https://mobilithek.info) sowie anonym gemeldete Staus des eigenen Crowd-Servers
(`services/crowd-traffic`). Er liefert sie ohne Dubletten im Format
[TraFF](https://gitlab.com/traffxml/traff) (Traffic Feed Format, Version 0.8) über die HTTP-Abo-Schnittstelle aus.
Gegenstelle ist die HTTP-TraFF-Quelle der App (`libs/traffxml/traff_source.cpp`, Branch `traffic`).

- Nur Standardbibliothek, keine externen Abhängigkeiten.
- Alles im Arbeitsspeicher: keine Datenbank, keine Dateien, Abos verschwinden beim Neustart.
- Zugang nur mit **Token pro Gerät** (`Authorization: Bearer …`), wie beim Routing-, Luftbild- und
  Innenraumkarten-Server; siehe [Sicherheit und Datenschutz](#sicherheit-und-datenschutz).
- Quellen docken über das `Source`-Interface an (`internal/source`): Autobahn-API (`internal/source/autobahn`),
  DATEX II (`internal/source/datex2`), Crowd-Staus (`internal/source/crowd`). Jede Quelle kann ein eigenes
  Abrufintervall haben (`IntervalSource`); Quellen mit verschiedenen Intervallen laufen nebenläufig.

## Sicherheit und Datenschutz

Der Server läuft auf dem Heimserver hinter Cloudflare und einem Caddy-Edge und ist damit von außen erreichbar.
Deshalb wie beim Routing-, Luftbild- und Innenraumkarten-Server:

- **Token pro Gerät**: Jede Anfrage außer `/healthz` braucht `Authorization: Bearer <token>` – TraFF-Anfragen
  (`POST /`, `/traff`), der Infotext (`GET /`), `/status` und auch unbekannte Pfade. Der Server kennt nur
  SHA-256-Hashes (`TRAFF_TOKENS_FILE`, z. B. `~/.config/bettermaps-traff/tokens`, Rechte 600), vergleicht in
  konstanter Zeit und antwortet ohne gültiges Token mit `401` und dem Text `unauthorized` – ohne Details und ohne den
  Rumpf der Anfrage zu lesen. Sperren durch Löschen der Zeile (wird nach ≤ 30 s bzw. bei `SIGHUP` neu gelesen; eine
  kaputte Datei ersetzt nie den bisherigen Stand).
- **Ohne Token-Datei startet der Server nicht.** Nur für lokale Entwicklung und Tests gibt es
  `TRAFF_INSECURE_NO_AUTH=1` (nur als Umgebungsvariable, kein Flag): Dann ist alles offen, und der Server warnt beim
  Start im Log. Beides zugleich ist ein Fehler.
- **Ratenlimit pro Token**: im Mittel 60 Anfragen/Minute, bis zu 30 auf einmal; darüber `429` mit `Retry-After`.
  Die App braucht weit weniger: ein `SUBSCRIBE` beim Start, ein `POLL` alle 5 Minuten (bzw. nach der halben
  Abo-Zeit) und ein `SUBSCRIPTION_CHANGE`, wenn sich die Karten um Ansicht, Position oder Route ändern.
  Fehlversuche mit falschem Token global ≤ 5/s (Anfragen ganz ohne Token zählen nicht, bekommen aber nie mehr als
  `401`).
- **`/healthz`** (ohne Token) antwortet nur `ok` – für Monitoring, ohne Zahlen. Den ausführlichen Zustand
  (Meldungen, Abos, Quellen, Fehler) gibt es unter **`/status`**, nur mit Token.
- **Logs**: pro Anfrage Methode, Pfadklasse (`traff`, `status`, `other`), Status, Dauer, **Label** des Tokens und
  ggf. der Ablehnungsgrund (`auth`, `rate_limit`, `unauthorized_flood`); mit `-log-level debug` zusätzlich
  TraFF-Operation, Status und Zahl der Meldungen. Nie das Token, nie Filter/bbox oder andere Koordinaten der
  Anfrage, nie Subscription-IDs, User-Agents oder Client-Adressen (auch die Fehlermeldungen von `net/http` werden
  bereinigt, `internal/logging`). `/healthz` wird nicht protokolliert.
- Abos liegen nur im Arbeitsspeicher und verfallen nach Inaktivität (`subscription-ttl`). Die Subscription-ID ist
  zufällig und nicht erratbar; sie wirkt nur zusammen mit einem gültigen Token.
- **Container**: ohne Capabilities, `no-new-privileges`, schreibgeschütztes Dateisystem, Speicher- und CPU-Limit
  (`compose.yaml`). Das Image (distroless, ohne Shell) sieht nur die Token-Datei, die Env-Datei und das
  Secrets-Verzeichnis (nur lesend). Ausgehendes Netz braucht es für die Quellen.
- **TLS** macht der Reverse Proxy; der Server lauscht über `compose.yaml` nur auf `127.0.0.1:18400`
  (`TRAFF_BIND`/`TRAFF_PORT`), ohne Container mit `TRAFF_LISTEN=127.0.0.1:18400`.

### Reverse Proxy (Beispiel Caddy)

```caddyfile
traff.example.org {
	# Die TraFF-Schnittstelle und der Zustand; /healthz nur intern (Monitoring direkt auf 127.0.0.1:18400).
	@traff path / /traff /status
	handle @traff {
		reverse_proxy 127.0.0.1:18400
	}
	handle {
		respond 404
	}
	# Caddy schreibt Zugriffslogs nur mit "log"; auch dann maskiert es den Authorization-Header
	# (log_credentials ist aus). Koordinaten stehen nur im Body (XML), nie in der URL.
}
```

## Schnellstart (Betrieb mit Podman)

Voraussetzung: Podman mit `podman-compose` (in einer Distrobox wird der Host über `distrobox-host-exec`
angesprochen; Go wird nur für `new-token.sh` und die Tests gebraucht).

```sh
cd services/traff-server
scripts/new-token.sh pixel            # Token für ein Gerät anlegen, wird einmal angezeigt
scripts/run.sh                        # Image bauen (beim ersten Mal) und starten, wartet auf /healthz
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18400/status
scripts/stop.sh                       # Container stoppen, Konfiguration bleibt
```

Die Skripte (`scripts/common.sh`) lesen diese Umgebungsvariablen:

| Variable | Standard | Bedeutung |
|----------|----------|-----------|
| `TRAFF_BIND` / `TRAFF_PORT` | `127.0.0.1` / `18400` | Veröffentlichter Port (im Emulator: `http://10.0.2.2:18400`) |
| `TRAFF_TOKENS_FILE` | `~/.config/bettermaps-traff/tokens` | Token-Hashes (`scripts/new-token.sh` hängt Zeilen an) |
| `TRAFF_ENV_FILE` | `~/.config/bettermaps-traff/traff.env` | Quellen und Zugangsdaten als `KEY=value`-Zeilen (`env_file` des Containers); wird leer angelegt = nur Autobahn-API |
| `TRAFF_SECRETS_DIR` | `~/.config/bettermaps-traff/secrets` | Client-Zertifikate, Schlüssel, Passwortdateien; im Container nur lesend unter `/secrets` |
| `TRAFF_MEMORY` | `256m` | Speicherlimit |
| `TRAFF_RATE_PER_MINUTE` / `TRAFF_RATE_BURST` | `60` / `30` | Ratenlimit pro Token |
| `TRAFF_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

Beispiel `traff.env` mit Mobilithek (Pfade aus Sicht des Containers):

```sh
TRAFF_DATEX2_FEEDS=nrw-lms=https://mobilithek.info:8443/…<Abo-URL>…
TRAFF_DATEX2_NRW_LMS_CLIENT_CERT=/secrets/mobilithek.crt
TRAFF_DATEX2_NRW_LMS_CLIENT_KEY=/secrets/mobilithek.key
TRAFF_USER_AGENT=better-maps-traff-server/0.1 (+https://github.com/WayneNani/better-maps)
```

`TRAFF_LISTEN`, `TRAFF_TOKENS_FILE` und die Ratenlimits setzt `compose.yaml` selbst; in der Env-Datei haben sie
keine Wirkung. Ein Image für andere Deployments entsteht mit `podman build -f Containerfile .` (Einstiegspunkt
`traff-server`, lauscht im Container auf `:8080`, braucht `TRAFF_TOKENS_FILE`).

### App einrichten

Einstellungen → **Verkehr**: „Live-Verkehrsdaten“ einschalten, zuerst die **Verkehrsdienst-URL** (z. B.
`https://traff.example.org/`, im Emulator `http://10.0.2.2:18400/`), dann das **Zugangs-Token** des Geräts
eintragen. Wie bei den anderen Servern: in der APK steht kein Token, `http://` geht nur für lokale Adressen (die URL
wird sonst abgelehnt), das Token wird nur als „Gesetzt“/„Nicht gesetzt“ angezeigt, nie geloggt und nicht gesichert
(app-private SharedPreferences `traffic_server`; die URL selbst liegt wie bisher in `settings.ini`).

Das Token ist an den Server gebunden, für den es eingetragen wurde (Schema, Host und Port der URL), und geht nur
dorthin:

- Wechselt die URL auf einen anderen Server (anderer Host, Port oder Schema), löscht die App das Token und weist
  darauf hin; für den neuen Server muss es neu eingetragen werden. Ein anderer Pfad auf demselben Server behält es.
- An den eingebauten öffentlichen Dienst (`https://api.dev.traffxml.org`, Standard-URL ohne Eintrag) geht nie ein
  Token; solange er eingestellt ist, lehnt die App die Token-Eingabe ab. Der Core prüft das zusätzlich
  (`Framework::TrafficHttpTokenFor`).
- Ein leeres Eingabefeld löscht das gespeicherte Token.

Die App schickt das Token als `Authorization: Bearer` mit jeder TraFF-Anfrage (`libs/traffxml/traff_source.cpp`,
`PrepareHttpRequest`); eine Änderung von Token oder URL baut die HTTP-Quelle neu auf.

## Lokal starten (Entwicklung)

Lokal mit Go 1.24, ohne Zugangskontrolle (nur auf dem eigenen Rechner!):

```sh
export TRAFF_INSECURE_NO_AUTH=1
go run ./cmd/traff-server                     # alle Autobahnen, Port 8080
go run ./cmd/traff-server -autobahn-roads A7,A9 -log-level debug

# Zusätzlich die frei zugänglichen DATEX-II-Feeds aus Baden-Württemberg
TRAFF_DATEX2_FEEDS="bw-incidents=https://api.mobidata-bw.de/datasets/traffic/incidents/incidents_lmsbw.datex2.xml,bw-roadworks=https://api.mobidata-bw.de/datasets/traffic/roadworks/roadworks_svzbw.datex2.xml" \
  go run ./cmd/traff-server

# Zusätzlich die Staus des Crowd-Servers (services/crowd-traffic, dort mit -listen :8081 gestartet)
TRAFF_CROWD_URL=http://127.0.0.1:8081 go run ./cmd/traff-server
```

Mit Token-Datei (wie im Betrieb):

```sh
go run ./cmd/traff-server token laptop        # druckt token=… und line=… (Zeile für die Token-Datei)
go run ./cmd/traff-server -tokens-file ~/.config/bettermaps-traff/tokens -listen 127.0.0.1:18400
```

Als Container (Podman):

```sh
podman build -t traff-server -f Containerfile .
podman run --rm -p 127.0.0.1:8080:8080 -e TRAFF_INSECURE_NO_AUTH=1 -e TRAFF_AUTOBAHN_ROADS=A7,A9 traff-server
```

Das Image ist mehrstufig gebaut, enthält nur das statische Binary (ca. 11 MB, `distroless/static`) und läuft
als Benutzer 65532 (non-root).

Der erste vollständige Abruf aller Autobahnen dauert wegen der Drosselung etwa 1,5–2 Minuten
(rund 110 Autobahnen × 3 Dienste, mindestens 250 ms Abstand zwischen zwei Anfragen). Bis dahin sind die Feeds leer.

## Konfiguration

Jede Option gibt es als Flag und als Umgebungsvariable; das Flag hat Vorrang.

| Flag | Umgebungsvariable | Standard | Bedeutung |
|------|-------------------|----------|-----------|
| `-listen` | `TRAFF_LISTEN` | `:8080` | Adresse des HTTP-Servers, z. B. `127.0.0.1:18400` |
| `-tokens-file` | `TRAFF_TOKENS_FILE` | – (Pflicht) | Datei mit den Token-Hashes der Geräte (`<label> sha256:<hex>` pro Zeile, `#` = Kommentar) |
| – | `TRAFF_INSECURE_NO_AUTH` | `false` | `1` = ohne Token-Datei und ohne Zugangskontrolle starten (nur lokale Entwicklung/Tests, Warnung im Log) |
| `-reload-tokens` | `TRAFF_RELOAD_TOKENS` | `30s` | Token-Datei so oft auf Änderungen prüfen, `0` = nur bei `SIGHUP` |
| `-rate-per-minute` | `TRAFF_RATE_PER_MINUTE` | `60` | Anfragen pro Minute und Token im Mittel, `0` = unbegrenzt |
| `-rate-burst` | `TRAFF_RATE_BURST` | `30` | Anfragen pro Token auf einmal |
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
| `-datex2-feeds` | `TRAFF_DATEX2_FEEDS` | leer = aus | DATEX-II-Feeds als Liste `name=URL` (Komma oder Zeilenumbruch getrennt; Name aus `a-z0-9-_`). URL: `https://`, `http://` oder `file://` |
| `-datex2-interval` | `TRAFF_DATEX2_INTERVAL` | `0` | Mindestabstand zwischen zwei Downloads eines Feeds; `0` = bei jedem Abruf (`poll-interval`). Dazwischen wird die letzte Publikation neu bewertet |
| `-datex2-horizon` | `TRAFF_DATEX2_HORIZON` | `168h` | Meldungen, die später als jetzt + Horizont beginnen, werden noch nicht ausgeliefert (`0` = alle) |
| `-datex2-timeout` | `TRAFF_DATEX2_TIMEOUT` | `1m` | Zeitlimit für einen Download |
| `-dedup` | `TRAFF_DEDUP_ENABLED` | `true` | Dubletten zwischen Quellen unterdrücken |
| `-dedup-distance` | `TRAFF_DEDUP_DISTANCE` | `500` | Höchstabstand in Metern, bis zu dem zwei Meldungen als derselbe Ort gelten |
| `-dedup-spatial-distance` | `TRAFF_DEDUP_SPATIAL_DISTANCE` | `250` | Höchstabstand in Metern für Stau-Dubletten ohne Straßennummer (Crowd-Daten), `0` = aus |
| `-crowd-url` | `TRAFF_CROWD_URL` | leer = aus | Basis-URL des Crowd-Servers, z. B. `http://crowd-traffic:8080`; abgerufen wird `/v1/segments` (eine Query wie `?bbox=…` bleibt erhalten) |
| `-crowd-interval` | `TRAFF_CROWD_INTERVAL` | `2m` | eigenes Abrufintervall der Crowd-Quelle (mind. 10 s), unabhängig von `poll-interval` |
| `-crowd-timeout` | `TRAFF_CROWD_TIMEOUT` | `20s` | Zeitlimit für einen Abruf |
| `-crowd-merge-distance` | `TRAFF_CROWD_MERGE_DISTANCE` | `25` | Toleranz in Metern zwischen Ausfahrts- und Einfahrtspunkt benachbarter Stau-Kacheln; `0` = nicht zusammenfassen (eine Meldung pro Kachel) |
| `-crowd-max-length` | `TRAFF_CROWD_MAX_LENGTH` | `5000` | Höchstlänge einer zusammengefassten Meldung in Metern, `0` = unbegrenzt |

`traff-server token <label>` erzeugt ein Token (256 Bit) und die Zeile für die Token-Datei;
`scripts/new-token.sh <label>` erledigt beides. `SIGHUP` lädt die Token-Datei sofort neu.

Zugangsdaten für DATEX-II-Feeds gibt es **nur** als Umgebungsvariablen (Flags wären in der Prozessliste sichtbar).
`<NAME>` ist der Feed-Name in Großbuchstaben, `-` wird zu `_` (Feed `bw-incidents` → `TRAFF_DATEX2_BW_INCIDENTS_…`):

| Umgebungsvariable | Bedeutung |
|-------------------|-----------|
| `TRAFF_DATEX2_<NAME>_USERNAME`, `TRAFF_DATEX2_<NAME>_PASSWORD` | HTTP-Basic-Auth, nur für diesen Feed (wird bei Weiterleitungen auf einen anderen Host nicht mitgeschickt) |
| `TRAFF_DATEX2_<NAME>_PASSWORD_FILE` | Passwort aus Datei (z. B. Podman-/Docker-Secret), statt `…_PASSWORD` |
| `TRAFF_DATEX2_<NAME>_CLIENT_CERT`, `TRAFF_DATEX2_<NAME>_CLIENT_KEY` | TLS-Client-Zertifikat und Schlüssel (PEM-Dateien), z. B. Mobilithek-Maschinenzertifikat |
| `TRAFF_DATEX2_CLIENT_CERT`, `TRAFF_DATEX2_CLIENT_KEY` | dito für alle Feeds ohne eigenes Zertifikat |
| `TRAFF_DATEX2_<NAME>_CA_CERT`, `TRAFF_DATEX2_CA_CERT` | zusätzliche CA (PEM) für den Server des Feeds |

## Schnittstelle

Alle TraFF-Anfragen gehen per `POST` als XML an **eine** URL: `/` (Alias `/traff`), immer mit
`Authorization: Bearer <token>`. TraFF-Fehler werden mit HTTP 200 und einem TraFF-Status gemeldet, wie es die
Spezifikation (Abschnitt 6.1) verlangt. HTTP-Fehler: `401` (Token fehlt oder falsch), `429` (Ratenlimit, mit
`Retry-After`), `413` (Anfrage > 1 MB), `405`.

`GET /healthz` antwortet ohne Token nur mit `ok` (für Monitoring). `GET /status` (mit Token) liefert den Zustand als
JSON: Anzahl Meldungen, Aufhebungen, Abos, `duplicates_suppressed` (im letzten
Abruf unterdrückte Dubletten) und je Quelle letzter Abruf, Fehler, `messages`, `duplicates`, `duplicates_total`
sowie bei DATEX-II-Feeds `details` (Downloads, `not_modified`, `fetch_errors`, Situationen, Records, Meldungen und
übersprungene Records nach Grund, z. B. `skipped_alertc_only`). Die Crowd-Quelle (`crowd`) meldet in `details`
`downloads`, `fetch_errors`, `segments` (Abschnitte im Export), `congested_segments`, `skipped_free_flow`,
`skipped_invalid`, `skipped_expired`, `messages` (nach dem Zusammenfassen), `joins`, `corner_joins`, `forks`,
`merges`, `splits`, `longest_chain` (Kacheln) und `export_age_seconds` (Alter des verwendeten Exports).

Unterstützte Operationen: `SUBSCRIBE`, `SUBSCRIPTION_CHANGE`, `POLL` (mit Abo, oder ohne Abo als
„One-Shot“-Abfrage mit `filter_list`), `HEARTBEAT`, `UNSUBSCRIBE`, `GET_CAPABILITIES`.
`FEED` (Push vom Client) wird mit `PUSH_REJECTED` beantwortet.

### Beispiele mit curl

`$TOKEN` ist ein Geräte-Token (`scripts/new-token.sh`); lokal mit `TRAFF_INSECURE_NO_AUTH=1` geht es auch ohne.

Abo für München und Umgebung anlegen (der erste Feed enthält alle passenden Meldungen):

```sh
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' http://localhost:8080/ --data-binary '
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
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="POLL" subscription_id="3f0c…9a1e"/>'
```

Filter ändern, Abo am Leben halten, abbestellen, Fähigkeiten abfragen:

```sh
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="SUBSCRIPTION_CHANGE" subscription_id="3f0c…9a1e"><filter_list><filter bbox="53.3 9.7 53.7 10.3"/></filter_list></request>'
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="HEARTBEAT" subscription_id="3f0c…9a1e"/>'
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="UNSUBSCRIBE" subscription_id="3f0c…9a1e"/>'
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="GET_CAPABILITIES"/>'
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/status
curl -s http://localhost:8080/healthz          # ohne Token: nur "ok"
```

Einmalabfrage ohne Abo (z. B. zum Erzeugen von Testdaten für das TraFF-Assessment-Tool):

```sh
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/ --data '<request operation="POLL"><filter_list><filter bbox="53.3 9.7 53.7 10.3"/></filter_list></request>'
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
eine Aufhebung. Fällt die Quelle aus, bleiben die letzten Daten bis zum Ablauf der Frist erhalten (Crowd-Quelle:
nur bis zum Ablauf der Crowd-Daten, siehe unten).

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

### DATEX II

**Quellen (recherchiert am 25.10.2026).** Der nationale Zugangspunkt [Mobilithek](https://mobilithek.info) listet
über 100 DATEX-II-Angebote mit Verkehrsmeldungen und Baustellen. Fast alle – auch die als „Open Data“ lizenzierten –
lassen sich nur über ein **Abonnement mit Maschinenzertifikat** abrufen (`mobilithek.info:8443` verlangt ein
TLS-Client-Zertifikat). Ohne Anmeldung geprüft:

| Quelle | Inhalt | Zugang | Koordinaten |
|--------|--------|--------|-------------|
| MobiData BW, [Verkehrsmeldungen BW](https://api.mobidata-bw.de/datasets/traffic/incidents/incidents_lmsbw.datex2.xml) (Landesmeldestelle BW) | Unfälle, Staus, Sperrungen, Gefahren, Baustellen; DATEX II 2.0, ~40–50 Situationen, ETag/gzip | **frei**, dl-de/by-2-0 | ja (TPEG, GML, OpenLR) |
| MobiData BW, [Baustellen BW](https://api.mobidata-bw.de/datasets/traffic/roadworks/roadworks_svzbw.datex2.xml) (Straßenbauverwaltung BW, MDM-Profil) | Baustellen auf Bundes-, Landes-, Kreisstraßen; ~600 Situationen | **frei**, dl-de/by-2-0 | ja (GML `posList`) |
| [Karlsruhe](https://mobil.trk.de/datex/datex.xml) (TechnologieRegion Karlsruhe) | städtische Baustellen | **frei**, CC BY 4.0 | ja; Datei zuletzt im August 2026 aktualisiert |
| Mobilithek, „Arbeitsstellen auf deutschen Autobahnen“ (BASt, Angebot 608447464874934272) | Autobahn-Baustellen, ~120 MB | **frei** (`/mdp-api/mdp-conn-server/v1/publication/<id>/file/noauth`) | **nein**, nur BISSTRA-Netzknoten → wird übersprungen |
| Mobilithek, Rüsselsheim (110000000003176000), Kreis Unna (930448553981730816) | kommunale Baustellen | **frei** (wie oben) | ja (LinearElementByPoints bzw. UTM 32N) |
| Mobilithek, Landesmeldestellen NRW/„Deutschland – Gesamtdatensatz“ (648516815296524288), Bayern, Berlin, Hamburg, Schleswig-Holstein, BW, Hessen/VMZ RheinMain, Autobahn GmbH „Verkehrsmeldungen mit Verlustzeiten“ (110000000003091005), Baustellen der Länder | Verkehrsmeldungen, Baustellen | **Abo + Maschinenzertifikat** (meist kostenlos, teils mit Freigabe des Datengebers) | je nach Angebot |

Ohne Zugangsdaten funktionieren damit live: die beiden Feeds von MobiData BW (getestet, im Container), Rüsselsheim,
Kreis Unna und Karlsruhe. Ein freier, bundesweiter DATEX-II-Feed für Verkehrsmeldungen mit Koordinaten existiert
nicht; dafür braucht es die Mobilithek (siehe unten).

**Lesen.** Der Parser (`internal/source/datex2`) arbeitet mit lokalen Elementnamen und liest so DATEX II v2
(`d2LogicalModel`/`payloadPublication`, inkl. MDM-Erweiterungen) und v3 (`payload`, auch in `messageContainer`).
Er liest Situationen einzeln (Streaming), versteht gzip (per `Content-Encoding` oder `.xml.gz`), ISO-8859-1 und fragt
mit `If-None-Match`/`If-Modified-Since` nach. Ein fehlerhafter Download oder eine kaputte Publikation lässt die
letzten Meldungen des Feeds stehen; andere Quellen sind nicht betroffen. Der Server geht davon aus, dass jede
Publikation vollständig ist (`allElementUpdate`/`snapshotPull`); fehlt ein Record, wird er aufgehoben. Bei
Delta-Feeds wird gewarnt.

**Ortsangaben** in absteigender Priorität: GML-Linie (`posList` „lat lon“, auch ETRS89/UTM 32N/33N; `coordinates`
„lon,lat“), `linearByCoordinates`, MDM-`LinearElementByPoints`, TPEG-Linie (`from`/`to`), OpenLR-Referenzpunkte,
Punkt (`pointByCoordinates`, TPEG-Punkt), zuletzt `locationForDisplay`/`coordinatesForDisplay`. Bei Gruppen und
Itineraren zählt das erste Element mit Koordinaten. Ohne Koordinaten werden Records übersprungen und gezählt:
ALERT-C (`skipped_alertc_only`; die Location Code List der BASt ist nicht frei verfügbar), Linearreferenzen auf
BISSTRA (`skipped_linear_reference_only`), Flächen wie „ganz Baden-Württemberg“ (`skipped_area_location`).

**Abbildung.** Records einer Situation mit gleicher Ortsangabe werden zu **einer** TraFF-Meldung zusammengefasst
(z. B. „Fahrstreifen gesperrt“ + „Baustelle“), ihre ID ist `de.datex2.<feed>:<situationRecordId>` des ersten Records.

| DATEX II (`xsi:type` / Untertyp) | TraFF |
|---|---|
| `Accident` (`accidentType`) | `INCIDENT_ACCIDENT`, `_SERIOUS_ACCIDENT`, `_MULTI_VEHICLE_ACCIDENT`, `_HGV_ACCIDENT`, `_OVERTURNED_VEHICLE`, … |
| `AbnormalTraffic` (`abnormalTrafficType`) | `CONGESTION_STATIONARY_TRAFFIC`, `_QUEUE`, `_SLOW_TRAFFIC`, `_HEAVY_TRAFFIC`, sonst `CONGESTION_TRAFFIC_PROBLEM` |
| `RoadOrCarriagewayOrLaneManagement` | `roadClosed` → `RESTRICTION_CLOSED`, `carriagewayClosures` → `_CARRIAGEWAY_CLOSED`, `laneClosures` → `_LANE_CLOSED q_int=numberOfLanesRestricted`, `narrowLanes`, `contraflow`, `singleAlternateLineTraffic`, `rampClosures`, `hardShoulderRunningInOperation`, `newRoadworksLayout` → `CONSTRUCTION_NEW_ROADWORKS_LAYOUT`, sonst `RESTRICTION_RESTRICTIONS` |
| `MaintenanceWorks` (`roadMaintenanceType`), `ConstructionWorks` | `CONSTRUCTION_ROADWORKS`, `_RESURFACING_WORK`, `_REPAIR`, `_MAINTENANCE`, `_CONSTRUCTION_WORK`, `_DEMOLITION_WORK`, … |
| `VehicleObstruction`, `GeneralObstruction`, `AnimalPresenceObstruction`, `EnvironmentalObstruction`, `InfrastructureDamageObstruction` | `INCIDENT_BROKEN_DOWN_VEHICLE`, `_VEHICLE_FIRE`, `HAZARD_WRONG_WAY_DRIVER`, `_OBJECT_ON_ROAD`, `_PEOPLE_ON_ROAD`, `_ANIMALS_ON_ROAD`, `_FALLEN_TREE`, `_FLOODING`, `_GAS_LEAK`, …, sonst `HAZARD_OBSTRUCTION` |
| `PoorEnvironmentConditions` | `WEATHER_FOG`, `_DENSE_FOG`, `_HEAVY_RAIN`, `_SNOWFALL`, `_GUSTY_WIND`, …, sonst `HAZARD_DIFFICULT_CONDITIONS` |
| `NonWeatherRelatedRoadConditions`, `WeatherRelatedRoadConditions` | `HAZARD_OIL_ON_ROAD`, `_BLACK_ICE`, `_SNOW_ON_ROAD`, …, sonst `HAZARD_BAD_ROAD` / `_SLIPPERY_ROAD` |
| `SpeedManagement` mit `temporarySpeedLimit` | `RESTRICTION_SPEED_LIMIT speed=…` |
| `AuthorityOperation` | `accidentInvestigationWork` → `INCIDENT_INVESTIGATION`, Kontrollen → `AUTHORITY_CHECKPOINT`, sonst `HAZARD_POLICE_ACTIVITY` |
| `impact/delays/delayTimeValue` (s) | zusätzlich `DELAY_DELAY q_duration=hh:mm` |
| Andere Typen (`ReroutingManagement`, `Conditions`, `GeneralNetworkManagement`, …) | übersprungen (`skipped_unsupported_type`) |
| `roadNumber` bzw. Ortsbeschreibung „A8 Stuttgart Richtung Karlsruhe zwischen …“ | `road_ref="A 8"`, `origin`, `destination`; Straßenklasse aus A/B/L/K bzw. OpenLR-FRC; Straßenname nur ohne Nummer |
| `bothDirections`, TPEG `bothWays`, ALERT-C `both` | `directionality="BOTH_DIRECTIONS"` |
| `overallStartTime`/`overallEndTime`, laufender oder nächster `validPeriod` | `start_time`/`end_time`; beendete Records entfallen |
| `validityStatus=suspended`, `lifeCycleManagement/cancel` oder `end` | Record entfällt → TraFF-Aufhebung (`cancellation="true"`) |
| `urgency` `urgent`/`extremelyUrgent` | `urgency="URGENT"`/`"X_URGENT"` |

Alle verwendeten Ereignistypen stehen in der TraFF-Ereignisliste (vom Test geprüft). Der App-Client kennt derzeit nur
`CONGESTION_*`, `DELAY_*` und Teile von `RESTRICTION_*`; reine Unfall-, Gefahren- oder Wettermeldungen ohne diese
Ereignisse verwirft er (siehe unten).

**Versionen und Aufhebungen.** DATEX-Versionsnummern werden nicht gebraucht: Der Store vergleicht den Inhalt jeder
Meldung und vergibt bei Änderungen eine neue Version; verschwundene Records werden aufgehoben.

### Crowd-Daten (`services/crowd-traffic`)

Der Crowd-Server veröffentlicht Staus aus anonymen Meldungen der App, erst ab k = 10 Meldungen pro Abschnitt und
15-Minuten-Fenster (Datenschutzkonzept: [docs/CROWD_TRAFFIC_PRIVACY.md](../../docs/CROWD_TRAFFIC_PRIVACY.md)). Ein
Abschnitt ist die Durchfahrt einer Web-Mercator-Kachel der Zoomstufe 17 (in Deutschland ≈ 180–210 m) von einer
Kante zur anderen, getrennt nach Straßenklasse; der Export enthält **eine Meldung pro Kachel**. Die Quelle
(`internal/source/crowd`) fasst sie zusammen, damit die App statt hunderter Kleinstmeldungen wenige Staumeldungen
bekommt.

**Abruf.** `GET <TRAFF_CROWD_URL>/v1/segments` (JSON, Version 1) alle 2 Minuten, als **vollständiger
Schnappschuss**: Was fehlt, hebt der Store auf. Der JSON-Export wird dem TraFF-Feed `/v1/traff` vorgezogen, weil er
Kachel, Ein- und Ausfahrtskante und Straßenklasse als eigene Felder enthält (im TraFF-Feed stecken sie nur in der
Meldungs-ID), dazu die ungerundete Geschwindigkeit, das Ablaufdatum jedes Abschnitts und eine Formatversion.
Verwendet werden Abschnitte mit `congested` und `confident`; ungültige (Zoomstufe, Kanten, Klasse, Koordinaten,
Zustand) und abgelaufene werden gezählt und übersprungen. Eine andere Formatversion gilt als Fehler.

**Zusammenfassen.** Abschnitt A wird mit Abschnitt B verkettet, wenn

1. beide dieselbe Straßenklasse haben,
2. B in der Nachbarkachel jenseits der Ausfahrtskante von A beginnt, und zwar an der gegenüberliegenden Kante
   (damit gleiche Fahrtrichtung; die Gegenrichtung beginnt an der anderen Kante und wird nie angehängt),
3. Ausfahrtspunkt von A und Einfahrtspunkt von B höchstens `TRAFF_CROWD_MERGE_DISTANCE` (25 m) auseinanderliegen
   (die Punkte sind die häufigsten gemeldeten Kantenpositionen, Raster ≈ 6 m).

Hat A keinen solchen Nachfolger, darf B auch in der **diagonal** angrenzenden Kachel liegen, wenn die Punkte
höchstens 50 m + Toleranz auseinanderliegen: Die App meldet Eck-Durchfahrten unter 50 m nicht, die Straße läuft dann
scheinbar „über Eck“ weiter (im aufgezeichneten A100-Stau 2 von 11 Verbindungen). Eine Verbindung wird nur
übernommen, wenn sie eindeutig ist: Bei **Verzweigungen** (mehrere Nachfolger) und **Zusammenführungen** (mehrere
Vorgänger) endet die Kette, jeder Ast wird eine eigene Meldung. **Lücken** (Kachel ohne Stau, frei oder unter k)
trennen ebenfalls. Geschlossene Ringe werden vor dem Abschnitt mit dem kleinsten Schlüssel aufgeschnitten. Ketten
über `TRAFF_CROWD_MAX_LENGTH` (5 km) werden vom Stauende (stromabwärts) her in Stücke geteilt, damit der Decoder der
App zwischen `from`, `via` und `to` sicher der Straße folgt.

**Abbildung einer Kette.**

| Feld | Wert |
|---|---|
| `id` | `de.crowd:17/<x>/<y>/<Einfahrt>-<Ausfahrt>/<Klasse>` des **stromabwärts letzten** Abschnitts. Der Stauanfang (Engstelle) bleibt meist stehen, während das Stauende wächst und schrumpft – die ID bleibt dann gleich, die Meldung wird nur aktualisiert. Verschwindet der vorderste Abschnitt, wird die Meldung aufgehoben und der Rest bekommt eine neue ID. |
| `from`, `to` | Einfahrtspunkt des ersten, Ausfahrtspunkt des letzten Abschnitts (auf der Straße) |
| `via` | Übergang zwischen zwei Abschnitten, der der halben Kettenlänge am nächsten liegt (ab zwei Abschnitten) |
| `road_class`, `directionality` | Straßenklasse der Abschnitte, `ONE_DIRECTION`; keine Straßennummer (kennt der Crowd-Server nicht) |
| Ereignis | ein `CONGESTION_*` mit dem **längengewichteten** Zustand (dichter Verkehr 1, zähfließend 2, Stau 3, stehender Verkehr 4; Mittel kaufmännisch gerundet) |
| `speed` | **längengewichtetes harmonisches Mittel** der Abschnittsgeschwindigkeiten (km/h, mind. 1) = die Geschwindigkeit, die dieselbe Fahrzeit über die Kette ergibt. Die App wendet `speed` als Höchstgeschwindigkeit auf den ganzen Ort an; das Minimum würde die Verzögerung eines langen Staus mit kurzem Stillstandskern stark überschätzen. |
| Zeiten | wie bei allen Quellen vom Store (`receive_time` = erstes Auftreten, Frist `message-lease`) |

**Ausfall.** Scheitert ein Abruf (Netz, HTTP-Fehler, Zeitlimit, kaputtes JSON), verwendet die Quelle den letzten
Export weiter, aber ohne die inzwischen abgelaufenen Abschnitte (`expires`, 20 min nach der Auswertung): Ein
ausgefallener Crowd-Server hält keine alten Staus am Leben, sie werden spätestens 20 Minuten nach der letzten
Auswertung aufgehoben. `/status` zeigt dann `last_error` und `export_age_seconds`. Vor dem ersten erfolgreichen
Abruf liefert die Quelle nichts.

**Datenschutz.** Der Export enthält nur Aggregate (Zustand, Median, Zahl der Meldungen ≥ k) ohne Bezug zu
einzelnen Fahrten; der traff-server übernimmt davon nur Ort, Klasse, Zustand und Geschwindigkeit, nicht die Zahl
der Meldungen. Er protokolliert keine Abschnitte.

### Dubletten

Mehrere Quellen melden oft dasselbe Ereignis (die Autobahn-API und die Polizei-Landesmeldestelle denselben Stau,
Baustellen-Feeds und Polizeimeldungen dieselbe Sperrung). Der Runner führt vor dem Speichern einen
Zusammenführungsschritt aus (`internal/dedup`): Eine Meldung wird **unterdrückt**, wenn sie eine aktive Meldung einer
Quelle mit höherer Priorität dupliziert. Priorität = Reihenfolge der Quellen: Autobahn-API vor allen DATEX-II-Feeds,
DATEX-II-Feeds in der Reihenfolge von `TRAFF_DATEX2_FEEDS`, die Crowd-Quelle **zuletzt** (amtliche Meldungen gewinnen).
Zwei Meldungen **mit Straßennummer** sind Dubletten, wenn **alle** Bedingungen gelten:

1. **Gleiche Straße:** beide `road_ref` sind gleich nach Normalisierung (`A 8` = `A8`).
2. **Gleiche Ereignisklasse:** die Kategorien überschneiden sich – Unfall/Gefahr (`INCIDENT`, `HAZARD`), Stau
   (`CONGESTION`, `DELAY`), Baustelle (`CONSTRUCTION`), Wetter. `RESTRICTION_*` zählt nur, wenn sonst nichts gemeldet
   ist (dann als Baustelle/Sperrung), damit eine unfallbedingte Fahrstreifensperrung nicht eine Baustelle verdeckt.
3. **Räumliche Nähe:** kürzester Abstand der Linien (`from`–`via`–`to` bzw. Punkt `at`) ≤ `TRAFF_DEDUP_DISTANCE`
   (500 m).
4. **Gleiche Richtung:** bei zwei einseitigen Meldungen mit mindestens 50 m Ausdehnung weichen die Richtungen um
   höchstens 90° ab; `BOTH_DIRECTIONS` passt immer.
5. **Überlappende Zeit:** `[start_time, end_time)` überschneiden sich (fehlende Grenzen sind offen).

Fehlt einer der beiden Meldungen die Straßennummer (Crowd-Staus haben nur die Straßenklasse), wird **nur für Staus**
räumlich verglichen. Meldung B wird als Dublette der höher priorisierten Meldung A unterdrückt, wenn

1. B nur Stau-Ereignisse enthält (`CONGESTION_*`, `DELAY_*`) und A ebenfalls einen Stau meldet,
2. beide dieselbe Straßenklasse haben (fehlt sie, nie),
3. **alle** Bezugspunkte von B (`from`, `via`, `to`) höchstens `TRAFF_DEDUP_SPATIAL_DISTANCE` (250 m) von A entfernt
   liegen – A deckt B ab; eine lange Crowd-Kette wird von einer kurzen amtlichen Meldung nicht verdeckt (beide bleiben),
4. Richtung und Zeit wie oben passen.

Die 250 m decken ab, dass die Autobahn-API nur `from`/`via`/`to` liefert und die Linie dazwischen in Kurven von der
Straße abweicht; Gleichheit der Straßenklasse und Richtung verhindert, dass ein Stau auf einer parallelen
Bundesstraße verschluckt wird. Verschwindet die Meldung der höheren Quelle, erscheint die bisher unterdrückte beim
nächsten Abruf. Unterdrückte
Meldungen zählt `/status` (`duplicates_suppressed`, je Quelle `duplicates`/`duplicates_total`); mit
`-log-level debug` stehen die Paare im Log (`duplicate suppressed id=… duplicate_of=…`). Live am 25.10.2026 mit
A5/A6/A7/A8/A81 und den beiden BW-Feeds: 1 von 54 Polizeimeldungen als Dublette der Autobahn-API erkannt (Unfall mit
Stau auf der A5 Karlsruhe → Heidelberg, gleiche Stelle wie die INRIX-Staumeldung), 5 von 1109 Baustellen als Dublette
von Polizeimeldungen (B 10 in Stuttgart).

### Mobilithek-Zugang einrichten (Betreiber)

Das kann nur der Projektinhaber erledigen (Konto, Vertragsannahme):

1. Auf [mobilithek.info](https://mobilithek.info) als Organisation registrieren.
2. Unter „Maschinenkonten“ ein Maschinenkonto anlegen und ein **Zertifikat beantragen** (die Mobilithek schickt die
   PKCS#12-Datei per E-Mail, das Passwort per SMS) oder ein eigenes, von einer vertrauenswürdigen CA signiertes
   Zertifikat hochladen.
3. Die gewünschten Datenangebote abonnieren, z. B. „LVZ.NRW Verkehrsinformationen der VIZ.NRW für Deutschland –
   Gesamtdatensatz“ (Polizei-Meldungen aller Länder), „Verkehrsmeldungen in Bayern“, „Verkehrsmeldungen mit
   Verlustzeiten und Sperrungen auf BAB“ (Autobahn GmbH) und Baustellen-Angebote der Länder. Manche Angebote brauchen
   die Freigabe des Datengebers. Lizenzbedingungen (Namensnennung) beachten.
4. Die Abruf-URL des Abonnements aus den Abo-Details übernehmen (Pull-Schnittstelle auf `https://mobilithek.info:8443/…`
   mit der Abonnement-ID; der genaue Pfad steht in der Mobilithek, er ist hier nicht geprüft).
5. Zertifikat in PEM umwandeln (Go liest kein PKCS#12):

   ```sh
   openssl pkcs12 -in maschinenkonto.p12 -clcerts -nokeys -out mobilithek.crt          # ggf. -legacy
   openssl pkcs12 -in maschinenkonto.p12 -nocerts -nodes -out mobilithek.key
   chmod 600 mobilithek.key
   ```

6. Server konfigurieren, Zertifikate als Secret einhängen (nie ins Repo): `mobilithek.crt` und `mobilithek.key`
   nach `~/.config/bettermaps-traff/secrets/` (Rechte 600) und in `~/.config/bettermaps-traff/traff.env`:

   ```sh
   TRAFF_DATEX2_FEEDS=nrw-lms=https://mobilithek.info:8443/…<Abo-URL>…,bw-incidents=https://api.mobidata-bw.de/datasets/traffic/incidents/incidents_lmsbw.datex2.xml
   TRAFF_DATEX2_NRW_LMS_CLIENT_CERT=/secrets/mobilithek.crt
   TRAFF_DATEX2_NRW_LMS_CLIENT_KEY=/secrets/mobilithek.key
   ```

   dann `scripts/run.sh`. Ohne die Skripte entsprechend `podman run … -v <secrets>:/secrets:ro,Z
   -v <tokens>:/config/tokens:ro,Z -e TRAFF_TOKENS_FILE=/config/tokens -e TRAFF_DATEX2_…` (siehe `compose.yaml`).

7. Prüfen: `curl -s -H "Authorization: Bearer $TOKEN" 127.0.0.1:18400/status` zeigt für `datex2-nrw-lms`
   `last_error` leer und `details.downloads ≥ 1`.
   Bei HTTP 400/403 fehlt das Zertifikat oder das Abo ist nicht freigegeben.

Feeds mit Basic-Auth (manche Länder-Portale) werden mit `TRAFF_DATEX2_<NAME>_USERNAME` und `…_PASSWORD_FILE`
angebunden.

### Höflichkeit gegenüber der API

Anfragen laufen nacheinander mit mindestens `autobahn-request-interval` Abstand, mit eigenem User-Agent und
`If-None-Match`/`If-Modified-Since` (die API liefert ETags und antwortet mit 304). Scheitert eine Anfrage für eine
Autobahn, werden deren letzte erfolgreiche Daten weiterverwendet; die übrigen Autobahnen sind nicht betroffen.

## Tests

```sh
go vet ./... && go test -race -cover ./...       # oder: tools/better-maps/bm.sh go-test services/traff-server
go test ./internal/source/autobahn ./internal/source/datex2 ./internal/server -update   # Golden-Files nach Prüfung neu schreiben
scripts/smoke.sh                                  # Container bauen, Token erzeugen, starten, 401 ohne Token,
                                                  # Abo/Poll/Change/Unsubscribe per curl mit Token, Log ohne
                                                  # Token/Koordinaten/IPs, lokale DATEX-II-Fixture als zweite Quelle
SMOKE_OFFLINE=1 scripts/smoke.sh                  # dito ohne Netzzugriff auf die Autobahn-API
SMOKE_DATEX2= scripts/smoke.sh                    # ohne DATEX-II-Quelle
scripts/smoke-crowd.sh                            # Container: crowd-traffic + Relay + traff-server im Pod, crowd-sim
                                                  # (A100-Stau), prüft zusammengefasste Meldungen und Aufhebung (~3 min)
```

- Zugang: `internal/auth` (Token-Datei, Hashes, konstante Vergleiche), `internal/ratelimit`,
  `internal/server/access_test.go` (401 ohne/mit falschem Token auf allen Pfaden, Rumpf wird nicht gelesen,
  `/healthz` ohne Token und ohne Inhalt, Ratenlimit pro Token mit `Retry-After`, Fehlversuchsbremse, Log ohne
  Token, bbox, Subscription-ID, User-Agent und Adressen), `cmd/traff-server/auth_test.go` (Start ohne Token-Datei
  verweigert, `TRAFF_INSECURE_NO_AUTH` mit Warnung, `token`-Befehl, Neuladen bei Änderung und `SIGHUP`).
  `tools/better-maps/traffic-e2e.sh` lässt den C++-Client mit Token gegen den Server laufen.
- `internal/source/autobahn/testdata/*.json` sind echte, am 25.10.2026 aufgezeichnete API-Antworten. Die Tests
  greifen nicht aufs Netz zu (die API wird mit `httptest` nachgestellt).
- `internal/source/datex2/testdata` enthält echte Auszüge der MobiData-BW-Feeds (25.10.2026) und selbst erstellte
  v2/v3-Dokumente für Sonderfälle; die Abbildung wird mit Golden-Files geprüft (`testdata/golden`). Client-Tests
  laufen gegen `httptest` (gzip, ETag, Basic-Auth, Weiterleitungen, TLS mit Client-Zertifikat).
- `internal/dedup` testet positive und negative Fälle sowie eine echte Polizeimeldung gegen die zugehörige
  Autobahn-Warnung. `internal/server/twosources_test.go` lässt beide Quellen gegen lokale Fake-Server laufen und prüft
  den Feed auf Dubletten, Aktualisierungen, Aufhebungen und Ausfälle.
- `internal/source/crowd/testdata/a100_segments.json` ist ein echter Export von `crowd-traffic` nach einem Lauf von
  `crowd-sim` (A100-Stau, Fenster 60 s, Seed 7): 105 Abschnitte, davon 12 Stau-Kacheln, die zu einer Meldung werden.
  Die Unit-Tests prüfen Ketten, Lücken, Gegenrichtung, Klassenwechsel, Toleranz, Eck-Verbindungen, Verzweigungen,
  Zusammenführungen, Ringe, Längenbegrenzung, stabile IDs, gewichteten Zustand und Geschwindigkeit sowie Abruffehler
  (HTTP, JSON, Version, Größe, Zeitlimit, Rückfall auf den letzten Export bis zum Ablauf).
  `internal/server/crowd_test.go` lässt Autobahn- und Crowd-Quelle gegen lokale Fake-Server laufen (synthetische
  A100-Warnung `testdata/A100_warning.json`): Zusammenfassen, Unterdrückung durch die amtliche Meldung und
  Wiederkehr, schrumpfendes Stauende (gleiche ID), aufgelöster Stauanfang (Aufhebung, neue ID), Ausfall und Ablauf.
- `internal/clientcheck` bildet die Annahmeregeln des App-Parsers (`libs/traffxml/traff_model_xml.cpp`) in Go nach;
  die HTTP-Ende-zu-Ende-Tests prüfen damit jede Antwort und vergleichen sie mit Golden-Files
  (`internal/server/testdata`).

## Kompatibilität mit dem App-Client (Stand Branch `traffic`)

- Der Client kennt bisher nur Ereignistypen der Klassen `CONGESTION`, `DELAY` und (teilweise) `RESTRICTION`.
  Unbekannte Ereignisse überspringt er; eine Meldung ganz ohne bekanntes Ereignis verwirft er. Baustellen ohne
  Tempolimit oder Sperrung (nur `CONSTRUCTION_ROADWORKS`, `RESTRICTION_LANE_CLOSED`, …) kommen daher derzeit in der
  App nicht an – sie hätten im Client ohnehin keine Auswirkung auf Routing und Einfärbung. Gemessen am
  25.10.2026 für Berlin (A10, A100, A111, A113, A114, A115): 90 Meldungen im Feed, davon 53 vom Client übernommen.
  Erweiterung der Ereignistypen ist ein Upstream-Thema (`libs/traffxml/traff_model.cpp`, `kEventSpeedGroupMap`).
- Der Client braucht mindestens zwei der Punkte `from`/`to`/`at`; Meldungen mit nur einem Punkt (`at`) ignoriert er.
  Das betrifft DATEX-II-Punktmeldungen (z. B. Unfall an einer Stelle).
- DATEX-II-Meldungen, die nur `INCIDENT_*`, `HAZARD_*`, `WEATHER_*`, `CONSTRUCTION_*` oder `AUTHORITY_*` enthalten
  (Unfall ohne Stau oder Sperrung, Gefahr, Wetter), sind gültiges TraFF, werden aber vom aktuellen Client verworfen.
  Sie sind trotzdem im Feed, damit spätere Client-Versionen sie anzeigen können.
- Seit AP4a (Branch `bm/traffic`) abonniert der Client nach `SUBSCRIPTION_UNKNOWN` (z. B. nach einem
  Server-Neustart oder abgelaufenem Abo) sofort neu, und ein gescheitertes Abonnement wird beim nächsten
  Poll-Zeitpunkt wiederholt.
- Der Client pollt alle 5 Minuten, auch nach leeren Feeds; meldet der Server ein `timeout`, pollt er
  spätestens nach der Hälfte davon (Raster des Traffic-Managers: 1 Minute). Die Abo-TTL von 1 h ist
  dafür großzügig bemessen.
- Ende-zu-Ende-Test Server + App-Client ohne Emulator: `tools/better-maps/traffic-e2e.sh`.
