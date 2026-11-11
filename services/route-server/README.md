# Routing-Server (better-maps, serverunterstütztes Auto-Routing)

Eigener Routing-Server für lange Autorouten. Die App fragt ihn, **welchen Weg** eine Route nimmt, und baut die Route
dann wie gewohnt selbst aus den Karten auf dem Handy – nur die teure Suche über die Kartengrenzen hinweg (Leaps)
und die Auswahl unter ~12 Kandidaten entfallen. Abbiegehinweise, Neuberechnung, Blitzer, Fahrspuren und der
Ladeplaner funktionieren unverändert, weil die Route weiterhin eine normale CoMaps-Route ist. Fällt der Server aus
oder passt seine Route nicht zu den Karten, rechnet die App wie bisher allein.

```
App ──HTTPS──► Reverse Proxy (TLS, z. B. Caddy) ──► route-gateway (Go, Token, Ratenlimit) ──► OSRM (intern)
```

- **OSRM** (`ghcr.io/project-osrm/osrm-backend`, Autoprofil, MLD) läuft in einem internen Netz ohne veröffentlichte
  Ports und ohne Internetzugang. Nur das Gateway erreicht es.
- **route-gateway** (`cmd/route-gateway`, nur Go-Standardbibliothek) bietet genau einen Endpunkt `POST /v1/route` an
  und leitet nur Routenanfragen weiter – kein `table`, `trip`, `match`, `nearest` oder `tile`.

## Sicherheit und Datenschutz

Der Server läuft im Heimnetz, ist aber von außen erreichbar. Deshalb:

- **Token pro Gerät**: Jede Anfrage braucht `Authorization: Bearer <token>`. Der Server kennt nur SHA-256-Hashes der
  Tokens (Datei `~/.config/bettermaps-route/tokens`, Rechte 600) und vergleicht in konstanter Zeit. Ohne gültiges
  Token gibt es `401`, ohne dass der Rest der Anfrage angesehen wird. Ein Token wird gesperrt, indem man seine Zeile
  löscht; das Gateway liest die Datei nach spätestens 30 s neu (oder sofort bei `SIGHUP`).
- **Ratenlimit pro Token**: im Mittel 30 Routen/Minute, bis zu 60 auf einmal (der Ladeplaner kann für einen Plan
  ~40 Umwegrouten rechnen). Darüber `429` mit `Retry-After`. Fehlversuche mit falschem Token sind global auf 5/s
  begrenzt.
- **Grenzen**: höchstens 25 Punkte pro Route, Anfrage höchstens 4 KB, nur `application/json`, Zeitlimits für
  Header (5 s), Anfrage (10 s) und OSRM (10 s), höchstens 8 gleichzeitige Anfragen an OSRM (sonst `503`).
- **Logs**: nur Methode, Status, Dauer, Bytes und das **Label** des Tokens (z. B. `device=pixel`). Nie das Token, nie
  Koordinaten, nie Client-Adressen (auch die Fehlermeldungen von `net/http` werden bereinigt).
- **TLS** macht der Reverse Proxy. Das Gateway lauscht standardmäßig nur auf `127.0.0.1:18100`. Die App akzeptiert
  `http://` nur für lokale Adressen (Emulator, LAN), sonst nur `https://`.
- In der App bleibt das Token in den app-privaten Einstellungen (nicht im Backup), wird nie geloggt und nie in die
  APK eingebaut.

### Reverse Proxy (Beispiel Caddy)

```caddyfile
route.example.org {
	# Nur den einen Endpunkt durchreichen.
	@route path /v1/route
	handle @route {
		reverse_proxy 127.0.0.1:18100
	}
	handle {
		respond 404
	}
	# Caddy schreibt Zugriffslogs nur mit "log"; auch dann maskiert es den Authorization-Header
	# (log_credentials ist aus). Koordinaten stehen nur im Body, nie in der URL.
}
```

OSRM selbst darf **nie** veröffentlicht werden (kein `ports:` am Dienst `osrm` in `compose.yaml`).

## Schnellstart

Voraussetzung: Podman mit `podman-compose` (in einer Distrobox wird der Host über `distrobox-host-exec`
angesprochen, wie bei `services/motis`).

