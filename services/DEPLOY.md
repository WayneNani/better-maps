# Backend von better-maps im Eigenbetrieb

Überblick für den Dauerbetrieb aller Server der App auf einer Maschine (VM) hinter einem Reverse Proxy. Die
Einzelheiten stehen in den READMEs der Dienste; die konkrete Umsetzung im Homelab (VM auf pve2, Quadlets, Caddy,
Firewall, Timer, Tokens) liegt im Homelab-Repo unter `services/better-maps/` (Branch `better-maps`, Vorschlag), die
offenen Entscheidungen dort in `ENTSCHEIDUNGEN.md`.

```
App ──HTTPS──► Cloudflare ──► Edge-Caddy (CrowdSec, AppSec) ──HTTPS──► Caddy in der VM (maps.internal)
                                                                        │ /route    ► route-gateway   :18100 ► OSRM (intern)
                                                                        │ /imagery  ► imagery-gateway :18200 ► MapProxy ► Landes-WMS, EOX
                                                                        │ /indoor   ► indoor-gateway  :18300 (Daten im Speicher)
                                                                        └ /traffic  ► traff-server    :18400 ► Autobahn-API, DATEX II
```

Alle Gateways lauschen nur auf `127.0.0.1` (veröffentlichter Port des Containers), TLS macht Caddy. Ein Name mit
Pfaden statt vier Namen: ein Zertifikat, ein DNS-Eintrag, ein Site-Block auf der Edge, ein Satz Monitore. Die App
hängt ihre Pfade an die eingetragene Adresse an (`/v1/route`, `/v1/tiles/…`, `/v1/attribution`, `/v1/venues…`),
eine Adresse mit Pfad (`https://maps.example.org/route`) funktioniert also unverändert; Caddy entfernt das Präfix.

## Dienste

| Dienst | Port (lokal) | Endpunkte | Betrieb (RAM) | Daten | Aktualisierung | Health |
|---|---|---|---|---|---|---|
| `route-server` | 18100 | `POST /v1/route` | Gateway 8 MB, OSRM Deutschland ~5–6 GB (Limit 6–7 GB) | `<region>/data` ~7–8 GB (+ gleich viel beim Austausch), Extrakt 4,6 GB | Import 17–20 GB RAM (siehe unten), wöchentlich bis monatlich | `GET /healthz` → `ok` (prüft OSRM nicht) |
| `imagery-proxy` | 18200 | `GET /v1/tiles/{z}/{x}/{y}.jpg`, `GET /v1/attribution` | MapProxy ≤ 768 MB, Gateway ≤ 192 MB | Kachel-Cache ≤ 1 GB, 30 Tage | kein Import; Cache verfällt selbst | `GET /healthz` → `ok` |
| `indoor-server` | 18300 | `GET /v1/venues?bbox=…`, `GET /v1/venues/{id}` | Gateway ~12 MB | wenige MB; Extrakte nur während des Imports | wöchentlich, Import ~3 GB (Hessen+NRW gemessen), Deutschland ungemessen (Limit 4 GB) | `GET /healthz` → `ok` |
| `traff-server` | 18400 | `POST /` (TraFF-XML, Alias `/traff`), `GET /status` (Zustand, mit Token) | ~50–100 MB (Limit 256 MB), alles im Speicher | keine | keiner, fragt die Quellen alle 5 min ab | `GET /healthz` → `ok` |

Ausgehend brauchen MapProxy (Landes-WMS, EOX), traff-server (Autobahn-API, DATEX-II-Feeds, Mobilithek mit
Client-Zertifikat aus `/secrets`, siehe `traff-server/README.md`) und die Downloads der Importe (Geofabrik) HTTPS
ins Internet. OSRM, die Import-Container und das Indoor-Gateway haben kein
Netz nach außen.

