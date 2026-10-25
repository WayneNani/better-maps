# better-maps – Umsetzungsplan

Dieser Plan setzt die Features aus [FEATURES.md](FEATURES.md) um. Er ist in **Arbeitspakete (AP)** zerlegt, die
von Subagenten parallel in **eigenen Git-Worktrees** bearbeitet werden können. Pakete derselben Welle berühren
möglichst keine gemeinsamen Dateien.

Stand der Analyse: 2026-10-25, `main` = `2cd7d8f8c` (CoMaps-Spiegel).

## Ausgangslage

| Thema | Vorhanden | Quelle |
|---|---|---|
| „Jetzt geöffnet“ | Wird pro Suchtreffer berechnet und in der Liste angezeigt, aber **kein Filter** | `libs/search/intermediate_result.cpp:282`, `android/app/src/main/java/app/organicmaps/search/SearchAdapter.java:153` |
| Suche | Kategorien, Ranking, Suchparameter | `data/translations/categories-strings/`, `libs/search/ranker.cpp`, `libs/search/search_params.hpp` |
| ÖPNV | Offline nur U-Bahn aus OSM; experimentelles GTFS ohne Echtzeit | `libs/routing/transit_*`, `docs/EXPERIMENTAL_PUBLIC_TRANSPORT_SUPPORT.md` |
| ÖPNV online | **Branch `transit`**: MOTIS-Client (OpenAPI-Codegen), `TransitService`, Abfahrten einer Haltestelle in der Ortsansicht | 4 Commits, Aug. 2026 |
| Verkehrslage | **Branch `traffic`** (mvglasow): `libs/traffxml`, TraFF-HTTP-Quelle, Android-Quelle für Companion-Apps (Qz/TMC), Einbindung ins Routing | 329 Commits, letzter 2026-10-24, Doku `docs/TRAFFIC.md` |
| Bewertungen | Mangrove-Bewertungen in den MWM-Dateien | `docs/REVIEWS.md` |

Probe-Merges auf `main` (per `git merge-tree`):
- `traffic`: **konfliktfrei**
- `LMBishop/preview-route-step`: konfliktfrei
- `transit`: Konflikte nur in Build-Dateien. `main` hat `build.gradle` → `build.gradle.kts` migriert, dazu kommt `libs.versions.toml`.

Build-Umgebung lokal: Android SDK mit NDK 27 und 28, Java, Go vorhanden. 16 Kerne, 31 GB RAM, ca. 119 GB frei.
Submodule (`3party/`) sind noch **nicht** initialisiert.

## Branch- und Worktree-Strategie

- **`main`** ist der Hauptzweig von better-maps. Upstream-CoMaps kommt als Remote `upstream` dazu
  (`https://codeberg.org/comaps/comaps.git`) und wird regelmäßig in `main` gemergt.
- **Feature-Branches** heißen `bm/<ap-kürzel>`, jeweils in einem eigenen Worktree unter `.worktrees/` bzw. dem
  Worktree-Mechanismus der Subagenten. Merge nach `main` erst nach Build und Tests, per PR oder `--no-ff`.
- **Upstream-WIP-Branches** (`traffic`, `transit`) werden nicht umgeschrieben. Wir bauen auf einer
  Kopie auf (`bm/traffic`, `bm/transit`) und übernehmen neue Upstream-Commits per Merge. Eigene Verbesserungen
  möglichst als PR an Upstream zurückgeben, damit der Fork klein bleibt.
- **Server-Code (Go)** liegt im Verzeichnis `services/` dieses Repos, getrennt vom App-Code. Konflikte mit
  Upstream sind dort ausgeschlossen. Später kann er in ein eigenes Repo wandern.

**Ressourcen-Grenze:** Ein vollständiger Android-Build von CoMaps ist C++-lastig. Höchstens **zwei App-Builds
gleichzeitig** (je ~8 Kerne). Go-Pakete laufen ohne Einschränkung parallel. `ccache` für alle Worktrees teilen.