```sh
cd services/route-server
scripts/fetch-data.sh                 # Geofabrik-Extrakt, Standard: europe/germany (~4,6 GB)
scripts/import.sh                     # filtern + osrm-extract/partition/customize (siehe Ressourcenbedarf)
scripts/new-token.sh pixel            # Token für ein Gerät anlegen, wird einmal angezeigt
scripts/run.sh                        # OSRM + Gateway starten (baut das Gateway-Image beim ersten Mal)
ROUTE_TOKEN=... scripts/smoke.sh      # Test: 401 ohne/mit falschem Token, Route Bonn -> Nürnberg
scripts/stop.sh                       # Container stoppen, Daten bleiben
```

Für Tests mit kleinem Datensatz: `OSRM_EXTRACT=europe/germany/bremen scripts/fetch-data.sh` usw. (alle Skripte
lesen dieselben Variablen).

### App einrichten

Einstellungen → Navigation → **Routing-Server**: einschalten, Server-Adresse (z. B. `https://route.example.org`)
und das Token des Geräts eintragen. Im Emulator erreicht man den Rechner unter `http://10.0.2.2:18100`.

## Konfiguration

Skripte (`scripts/common.sh`, alles über Umgebungsvariablen):

| Variable | Standard | Bedeutung |
|----------|----------|-----------|
| `OSRM_EXTRACT` | `europe/germany` | Geofabrik-Pfad ohne `-latest.osm.pbf` |
| `OSRM_CACHE` | `~/.cache/bettermaps-osrm` | Downloads (`input/`) und Importe (`<region>/data`) |
| `OSRM_IMPORT_MEMORY` | `12g` | Speicherlimit des Imports (Container) |
| `OSRM_THREADS` | `6` | Threads des Imports |
| `OSRM_PREFILTER` | `1` | Extrakt vorher mit osmium auf Straßen/Fähren/Abbiegeverbote filtern |
| `OSRM_BBOX` | leer | Nur diesen Ausschnitt importieren, `minLon,minLat,maxLon,maxLat` (braucht `OSRM_PREFILTER=1`) |
| `OSRM_MEMORY` / `OSRM_CPUS` | `6g` / `2` | Limits von `osrm-routed` |
| `ROUTE_BIND` / `ROUTE_PORT` | `127.0.0.1` / `18100` | Veröffentlichter Port des Gateways |
| `ROUTE_TOKENS_FILE` | `~/.config/bettermaps-route/tokens` | Token-Hashes |
| `ROUTE_RATE_PER_MINUTE` / `ROUTE_RATE_BURST` | `30` / `60` | Ratenlimit pro Token |
| `ROUTE_MAX_POINTS` | `25` | Höchstzahl Punkte (Start, Zwischenziele, Ziel) |

Gateway (Flag oder Umgebungsvariable, das Flag hat Vorrang): `-listen`/`ROUTE_LISTEN` (`:8080`),
`-osrm-url`/`ROUTE_OSRM_URL`, `-osrm-timeout`/`ROUTE_OSRM_TIMEOUT` (`10s`), `-tokens-file`/`ROUTE_TOKENS_FILE`,
`-reload-tokens`/`ROUTE_RELOAD_TOKENS` (`30s`), `-max-points`/`ROUTE_MAX_POINTS`,
`-rate-per-minute`/`ROUTE_RATE_PER_MINUTE`, `-rate-burst`/`ROUTE_RATE_BURST`,
`-max-concurrent`/`ROUTE_MAX_CONCURRENT` (`8`), `-log-level`/`ROUTE_LOG_LEVEL`. Tokens gibt es nur als Datei.

`route-gateway token <label>` erzeugt ein Token (256 Bit) und die Zeile für die Token-Datei
(`<label> sha256:<hex>`); `scripts/new-token.sh` erledigt beides.

## API

```http
POST /v1/route
Authorization: Bearer <token>
Content-Type: application/json

{"points": [[50.7295477, 7.1057999], [49.453861, 11.0772885]]}
```

Antwort (`gzip`, wenn der Client es anbietet):

```json
{"geometry": "<Polyline, Präzision 6>", "precision": 6, "distance_m": 410123.4, "duration_s": 15012.3,
 "data_timestamp": "2026-11-08T21:21:00Z"}
```

Fehler als `{"error": "<code>"}`: `400` (`bad_request`, `point_count`, `bad_point`), `401` (`unauthorized`),
`404` (`no_route`), `405`, `413` (`too_large`), `415`, `422` (`no_segment`: Punkt nicht in Straßennähe),
`429` (`rate_limited`), `502` (`backend_error`), `503` (`overloaded`). `GET /healthz` antwortet `ok` (ohne Token,
ohne Informationen).

