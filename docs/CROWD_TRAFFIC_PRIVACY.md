# Datenschutzfreundliche Stauerkennung (Feature 5) – Design

Status: **Freigegeben für die Umsetzung (AP5b)** – von der Koordination im Auftrag des Projektinhabers, 2026-10-25. Eine abschließende Durchsicht durch den Projektinhaber (v. a. Abschnitt 11, Restrisiken) steht noch aus.
Implementierung: `services/crowd-traffic/` (Server, Referenz-Client, Simulator).
Client in der App: AP5b, siehe Abschnitt [Client-Vertrag](#13-client-vertrag-ap5b).

## 1. Überblick

Die App meldet während einer aktiven Navigation, wie schnell sie einzelne Straßenabschnitte durchfahren hat.
Der Server zählt diese Meldungen pro Abschnitt und 15-Minuten-Fenster und veröffentlicht einen Stau erst, wenn
mindestens k unabhängige Meldungen vorliegen. Ziel ist eine Stauerkennung „wie bei Google“, bei der aber
niemand – auch nicht der Betreiber – Fahrten einzelner Personen rekonstruieren kann.

```
 App (AP5b)                         Relay (Dritter)            Gateway + Aggregation (wir)        App (AP4a)
 ─────────────────────────────      ─────────────────          ─────────────────────────────      ───────────
 Map-Matching auf dem Gerät          sieht IP-Adresse,          sieht Inhalt, keine IP             TraFF-Feed
 → Kachel-Abschnitte                 nicht den Inhalt           → Zähler pro Abschnitt+Fenster     über traff-server
 → Trimmen, Stichprobe, Rauschen  ─► Ratenlimit pro IP     ─►   → k-Schwelle, Entzerrung       ─►  (Quelle „crowd“)
 → Bündel, Verzögerung, Padding      leitet blind weiter        → Stau-Zustand → Export
 → OHTTP-Verschlüsselung
```

Schutzschichten, jede für sich nicht ausreichend, zusammen tragfähig:

1. **Datensparsamkeit auf dem Gerät:** nur Abschnitt, Geschwindigkeitsklasse, Zeitfenster; keine Kennung,
   keine Uhrzeit genauer als 15 min, keine Koordinaten; Fahrtanfang und -ende entfernt; nur Stichprobe.
2. **Lokale Differential Privacy** auf die Geschwindigkeit (randomisierte Antwort, ε = 3 pro Meldung).
3. **Unverknüpfbarkeit beim Transport:** Oblivious HTTP (RFC 9458), Relay und Gateway getrennt betrieben.
4. **Aggregation mit k-Schwelle** (k = 10) und Speicherung nur von Zählern.

## 2. Bedrohungsmodell

| Akteur | Fähigkeiten | Was er nicht erfahren soll | Schutz |
|---|---|---|---|
| **Neugieriger Server** (Gateway-/Aggregations-Betreiber, auch bei Beschlagnahme oder Einbruch) | sieht alle entschlüsselten Meldungen in Echtzeit, kann beliebig loggen | wer eine Meldung geschickt hat; Fahrten (Start, Ziel, Route) einer Person; genaue Geschwindigkeit | keine Kennung, keine IP (OHTTP), Trimmung, Stichprobe ≤ 12 Abschnitte pro Fenster, LDP auf Geschwindigkeit, grobe Zeit |
| **Netzbeobachter** (WLAN, Mobilfunkbetreiber) | sieht Verbindungen des Geräts | Inhalt, Ort, Fahrverhalten | TLS zum Relay, OHTTP-Verschlüsselung, einheitliche Größe (Padding auf 4096 Byte), feste Sendezeitpunkte nach Fensterende |
| **Relay** | sieht IP-Adresse, Zeitpunkt, Größe | Inhalt | OHTTP (HPKE); das Relay kann Anfragen verwerfen oder verzögern, aber nicht lesen oder unbemerkt ändern |
| **Relay + Server gemeinsam** (Absprache) | IP + Inhalt | – | **nicht abgedeckt** – deshalb müssen Relay und Gateway von unabhängigen Parteien betrieben werden (Abschnitt 8) |
| **Böswillige Clients / Sybil** (z. B. die „99 Handys im Bollerwagen“-Aktion) | erzeugen beliebig viele gültige Meldungen | – (Ziel: Integrität) | Ratenlimit am Relay, Validierung, robuste Schätzung, Konfidenzregel; vollständig verhindern lässt sich ein physischer Sybil-Angriff ohne Identität nicht (Abschnitt 10) |
| **Öffentlichkeit / Export-Konsumenten** | sehen TraFF-Feed und JSON-Export | Anwesenheit Einzelner auf einem Abschnitt | k-Schwelle, nur abgeleitete Werte (Zustand, Median), LDP |

**Nicht-Ziele:** Schutz gegen ein kompromittiertes Gerät; Schutz gegen ein kollaborierendes Relay+Gateway;
Verbergen, *dass* jemand die App mit aktivierter Meldung nutzt (das Relay sieht OHTTP-Anfragen).

## 3. Abschnitts-Schlüssel

Die Feature-IDs von CoMaps ändern sich mit jedem Kartenupdate, OSM-Wege werden ständig geteilt und
zusammengelegt. Der Schlüssel muss daher **unabhängig von der Kartenversion** und auf allen Geräten gleich
sein.

**Entscheidung: Durchfahrt einer Web-Mercator-Kachel der Zoomstufe 17.**

```
Schlüssel = (Kachel x, Kachel y auf z17, Einfahrtskante, Ausfahrtskante, Straßenklasse)
```

- Kacheln nach dem bekannten Slippy-Map-Schema (`x = floor((lon+180)/360 · 2^17)`, `y` analog mit Mercator).
  Eine Kachel ist ≈ 305 m · cos(Breite) groß, in Deutschland **≈ 180–210 m** – im gewünschten Bereich von
  100–500 m.
- Ein Abschnitt ist die Fahrt durch eine Kachel von einer Kante (N/O/S/W) zu einer anderen. **Fahrtrichtung**
  steckt in der Reihenfolge der Kanten (W→O ≠ O→W); auch Abbiegebeziehungen an Kreuzungen sind getrennt.
- **Straßenklasse** trennt Autobahn und parallel laufende Neben- oder Erschließungsstraßen.
- Zusätzlich meldet der Client die Position des Kantenübergangs (`offset`, 0–255, ≈ 0,7 m Raster). Die
  Positionen sind **nicht Teil des Schlüssels** (kleine Geometrieänderungen zwischen Kartenversionen würden ihn
  sonst spalten). Der Server bildet daraus pro Schlüssel ein Histogramm (8-Pixel-Klassen ≈ 6 m) und nimmt
  den häufigsten Wert als `from`/`to` der TraFF-Meldung – so liegen die Punkte auf der Straße und der
  TraFF-Decoder der App (AP4a) findet die richtige Strecke.

**Begründung gegenüber Alternativen:**

| Variante | Bewertung |
|---|---|
| CoMaps-Feature-ID + Segmentindex | ändert sich mit jedem Kartenupdate; Clients mit verschiedenen Kartenständen melden auf verschiedene Schlüssel |
| OpenLR | robust, aber Encoder und Decoder sind aufwendig (Kandidatensuche, Routing); der Server müsste dekodieren, um zu aggregieren – zwei Clients erzeugen für dieselbe Strecke oft unterschiedliche Codes |
| Quantisierte Start-/Endkoordinaten freier Teilstücke | Schnittpunkte hängen von der Segmentierung ab; Quantisierungsgrenzen spalten Schlüssel |
| **Kachel-Durchfahrt (gewählt)** | deterministisch aus der Geometrie, nur abhängig vom gefahrenen Verlauf; trivial auf dem Gerät berechenbar; global; leicht zu dokumentieren |

Grenzen: Zwei parallele Straßen **gleicher Klasse und Richtung** in derselben Kachel fallen zusammen (selten,
v. a. Innenstadt). Kurze Eck-Durchfahrten (< 50 m) werden nicht gemeldet. Eine Kachel mit Wechsel der
Straßenklasse wird nicht gemeldet.

## 4. Meldungsformat

Eine Meldung (Feld `reports[]` im Bündel, JSON):

| Feld | Typ / Wertebereich | Bedeutung |
|---|---|---|
| `window` | Ganzzahl, Unix-Sekunden, Vielfaches von 900 | Beginn des 15-min-Fensters, in dem die Kachel **verlassen** wurde |
| `tile.x`, `tile.y` | 0 … 131071 | Kachel auf Zoomstufe 17 |
| `entry.side`, `exit.side` | `"N"`, `"E"`, `"S"`, `"W"` | Einfahrts- und Ausfahrtskante |
| `entry.offset`, `exit.offset` | 0 … 255 | Position auf der Kante in Pixeln einer 256-px-Kachel, von der NW-Ecke aus (N/S-Kante: von West nach Ost; W/O-Kante: von Nord nach Süd) |
| `road_class` | `"MOTORWAY"`, `"TRUNK"`, `"PRIMARY"`, `"SECONDARY"`, `"TERTIARY"` | Straßenklasse (OSM `highway=*`, ohne `*_link`) |
| `speed_bucket` | 0 … 6 | **verrauschte** Geschwindigkeitsklasse (Abschnitt 5) |

Geschwindigkeitsklassen (km/h, Mittelwert über die Kachel = gefahrene Strecke / Zeit zwischen Ein- und Ausfahrt):

| Klasse | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| Bereich | 0–10 | 10–20 | 20–35 | 35–55 | 55–80 | 80–110 | ≥ 110 |

Die Klassen sind fein im Staubereich und grob bei freier Fahrt; mehr Klassen würden das Rauschen bei
gleichem ε verstärken. Es gibt **kein** Feld für Uhrzeit, Kennung, Gerät, App-Version, Kartenversion,
Fahrt oder Reihenfolge.

## 5. Rauschen (lokale Differential Privacy)

**Mechanismus:** k-äre randomisierte Antwort (Generalized Randomized Response) über die 7 Klassen. Mit der
wahren Klasse v meldet der Client

- v mit Wahrscheinlichkeit p = e^ε / (e^ε + 6),
- jede andere Klasse mit Wahrscheinlichkeit q = 1 / (e^ε + 6).

**Parameter: ε = 3 pro Meldung** ⇒ p = 0,770, q = 0,038. Jede einzelne Meldung ist ε-LDP: Für jede Ausgabe
ist das Wahrscheinlichkeitsverhältnis zweier beliebiger wahrer Werte höchstens e³ ≈ 20. Fast jede vierte
Meldung ist falsch; ob eine bestimmte gemeldete „≥ 110 km/h“ stimmt, ist nicht entscheidbar.

**Begründung von ε = 3:** Geschützt wird die Geschwindigkeit einer *anonymen* Meldung; das Risiko (z. B.
Nachweis einer Überschreitung) ist ohne Personenbezug gering, die Nutzbarkeit hängt dagegen stark an ε.
Bei ε = 2 läge der Standardfehler eines Anteils bei n = 10 Meldungen bei ≈ 0,15–0,3, Staus ließen sich erst
ab ≈ 30 Meldungen sicher erkennen, was bei geringer Verbreitung der App praktisch nie erreicht würde. ε = 3
liegt im Bereich eingesetzter Systeme (Apple verwendet je nach Anwendungsfall ε = 2 bis 8 pro Tag, Google
RAPPOR Werte im niedrigen einstelligen Bereich).

**ε pro Tag:** Der Client sendet höchstens 12 Meldungen pro Fenster, **höchstens 96 pro Tag** und
**höchstens 2 pro Abschnitts-Schlüssel und Tag** (Hin- und Rückweg). Damit gilt:

- pro Abschnitt und Tag: ε ≤ 6 (Komposition von 2 Meldungen),
- über alle Meldungen eines Tages, *falls* der Server sie einer Person zuordnen könnte: ε ≤ 288. Diese Schranke
  ist formal wertlos – der Schutz über einen Tag beruht bewusst auf der **Unverknüpfbarkeit** (keine Kennung,
  OHTTP, Stichprobe, Trimmung), nicht auf DP. Die LDP schützt den Messwert einer Meldung, auch dann, wenn ein
  Angreifer die Meldung isoliert (z. B. durch k−1 gefälschte Meldungen, Abschnitt 11).
- Option für AP5b: Memoisierung à la RAPPOR (gleicher Abschnitt + gleiche wahre Klasse am selben Tag ⇒
  gleiche verrauschte Antwort) senkt die Tages-Komposition für Pendler auf ε = 3 pro Abschnitt.

**Wichtig:** LDP schützt nur die Geschwindigkeit. *Dass* auf Abschnitt X im Fenster W jemand gefahren ist,
sieht der Server im Klartext; davor schützen Trimmung, Stichprobe und Unverknüpfbarkeit.

**Entzerrung auf dem Server:** Für n Meldungen mit Klassenzählern n_j ist

```
f̂_j = (n_j / n − q) / (p − q)
```

ein erwartungstreuer Schätzer des wahren Anteils f_j (E[n_j/n] = q + f_j (p − q)). Weil f̂ negativ werden kann,
projiziert der Server auf das Simplex (**Norm-Sub**: finde δ mit Σ max(f̂_j − δ, 0) = 1; Wang et al.,
NDSS 2020) und interpoliert daraus den **Median** linear innerhalb der Klasse. Für die Freifluss-Referenz
(V85, Abschnitt 6) werden zusätzlich Klassen verworfen, deren Schätzung innerhalb von 2 Standardfehlern
von 0 liegt (**Base-Cut**), weil Rauschreste in leeren hohen Klassen das 85-%-Quantil sonst nach oben ziehen.

Standardfehler des geschätzten Anteils einer Klassenmenge S mit beobachtetem Anteil π_S:
SE = √(π_S (1 − π_S) / n) / (p − q), mit p − q = 0,732:

| n | SE (Klasse mit wahrem Anteil 0) | SE (Anteil ≈ 50 %) |
|---|---|---|
| 10 | 0,083 | 0,216 |
| 20 | 0,059 | 0,153 |
| 50 | 0,037 | 0,097 |
| 100 | 0,026 | 0,068 |

Die Tests (`internal/ldp`, `internal/aggregate`) prüfen mit festem Seed: Erwartungstreue (Mittel über 4000
Wiederholungen innerhalb von 5 Standardfehlern), Übereinstimmung der Standardfehler-Formel mit der empirischen
Streuung (±10 %), Fehlerschranke bei n = 10 (99 % der Fehler < 0,5) und die Raten der Stau-Entscheidung.

**Keine Plausibilitätsprüfung auf der verrauschten Klasse:** „130 km/h auf einer Tertiärstraße“ ist bei
randomisierter Antwort ein normaler Wert. Würde der Server solche Meldungen verwerfen, wäre der Schätzer
verzerrt. Geprüft werden nur unverrauschte Felder (Abschnitt 10).

## 6. Zeitfenster, k-Schwelle und Stau-Zustand

- **Fenster:** 15 min, beginnend bei Vielfachen von 900 s (UTC). Der Client ordnet eine Durchfahrt dem
  Fenster zu, in dem er die Kachel verlassen hat.
- **Auswertung:** Ein Fenster wird **5 min nach seinem Ende** (Karenzzeit) ausgewertet. Bis dahin nimmt der
  Server Meldungen für das laufende und das vorige Fenster an, danach nur noch für das laufende. Veröffentlicht
  werden nur abgeschlossene Fenster, nie ein laufendes, damit niemand durch Differenzbildung zweier
  Veröffentlichungen eine einzelne Meldung isolieren kann. Latenz: 5–20 min nach der Durchfahrt.
- **k-Schwelle: k = 10** Meldungen pro Abschnitt und Fenster. Jede Meldung eines Abschnitts in einem Fenster
  stammt aus einem anderen Bündel (ein Bündel darf jeden Schlüssel nur einmal enthalten), also – soweit ohne
  Identität feststellbar – von verschiedenen Fahrten. Unter k wird **nichts** veröffentlicht und **nichts**
  aufbewahrt. Begründung: (1) Eine Veröffentlichung soll nicht die Fahrt Einzelner sichtbar machen (z. B. ein
  „Stau“ nachts auf einer Nebenstraße); (2) unter 10 verrauschten Meldungen ist die Schätzung zu unsicher
  (SE ≥ 0,2).
- **Freifluss-Referenz** pro Abschnitt: V85 (85-%-Quantil) der entzerrten Langzeitverteilung. Die Zähler
  aller Fenster mit ≥ k Meldungen gehen in ein exponentiell abklingendes Histogramm ein (Halbwertszeit
  7 Tage). Solange es weniger als 100 (abgeklungene) Meldungen enthält, gilt ein bewusst niedriger
  Straßenklassen-Standard: Autobahn 100, Kraftfahrstraße 80, Primär 50, Sekundär 45, Tertiär 40 km/h. Die
  beobachtete V85 wird auf das 0,6- bis 1,3-fache dieses Werts begrenzt. V85 statt Median über
  Nicht-Stau-Zeiten, weil es ohne Zirkelschluss (was ist „Nicht-Stau“?) auskommt und Staus bis ≈ 15 % der
  Zeit die Referenz nicht beeinflussen.
- **Zustand** aus dem Verhältnis r = Median / Referenz (Schwellen zwischen den relativen Geschwindigkeiten
  der CoMaps-Speed-Groups G5 100 %, G4 83 %, G3 58 %, G2 33 %, G1 16 %):

  | r | Zustand | TraFF-Ereignis | App (Speed-Group) |
  |---|---|---|---|
  | ≥ 0,75 | frei | – (keine Meldung) | – |
  | 0,55–0,75 | dichter Verkehr | `CONGESTION_HEAVY_TRAFFIC` | G4 |
  | 0,35–0,55 | stockender Verkehr | `CONGESTION_SLOW_TRAFFIC` | G3 |
  | < 0,35, Median ≥ 10 km/h | Stau | `CONGESTION_QUEUE` | G2 |
  | < 0,35, Median < 10 km/h | stehender Verkehr | `CONGESTION_STATIONARY_TRAFFIC` | G1 |

- **Konfidenzregel:** Ein Stau wird nur gemeldet, wenn der geschätzte Anteil der Fahrzeuge unter
  0,75 · Referenz auch nach Abzug von **einem Standardfehler** über 50 % liegt. „Dichter Verkehr“ wird nur
  gemeldet, wenn die Referenz beobachtet ist (sonst könnte schlicht der Standard zu hoch sein, etwa auf der
  Stadtautobahn mit 80 km/h).
- **Gemessen** (Tests mit Rauschen, 2000 Abschnitte, fester Seed): freie Fahrt 95–130 km/h → 0 von 2000
  fälschlich als Stau (n = 10 und n = 30); Stop-and-go 3–25 km/h → 98,6 % erkannt bei n = 10, 100 % bei
  n = 30. Ende-zu-Ende-Simulation (A100-Stau, 101 Seeds): kein Stau außerhalb der Stauzone, ≥ 90 % der
  veröffentlichten Stauabschnitte erkannt.

**Ausgabe:** TraFF-0.8-Meldungen (eine pro Abschnitt, stabile ID `org.bettermaps.crowd:17/x/y/E-A/KLASSE`),
`from`/`to` auf den Kachelkanten, `road_class`, `directionality="ONE_DIRECTION"`, ein
`CONGESTION_*`-Ereignis mit `speed` = geschätzter Median. `expiration_time` = Auswertung + 20 min; eine
neuere Auswertung ersetzt die Meldung. Der Feed enthält nur aktuelle Staus; `services/traff-server` hebt
verschwundene Meldungen selbst auf. Format und Endpunkte: `services/crowd-traffic/README.md`.

## 7. Regeln auf dem Gerät (Kurzfassung, verbindlich im Client-Vertrag)

1. **Opt-in**, standardmäßig aus; nur während **aktiver Auto-Navigation**; jederzeit abschaltbar, dabei
   werden ungesendete Meldungen verworfen.
2. Nur Straßenklassen MOTORWAY … TERTIARY, keine Auffahrten (`*_link`), keine Wohn- und Erschließungsstraßen.
3. **Fahrtanfang:** pro Fahrt zufällige Strecke D ∈ [1000, 1500] m; Abschnitte, die vor D beginnen oder
   näher als 1000 m (Luftlinie) am Startpunkt liegen, werden verworfen. Die Zufallsgrenze verhindert, dass
   der Startpunkt aus der ersten Meldung zurückgerechnet wird.
4. **Ziel:** Abschnitte, die weniger als 1500 m Route oder 1000 m Luftlinie vor dem Navigationsziel enden,
   werden verworfen.
5. **Ungeplantes Ende:** Ein Abschnitt wird erst freigegeben, wenn das Fahrzeug danach weitere 500 m
   gefahren ist oder 3 min vergangen sind und die Navigation noch läuft. Endet die Navigation (Ankunft,
   Abbruch, App beendet), werden nicht freigegebene Abschnitte verworfen.
6. **Stichprobe:** pro Fenster höchstens 12 zufällig ausgewählte Abschnitte, jeder Schlüssel höchstens einmal;
   pro Tag höchstens 96 Meldungen und höchstens 2 pro Schlüssel.
7. **Rauschen** auf jede Meldung (Abschnitt 5) mit kryptographisch sicherem Zufall.
8. **Bündelung und Verzögerung:** ein Bündel pro Fenster, gesendet zu einem zufälligen Zeitpunkt 30–240 s
   nach Fensterende, Meldungen in zufälliger Reihenfolge. Was bis dahin nicht freigegeben ist, entfällt.
9. **Padding:** Die Binary-HTTP-Anfrage wird auf 4096 Byte aufgefüllt; alle Anfragen sind gleich groß.
10. Keine Wiederholung fehlgeschlagener Bündel nach Ablauf der Karenzzeit; keine lokale Historie über den
    Tag hinaus (die Tageszähler werden um Mitternacht gelöscht).

## 8. Oblivious HTTP

**Rollen (RFC 9458):** *Client* (App) → *Oblivious Relay Resource* (Relay, sieht IP, nicht Inhalt) →
*Oblivious Gateway Resource* (`POST /gateway`, sieht Inhalt, nicht IP) → *Target Resource* `POST /v1/reports`
(im selben Prozess; es gibt **keinen** Endpunkt, der Meldungen ohne OHTTP annimmt).

- **Relay:** muss von einer unabhängigen Partei betrieben werden (kommerzieller OHTTP-Relay-Dienst, z. B. von
  Fastly oder Cloudflare, oder eine Partnerorganisation), sonst ist OHTTP wirkungslos. Es leitet nur den
  verschlüsselten Body und `Content-Type` weiter (kein `X-Forwarded-For`, kein User-Agent), begrenzt die Rate
  pro IP-Adresse (IPv6 pro /64) und reicht die Schlüsselkonfiguration durch. `cmd/ohttp-relay` ist eine
  Entwicklungsversion davon.
- **Kryptographie:** HPKE (RFC 9180) mit DHKEM(X25519, HKDF-SHA256); angeboten werden
  HKDF-SHA256 + AES-128-GCM und HKDF-SHA256 + ChaCha20-Poly1305. Binary HTTP (RFC 9292), known-length.
- **Bibliothek:** HPKE aus `github.com/cloudflare/circl` v1.6.4 (BSD-3-Clause, von Cloudflare aktiv gepflegt;
  v1.6.4 vom Juni 2026 ist die letzte Version, die mit Go 1.24 baut, v1.6.5 verlangt Go 1.25). Die
  OHTTP-Kapselung (≈ 380 Zeilen inkl. Schlüsselformat) und Binary HTTP (≈ 330 Zeilen) sind eine dünne eigene
  Schicht ohne eigene Kryptographie, geprüft gegen das vollständige Beispiel aus RFC 9458,
  Anhang A (Schlüsselkonfiguration, Entschlüsselung der Anfrage, byte-genaue Antwort). Verworfen:
  `github.com/chris-wood/ohttp-go` (gepflegt, aber verlangt Go 1.25 und hat keine Releases),
  `github.com/confidentsecurity/ohttp` (Go 1.25, schwere Abhängigkeiten wie quic-go und OpenTelemetry).
- **Schlüsselverteilung:** `GET /ohttp-keys` (`application/ohttp-keys`) liefert die Konfiguration der
  laufenden und der nächsten Epoche. Clients holen sie **über das Relay** (alle bekommen dieselbe Antwort, das
  Gateway kann einzelne Clients nicht über eigene Schlüssel markieren) und cachen sie bis zu 24 h.
- **Key-Rotation:** Epochen von 7 Tagen. Der Schlüssel der Epoche e wird aus einem Master-Geheimnis abgeleitet:
  `seed = HKDF-SHA256(master, salt="better-maps crowd-traffic ohttp key v1", info="epoch"‖e)`, Key-ID = e mod 256.
  Das Gateway akzeptiert die Epochen e−1, e, e+1 – höchstens drei gültige Schlüssel zugleich. Die Rotation
  braucht keinen Zustand; mehrere Gateway-Instanzen mit demselben Master liefern identische Schlüssel. Das
  Master-Geheimnis kommt nur aus der Umgebung (`CROWD_OHTTP_MASTER_KEY`) oder einer Datei.
- **Replay-Schutz (RFC 9458, 6.5):** Die innere Anfrage muss einen `Date`-Header tragen, der höchstens 5 min
  von der Serverzeit abweicht. Das Gateway merkt sich den (gekürzten) SHA-256 des zufälligen `enc`-Werts jeder
  Anfrage für 10 min und weist Wiederholungen ab. `enc` ist pro Anfrage frisch und enthält keine Information
  über den Client.
- **Fehler:** Unbekannte Schlüssel-ID → HTTP 400 mit Problem-Typ `…#ohttp-key` (Client holt neue Schlüssel);
  alle Fehler *nach* der Entschlüsselung (Datum, Replay, ungültiges Bündel) stehen verschlüsselt in der inneren
  Antwort – das Relay erfährt nicht, ob eine Meldung angenommen wurde.

## 9. Aufbewahrung

| Daten | Ort | Lebensdauer |
|---|---|---|
| IP-Adresse | nur Relay, Ratenlimit-Zähler im Speicher | bis der Zähler wieder voll ist (≤ 20 min bei Standardwerten); nie im Log |
| Verschlüsselte Anfrage | Relay/Gateway, nur während der Anfrage | Sekunden |
| Entschlüsselte Anfrage (Klartext) | Gateway, ein Puffer | bis zum Ende der Anfrage, danach mit Nullen überschrieben |
| **Einzelne Meldung** | – | **nie gespeichert**; nur Zähler werden erhöht |
| Zähler pro Abschnitt und offenem Fenster (7 Klassen + Kantenhistogramm) | Arbeitsspeicher | bis zur Auswertung (≤ 20 min nach Fensterbeginn); unter k danach spurlos gelöscht |
| Veröffentlichung (Zustand, Median, n) | Arbeitsspeicher, Export | 20 min ab Auswertung (oder bis zur nächsten) |
| Freifluss-Referenz (abklingendes Histogramm, nur aus Fenstern ≥ k) | Arbeitsspeicher | 14 Tage ohne neue Daten |
| Replay-Cache (Hash von `enc`) | Arbeitsspeicher | 10 min |
| Logs | stdout | nur Start/Stopp und aggregierte Zähler; keine IP, kein User-Agent, keine Meldungsinhalte |

Es gibt keine Datenbank und keine Dateien; ein Neustart löscht alles (die Referenzen beginnen wieder beim
Straßenklassen-Standard). Ein Test stellt sicher, dass nach der Auswertung keine Spur eines Abschnitts unter
k bleibt, dass der Klartextpuffer überschrieben wird und dass weder Log noch `/healthz` Kacheln, Klassen,
IP-Adressen oder User-Agents enthalten.

## 10. Missbrauchsschutz ohne Identifikatoren

1. **Ratenlimit am Relay** pro IP-Adresse (Standard: 30 Anfragen pro Stunde, Burst 10; ehrliche Clients
   senden ≤ 4–8 pro Stunde). Das Gateway hat zusätzlich ein globales Limit (Schutz der Ressourcen).
2. **Validierung ganzer Bündel** (ein Fehler ⇒ ganzes Bündel abgelehnt): Version; 1–12 Meldungen; alle aus
   demselben, aktuell offenen Fenster; gültige Kachel, Kanten, Offsets, Klasse; Ein- ≠ Ausfahrt; jeder Schlüssel
   nur einmal; alle Kacheln im Abdeckungsgebiet (Deutschland) und höchstens 400 Kacheln (≈ 75 km) voneinander
   entfernt – weiter kommt ein Fahrzeug in einem Fenster nicht.
3. **Robuste Schätzung:** Median statt Mittelwert; eine Minderheit gefälschter Meldungen (z. B. 10 gegen 30)
   kann weder einen Stau erzeugen noch verbergen (Test `TestOutlierRobustness`).
4. **Konfidenzregel und Referenzbegrenzung** (Abschnitt 6): Gefälschte schnelle Meldungen können die Referenz
   höchstens auf das 1,3-fache des Standards heben.
5. **Abgleich** im `traff-server` mit amtlichen Quellen (Autobahn-API, DATEX II) ist möglich, aber nicht Teil
   von AP5a.
6. **Option: anonyme Tokens** nach Privacy Pass (RFC 9576–9578). Ein Aussteller gibt pro Gerät und Tag z. B.
   8 blinde Tokens aus (Bindung an Geräteattestierung oder ein Konto), das Gateway verlangt pro Bündel ein
   Token. Damit wäre ein Sybil-Angriff auf die Zahl der Geräte begrenzt, ohne dass das Gateway Geräte
   wiedererkennt. Aufwand: eigener Aussteller-Dienst und Attestierung (Play Integrity widerspricht dem
   F-Droid-Gedanken) – daher zurückgestellt.

**Grenze:** Ein physischer Sybil-Angriff wie die „99 Handys im Bollerwagen“ (echte Geräte, echte Bewegung,
verschiedene IPs) ist ohne Identität nicht zu verhindern; er erzeugt einen Stau auf einem Abschnitt für ein
Fenster. Die Wirkung ist auf eine Umfahrungsempfehlung begrenzt.

## 11. Bewusste Restrisiken

- **Anwesenheit im Klartext:** Der Server sieht „jemand fuhr im Fenster W über Abschnitt X“. Auf sehr ruhigen
  Straßen kann ein Betreiber, der alle Anfragen protokolliert, aufeinanderfolgende Abschnitte desselben Bündels
  zu einem groben Routenstück (≤ 12 Kacheln, ohne Anfang und Ende) verbinden. Gegenmaßnahmen: Stichprobe,
  Trimmung, keine Kennung über Bündel hinweg; Option: Bündel aufteilen (z. B. 3 × 4 Meldungen zu
  unabhängigen Zeitpunkten).
- **Isolationsangriff auf die k-Schwelle:** Wer k−1 Meldungen fälscht, erfährt aus einer Veröffentlichung,
  dass mindestens eine echte Meldung vorlag (Anwesenheit). Die Geschwindigkeit bleibt durch LDP geschützt.
- **Relay und Gateway in einer Hand** heben die Unverknüpfbarkeit auf; die Betreiberwahl ist eine
  organisatorische Voraussetzung.
- **Schlüsselkonsistenz:** Ein böswilliges Gateway könnte über die Schlüsselkonfiguration Clients markieren,
  wenn das Relay sie nicht einheitlich ausliefert. Abhilfe: Konfiguration über das Relay mit Cache, langfristig
  Key-Transparency (draft-ietf-privacypass-key-consistency).
- **Verkehrsanalyse:** Das Relay sieht, dass ein Gerät zu bestimmten Zeiten meldet (also navigiert). Abhilfe
  (Option): Attrappen-Bündel in Fenstern ohne Fahrt; Kosten: Akku und Datenvolumen.
- **Kollisionen von Schlüsseln** paralleler Straßen gleicher Klasse (Abschnitt 3) mischen deren Messwerte.
- **Latenz** von 5–20 min durch Fenster und Karenzzeit; kürzere Fenster würden weniger Meldungen pro Fenster
  und mehr Rauschen bedeuten.
- **Neustart** des Servers verliert die Referenzen; bis 100 Meldungen pro Abschnitt gelten die (niedrigen)
  Standardwerte und „dichter Verkehr“ wird nicht gemeldet.

## 12. Optionale Erweiterung: DAP / Janus

Mit dem Distributed Aggregation Protocol (draft-ietf-ppm-dap, Implementierung **Janus** von Divvi Up) wird
jede Meldung in zwei Anteile zerlegt, die zwei nicht kollaborierende Aggregatoren (Leader, Helper) erhalten
(Prio3Histogram über die 7 Klassen, mit Gültigkeitsbeweis). Kein einzelner Server sähe dann die Geschwindigkeit
einer Meldung; das lokale Rauschen könnte entfallen oder durch zentrales Rauschen auf das Aggregat ersetzt
werden (deutlich genauer bei gleichem ε). Probleme: DAP aggregiert pro *Task*, nicht pro frei wählbarem
Schlüssel – der Abschnitt müsste als öffentliche Gruppierung (z. B. ein Task pro Region und Fenster mit
`Prio3SumVec` über Abschnitt×Klasse) abgebildet werden, was Messwertlänge und Rechenaufwand stark erhöht. Dazu
kommt ein zweiter, unabhängiger Betreiber. Deshalb nur als Ausblick, wenn die Nutzerzahl das rechtfertigt.

## 13. Client-Vertrag (AP5b)

Verbindlich für die App. Referenzimplementierung: `services/crowd-traffic/internal/client`
(Segmentierung `segment.go`, Regeln `pipeline.go`, Versand `sender.go`); Beispiele erzeugt
`go run ./cmd/crowd-sim -dry-run`.

### 13.1 Konstanten

| Name | Wert |
|---|---|
| Zoomstufe | 17, Kachelkante 256 Pixel |
| Fenster | 900 s, Beginn bei Vielfachen von 900 (Unix-Zeit) |
| Geschwindigkeitsklassen | Grenzen 10, 20, 35, 55, 80, 110 km/h (7 Klassen) |
| ε | 3 (p = e³/(e³+6), q = 1/(e³+6)) |
| Meldungen | ≤ 12 pro Bündel/Fenster, ≤ 96 pro Tag, ≤ 2 pro Schlüssel und Tag |
| Mindestlänge in der Kachel | 50 m; Messungen über 250 km/h verwerfen |
| Trimmung | Start: U(1000, 1500) m Route und 1000 m Luftlinie; Ziel: 1500 m Route und 1000 m Luftlinie |
| Freigabe | 500 m weiter oder 3 min nach Verlassen der Kachel, Navigation läuft noch |
| Sendezeitpunkt | Fensterende + U(30 s, 240 s) |
| Padding | Binary-HTTP-Nachricht auf 4096 Byte |
| Endpunkte | Relay-URL (Konfiguration), Schlüssel über das Relay: `<relay>/ohttp-keys` |

### 13.2 Segmentierung

Eingabe: die auf den Straßengraphen abgebildete Spur (Positionen mit Zeit und Straßenklasse des jeweils
folgenden Stücks). In globalen Pixelkoordinaten der Zoomstufe 17 (`px = (lon+180)/360 · 2^17 · 256`,
`py` entsprechend Web Mercator) für jedes Stück A→B:

1. Alle Schnittpunkte mit Gitterlinien `px = i·256` und `py = j·256` bestimmen, nach Lage auf A→B sortieren
   (bei Gleichstand zuerst die senkrechte Linie). Ein Punkt genau auf einer Linie gehört zur östlichen bzw.
   südlichen Kachel.
2. Für jeden Schnittpunkt: Ausfahrtskante der aktuellen Kachel (Bewegung +x → E, −x → W, +y → S, −y → N),
   Offset = `floor(py − y·256)` (E/W) bzw. `floor(px − x·256)` (N/S), auf 0…255 begrenzt; Zeitpunkt
   linear interpoliert. War die Kachel über eine Kante betreten worden, entsteht eine Durchfahrt
   (Kachel, Einfahrt, Ausfahrt, Länge, Ein-/Ausfahrtszeit). Die nächste Kachel beginnt mit der
   Gegenkante und demselben Offset.
3. Durchfahrten mit wechselnder Straßenklasse, nicht meldbarer Klasse, Länge < 50 m oder Ein- = Ausfahrt
   (gleiche Kante und gleicher Offset) entfallen. Geschwindigkeit = Länge / (Ausfahrt − Einfahrt).

### 13.3 Verrauschen

```
bucket = Klasse der gemessenen Geschwindigkeit (0…6)
u = Zufallszahl in [0, 1)              // kryptographisch sicherer Generator
if u < p:  melde bucket
else:      j = Zufallszahl in {0, …, 5}; if j >= bucket: j = j + 1; melde j
```

### 13.4 Anfrage

1. Schlüssel holen: `GET <relay>/ohttp-keys` → `application/ohttp-keys` (RFC 9458, 3.2). Die erste
   Konfiguration mit KEM 0x0020 und einem unterstützten Paar (KDF 0x0001, AEAD 0x0001 oder 0x0003) verwenden.
   Bei fehlerhafter Liste: ganz verwerfen.
2. Bündel als JSON (UTF-8, keine weiteren Felder):

   ```json
   {
     "version": 1,
     "reports": [
       {"window": 1793029500, "tile": {"x": 70394, "y": 43010},
        "entry": {"side": "W", "offset": 201}, "exit": {"side": "E", "offset": 239},
        "road_class": "MOTORWAY", "speed_bucket": 0},
       {"window": 1793029500, "tile": {"x": 70395, "y": 43011},
        "entry": {"side": "N", "offset": 109}, "exit": {"side": "E", "offset": 21},
        "road_class": "MOTORWAY", "speed_bucket": 5}
     ]
   }
   ```

   (A100 bei Berlin-Schöneberg, Fenster 2026-10-26 15:45–16:00 UTC; dasselbe Beispiel prüft
   `internal/protocol/protocol_test.go`.)
3. Binary HTTP (RFC 9292), known-length, auf 4096 Byte mit Nullen aufgefüllt:

   ```
   00                                          Framing: Known-Length Request
   04 "POST"  05 "https"  18 "crowd.bettermaps.invalid"  0b "/v1/reports"
   40 41  04 "date" 1d "Mon, 26 Oct 2026 16:01:30 GMT"  0c "content-type" 10 "application/json"
   <Länge als QUIC-Varint> <JSON-Bündel>
   00                                          leere Trailer
   00 00 … 00                                  Padding bis 4096 Byte
   ```

   `date` ist die aktuelle Zeit (IMF-fixdate); die Authority ist frei (der Gateway bedient das Ziel selbst).
4. OHTTP-Kapselung (RFC 9458, 4.3): `hdr = key_id ‖ kem_id ‖ kdf_id ‖ aead_id`,
   `info = "message/bhttp request" ‖ 0x00 ‖ hdr`, HPKE-Base-Modus, Anfrage = `hdr ‖ enc ‖ ciphertext`.
5. `POST <relay-url>` mit `Content-Type: message/ohttp-req`, ohne weitere identifizierende Header (keine
   Cookies, kein individueller User-Agent).

### 13.5 Antwort

- HTTP 200 + `message/ohttp-res`: entkapseln (RFC 9458, 4.4) und als Binary HTTP lesen.
  - innen **204**: angenommen.
  - innen **400** `application/problem+json`, `type` …`#date` (mit `date`-Header der Serverzeit; Uhr prüfen),
    `urn:better-maps:crowd-traffic:replay` oder `urn:better-maps:crowd-traffic:invalid-bundle` (`title` =
    Grund, z. B. `window_late`, `duplicate_key`): **nicht** wiederholen, Bündel verwerfen.
  - innen 404/405/415: Programmierfehler.
- HTTP 400 mit Problem-Typ `https://iana.org/assignments/http-problem-types#ohttp-key`: Schlüssel neu holen,
  **einmal** neu kapseln und senden.
- HTTP 429/503 (Relay- oder Gateway-Limit) oder Netzfehler: höchstens bis zum Ende der Karenzzeit
  (Fensterende + 5 min) mit Zufallsverzögerung wiederholen, danach verwerfen.

Ablehnungsgründe (`title`): `syntax`, `too_large`, `version`, `empty`, `too_many`, `mixed_windows`,
`window_misaligned`, `window_late`, `window_future`, `tile`, `side`, `offset`, `same_edge_position`,
`road_class`, `speed_bucket`, `duplicate_key`, `coverage`, `spread`.

## 14. Umsetzung in der App (AP5b)

**Aufteilung.** Die Regeln des Client-Vertrags liegen plattformunabhängig in C++, Verschlüsselung und Versand
auf Android-Seite:

| Teil | Ort | Inhalt |
|---|---|---|
| Kern-Logik | `libs/crowd_traffic/` | `tiles` (Kachelgitter), `segmenter` (13.2, inkrementell), `client` (Trimmung, Freigabe, Fenster, Stichprobe, Tageslimits, Rauschen), `randomness` (sicherer Zufall, randomisierte Antwort), `daily_limits`, `protocol` (JSON, Binary HTTP mit Padding), `route_follower` |
| Anbindung an die Navigation | `libs/map/crowd_traffic_recorder.*`, Aufrufe in `RoutingManager` | Fahrtbeginn bei `FollowRoute` (nur Auto), Positionen nach dem Route-Matching, Ende bei Ankunft/Abbruch/`CloseRouting` |
| JNI | `android/sdk/src/main/cpp/.../crowdtraffic/CrowdTraffic.cpp` | fällige Bündel als fertige Binary-HTTP-Anfragen an Java |
| OHTTP, Versand, Planung | `android/sdk/.../sdk/crowdtraffic/` | `Ohttp` (RFC 9458), `BinaryHttp`, `CrowdTrafficSender`, `CrowdTrafficScheduler`, `CrowdTrafficSettings`, `CrowdTraffic` |
| Einstellungen | `TrafficSettingsFragment`, `prefs_traffic.xml` | Opt-in „Anonym zur Stauerkennung beitragen“ (aus), Link auf dieses Dokument, Relay-URL (Experten, leer = aus) |

**Datenquelle.** Map-Matching ist das Route-Matching der Navigation: Die Position wird auf die Routenlinie
projiziert (`FollowedPolyline`), die seit dem letzten Fix passierten Routenpunkte werden mit nach Strecke
interpolierter Zeit eingefügt, die Straßenklasse kommt aus `RouteSegment::RoadNameInfo` (`m_highwayClass`,
`m_isLink` ⇒ nicht meldbar; der Router setzt die Klasse nur im ersten Segment eines Features, sie wird bis
zum nächsten Feature fortgeschrieben, Fake-Segmente sind nicht meldbar). Restdistanz zum Ziel: `Route::GetCurrentDistanceToEndMeters()`. Nur echte
GPS-Fixes werden verwendet (keine extrapolierten Positionen). Abseits der Route, beim Neuberechnen oder ohne
Treffer wird eine Lücke markiert: Die betroffenen Kacheln entfallen (Klasse „gemischt“).

**Kryptographie.** HPKE (RFC 9180) kommt aus **Bouncy Castle 1.81** (`bcprov-jdk18on`, MIT-Lizenz) auf
Java-Seite; `Ohttp` ist nur die dünne OHTTP-Rahmung (Header, `info`, Antwortschlüssel per Export + HKDF).
Begründung: Im Projekt ist mit Monocypher zwar X25519 und ChaCha20-Poly1305 vorhanden, aber kein SHA-256/HKDF,
das der Gateway (HKDF-SHA256) verlangt; eine C++-Lösung hätte eigene Hash-Implementierung oder eine neue
3party-Bibliothek erfordert. Unterstützt werden AES-128-GCM und ChaCha20-Poly1305 (das erste unterstützte
Paar der ersten passenden Konfiguration). Geprüft gegen RFC 9458, Anhang A, Vektoren des Go-Gateways
(`go run ./cmd/crowd-vectors java`) und Ende-zu-Ende gegen Gateway und Relay.

**Abweichungen und Präzisierungen gegenüber Abschnitt 13:**

- *Tageslimits ohne Orte:* Die Zähler pro Schlüssel liegen in 4096 Hash-Fächern (BLAKE2b mit täglich neuem
  Zufallssalz, Monocypher) in `crowd_traffic_limits.bin` (Fächer, Gesamtzahl, Salz, Tag – keine Schlüssel).
  Kollisionen machen das Limit nur strenger. „Tag“ ist der lokale Kalendertag des Geräts.
- *Zwischenziele* werden wie das Ziel behandelt (1000 m Luftlinie), unbekannte Restdistanz gilt als „nahe am
  Ziel“ (strenger als der Vertrag).
- *Freigabe nach 3 min* wird während laufender Navigation auch ohne neue Positionen geprüft (Stillstand im
  Stau); beim Ende der Navigation werden nicht freigegebene Abschnitte verworfen.
- *Planung* im Speicher (`ScheduledExecutorService`, Takt 5 s während der Navigation und solange Bündel
  ausstehen) statt WorkManager, damit keine Meldungsdaten auf den Datenträger gelangen. Stirbt der Prozess,
  gehen ungesendete Bündel verloren. Wiederholung bei 429/503/Netzfehler nach 10–40 s, längstens bis
  Fensterende + 5 min − 10 s.
- *User-Agent:* fest `better-maps-crowd-traffic` (statt des Android-Standards mit Gerätemodell und Build).
- *Relay-URL:* Basis-URL (`http://10.0.2.2:18090` ⇒ `/relay` und `/ohttp-keys`, wie `cmd/ohttp-relay`) oder
  vollständige URL der Relay-Ressource (Schlüssel daneben unter `ohttp-keys`).
- `libs/crowd_traffic` wird ohne `-ffast-math` übersetzt, damit Kachelgrenzen und Offsets genau wie im
  Go-Referenzclient berechnet werden.
- Nicht umgesetzt (Optionen aus Abschnitt 5 und 11): Memoisierung à la RAPPOR, Attrappen-Bündel, Aufteilen
  von Bündeln.

**Tests.** `bm.sh cpp-test crowd_traffic_tests` (u. a. byte-genaue Bündel ganzer Fahrten gegen den Go-Client
mit deterministischem Zufall, Segmentierung, Wire-Format, Verteilung der randomisierten Antwort, Tageslimits);
`bm.sh android-unit` (`app.organicmaps.sdk.crowdtraffic.*`: OHTTP gegen RFC 9458 und Go, Sender gegen einen
Test-Gateway, Planung, Einstellungen); `tools/better-maps/crowd-e2e.sh` (C++-Client → Java-Sender → Relay →
Gateway, Stau auf der A100 im Export). Testvektoren neu erzeugen:
`cd services/crowd-traffic && go run ./cmd/crowd-vectors cpp -out ../../libs/crowd_traffic/crowd_traffic_tests/go_vectors.hpp`.
