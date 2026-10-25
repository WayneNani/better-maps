# better-maps: Bauen, Testen, Emulator

Alle Befehle laufen über `tools/better-maps/bm.sh` (ohne Argumente zeigt es die Hilfe). Das Skript funktioniert
im Haupt-Checkout und in jedem Worktree unter `.worktrees/`.

## Einrichtung (einmalig)

Getestet in einer Debian-13-Distrobox auf einem Fedora-Host.

1. Pakete (Debian): `build-essential cmake ninja-build ccache jq optipng python3-venv qt6-base-dev qt6-svg-dev
   qt6-positioning-dev libicu-dev libfreetype-dev libharfbuzz-dev libxrandr-dev libxinerama-dev libxcursor-dev
   libxi-dev libgl-dev clang` (siehe auch `docs/INSTALL.md`).
2. Android SDK unter `~/Android/Sdk` mit NDK 28.2.13676358, CMake 3.22.1, Plattform 36, Emulator und
   System-Image `android-35;google_apis;x86_64`; JDK 21 unter `~/java/jdk-21.*` (oder `BM_JAVA_HOME` setzen).
3. Remote `upstream` = `https://codeberg.org/comaps/comaps.git`. Die relativen Submodul-URLs (`../kothic.git`,
   `../protobuf.git`, `../just_gtfs.git`) zeigen sonst auf den GitHub-Spiegel und müssen per
   `git config submodule.<pfad>.url https://codeberg.org/comaps/<name>.git` umgebogen werden.
4. `git submodule update --init --recursive --depth 1`, dann `./configure.sh`.
5. Commit-Signing ist für dieses Repo lokal abgeschaltet (`commit.gpgsign=false`), weil der globale
   Signierschlüssel eine Hardware-Bestätigung braucht.

Neuer Worktree für ein Arbeitspaket:

```bash
git worktree add .worktrees/<name> -b bm/<name> main
.worktrees/<name>/tools/better-maps/bm.sh worktree-setup
```

`worktree-setup` übernimmt Submodule lokal aus dem Haupt-Checkout (ohne Netz, ca. 40 s), die Boost-Header und die
von `configure.sh` erzeugten Dateien. Nach Änderungen an Kategorien im Haupt-Checkout
`tools/unix/generate_json_strings.sh` und `tools/unix/generate_categories.sh` erneut ausführen.

## Befehle

| Befehl | Zweck |
|---|---|
| `bm.sh android-build` | Debug-APK (F-Droid, nur x86_64) |
| `bm.sh android-unit` | JVM-Tests von App und SDK |
| `bm.sh android-connected` | Instrumentierte Tests im Emulator (App bleibt installiert) |
| `bm.sh android-run` | APK installieren (`-r -d -g`) und starten |
| `bm.sh emulator bash <skript>` | Eigenes adb-Skript exklusiv am Emulator ausführen |
| `bm.sh cpp-test <ziel …>` | C++-Testprogramme bauen und starten (Desktop, Debug, `build/desktop`) |
| `bm.sh go-test services/<dienst>` | `go vet` + `go test -race -cover` |
| `tools/better-maps/traffic-e2e.sh` | TraFF-Server + C++-Client Ende-zu-Ende (braucht `~/.cache/bettermaps-maps/Germany_Berlin.mwm`) |
| `tools/better-maps/crowd-e2e.sh` | Crowd-Gateway/Relay + C++/Java-Client Ende-zu-Ende |

Schwere Builds laufen über zwei rechnerweite Slots (`flock`, `nice`, 6 Jobs), damit parallele Worktrees den
Rechner nicht überlasten. Testserver wählen freie Ports.

## Emulator

- AVD `bettermaps_api35` (API 35, x86_64, 3 GB). Läuft headless **auf dem Host** als systemd-User-Unit
  `bettermaps-emulator`; `adb` in der Distrobox sieht ihn als `emulator-5554`.
- Bekannte Probleme auf diesem Rechner: In der Distrobox stürzt qemu ab; auf dem Host stürzen
  `-gpu swiftshader_indirect` und `-gpu guest` ab, nur `-gpu host` bootet. Mit `distrobox-host-exec`
  gestartete Hintergrundprozesse sterben mit der Shell, daher `systemd-run --user`.
- Der Emulator ist gemeinsam genutzt: Installation **und** Prüfung immer in einem Skript über
  `bm.sh emulator bash`, nie deinstallieren oder Daten löschen. Weltkarte und Berlin sind geladen.
- Den Host erreicht der Emulator unter `10.0.2.2`.

## Bekannte Testfehler (Upstream)

Auf `main` schlagen 8 Such-Tests fehl, deren Erwartungen nach Upstream-Änderungen veraltet sind (verdoppelte
Entfernungsgewichte 9b1fb1e99, entfernte Stoppwörter 71b47719a u. a.); Details im Merge von AP2b:

- `search_tests`: `BookmarksProcessorTest_Smoke`, `NameScore_Smoke`, `RankingInfo_PreferCountry`
- `search_integration_tests`: `ProcessorTest_StopWords`, `ProcessorTest_Suburbs1`, `ProcessorTest_PostCategoryTest`,
  `RankerTest_PreferCountry`, `SmokeTest_CategoriesTest`

Drei `routing_tests` (`RoadAccess_WayBlockedWhenStartButOpenWhenReach`, zwei `OpeningHoursSerDes`) hängen von der
Zeitzone ab; mit `TZ=UTC` sind sie grün.