## Wie die App die Serverroute nutzt

`libs/routing` (`IndexRouter`, nur Auto, nur ohne „Vermeiden“-Optionen; Modus LeapsOnly sowie Joints-Routen über
Kartengrenzen ab 80 km Luftlinie):

1. Anfrage an den Server (Zeitlimit 2 s; unter Android Verbindungs- und Lese-Timeout je 2 s).
2. Die Serverroute muss höchstens 2 km neben Start und Ziel beginnen und enden.
3. `ServerRouteMatcher`: Wo die Polylinie laut Länderpolygonen eine Kartengrenze überquert, wird im Umkreis von
   600 m das Übergangssegment (cross-mwm transition) gesucht, dessen beide Enden höchstens 10 m neben der Polylinie
   liegen, das in Fahrtrichtung zeigt und dessen Zwilling in der nächsten Karte liegt.
4. Daraus entsteht der Leaps-Pfad Start → Übergänge → Ziel, der wie bisher verfeinert wird
   (`ProcessLeapsJoints`).
5. Plausibilitätsprüfung: Länge ±10 % (mindestens ±3 km) der Serverroute. Bei jedem Fehler rechnet die App wie
   bisher lokal. Ohne Server ist das Verhalten unverändert.

Desktop (Release), lokal → mit Server: Bonn–Nürnberg 2,7–4,1 s → 0,72–0,78 s (gleiche Route), Köln–München
4,9–5,5 s → 1,0 s, Köln–Stuttgart (Joints) 31 s → 1,1 s.

## Ressourcenbedarf

Siehe `<region>/import-stats.txt` und `import-history.txt` im Cache. Gemessen am 11.11.2026 (OSRM v6.0.0,
Geofabrik-Stand 08.11.2026, Rechner mit 16 Threads / 31 GB):

| | Deutschland komplett | West-/Süddeutschland (`OSRM_BBOX=5.9,47.8,12.3,51.4`) |
|---|---|---|
| Download (`input/`) | 4,6 GB | (derselbe) |
| nach dem Filtern (osmium, 70–100 s) | 1,2 GB | 0,65 GB |
| `osrm-extract` Spitze | **> 12 GB**: zweimal am Limit von 12 GB abgebrochen (6 und 2 Threads) | 10,8 GB (OSRM), 12,2 GB cgroup inkl. Seitencache |
| Import gesamt (6 Threads) | – | 6 min |
| Daten (`<region>/data`) | geschätzt 7–8 GB | 4,0 GB |
| `osrm-routed` im Betrieb | geschätzt 5–6 GB | 2,8 GB nach ~30 Routen (memory-mapped) |
| Gateway | 8 MB | 8 MB |
| Antwortzeit (Gateway + OSRM) | | 15–30 ms für 300–600 km |

Für ganz Deutschland also auf dem Server `OSRM_IMPORT_MEMORY=20g` setzen (und ~20 GB frei haben, alte und neue Daten
liegen beim Update nebeneinander); mit weniger RAM einen Ausschnitt mit `OSRM_BBOX` importieren. Routen, deren Start
oder Ziel außerhalb des Ausschnitts liegt, rechnet die App lokal (die Serverroute endet dann weit vom Ziel).

## Wöchentliches Update

```sh
mkdir -p ~/.config/systemd/user
cp systemd/bettermaps-route-update.* ~/.config/systemd/user/
# Pfad in ExecStart= anpassen, dann:
systemctl --user daemon-reload
systemctl --user enable --now bettermaps-route-update.timer
```

`scripts/update.sh` lädt den Extrakt nur bei Änderungen (ETag/Last-Modified, MD5-Prüfung), importiert in
`data.next` und tauscht erst nach Erfolg; ein laufender Server wird mit den neuen Daten neu gestartet. Während des
Imports liegen alte und neue Daten nebeneinander.

## Tests

```sh
tools/better-maps/bm.sh go-test services/route-server     # go vet + go test -race
```

Abgedeckt: Token-Datei und -Prüfung, Ratenlimits, Eingabeprüfung, Abbildung der OSRM-Antworten (mit
Fake-OSRM), Logs ohne Token/Koordinaten, Gleichzeitigkeitslimit.
