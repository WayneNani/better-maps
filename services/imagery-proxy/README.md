# Luftbild-Server (better-maps, Ebene „Luftbild“)

Eigener Kachelserver für die optionale Luftbild-Ebene der App. Er setzt die Orthophotos (DOP, 10–20 cm) der
Bundesländer und außerhalb davon bzw. bei kleinem Maßstab das weltweite Sentinel-2-Mosaik 2016 von EOX zu
WebMercator-Kacheln (`/v1/tiles/{z}/{x}/{y}.jpg`) zusammen. Es werden nur offen lizenzierte Daten verwendet (keine
Google-, Bing-, Esri- oder Mapbox-Bilder).

```
App ──HTTPS──► Reverse Proxy (TLS) ──► imagery-gateway (Go: Token, Ratenlimit, Kachel-Cache, Attribution)
                                                └──► MapProxy (intern) ──► WMS der Länder / EOX-WMTS
```

- **MapProxy** (offizielles Image `ghcr.io/mapproxy/mapproxy/mapproxy`, plus gunicorn, `mapproxy.Containerfile`)
  rechnet die WMS-Quellen in WebMercator um und legt sie übereinander: unten Sentinel-2, darüber die Länder ab
  Zoom 12, jedes auf sein Landespolygon zugeschnitten (`config/coverage/*.geojson`), damit jede Kachel aus dem
  richtigen Land kommt. MapProxy speichert selbst nichts und hat keinen veröffentlichten Port; es erreicht nur
  ausgehend die Quellen.
- **imagery-gateway** (`cmd/imagery-gateway`, nur Go-Standardbibliothek) ist der einzige veröffentlichte Dienst:
  Token-Prüfung, Ratenlimit, Plattencache mit Größen- und Altersgrenze und `GET /v1/attribution`.

## Quellen und Lizenzen

Geprüft am 25.11.2026 anhand der GetCapabilities der Dienste bzw. der Nutzungsbedingungen der Anbieter, jeweils
mit einer Testkachel. Die Quellenvermerke stehen in `config/sources.json` (mit `{year}` = Jahr des Abrufs) und
werden in der App angezeigt, solange die Ebene an ist.

| Id | Quelle | Dienst / Layer | Lizenz | Quellenvermerk |
|----|--------|----------------|--------|----------------|
| `s2cloudless2016` | EOX Sentinel-2 cloudless **2016** (weltweit, ca. 10 m) | WMTS `s2cloudless_3857` | CC BY 4.0 (nur 2016; 2018 ff. sind CC BY-NC-SA und **nicht** erlaubt) | EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016) |
| `nw` | Nordrhein-Westfalen, DOP 10 cm | `wms_nw_dop` / `nw_dop_rgb` | dl-de/zero-2.0 (kein Vermerk nötig) | Geobasis NRW, dl-de/zero-2-0 |
| `rp` | Rheinland-Pfalz, DOP 20 cm | `rp_dop20.fcgi` / `rp_dop20` | dl-de/by-2.0 | ©GeoBasis-DE / LVermGeoRP (Jahr), dl-de/by-2-0, http://www.lvermgeo.rlp.de [Daten bearbeitet] |
| `he` | Hessen, DOP 20 cm | `ogc-free-images.ows` / `he_dop_rgb` | frei nach § 18 HVGG (ohne Bedingungen; ein Vermerk muss Änderungen nennen) | Geobasisdaten © Hessische Verwaltung für Bodenmanagement und Geoinformation [Daten bearbeitet] |
| `ni` | Niedersachsen, DOP 20 cm | LGLN `dop_wms` / `ni_dop20` | CC BY 4.0 | LGLN (Jahr) Creative Commons Namensnennung – 4.0 International (CC BY 4.0) [Daten bearbeitet] |
| `by` | Bayern, DOP 20 cm | `od/wms/dop/v1/dop20` / `by_dop20c` | CC BY 4.0 | Bayerische Vermessungsverwaltung – www.geodaten.bayern.de [Daten bearbeitet] |
| `bw` | Baden-Württemberg, DOP 20 cm | `WMS_LGL-BW_ATKIS_DOP_20_C` / `IMAGES_DOP_20_RGB` | dl-de/by-2.0 | LGL-BW (Jahr) Datenlizenz Deutschland - Namensnennung - Version 2.0, www.lgl-bw.de [Daten bearbeitet] |
| `sl` | Saarland, DOP 20 cm (2023) | `freewms/dop2023` / `sl_dop20_rgb` (WMS 1.1.1) | dl-de/by-2.0 (laut Metadatensatz) | © GeoBasis DE/LVGL-SL (Jahr), dl-de/by-2-0 [Daten bearbeitet] |
| `th` | Thüringen, DOP 20 cm | `geoproxy/services/DOP` / `th_dop` | CC BY 4.0 | © GDI-Th, CC BY 4.0 [Daten bearbeitet] |
| `sn` | Sachsen, DOP 20 cm | `wms_geosn_dop-rgb/guest` / `sn_dop_020` | dl-de/by-2.0 | Quelle: GeoSN, dl-de/by-2-0 [Daten bearbeitet] |
| `bb` | Brandenburg, DOP 20 cm | `isk.geobasis-bb.de/mapproxy/dop20c` / `bebb_dop20c` | dl-de/by-2.0 | © GeoBasis-DE/LGB, dl-de/by-2-0, (Daten geändert) |
| `be` | Berlin, TrueDOP 20 cm Sommer 2025 | `truedop_2025_sommer` / `truedop_2025_sommer_rgb` | dl-de/zero-2.0 | Geoportal Berlin / TrueDOP 2025 Sommer, dl-de/zero-2-0 |
| `sh` | Schleswig-Holstein, DOP 20 cm | `WMS_SH_DOP20col_OpenGBD` / `sh_dop20_rgb` | CC BY 4.0 | © GeoBasis-DE/LVermGeo SH/CC BY 4.0 [Daten bearbeitet] |