**Nicht betrieben:** `services/motis` (die App nutzt [Transitous](https://transitous.org)) und vorerst
`services/crowd-traffic` (siehe unten).

## App einstellen

Pro Dienst eine Adresse und ein Token (je Gerät, siehe unten). Mit dem öffentlichen Namen `maps.example.org`:

| Einstellung in der App | Adresse | Token |
|---|---|---|
| Navigation → **Routing-Server** | `https://maps.example.org/route` | Route-Token |
| Kartendarstellung → **Luftbild-Server** | `https://maps.example.org/imagery` | Luftbild-Token |
| Kartendarstellung → **Innenraumkarten-Server** | `https://maps.example.org/indoor` | Indoor-Token |
| Verkehr → **Verkehrsdienst-URL** | `https://maps.example.org/traffic/` | Zugangs-Token (TraFF) |

- `http://` nimmt die App nur für lokale Adressen an (Emulator, private IPv4, `.local`). Interne Namen wie
  `maps.internal` gehen nur mit `https://` und einem Zertifikat, dem das Handy vertraut: die App vertraut auch vom
  Nutzer installierten CAs (`network_security_config.xml`), die interne Root-CA müsste also auf dem Handy installiert
  sein.
- **TraFF:** Die App schickt ihre Anfragen per `POST` genau an die eingetragene Adresse (`libs/traffxml`, kein
  Pfad angehängt), mit oder ohne `/` am Ende; der Proxy muss `/traffic` und `/traffic/` auf `/` abbilden. Das Token
  ist an Schema, Host und Port gebunden: ein anderer Pfad auf demselben Host (hier `/traffic/`) behält es, ein
  anderer Host löscht es (`traff-server/README.md`, „App einrichten“).

## Tokens

- Pro Gerät und Dienst ein Token (256 Bit). Der Server speichert nur `<label> sha256:<hex>` je Zeile; das Token
  selbst wird einmal angezeigt und in der App eingetragen. Erzeugen: `<gateway> token <label>` im jeweiligen Image
  (`scripts/new-token.sh` macht das und hängt die Zeile an). Die Formate sind bei allen Gateways gleich, ein Gerät
  kann also auch dasselbe Token für alle Dienste bekommen (gleiche Zeile in jeder Datei).
- Sperren: Zeile löschen; die Gateways lesen die Datei nach spätestens 30 s neu (`SIGHUP` sofort).
- **Bind-Mounts:** Die Token-Datei nicht einzeln einhängen, sondern ihr Verzeichnis. Ein Editor oder `sed -i`
  ersetzt die Datei durch eine neue, ein Datei-Bind-Mount zeigt dann weiter auf die alte, und ein gesperrtes Token
  bliebe gültig.
- Die Gateway-Images laufen als Benutzer 65532 (distroless `nonroot`). Unter rootful Podman die Token-Datei also
  `0640 root:65532` (oder den Container als Root laufen lassen). `scripts/new-token.sh` setzt die Datei immer auf
  `0600` und ist deshalb für diesen Fall nicht gedacht.
- Ratenlimits pro Token: Route 30/min (Burst 60), Luftbild 600/min (Burst 300), Indoor 120/min (Burst 60),
  TraFF 60/min (Burst 30); Fehlversuche mit falschem Token global 5/s.
- traff-server startet ohne Token-Datei nicht (nur `TRAFF_INSECURE_NO_AUTH=1` für Tests).

## Betrieb als systemd-/Quadlet-Dienste

Die Skripte der Dienste (`scripts/*.sh`) sind für rootless Podman mit `podman-compose` gebaut. Für eine VM mit
Quadlets gilt:

- **Server** als `.container`-Units mit denselben Einstellungen wie in `compose.yaml` (Images, Netze, Limits,
  `ReadOnly`, `DropCapability=ALL`, `NoNewPrivileges`). OSRM und MapProxy nur in internen Netzen (`Internal=true`),
  erreichbar nur vom jeweiligen Gateway. `osrm-routed` mit `--verbosity WARNING` (sonst loggt es jede Route mit
  Koordinaten).
- **Importe** weiter über `scripts/update.sh` der Dienste (podman-compose, rootful geht ebenso; die
  Import-Container laufen ohne Netz und ohne Capabilities), aufgerufen von einem systemd-Timer. `data/` des
  Route-Servers ist nach dem Austausch für alle lesbar (OSRM kann als `nobody` laufen). Die Server laufen dann nicht über `run.sh`; damit die Importe sie trotzdem neu starten:
  - `ROUTE_RESTART_CMD` (z. B. `systemctl restart bettermaps-osrm.service`) und optional `ROUTE_STOP_CMD`
    (OSRM während `osrm-extract` anhalten, spart dessen Speicher; die App rechnet solange selbst)
  - `INDOOR_RESTART_CMD` (z. B. `systemctl restart bettermaps-indoor-gateway.service`)
  - nötig, weil ein Bind-Mount von `data/` nach dem Austausch (`data` → `data.prev`, `data.next` → `data`) noch
    auf das alte Verzeichnis zeigt
- `INDOOR_KEEP_PBF=1`, wenn sich Route- und Indoor-Import einen Deutschland-Extrakt teilen (gleicher Dateiname,
  gleiches `.sha256`-Format): sonst löscht der Indoor-Import die Datei, die der Route-Import noch braucht.
- `fetch-data.sh` setzt `INDOOR_CACHE` auf `0700`; das Indoor-Gateway deshalb nur `data/` einhängen, nicht den
  ganzen Cache.

## Ressourcen und der OSRM-Import

Gemessen (Details in `route-server/README.md`, Abschnitt „Ressourcenbedarf“): `osrm-extract` braucht gut das
14-Fache der gefilterten Eingabe, für Deutschland (1,2 GB gefiltert) **etwa 17–20 GB**; 12 GB reichten zweimal
nicht. Im Betrieb braucht OSRM für Deutschland nur ~5–6 GB. Der Import muss also nicht auf derselben Maschine laufen:

1. **Import woanders, Daten kopieren** (für ganz Deutschland, wenn der Server < 20 GB frei hat): `scripts/update.sh`
   mit `OSRM_IMPORT_MEMORY=20g` auf einem Rechner mit genug RAM, Ergebnis (~7–8 GB) per `rsync` auf den Server,
   dort `scripts/install-data.sh <verzeichnis>` (prüft Region, OSRM-Version und Vollständigkeit, tauscht aus,
   startet OSRM neu). Routen ändern sich langsam, monatlich reicht; die App prüft jede Serverroute gegen ihre Karten
   und rechnet bei Abweichungen selbst.
2. **Ausschnitt** mit `OSRM_BBOX` (gemessen: West/Süd `5.9,47.8,12.3,51.4` mit 10,8 GB Spitze, 2,8 GB im Betrieb):
   wöchentlich automatisch auf dem Server, Routen außerhalb rechnet die App lokal.
3. **Ganz Deutschland auf dem Server** nur mit ≥ 20 GB freiem RAM während des Imports (mit `ROUTE_STOP_CMD` braucht
   OSRM in der Zeit nichts).

Platte: Extrakt 4,6 GB + gefiltert 1,2 GB + Daten 7–8 GB + neue Daten 7–8 GB beim Austausch, Kachel-Cache ≤ 1 GB,
Images ~4 GB. Alles außer Tokens und Konfiguration ist aus den Quellen neu erzeugbar und gehört nicht ins Backup.

## Datenschutz: wer sieht was

Die App schickt bei der Nutzung:

| Dienst | Was die Anfrage verrät |
|---|---|
| Route | Start, Zwischenziele und Ziel als Koordinaten (im Body von `POST /v1/route`) |
| Luftbild | die angesehenen Kacheln (`z/x/y` in der URL) und den Kartenausschnitt (`bbox` bei `/v1/attribution`) |
| Indoor | den Kartenausschnitt ab Zoom 15 (`bbox` in der URL, auf 0,02° gerundet) und die geöffneten Gebäude (Id) |
| TraFF | Filter-Rechtecke der Kartenregionen, für die die App Verkehr abonniert (MWM-Grenzen, grob) |

- **Cloudflare** terminiert TLS: Es sieht jede Anfrage im Klartext, also Ziele und Startpunkte der Routen, die
  angesehenen Kartenausschnitte, die IP-Adresse des Handys **und die Bearer-Tokens**. Das ist bei diesem Aufbau
  nicht vermeidbar. Kacheln sind `Cache-Control: private`, Cloudflare speichert sie deshalb nicht zwischen.
- **Edge (eigener Caddy)**: ohne Zugriffslog für diese Seite (sonst stünden Kacheln und Ausschnitte mit IP im Log).
  CrowdSec wertet deshalb keine Zugriffe dieser Seite aus; AppSec prüft jede Anfrage im Speicher (auch den Body der
  Routen), schreibt aber nur Treffer. Bei Fehlern des Upstreams (502) kann Caddy die URL mit IP ins Fehlerlog
  schreiben.
- **Caddy in der VM**: kein Zugriffslog, Fehlerlogs ohne IP, Pfad und Header.
- **Gateways**: loggen nur Methode, Pfadklasse, Status, Dauer und das Label des Tokens, nie Koordinaten, bbox,
  Venue-Ids, Tokens oder IPs. OSRM mit `--verbosity WARNING`, MapProxy nur Fehler ohne Quell-URLs.
- **Quellen**: Die Landes-WMS und EOX sehen die Kachelausschnitte, die nicht im Cache liegen (mit der IP des Servers,
  nicht der des Handys). Autobahn-API, DATEX-Feeds und Geofabrik sehen nur den Server.

**Alternative ohne Cloudflare: nur über VPN.** Der öffentliche Name wird intern (Split-DNS) auf den eigenen Caddy
aufgelöst, der Site-Block lässt nur LAN/VPN zu, und das Handy erreicht ihn nur mit aktivem VPN. Dann sieht außer dem
eigenen Server niemand Routen, Ausschnitte oder Tokens; das Zertifikat bleibt das öffentliche, das Handy braucht
keine eigene CA. Preis: ohne VPN keine Serverdienste (die App fällt still auf ihre lokalen Funktionen zurück).

## Crowd-Traffic (optional, nicht verdrahtet)

`services/crowd-traffic` nimmt anonyme Staumeldungen nur über Oblivious HTTP an. Das Verfahren schützt nur, wenn
**Relay und Gateway von verschiedenen Betreibern** laufen: das Relay sieht IP-Adressen, aber keine Inhalte, das
Gateway Inhalte, aber keine IPs. Ein Relay im selben Homelab (z. B. das OHTTP-Relay der Praxislotse-Demo) gehört
demselben Betreiber und hebt diese Trennung auf; es ist dafür nicht geeignet. Betrieb erst, wenn ein unabhängiges
Relay feststeht; das Gateway passt dann als weiterer Pfad (`/crowd`) in dasselbe Schema.

## Vor dem Livegang noch offen

- Indoor-Import für ganz Deutschland einmal messen (Speicher, Dauer), `INDOOR_IMPORT_MEMORY` anpassen
- OSRM Deutschland im Betrieb messen (RSS nach einigen Routen), Speicherlimit anpassen