## Wellenübersicht

```
Welle 0 (seriell)   AP0 Einrichtung + Referenz-Build
                       │
Welle 1 (parallel)  AP1 Filter „Jetzt geöffnet“      [App: Suche]
                    AP2a Deutsche Such-Synonyme      [nur Daten]
                    AP3a transit auf main + /plan    [App: ÖPNV]
                    AP4a traffic integrieren         [App: Verkehr]
                    AP4b TraFF-Server in Go          [services/]
                       │
Welle 2 (parallel)  AP2b Ranking + Filter aus Anfrage   (nach AP1)
                    AP3b ÖPNV-Oberfläche + Anbieter     (nach AP3a)
                    AP4c DATEX-II-Quelle                (nach AP4b)
                    AP5a Aggregations-Server Stau       (nach Design-Freigabe)
                       │
Welle 3 (parallel)  AP5b Melde-Client in der App        (nach AP4a, AP5a)
                    AP3c DB-Anbieter (optional)          (nach AP3b)
                    AP3d Eigener MOTIS-Server (optional)
                    AP2c Online-Suche Photon (optional)
                    AP4d TMC-Empfang testen (Qz)          (nach AP4a)
```

In Welle 1 laufen drei App-Pakete. Wegen der Build-Grenze bauen AP1 und AP3a zuerst, AP4a danach. AP2a und
AP4b brauchen keinen App-Build.

---

## Welle 0 – Einrichtung (seriell, Hauptsitzung)

**AP0 Einrichtung**
1. `FEATURES.md` und `IMPLEMENTATION_PLAN.md` auf `main` committen und pushen.
2. Remote `upstream` (Codeberg) hinzufügen, `git fetch upstream`.
3. Submodule initialisieren (`git submodule update --init --recursive`), `./configure.sh` laut `docs/INSTALL.md`.
4. Referenz-Build der Android-App (F-Droid-Debug-Variante) und der C++-Unit-Tests für `search` und `routing`.
   Dauer und Befehle in `docs/BETTER_MAPS_BUILD.md` festhalten, damit jeder Subagent dieselben Befehle nutzt.
5. `.worktrees/` in `.gitignore`, `ccache` einrichten.

**Fertig, wenn:** APK baut, Unit-Tests laufen, Build-Anleitung liegt im Repo.

---

## Welle 1

### AP1 – Filter „Jetzt geöffnet“ (Feature 1)
- **Basis:** `main` → `bm/open-now`
- **Bereiche:** `libs/search/search_params.hpp`, `libs/search/processor.cpp` bzw. `ranker.cpp`
  (Treffer mit `IsOpenNow() == No` verwerfen), JNI-Brücke der Suche, Android `search/SearchFragment.java`
  und `SearchCategoriesFragment.java` (Filter-Chip „Jetzt geöffnet“, zusätzlich in der Kategorieliste).
- **Entscheidung:** Orte mit **unbekannten** Öffnungszeiten standardmäßig ausblenden, aber als Option
  „auch unbekannte zeigen“ einblenden (sonst wirkt das Ergebnis in ländlichen Regionen leer).
- **Tests:** Unit-Test in `libs/search/search_tests` mit Orten, die offen, geschlossen und ohne Öffnungszeiten sind.
- **Fertig, wenn:** Filter in der App schaltbar, Kategoriesuche „Café“ zeigt nur geöffnete, Tests grün.

### AP2a – Deutsche Such-Synonyme (Feature 2, Teil 1)
- **Basis:** `main` → `bm/search-synonyms`
- **Bereiche:** nur `data/translations/categories-strings/*/localize.json` (Deutsch), keine Code-Änderung.
- **Aufgabe:** Synonymliste für die häufigsten Kategorien (Café, Bäckerei, Restaurant, Tankstelle, Apotheke,
  Supermarkt, Arzt, Bank/Geldautomat, Haltestelle, Parkplatz …), z. B. „Frühstück“, „Kaffee“, „Sprit“.