**Nicht aufgenommen** (vorerst; dort zeigt die Ebene Sentinel-2):

- **Mecklenburg-Vorpommern** (`adv_dop`, CC BY 4.0): Der Dienst brennt „© GeoBasis-DE/M-V“ in jedes Bild; in
  256-px-Kacheln stünde der Vermerk auf jeder Kachel.
- **Hamburg** (`wms_dop_zeitreihe_belaubt`): liefert erst ab Maßstab 1:5000 (~Zoom 17) Bilder, und die Zeitreihe
  enthält Jahrgänge unter geschlossener Lizenz (Pléiades © CNES/Airbus). Ein aktueller frei lizenzierter Dienst
  ohne diese Einschränkungen war nicht zu finden.
- **Sachsen-Anhalt** (`ST_LVermGeo_DOP_WMS_OpenData`, dl-de/by-2.0): Der verbindliche Wortlaut des Quellenvermerks
  ließ sich nicht prüfen (Nutzungsbedingungen nur als verschlüsseltes PDF).
- **Bremen** (`wms_dop20_2023`, „CC-BY“ ohne Versionsangabe): Lizenzversion unklar, und `data/borders` hat kein
  eigenes Polygon für das Land Bremen (es liegt in der Region „Lower Saxony_Bremen“).

Lizenzhinweise: „dl-de/by-2.0“ und CC BY 4.0 verlangen den Vermerk und den Hinweis auf Änderungen
(Umprojektion/Kachelung → „[Daten bearbeitet]“). Sentinel-2: nur der Jahrgang 2016 (`s2cloudless_3857`) ist
CC BY 4.0; laut EOX ist kommerzielle Nutzung bei „vernünftigem“ Verkehr erlaubt, EOX drosselt Lastspitzen.

## Sicherheit, Datenschutz und Rücksicht auf die Quellen

- **Token pro Gerät** wie beim Routing-Server: `Authorization: Bearer <token>`, der Server kennt nur SHA-256-Hashes
  (`~/.config/bettermaps-imagery/tokens`, Rechte 600), Vergleich in konstanter Zeit, Sperren durch Löschen der
  Zeile (wird nach ≤ 30 s bzw. bei `SIGHUP` neu gelesen).
