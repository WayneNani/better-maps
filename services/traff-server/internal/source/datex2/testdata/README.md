# Testdaten DATEX II

| Datei | Herkunft |
|-------|----------|
| `bw_incidents_v2.xml` | Auszug (12 Situationen) aus den Verkehrsmeldungen der Landesmeldestelle Baden-Württemberg, abgerufen am 25.10.2026 über MobiData BW (`https://api.mobidata-bw.de/datasets/traffic/incidents/incidents_lmsbw.datex2.xml`). Datenquelle: Landesmeldestelle BW / MobiData BW, Lizenz: Datenlizenz Deutschland – Namensnennung – Version 2.0 (dl-de/by-2-0). |
| `bw_roadworks_v2.xml` | Auszug (8 Situationen) aus den Baustelleninformationen Baden-Württemberg (MDM-Profil „Baustellen“), abgerufen am 25.10.2026 über MobiData BW (`https://api.mobidata-bw.de/datasets/traffic/roadworks/roadworks_svzbw.datex2.xml`). Datenquelle: Verkehrsministerium Baden-Württemberg / MobiData BW, Lizenz: dl-de/by-2-0. |
| `synthetic_v2.xml`, `synthetic_v3.xml`, `container_v3.xml`, `latin1_v2.xml`, `measured_v2.xml` | Selbst erstellt nach den Schemata DATEX II 2.3 (inkl. MDM-Erweiterungen) und 3.x, Inhalte erfunden. Decken Sonderfälle ab (ALERT-C ohne Koordinaten, BISSTRA, UTM, OpenLR, Gültigkeitszeiträume, Aufhebungen, …). |
| `traff_event_types.txt` | Ereignistypen der TraFF-Ereignisliste (`TraFF Event List.md`, https://gitlab.com/traffxml/traff). |
| `golden/` | Erwartete TraFF-Ausgabe; nach Prüfung mit `go test ./internal/source/datex2 -update` neu schreiben. |

Bezugszeitpunkt aller Tests: 2026-10-25T16:00:00Z.