- **Fertig, wenn:** Liste ergänzt, Prüfskripte unter `tools/` für Kategorien laufen sauber,
  passender Teil als Upstream-PR vorbereitet.

### AP3a – `transit` auf `main` bringen und Routing anbinden (Feature 3, Teil 1)
- **Basis:** `origin/transit` → `bm/transit`, dann `main` hineinmergen.
- **Konflikte:** OpenAPI-Codegen aus `android/sdk/build.gradle` nach `android/sdk/build.gradle.kts` übertragen,
  `android/gradle/libs.versions.toml` zusammenführen. `android/app/build.gradle` entsprechend.
- **Erweiterung:** `TransitService` um eine Methode `plan(...)` für den MOTIS-Endpunkt `/plan` ergänzen,
  mit einem Optionsobjekt (`maxTransfers`, `minTransferTime`, `additionalTransferTime`, `transferTimeFactor`,
  `pedestrianSpeed`, `pedestrianProfile`, `useRoutedTransfers=true`, `requireBikeTransport`, `transitModes`,
  `numItineraries`, `pageCursor`). MOTIS-Server-URL konfigurierbar, Standard Transitous.
- **Tests:** Unit-Tests gegen aufgezeichnete MOTIS-Antworten (JSON-Fixtures), keine Live-Aufrufe in Tests.
- **Fertig, wenn:** App baut, Abfahrten in der Ortsansicht funktionieren auf aktuellem `main`,
  `plan()` liefert geparste Verbindungen.

### AP4a – `traffic` integrieren (Feature 4, Teil 1)
- **Basis:** `origin/traffic` → `bm/traffic`, `main` hineinmergen (Probe-Merge war konfliktfrei).
- **Aufgabe:** Bauen, C++-Tests für `traffxml`/`traffic`/`routing` ausführen, mit den Beispiel-Feeds aus
  `data/test_data/traff` prüfen (Desktop-App oder `traff_assessment_tool`). Konfiguration der HTTP-TraFF-Quelle
  so vorbereiten, dass sie auf unseren Server aus AP4b zeigen kann.
- **Nicht ändern:** Kern-Logik des Branches. Fehler als Issue bzw. PR an mvglasow/Upstream.
- **Fertig, wenn:** App mit Verkehrsschicht baut, Beispiel-Feed wird eingefärbt und beeinflusst das Routing.

### AP4b – TraFF-Server in Go (Feature 4, Teil 2)
- **Basis:** `main` → `bm/traff-server`, Verzeichnis `services/traff-server/`
- **Aufgabe:**
  1. TraFF-HTTP-API laut Spezifikation umsetzen (Abonnement mit zufälliger ID, Filter mit Bounding-Box und
     Mindest-Straßenklasse, Polling, nur geänderte Meldungen pro Feed). Referenz: `docs/TRAFFIC.md` im
     Branch `traffic` und die HTTP-Quelle in `libs/traffxml`.
  2. Erste Datenquelle: **Autobahn-API** (autobahn.api.bund.dev): Baustellen, Warnungen, Sperrungen →
     TraFF-Meldungen mit OpenLR- bzw. Koordinaten-Ortsangaben.
  3. Keine Logs mit IP-Adressen, Abonnements laufen nach Zeit ab.
- **Tests:** Go-Unit-Tests für Umwandlung und Abo-Logik, Integrationstest mit dem Client aus AP4a
  (sobald verfügbar).
- **Fertig, wenn:** `go test ./...` grün, Server liefert gültige Feeds, Dockerfile vorhanden.

---

## Welle 2

