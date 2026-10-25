#!/usr/bin/env bash
# better-maps: gemeinsame Build-, Test- und Emulator-Befehle für alle Worktrees.
#
# Schwere Builds (Android, C++) laufen über zwei globale Slots (flock), damit parallel arbeitende
# Subagenten den Rechner nicht überlasten. Der Emulator ist ein gemeinsames Gerät und wird über
# einen eigenen Lock exklusiv benutzt.
#
# Aufruf aus einem beliebigen Worktree:  tools/better-maps/bm.sh <befehl> [argumente]
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
MAIN_ROOT="$(dirname "$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
export JAVA_HOME="${BM_JAVA_HOME:-$HOME/java/jdk-21.0.12.1+1}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmake/3.22.1/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# In der Distrobox meldet die JVM Cgroup-Warnungen auf stdout; die landen sonst in von AGP
# ausgewerteten Ausgaben (z. B. jmod --module-version) und brechen den Build.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xlog:os+container=off}"
export CCACHE_DIR="${CCACHE_DIR:-$HOME/.cache/ccache-bettermaps}"
export CCACHE_BASEDIR="$MAIN_ROOT"
export CCACHE_NOHASHDIR=true

NJOBS="${BM_NJOBS:-6}"
LOCK_DIR="${BM_LOCK_DIR:-$HOME/.cache/bettermaps-locks}"
AVD="${BM_AVD:-bettermaps_api35}"
APP_ID="app.comaps.fdroid.debug"
mkdir -p "$LOCK_DIR" "$CCACHE_DIR"

log() { echo "[bm] $*" >&2; }

# Führt einen Befehl in einem der zwei Build-Slots aus (wartet, bis einer frei ist).
with_build_slot() {
  while true; do
    for slot in 1 2; do
      exec {fd}>"$LOCK_DIR/build-$slot.lock"
      if flock -n "$fd"; then
        log "Build-Slot $slot belegt ($REPO_ROOT)"
        local rc=0
        nice -n 15 ionice -c3 "$@" || rc=$?
        flock -u "$fd"
        exec {fd}>&-
        return $rc
      fi
      exec {fd}>&-
    done
    sleep 20
  done
}

# Exklusive Nutzung des Emulators.
with_emulator() {
  exec {efd}>"$LOCK_DIR/emulator.lock"
  log "Warte auf Emulator-Lock ..."
  flock "$efd"
  local rc=0
  "$@" || rc=$?
  flock -u "$efd"
  exec {efd}>&-
  return $rc
}

emulator_running() { adb devices 2>/dev/null | grep -q '^emulator-.*device$'; }

emulator_start() {
  if emulator_running; then log "Emulator läuft bereits"; return 0; fi
  log "Starte Emulator $AVD auf dem Host (headless, 2 Kerne, 3 GB)"
  # Erfahrungen auf diesem Rechner (Fedora-Host, Debian-Distrobox):
  # - In der Distrobox stürzt qemu beim Start ab (Segfault).
  # - Auf dem Host stürzen -gpu swiftshader_indirect und -gpu guest ab (auch mit -feature -Vulkan);
  #   nur -gpu host bootet (~25 s).
  # - Ein per distrobox-host-exec gestarteter Hintergrundprozess wird mit der Shell beendet, darum
  #   läuft der Emulator als transiente systemd-User-Unit auf dem Host.
  # adb in der Distrobox erreicht ihn über das gemeinsame Netzwerk.
  local host=()
  command -v distrobox-host-exec >/dev/null && [[ -e /run/.containerenv ]] && host=(distrobox-host-exec)
  "${host[@]}" systemctl --user reset-failed bettermaps-emulator >/dev/null 2>&1 || true
  "${host[@]}" systemd-run --user --unit=bettermaps-emulator --collect \
    --setenv=ANDROID_AVD_HOME="$ANDROID_AVD_HOME" --setenv=ANDROID_HOME="$ANDROID_HOME" \
    bash -c "exec nice -n 5 '$ANDROID_HOME/emulator/emulator' -avd '$AVD' -no-window -gpu host \
-memory 3072 -cores 2 -no-snapshot -no-audio -no-boot-anim -no-metrics >'$LOCK_DIR/emulator.log' 2>&1" >/dev/null
  adb wait-for-device
  for _ in $(seq 1 120); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && { log "Emulator bereit"; return 0; }
    sleep 3
  done
  log "Emulator bootet nicht, siehe $LOCK_DIR/emulator.log"; return 1
}

