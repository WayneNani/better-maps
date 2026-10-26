# Datenschutz in Better Maps

Better Maps funktioniert ohne Internetverbindung. Es gibt keine Werbung, keine Analyse-Werkzeuge und kein Konto bei
Better Maps. Die App sendet nur dann Daten, wenn du eine der folgenden Funktionen nutzt. Online-Funktionen, die über
den Kartendownload hinausgehen, sind ausgeschaltet, bis du sie in den Einstellungen oder im jeweiligen Dialog
einschaltest, und lassen sich dort jederzeit wieder abschalten.

| Funktion | Was gesendet wird | Empfänger | Standard |
|---|---|---|---|
| Karten herunterladen und aktualisieren | Name und Version der Kartendatei | Kartenserver von CoMaps | beim Herunterladen |
| Öffentlicher Verkehr (Verbindungen, Abfahrten) | Start, Ziel bzw. Haltestellenposition, gewünschte Zeit, Optionen | [Transitous](https://transitous.org) oder ein selbst eingetragener MOTIS-Server | aus |
| Online-Suche | Suchbegriff, auf etwa 1 km gerundete Position, Sprache | [Photon](https://photon.komoot.io) oder ein selbst eingetragener Server | aus |
| Verkehrslage | Kartenausschnitte (Bounding-Boxen) der geladenen Regionen, anonyme Abo-Kennung | der eingetragene TraFF-Server (voreingestellt: `api.dev.traffxml.org`) | aus |
| Beitrag zur Stauerkennung | Pro 15 Minuten höchstens 12 Meldungen „Abschnitt (≈ 200 m), Geschwindigkeitsklasse (verrauscht), Zeitfenster“, ohne Kennung, Anfang und Ende jeder Fahrt abgeschnitten, verschlüsselt über Oblivious HTTP | Relay und Aggregations-Server (siehe [Design](docs/CROWD_TRAFFIC_PRIVACY.md)) | aus |
| OpenStreetMap bearbeiten | Deine Änderungen und dein OSM-Konto | openstreetmap.org | nur nach Anmeldung |

Jeder Server sieht bei einer Anfrage technisch bedingt deine IP-Adresse. Die Server von Better Maps
(`services/traff-server`, `services/crowd-traffic`) speichern keine IP-Adressen und keine einzelnen Meldungen; bei den
Diensten Dritter gelten deren Datenschutzbestimmungen.

Fehlerberichte werden nur auf deinen Wunsch erstellt und über die Teilen-Funktion deines Geräts an eine App deiner
Wahl übergeben.
