# Eigener MOTIS-Server (better-maps, AP3d)

Selbst betriebener [MOTIS](https://github.com/motis-project/motis)-Server für das ÖPNV-Routing der App
(Feature 3). Er spricht dieselbe API wie [Transitous](https://transitous.org) (MOTIS v2.11.4, `/api/v6`),
in der App ändert sich nur die Basis-URL.

- Offizielles Image `ghcr.io/motis-project/motis:2.11.4` (≈150 MB), keine eigenen Builds.
- Podman (rootless) mit `podman-compose`, Container mit Limits (Standard 6 GB RAM, 3 CPUs), Import mit `nice`.
- Testausschnitt Berlin: VBB-Fahrplan + OSM Berlin. Optional Echtzeit (GTFS-RT) und ganz Brandenburg.
- Downloads und Importdaten liegen **nicht im Repo**, sondern unter `~/.cache/bettermaps-motis/`
  (`MOTIS_CACHE`). `services/motis/.gitignore` schützt zusätzlich vor versehentlichem Einchecken.

## Schnellstart

```sh
cd services/motis
scripts/fetch-data.sh      # OSM + GTFS laden (idempotent, ~160 MB)
scripts/import.sh          # Vorverarbeitung, ~35 s
scripts/run.sh             # Server auf http://127.0.0.1:18090
scripts/smoke.sh           # Smoke-Test
scripts/compat.sh          # Kompatibilitätstest mit den Anfragen der App
scripts/stop.sh            # Server stoppen, Daten bleiben im Cache
```

In einer Distrobox/Toolbox rufen die Skripte Podman automatisch über `distrobox-host-exec` auf.
Benötigt werden `bash`, `curl`, `unzip`, `python3` (für die Tests zusätzlich PyYAML) und auf dem Host
`podman` + `podman-compose` (getestet: Podman 5.8.9, podman-compose 1.6.0).

## Profile

Ein Profil ist eine MOTIS-Konfiguration in `config/<profil>.yml`. Die Eingabedateien stehen dort als
Kommentar `# input: <datei> <url>`; `fetch-data.sh` liest sie daraus.

| Profil | Fahrplan | Echtzeit | OSM | Zweck |
|--------|----------|----------|-----|-------|
| `berlin` (Standard) | VBB | – | Berlin | kleinster Test |
| `berlin-rt` | VBB + gtfs.de Regionalverkehr (`de_rv`) | VBB GTFS-RT, gtfs.de GTFS-RT | Berlin | Echtzeit testen |
| `brandenburg` | VBB | – | Brandenburg inkl. Berlin | ganzes VBB-Gebiet (Potsdam, Cottbus), zweiter Messpunkt für die Hochrechnung |

Der Dataset-Tag ist wie bei Transitous `de-VBB`. Haltestellen- und Fahrt-IDs haben deshalb dieselbe Form
(`de-VBB_de:11000:900100003`), in der App gespeicherte IDs funktionieren auf beiden Servern.

Mit dem Berlin-OSM sind Koordinaten außerhalb Berlins (z. B. Potsdam) nicht erreichbar; Haltestellen in
Brandenburg schon. Dafür gibt es das Profil `brandenburg`.

## Einstellungen

Alle Skripte lesen `scripts/common.sh`; jeder Wert lässt sich per Umgebungsvariable setzen.

| Variable | Standard | Bedeutung |
|----------|----------|-----------|
| `MOTIS_PROFILE` | `berlin` | Profil, siehe oben |
| `MOTIS_PORT` | `18090` | Port auf dem Host |
| `MOTIS_BIND` | `127.0.0.1` | Bind-Adresse; `0.0.0.0` für echte Geräte im WLAN |
| `MOTIS_CACHE` | `~/.cache/bettermaps-motis` | Downloads (`input/`) und Importe (`<profil>/data`) |
| `MOTIS_MEMORY` / `MOTIS_CPUS` | `6g` / `3` | Container-Limits für Import und Server |
| `MOTIS_VERSION` | `2.11.4` | Image-Tag, passend zur API-Spezifikation der App |
| `MOTIS_MAX_FILESIZE` | `1G` | größere Downloads bricht `fetch-data.sh` ab |
| `MOTIS_URL` | `http://127.0.0.1:$MOTIS_PORT` | Ziel von `smoke.sh`/`compat.sh` |

Beispiel: `MOTIS_PROFILE=berlin-rt MOTIS_PORT=18091 scripts/run.sh`.

## Ablauf und Skripte

- **`scripts/fetch-data.sh`** lädt die Eingabedateien nach `$MOTIS_CACHE/input`. Mit ETag/`If-Modified-Since`
  (HTTP 304) lädt es nur Neues (Geofabrik, gtfs.de). Der VBB-Server liefert weder ETag noch Last-Modified;
  dort wird neu geladen und per SHA-256 verglichen. Prüfungen vor dem Ersetzen: MD5 von Geofabrik,
  ZIP-Integrität und GTFS-Pflichtdateien. Zu jeder Datei liegt eine `.sha256` daneben.
- **`scripts/import.sh [--clean]`** führt `motis import` im Container `import` aus `compose.yaml` aus.
  Ziel ist `<profil>/data.next` (vorbelegt mit einer Reflink-Kopie der alten Daten; MOTIS überspringt
  unveränderte Schritte). Nur bei Erfolg wird `data.next` zu `data`; ein laufender Server desselben Profils
  wird neu gestartet. Ein fehlgeschlagener Import lässt den laufenden Server unberührt. Messwerte stehen in
  `<profil>/import-stats.txt` und `import-history.txt`, MOTIS' eigene Zeiten in `<profil>/data/timing.txt`.
- **`scripts/run.sh`** startet den Dienst `motis` (`podman-compose up -d`) und wartet auf `/api/v1/health`.
- **`scripts/stop.sh`** stoppt und entfernt den Container (`podman-compose down`); Downloads und Importe
  bleiben in `$MOTIS_CACHE`. Neu starten mit `scripts/run.sh` (ohne erneuten Import, Start in ~1 s).
- **`scripts/update.sh [--force]`** = `fetch-data.sh` + Import, wenn sich Eingabedaten oder Konfiguration
  geändert haben oder der Import älter als 6 Tage ist (die Konfiguration lädt 21 Tage ab Importtag).
  Danach Smoke-Test, falls der Server läuft. `--force` importiert komplett neu.

`compose.yaml` lässt sich auch direkt nutzen (Variablen siehe Kopf der Datei). Die Container laufen
rootless als Root im User-Namespace, die Dateien im Cache gehören also dem eigenen Benutzer.

### Wöchentliches Update (systemd-Timer)

Beispiel-Units liegen in `systemd/`. Pfad in `ExecStart` anpassen, dann als Benutzer-Units installieren:

```sh
cp systemd/bettermaps-motis-update.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now bettermaps-motis-update.timer
systemctl --user list-timers bettermaps-motis-update.timer
journalctl --user -u bettermaps-motis-update.service   # Log des letzten Laufs
```

Damit der Timer ohne Anmeldung läuft: `loginctl enable-linger $USER`. Der Timer läuft montags 03:30 Uhr
(bis 30 min Zufallsversatz, `Persistent=true` holt verpasste Läufe nach). Der Server selbst kann mit
`restart: unless-stopped` laufen; für einen Start beim Booten eignet sich `podman generate systemd`
bzw. eine Quadlet-Datei (nicht Teil dieses Pakets, Hosting ist noch offen).

## App-Konfiguration

Die App nutzt `TransitServiceConfig` (`android/sdk/.../transit/TransitServiceConfig.java`); nur die
Basis-URL ändert sich (ohne `/api`, ohne abschließenden Schrägstrich):

```java
TransitServiceConfig.builder(userAgent).setBaseUrl("http://10.0.2.2:18090").build();
```

- **Android-Emulator:** `http://10.0.2.2:18090` (10.0.2.2 ist das Loopback des Hosts, `MOTIS_BIND=127.0.0.1` genügt).
- **Gerät im WLAN:** `MOTIS_BIND=0.0.0.0 scripts/run.sh`, dann `http://<IP-des-Rechners>:18090`;
  ggf. Port in der Firewall freigeben.
- Klartext-HTTP erlaubt die App bereits (`network_security_config.xml`, `cleartextTrafficPermitted="true"`).
- Derzeit ist die URL in `android/app/.../transit/TransitServices.java` fest auf Transitous gesetzt
  (TODO AP3b: Einstellung). Für einen Test dort `TRANSITOUS_URL` ersetzen.

Die Version des Servers muss zur Spezifikation passen, aus der der App-Client erzeugt wird
(`android/sdk/motis_openapi.yaml`, MOTIS 2.11). `compat.sh` prüft das.

## Tests

**`scripts/smoke.sh [--expect-realtime]`** fragt den laufenden Server ab und prüft strukturell:

- `/api/v6/plan` Berlin Hbf → Alexanderplatz: Verbindungen vorhanden, jede mit Abschnitten (Legs),
  ÖPNV-Abschnitte mit Linie, Fahrt-ID, Richtung, Haltestellen-IDs und Geometrie; Zeiten plausibel
  (Dauer stimmt mit Start/Ende überein, Abschnitte überlappen nicht, schnellste Verbindung ≤ 30 min),
  `nextPageCursor` vorhanden.
- `/api/v6/stoptimes` Alexanderplatz (`de-VBB_de:11000:900100003`): ≥ 10 Abfahrten mit Linie, Ziel,
  ÖPNV-Modus und plausibler, aufsteigender Zeit.
- `--expect-realtime`: Abfahrten rund um Hbf/Alexanderplatz mit `realTime=true` und berechenbarer
  Verspätung (`departure − scheduledDeparture`), Echtzeitanteil je Verkehrsmittel.

**`scripts/compat.sh`** schickt genau die Anfragen der App (`OnlineTransitService` und
`OnlineTransitServiceTest`): `/plan` mit Standardoptionen, mit allen Optionen, mit Haltestellen-ID +
`arriveBy` + `time`, Blättern mit `pageCursor` (vor/zurück), alle `realtimeMode`-Werte,
`/api/v1/reverse-geocode?type=STOP`, `/stoptimes` mit `n=20` und „nächste Haltestelle“ mit `n=3`.
Jede Antwort muss HTTP 200 liefern und zur OpenAPI-Spezifikation des App-Clients passen (Pflichtfelder,
Typen, Datumsformate, Enum-Werte); jeder gesendete Query-Parameter muss in der Spezifikation stehen.

Ergebnisse vom 2026-10-25 (MOTIS 2.11.4; die Zahl der Prüfungen hängt von der Uhrzeit ab, weil jede
Verbindung und Abfahrt einzeln geprüft wird):

| Profil | `smoke.sh` | `compat.sh` |
|--------|-----------|-------------|
| `berlin` | 278 Prüfungen bestanden, 0 Fehler | 164 bestanden, 0 Fehler, 3 Hinweise |
| `berlin-rt` | 297 bestanden (mit `--expect-realtime`), 0 Fehler | 164 bestanden, 0 Fehler, 3 Hinweise |
| `brandenburg` | 295 bestanden, 0 Fehler | 164 bestanden, 0 Fehler, 2 Hinweise |

Hinweise (keine Fehler, die Antworten sind gültig):

1. *Cottbus mit allen Optionen* liefert keine Verbindung: Rollstuhlprofil + Fahrradmitnahme + nur
   Tram/Bus/Regionalbahn + 20/15 min Vor-/Nachlauf sind zu streng (die Anfrage stammt aus einem Unit-Test).
   Ohne diese Optionen findet `brandenburg` Verbindungen Hbf → Cottbus (RE20, RE2 …).
2. *Potsdam (Koordinate)* ist mit dem Berlin-OSM nicht erreichbar; mit `brandenburg` schon.
3. *Nächste Haltestelle:* Die erste Haltestelle aus `reverse-geocode` am Alexanderplatz ist ein
   Tramsteig (`…900100024::2`), den im VBB-Datensatz keine Fahrt bedient (Transitous füllt ihn aus DELFI).
   Die App nimmt immer den ersten Treffer und zeigt dann „keine Abfahrten“, siehe Offene Punkte.

Antwortzeiten lokal: `/plan` 20–300 ms, `/stoptimes` und `/reverse-geocode` < 10 ms.

## Echtzeit (Profil `berlin-rt`)

| Quelle | Lizenz | Abdeckung im Test |
|--------|--------|-------------------|
| [VBB GTFS-RT](https://production.gtfsrt.vbb.de) | CC BY 4.0, ohne Anmeldung, max. 60 Abrufe/min, aussagekräftiger User-Agent nötig | 7193 Entitäten, 100 % den VBB-Fahrten zugeordnet. Der Betreiber meldet seit 2026-06 eingeschränkte Abdeckung. |
| [gtfs.de GTFS-RT](https://gtfs.de/de/realtime/) (`realtime-free.pb`, ~13 MB) | CC BY-SA 4.0, ohne Anmeldung | ~47 000 Entitäten für ganz Deutschland, davon ~14 % zugeordnet (nur `de_rv` geladen; der Rest betrifft Nah-/Fernverkehr) |

Gemessen an Abfahrten um Hbf/Alexanderplatz: Regionalbahn 84/85 mit Echtzeit, U-Bahn 31/62, Tram 21/129,
Bus 16/323; Verspätungen von 1 bis 29 min sichtbar (`realTime=true`, `departure ≠ scheduledDeparture`).
Abruf alle 60 s (`update_interval`).

## Ressourcenbedarf (gemessen)

Rechner: 16 Kerne, 31 GB RAM, btrfs. Container-Limits 6 GB RAM / 3 CPUs, Import mit `nice -n 15`.
RAM-Spitze = `memory.peak` der Container-cgroup (enthält auch Page-Cache der Ausgabedateien, ist also eine
Obergrenze). Server-RAM laut `podman stats` nach Smoke- und Kompatibilitätstest.

| | `berlin` | `berlin-rt` | `brandenburg` |
|--|---------|-------------|---------------|
| Download OSM | 95 MiB | 95 MiB | 283 MiB |
| Download GTFS | 62 MiB (VBB) | 62 + 11 MiB (VBB + gtfs.de RV) | 62 MiB |
| Downloadzeit | ~5 s | ~6 s | ~12 s |
| Fahrten / Haltestellen | 183 000 / 28 800 | 290 000 / 45 100 | 183 000 / 28 800 |
| Importdauer (3 CPUs) | 34 s | 41 s | 68 s |
| RAM-Spitze Import | 1,8 GB | 1,7 GB | 2,9 GB |
| Importdaten (`data/`) | 583 MB | 682 MB | 873 MB |
| RAM Server | 420 MB | 410–570 MB | 490 MB |
| Plattenbedarf gesamt | ~0,75 GB | ~0,85 GB | ~1,2 GB |

Dazu kommt das Image (150 MB). Während eines Imports existiert `data.next` neben `data`; auf btrfs/XFS
teilt die Reflink-Kopie die Blöcke, sonst doppelter Platz. Der Fahrplan umfasst 21 Tage ab Importtag
(`num_days`). Importschritte Berlin → Brandenburg (aus `timing.txt`): Straßengraph `osr` 5,8 → 14,6 s,
Geocoding `adr` 7,8 → 24,6 s, Fahrplan `tt` 4,6 → 5,3 s, Fußwege `osr_footpath` 12,4 → 18,8 s.

## Hochrechnung für ganz Deutschland (geschätzt)

Nicht gemessen: `germany-latest.osm.pbf` ist 4,9 GB groß (über der Download-Grenze dieses Tests).
Grundlage sind die beiden Messpunkte oben. OSM-abhängige Teile (Straßengraph, Geocoding) wuchsen von
Berlin auf Brandenburg (OSM ×3,0) bei Zeit ×2,5–3,2, Plattenplatz ×2,3–2,5 und RAM-Spitze +1,05 GB, also
etwa linear. Deutschland hat das 16-Fache des Brandenburg-OSM. Der Fahrplan wächst schwächer:
gtfs.de `de_full` (289 MB ZIP, ohne Shapes) bzw. DELFI hat etwa 8–12-mal so viele Halte-Ereignisse wie VBB
(`stop_times.txt` VBB 294 MB, gtfs.de Regionalverkehr allein 81 MB).

| | Schätzung Deutschland | Begründung |
|--|-----------------------|------------|
| Download | ~5,2 GB (OSM 4,9 GB + GTFS 0,3 GB) | Geofabrik, gtfs.de `de_full` |
| Importdauer | 20–60 min mit 8 Kernen | OSM-Schritte ×16 auf Brandenburg (~20 min mit 3 Kernen), Fahrplan und Fußwege ×10, Puffer |
| RAM-Spitze Import | 16–28 GB, Rechner mit ≥ 32 GB | linear +5,6 GB je GB OSM (Messpunkte) ergibt ~28 GB; der cgroup-Wert enthält Page-Cache, der echte Bedarf liegt darunter |
| Importdaten | 12–20 GB | `osr` 309 MB, `adr` 165 MB je 0,3 GB OSM ×16; Fahrplan + Shapes ~2–3 GB; ohne Kacheln |
| Platte gesamt | ~30–40 GB | Eingabe + Daten + `data.next` ohne Reflink |
| RAM Server | 4–8 GB | Server mappt die Daten per mmap; im Test ~0,5 GB bei 0,6–0,9 GB Daten, Echtzeit für ganz Deutschland zusätzlich |
| Echtzeit | gtfs.de `realtime-free.pb` passt zu allen gtfs.de-Feeds | ein Dataset mit `de_full` und einem RT-Abruf je Minute |

Für Deutschland bietet sich ein Dataset mit gtfs.de `de_full` (oder DELFI) plus gtfs.de GTFS-RT an.
Ein echter Testlauf sollte die Schätzung prüfen, sinnvollerweise auf einem eigenen Server.

## Datenquellen und Lizenzen

Alle Quellen sind ohne Account oder Registrierung abrufbar.

| Daten | Quelle | Lizenz / Namensnennung |
|-------|--------|------------------------|
| Fahrplan VBB (GTFS, 2× pro Woche aktualisiert) | <https://www.vbb.de/vbbgtfs>, Infos: <https://unternehmen.vbb.de/digitale-services/datensaetze/> | CC BY 4.0, „VBB Verkehrsverbund Berlin-Brandenburg GmbH“ bei jeder Nutzung nennen |
| Echtzeit VBB (GTFS-RT) | <https://production.gtfsrt.vbb.de/data> | CC BY 4.0; max. 60 Abrufe/min, aussagekräftiger User-Agent |
| Fahrplan Deutschland Regionalverkehr (GTFS) | <https://download.gtfs.de/germany/rv_free/latest.zip> ([gtfs.de](https://gtfs.de/de/feeds/de_rv/)) | CC BY 4.0, Datenbasis DELFI e.V. (NeTEx), „gtfs.de“ |
| Echtzeit Deutschland (GTFS-RT) | <https://realtime.gtfs.de/realtime-free.pb> ([gtfs.de](https://gtfs.de/de/realtime/)) | CC BY-SA 4.0, ohne Gewähr |
| Straßennetz Berlin / Brandenburg (OSM) | <https://download.geofabrik.de/europe/germany/berlin-latest.osm.pbf>, `…/brandenburg-latest.osm.pbf` | ODbL 1.0, „© OpenStreetMap-Mitwirkende“ |
| Software MOTIS | <https://github.com/motis-project/motis>, Image `ghcr.io/motis-project/motis` | MIT |

Wer den Server öffentlich betreibt, muss diese Namensnennungen in der App bzw. auf einer Infoseite zeigen.
`server.data_attribution_link` in der Konfiguration verweist zusätzlich darauf (HTTP-Header).

## Offene Punkte

- Öffentliches Hosting (TLS, Reverse Proxy, Start beim Booten, Monitoring) entscheidet der Projektinhaber.
  Ein Reverse Proxy mit Pfadpräfix (`/motis/api/v6/...`) wird von der App unterstützt, ist hier aber nicht getestet.
- Die App nimmt bei „nächste Haltestelle“ den ersten `reverse-geocode`-Treffer, auch wenn dort keine Fahrt
  hält. Besser: mehrere Treffer probieren oder Haltestellen ohne Abfahrten überspringen (betrifft auch Transitous).
- VBB GTFS-RT hat laut Betreiber seit 2026-06 Lücken; gtfs.de-RT deckt nur gtfs.de-Fahrpläne ab.
- Fernverkehr (gtfs.de `de_fv`) ist im Testprofil nicht enthalten (zweiter Abruf des 13-MB-Echtzeitfeeds).
- Die Hochrechnung für Deutschland ist ungeprüft.