### AP2b – Ranking und Filter aus der Anfrage (Feature 2, Teil 2)
- **Basis:** `main` nach Merge von AP1 → `bm/search-ranking`
- **Aufgabe:** „Café offen“, „Apotheke Sonntag“ in Kategorie + Filter zerlegen (Schlüsselwörter pro Sprache);
  im Ranking geöffnete Orte bevorzugen, Wichtigkeitssignale (Wikidata, Marke, Tag-Vollständigkeit)
  und Nähe zum Kartenausschnitt stärker gewichten (`libs/search/ranker.cpp`, `ranking_info.cpp`).
- **Tests:** Ranking-Tests in `libs/search/search_quality` bzw. `search_tests` mit festen Beispielanfragen.
- **Fertig, wenn:** Vorher/Nachher-Vergleich für ~30 Testanfragen dokumentiert, keine Verschlechterung bei
  Adresssuchen.

### AP3b – ÖPNV-Oberfläche und Anbieter-Schicht (Feature 3, Teil 2)
- **Basis:** `bm/transit` nach AP3a → `bm/transit-ui`
- **Aufgabe:**
  1. Anbieter-Schicht: `RoutingProvider`/`DepartureProvider` mit Transitous als erster Implementierung,
     automatischer Rückfall bei Fehlern.
  2. ÖPNV-Modus im Routenplaner: Liste der Verbindungen (Abfahrt, Ankunft, Umstiege, Verspätung),
     „frühere/spätere“ per `pageCursor`, Detailansicht mit Fußwegen auf der Karte.
  3. Einstellungen: Umsteigezeit, Gehgeschwindigkeit, Barrierefrei, Fahrrad mitnehmen, max. Fußweg.
  4. Opt-in-Dialog beim ersten Aufruf (Online-Anfrage an Transitous).
- **Fertig, wenn:** Verbindung von A nach B mit Bus/Bahn inklusive Echtzeit wird angezeigt und auf der Karte
  dargestellt.

### AP4c – DATEX-II-Quelle (Feature 4, Teil 3)
- **Basis:** `bm/traff-server` nach AP4b
- **Aufgabe:** Verkehrsmeldungen aus der Mobilithek (DATEX II) einlesen und nach TraFF umwandeln.
  Zugangsdaten nur serverseitig (Umgebungsvariablen, nicht im Repo).
- **Fertig, wenn:** Meldungen beider Quellen erscheinen ohne Dubletten im Feed.

### AP5a – Aggregations-Server für Stau-Meldungen (Feature 5, Teil 1)
- **Voraussetzung:** Kurzes Design-Dokument `docs/CROWD_TRAFFIC_PRIVACY.md` (Meldungsformat, Rauschen,
  k-Schwelle, Aufbewahrungsfristen), vom Projektinhaber freigegeben.
- **Basis:** `main` → `bm/crowd-server`, Verzeichnis `services/crowd-traffic/`
- **Aufgabe:** Annahme anonymer Meldungen (Abschnitt, Geschwindigkeitsbereich, Zeitfenster) über
  OHTTP-Gateway, Aggregation pro Abschnitt und 15-Minuten-Fenster, Veröffentlichung ab k Meldungen,
  **Ausgabe als TraFF-Feed**. Die App braucht dadurch keine neue Darstellungslogik, die Daten laufen über AP4a/AP4b.
- **Fertig, wenn:** Simulierte Meldungen erzeugen erwartete TraFF-Meldungen, Einzelmeldungen werden nicht gespeichert.

---

## Welle 3

### AP5b – Melde-Client in der App (Feature 5, Teil 2)
- **Basis:** `main` nach Merge von AP4a und AP5a → `bm/crowd-client`
- **Aufgabe:** Opt-in; nur während aktiver Navigation; Map-Matching über den Straßengraphen in `libs/routing`;
  Anfang und Ende der Fahrt abschneiden; Rauschen auf dem Gerät; Versand gebündelt und verzögert über OHTTP.
- **Fertig, wenn:** Testfahrt (Simulation per GPX) erzeugt korrekte, anonymisierte Meldungen.