emulator_stop() {
  emulator_running && adb emu kill || true
  if command -v distrobox-host-exec >/dev/null && [[ -e /run/.containerenv ]]; then
    distrobox-host-exec systemctl --user stop bettermaps-emulator >/dev/null 2>&1 || true
  else
    systemctl --user stop bettermaps-emulator >/dev/null 2>&1 || true
  fi
}

# Screenshot des Emulators, z. B. für UI-Prüfungen: bm.sh emulator-screenshot /pfad/bild.png
emulator_screenshot() { adb exec-out screencap -p >"${1:?Zieldatei fehlt}"; log "Screenshot: $1"; }

android_build() {
  cd "$REPO_ROOT/android"
  with_build_slot ./gradlew --no-daemon --max-workers=4 -Px86_64 -Pnjobs="$NJOBS" assembleFdroidDebug "$@"
}

android_unit() {
  cd "$REPO_ROOT/android"
  # App- und SDK-Modul; das SDK hat keine Produkt-Flavors.
  with_build_slot ./gradlew --no-daemon --max-workers=4 -Px86_64 -Pnjobs="$NJOBS" \
    :app:testFdroidDebugUnitTest :sdk:testDebugUnitTest "$@"
}

apk_path() { ls -t "$REPO_ROOT"/android/app/build/outputs/apk/fdroid/debug/*.apk | head -1; }

android_install_run() {
  local apk; apk="$(apk_path)"
  emulator_start
  # -d: andere Worktrees installieren evtl. einen höheren Versionscode derselben App-ID.
  adb install -r -d -g "$apk"
  adb shell am start -n "$APP_ID/app.organicmaps.SplashActivity" || adb shell monkey -p "$APP_ID" 1
}

# Instrumentierte Tests (androidTest) auf dem Emulator.
android_connected() {
  emulator_start
  cd "$REPO_ROOT/android"
  # leaveApksInstalledAfterRun: sonst deinstalliert Gradle die App und löscht die Karten im gemeinsamen Emulator.
  with_build_slot ./gradlew --no-daemon --max-workers=4 -Px86_64 -Pnjobs="$NJOBS" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true connectedFdroidDebugAndroidTest "$@"
}

cpp_configure() {
  local dir="$REPO_ROOT/build/desktop"
  [[ -f "$dir/build.ninja" ]] && return 0
  cmake -S "$REPO_ROOT" -B "$dir" -G Ninja -DCMAKE_BUILD_TYPE=Debug -DSKIP_QT_GUI=ON -DNJOBS="$NJOBS"
}

# Baut und startet C++-Testprogramme, z. B.: bm.sh cpp-test search_tests traffic_tests
cpp_test() {
  [[ $# -ge 1 ]] || { log "Ziel fehlt, z. B. search_tests"; return 2; }
  cpp_configure
  with_build_slot cmake --build "$REPO_ROOT/build/desktop" --target "$@" -- -j"$NJOBS"
  local rc=0
  for t in "$@"; do
    local bin; bin="$(find "$REPO_ROOT/build/desktop" -type f -name "$t" -perm -u+x | head -1)"
    log "Starte $bin"
    (cd "$REPO_ROOT/build/desktop" && "$bin" --data_path="$REPO_ROOT/data" --user_resource_path="$REPO_ROOT/data") || rc=$?
  done
  return $rc
}

go_test() {
  local dir="${1:?Verzeichnis unter services/ fehlt}"
  (cd "$REPO_ROOT/$dir" && nice -n 10 go vet ./... && nice -n 10 go test -race -cover ./...)
}

# Kopiert generierte, nicht versionierte Dateien (configure.sh) und Submodule aus dem Haupt-Checkout.
worktree_setup() {
  [[ "$REPO_ROOT" != "$MAIN_ROOT" ]] || { log "Nur in einem Worktree nötig"; return 0; }
  log "Initialisiere Submodule aus dem Haupt-Checkout (lokal, ohne Netz)"
  # Jede Submodul-URL (auch rekursive und die relativen ../kothic.git usw., die sonst auf den
  # GitHub-Spiegel zeigen würden) auf das bereits ausgecheckte Submodul im Haupt-Checkout umleiten.
  local -a redirects=(-c protocol.file.allow=always)
  while read -r path url; do
    [[ -n "$url" ]] && redirects+=(-c "url.$MAIN_ROOT/$path.insteadOf=$url")
  done < <(git -C "$MAIN_ROOT" submodule foreach --quiet --recursive \
             'echo "$displaypath $(git remote get-url origin)"')
  local origin_base; origin_base="$(dirname "$(git -C "$MAIN_ROOT" remote get-url origin)")"
  while read -r path rel; do
    redirects+=(-c "url.$MAIN_ROOT/$path.insteadOf=$origin_base/${rel#../}")
  done < <(git -C "$MAIN_ROOT" config -f .gitmodules --get-regexp '^submodule\..*\.url$' \
             | awk '$2 ~ /^\.\.\// {sub(/^submodule\./,"",$1); sub(/\.url$/,"",$1); print $1, $2}')
  git -C "$REPO_ROOT" "${redirects[@]}" submodule update --init --recursive >/dev/null 2>&1 || true
  # Der erste Lauf hinterlässt teils leere Arbeitsverzeichnisse; ein zweiter Lauf behebt das.
  git -C "$REPO_ROOT" submodule foreach --quiet --recursive 'git reset -q --hard HEAD' 2>/dev/null || true
  git -C "$REPO_ROOT" "${redirects[@]}" submodule update --init --recursive >/dev/null
  if git -C "$REPO_ROOT" submodule status --recursive | grep -qE '^[-+U]'; then
    log "Submodule nicht sauber:"; git -C "$REPO_ROOT" submodule status --recursive | grep -E '^[-+U]' >&2; return 1
  fi
  # Boost-Header entstehen erst durch "b2 headers" und sind im Submodul ignoriert.
  [[ -d "$MAIN_ROOT/3party/boost/boost" && ! -e "$REPO_ROOT/3party/boost/boost" ]] && \
    cp -a --reflink=auto "$MAIN_ROOT/3party/boost/boost" "$REPO_ROOT/3party/boost/"
  log "Übernehme generierte Dateien aus configure.sh"
  (cd "$MAIN_ROOT" && git ls-files --others --ignored --exclude-standard -- data libs/platform android \
     | grep -vE '(^|/)(build|\.gradle|\.cxx)/|\.bak$' \
     | while read -r f; do mkdir -p "$REPO_ROOT/$(dirname "$f")"; cp --reflink=auto -p "$f" "$REPO_ROOT/$f"; done)
  [[ -d "$MAIN_ROOT/.venv" && ! -e "$REPO_ROOT/.venv" ]] && ln -s "$MAIN_ROOT/.venv" "$REPO_ROOT/.venv"
  log "Worktree bereit"
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  android-build)   android_build "$@" ;;
  android-unit)    android_unit "$@" ;;
  android-run)     with_emulator android_install_run ;;
  android-connected) with_emulator android_connected "$@" ;;
  emulator)        with_emulator "$@" ;;
  emulator-start)  emulator_start ;;
  emulator-stop)   emulator_stop ;;
  emulator-screenshot) emulator_screenshot "$@" ;;
  cpp-test)        cpp_test "$@" ;;
  go-test)         go_test "$@" ;;
  worktree-setup)  worktree_setup ;;
  *) cat <<EOF
Befehle:
  android-build [gradle-args]   Debug-APK (F-Droid, x86_64) bauen
  android-unit  [gradle-args]   JVM-Unit-Tests der App
  android-run                   APK im Emulator installieren und starten (exklusiv)
  android-connected [args]      Instrumentierte Tests im Emulator (exklusiv)
  emulator <befehl ...>         Beliebigen Befehl exklusiv am Emulator ausführen (z. B. adb-Skript)
  emulator-start|emulator-stop  Gemeinsamen Emulator starten/stoppen
  cpp-test <ziel ...>           C++-Testprogramme bauen und ausführen (z. B. search_tests)
  go-test <services/dienst>     go vet + go test -race für einen Dienst
  worktree-setup                Submodule und generierte Dateien in einem neuen Worktree einrichten
EOF
  ;;
esac