- **Ratenlimit pro Token**: im Mittel 600 Kacheln/Minute, bis zu 300 auf einmal (ein Handybildschirm braucht
  20–40 Kacheln); darüber `429` mit `Retry-After`. Fehlversuche global ≤ 5/s.
- **Last auf den Landesservern**: höchstens 4 gleichzeitige Kachelberechnungen im Gateway, höchstens 2
  gleichzeitige Anfragen je Landesserver (MapProxy `concurrent_requests`), 256-px-Kacheln ohne Metakacheln, JPEG,
  gleichzeitige Anfragen derselben Kachel werden zusammengelegt. **Kein Seeding**: Es wird nur geholt, was ein
  Gerät ansieht. User-Agent: `better-maps-imagery-proxy/0.1 (self-hosted tile proxy of an open-source map app; low
  volume, cached)` – ohne persönliche Daten.
- **Cache**: Das Gateway speichert jede gelieferte Kachel unter `~/.cache/bettermaps-imagery/tiles/{z}/{x}/{y}.jpg`,
  höchstens `IMAGERY_CACHE_MAX_MB` (Standard 1024 MB; bei Überschreitung werden die am längsten nicht benutzten
  Kacheln gelöscht, bis 90 % erreicht sind) und höchstens `IMAGERY_CACHE_MAX_AGE` alt (Standard 30 Tage).
- **Logs**: Gateway nur Methode, Pfadklasse (`tile`/`attribution`/`other`), Status, Dauer, Cache-Treffer und das
  **Label** des Tokens; erfolgreiche Kacheln nur auf Debug-Stufe. Nie Token, Kachelkoordinaten, Bounding-Boxen oder
  Client-Adressen. MapProxy loggt nur Fehler, nie die Anfragen an die Quellen (`config/logging.ini`), gunicorn hat
  kein Zugriffslog.
- **Container**: alle ohne Capabilities, `no-new-privileges`, schreibgeschütztes Dateisystem, Speicherlimits
  (MapProxy 768 MB, Gateway 192 MB). MapProxy sitzt im internen Netz `backend` (nur Gateway) plus `egress`
  (nur ausgehend), ohne veröffentlichten Port.
- **TLS** macht der Reverse Proxy; das Gateway lauscht standardmäßig auf `127.0.0.1:18200`. Die App akzeptiert
  `http://` nur für lokale Adressen. Token bleibt in den app-privaten Einstellungen (nicht im Backup).

### Reverse Proxy (Beispiel Caddy)

```caddyfile
luftbild.example.org {
	@api path /v1/tiles/* /v1/attribution
	handle @api {
		reverse_proxy 127.0.0.1:18200
	}
	handle {
		respond 404
	}
	# Kein "log" hier: ein Zugriffslog enthielte die Kachelkoordinaten in der URL.
}
```

## Schnellstart

Voraussetzung: Podman mit `podman-compose` (in einer Distrobox über `distrobox-host-exec`).

```sh
cd services/imagery-proxy
scripts/new-token.sh pixel             # Token für ein Gerät anlegen, wird einmal angezeigt
scripts/run.sh                         # Images bauen (beim ersten Mal) und starten
IMAGERY_TOKEN=... scripts/smoke.sh     # Test: 401 ohne Token, Kacheln Köln/München/Paris, Attributionen
scripts/stop.sh                        # Container stoppen, Cache bleibt
```

### Dauerbetrieb

```sh
cp systemd/bettermaps-imagery.service ~/.config/systemd/user/   # Pfad darin anpassen
systemctl --user daemon-reload && systemctl --user enable --now bettermaps-imagery.service
loginctl enable-linger "$USER"                                  # Start ohne Anmeldung
```

### App einrichten

Einstellungen → Karte → **Luftbild-Server**: Server-Adresse (z. B. `https://luftbild.example.org`) und Token des
Geräts eintragen. Dann im Ebenen-Menü der Karte **Luftbild** einschalten. Im Emulator erreicht man den Rechner unter
`http://10.0.2.2:18200`.

## Konfiguration

Skripte (`scripts/common.sh`, alles über Umgebungsvariablen):