### AP3c – DB-Anbieter (optional)
- Kotlin-Client für die DB-Schnittstelle (Vorbild `db-vendo-client`), nur vom Gerät, abschaltbar,
  Rückfall auf Transitous. Rechtliches Risiko bewusst entscheiden, bevor es veröffentlicht wird.

### AP3d – Eigener MOTIS-Server (optional)
- `services/motis/` mit Docker Compose: gtfs.de-Fahrpläne, GTFS-RT, Geofabrik-OSM; wöchentlicher Import-Job.
  Erst mit einem Bundesland testen, Ressourcenbedarf messen.

### AP2c – Online-Suche (optional)
- Photon (oder Nominatim) als zusätzlicher Suchanbieter für nicht heruntergeladene Regionen, Opt-in.

### AP4d – TMC-Empfang testen
- Mit Qz und einem TMC-Empfänger prüfen, ob die Android-TraFF-Quelle aus AP4a Meldungen übernimmt.
  Braucht Hardware, deshalb manuell.

---

## Regeln für Subagenten

Jeder Subagent bekommt **genau ein AP** und arbeitet in seinem eigenen Worktree. Vorlage für den Auftrag:

> Du arbeitest an AP<x> aus `IMPLEMENTATION_PLAN.md` im Repo better-maps (CoMaps-Fork), in deinem eigenen
> Git-Worktree. Lege den Branch `bm/<kürzel>` von `<basis>` an. Halte dich an die dort genannten Bereiche und
> ändere keine Dateien außerhalb davon ohne Rückfrage. Nutze die Build-Befehle aus `docs/BETTER_MAPS_BUILD.md`.
> Halte den Code-Stil von CoMaps ein (`docs/CPP_STYLE.md`, `docs/JAVA_STYLE.md`). Committe in kleinen Schritten,
> pushe nicht. Melde am Ende: was erledigt ist, Testergebnis (Befehl + Ausgabe), offene Punkte, Branch-Name.

Weitere Regeln:
- **Kein Push, kein Merge nach `main`** durch Subagenten. Das macht die Hauptsitzung nach Prüfung.
- **Keine Zugangsdaten** im Repo; Server-Keys nur über Umgebungsvariablen.
- **Builds drosseln:** höchstens zwei gleichzeitige App-Builds (`-j8`).
- Upstream-taugliche Änderungen (z. B. AP1, AP2a) so schreiben, dass sie als PR an CoMaps gehen können.

## Entscheidungen (2026-10-25)

Der Projektinhaber hat die Koordination delegiert. Getroffene Entscheidungen:

1. **Unbekannte Öffnungszeiten:** Bei aktivem Filter ausgeblendet; ein zweiter Schalter
   „auch ohne Öffnungszeiten“ blendet sie wieder ein.
2. **Server-Code:** in diesem Repo unter `services/`, jeder Dienst mit eigenem `go.mod` und `Containerfile`.
3. **DB-Anbieter (AP3c):** zurückgestellt. Wird nicht automatisch gestartet, weil die inoffizielle Schnittstelle
   rechtlich und technisch unsicher ist. Die Anbieter-Schicht aus AP3b wird so gebaut, dass er später einsteckbar ist.
4. **Hosting:** vorerst nur lokal mit Podman (Entwicklung und Tests). Öffentliches Hosting entscheidet der
   Projektinhaber später.
5. **Welle 3 automatisch:** AP5b und AP2c werden umgesetzt; AP3d (eigener MOTIS-Server) nur als
   Compose-Datei mit kleinem Testausschnitt; AP4d braucht Hardware und bleibt manuell.

## Nicht im Plan, aber interessant

Vorhandene Branches, die sich später übernehmen lassen: `LMBishop/preview-route-step` (Routenvorschau,
konfliktfrei), `reviews-add-review-link` (Link „Bewertung schreiben“), `zy-mwm-pano` (Panoramax-Fotos),
`zy-indoor-mapping` (Innenpläne, z. B. für Bahnhöfe), `hbond/live-notification`.
