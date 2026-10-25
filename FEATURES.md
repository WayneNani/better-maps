# better-maps – geplante Features

Fork von [CoMaps](https://codeberg.org/comaps/comaps) (Apache 2.0) mit dem Ziel, näher an den
Funktionsumfang von Google Maps heranzukommen – ohne den Offline- und Datenschutz-Anspruch aufzugeben.
Änderungen sollen möglichst nah an Upstream bleiben, damit Verbesserungen von CoMaps weiter einfließen.

## 1. Orte in der Nähe mit „Jetzt geöffnet“

Cafés und andere Einrichtungen im Umkreis anzeigen, filterbar nach aktuell geöffneten Orten.

- Kategoriesuche um einen Filter „Jetzt geöffnet“ erweitern.
- `opening_hours` aus OSM ist bereits in den MWM-Kartendateien enthalten; Parser und Auswertung
  liegen in `3party/opening_hours/` (u. a. `rules_evaluation.hpp`).

## 2. Bessere Suche

Relevantere Ergebnisse und bessere Suche nach Einrichtungen, ähnlich Google Maps.

- **Abfrageverständnis:** Deutsche Synonyme pro Kategorie ergänzen (z. B. „Frühstück“ → Café,
  „Sprit“ → Tankstelle). Die Suchbegriffe liegen in
  `data/translations/categories-strings/de.json/localize.json`.
- **Filter aus der Anfrage:** Anfragen wie „Café offen“ oder „Apotheke Sonntag“ in Kategorie +
  Filter zerlegen (baut auf Feature 1 auf).
- **Ranking:** Geöffnete Orte bevorzugen; Wichtigkeitssignale nutzen (Wikidata/Wikipedia, Marke,
  Vollständigkeit der Tags); Nähe zum Kartenausschnitt stärker gewichten. Einstieg:
  `libs/search/ranker.cpp`, `libs/search/ranking_info.cpp`.
- **Optionale Online-Suche:** Photon oder Nominatim auf eigenem Server für Regionen, die nicht
  heruntergeladen sind.
- **Später:** Semantische Suche mit kleinem On-Device-Embedding-Modell – nur für Kategorien,
  nicht für einzelne POIs.

Offline-Daten für ganz Deutschland: ca. 4,6 GB (48 Regionsdateien, Kartenstand 2026-06),
Suchindex inklusive. Die Suche bleibt dabei performant.

## 3. ÖPNV-Routing mit Fahrplan und Echtzeit

Echte Verbindungen mit Bus, Tram und Bahn statt des heutigen U-Bahn-Routers.
Gute Abfahrts- und Verspätungszeiten sind unverzichtbar.

- **Basis:** [Transitous](https://transitous.org) (MOTIS) als Online-Opt-in.
- **Einstellbar:** Umstiege, Umsteigezeit, Fußwege, Gehgeschwindigkeit, Barrierefreiheit,
  Fahrradmitnahme (über die Parameter der MOTIS-`/plan`-API).
- **Später:** Eigener MOTIS-Server mit gtfs.de-Fahrplänen, GTFS-RT-Echtzeit und OSM-Daten –
  gleiche API, in der App ändert sich nur die URL.
- **Anbieter-Schicht in der App:** Inoffizielle DB-Schnittstelle optional für bessere Echtzeit,
  direkt vom Gerät; bei Ausfall Rückfall auf Transitous.

## 4. Stauinformationen aus dem Verkehrsfunk

- Verkehrsmeldungen aus TMC (UKW/RDS), später eventuell TPEG (DAB+).
- Austauschformat TraFF, aufbauend auf der Arbeit von Michael von Glasow.
- Vorhandene Verkehrsschicht aus MAPS.ME-Zeiten reaktivieren: `libs/traffic/`.
- Ergänzend offene Daten: Autobahn-API und DATEX II aus der Mobilithek.

## 5. Datenschutzfreundliche Stauerkennung

Staus aus anonymen Bewegungsdaten der Nutzer erkennen, ähnlich wie Google.

- Map-Matching auf dem Gerät mit dem Straßengraphen von CoMaps.
- Gesendet wird nur „Abschnitt, Geschwindigkeitsbereich, Zeitfenster“ – kein Identifikator,
  Fahrtanfang und -ende werden abgeschnitten.
- Lokale Differential Privacy (Rauschen), Übertragung über OHTTP, Veröffentlichung erst ab einer
  Mindestzahl an Meldungen (k-Schwelle); optional DAP über Janus.
- Schutz vor Missbrauch durch Ratenbegrenzung und Plausibilitätsprüfungen.

## Technische Grundentscheidungen

- **App:** C++ (Kern) und Kotlin (Android-Oberfläche), nah an Upstream. Kein Rust-Port.
- **Server-Dienste:** Go (Verkehrsdaten-Pipeline, Aggregation der Stau-Meldungen, Proxy/Cache);
  Janus als fertiger Dienst, falls DAP gebraucht wird.
- **ÖPNV-Backend:** Transitous, später optional eigener MOTIS-Server.