| Variable | Standard | Bedeutung |
|----------|----------|-----------|
| `IMAGERY_BIND` / `IMAGERY_PORT` | `127.0.0.1` / `18200` | Veröffentlichter Port des Gateways |
| `IMAGERY_TOKENS_FILE` | `~/.config/bettermaps-imagery/tokens` | Token-Hashes |
| `IMAGERY_CACHE` | `~/.cache/bettermaps-imagery` | Kachel-Cache (`tiles/`) |
| `IMAGERY_CACHE_MAX_MB` / `IMAGERY_CACHE_MAX_AGE` | `1024` / `720h` | Größen- und Altersgrenze des Caches |
| `IMAGERY_RATE_PER_MINUTE` / `IMAGERY_RATE_BURST` | `600` / `300` | Ratenlimit pro Token |
| `IMAGERY_MAX_CONCURRENT` | `4` | Gleichzeitige Kachelberechnungen |
| `IMAGERY_MAX_ZOOM` | `19` | Höchste ausgelieferte Zoomstufe |
| `MAPPROXY_VERSION` / `MAPPROXY_MEMORY` | `8.1.1` / `768m` | MapProxy-Image und Speicherlimit |

Gateway (Flag oder Umgebungsvariable): `-listen`, `-upstream-url`, `-upstream-layer` (`aerial`), `-upstream-grid`
(`webmercator`), `-upstream-timeout` (`30s`), `-tokens-file`, `-sources-file`, `-cache-dir`, `-cache-max-mb`,
`-cache-max-age`, `-reload-tokens`, `-max-zoom`, `-rate-per-minute`, `-rate-burst`, `-max-concurrent`,
`-log-level` (jeweils `IMAGERY_…`). `imagery-gateway token <label>` erzeugt ein Token und die Zeile für die
Token-Datei.

Quellen ändern: `config/mapproxy.yaml` (Dienste, Reihenfolge = Zeichenreihenfolge), `config/sources.json`
(Vermerke, Lizenzen, Mindestzoom, Abdeckung; gleiche Reihenfolge) und bei neuen Ländern `scripts/make-coverages.py`
(Landespolygone aus `data/borders`, um ~30 m erweitert und vereinfacht; `scripts/make-coverages.sh` baut sie im
MapProxy-Image neu).

## API

```http
GET /v1/tiles/{z}/{x}/{y}.jpg          (z 0–19, XYZ, Ursprung oben links)
Authorization: Bearer <token>
```

Antwort `image/jpeg` (256 × 256), `Cache-Control: private, max-age=604800`.

```http
GET /v1/attribution?bbox=<minLon>,<minLat>,<maxLon>,<maxLat>&z=<zoom>
Authorization: Bearer <token>
```

```json
{"sources": [{"id": "nw", "name": "Nordrhein-Westfalen, DOP 10 cm (Geobasis NRW)",
  "attribution": "Geobasis NRW, dl-de/zero-2-0", "license": "dl-de/zero-2.0",
  "license_url": "https://www.govdata.de/dl-de/zero-2-0", "min_zoom": 12, "bbox": [5.86, 50.32, 9.46, 52.53]}]}
```

Ohne `bbox` alle Quellen (oberste zuerst). Mit `bbox` die dort sichtbaren: ab dem Mindestzoom, Abdeckung schneidet
die Box, und Sentinel-2 nur, wenn die Länder die Box nicht ganz abdecken. Fehler als `{"error": "<code>"}`: `400`,
`401`, `404` (auch Kacheln außerhalb des Rasters), `405`, `429` (`Retry-After`), `502` (`backend_error`), `503`
(`overloaded`). `GET /healthz` antwortet `ok`.

## Bekannte Grenzen

- Küstenländer (NI, SH): Die Regionspolygone reichen teils ins Meer; wo der Landesdienst dort keine Bilder hat,
  erscheint Weiß statt Sentinel-2.
- Bei Zoom > 14 vergrößert EOX das 10-m-Mosaik nur; außerhalb der Länder wirkt das unscharf.
- Ein Ausfall eines Landesservers ergibt `502` für die betroffenen Kacheln (die App zeigt dort die normale Karte).
