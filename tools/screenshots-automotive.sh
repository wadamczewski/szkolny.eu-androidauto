#!/bin/sh
# Car-screen screenshots for the README, from the Android Automotive OS emulator (same Car App
# Library templates as Android Auto). Demo mode = fake data. Expects a running emulator from
# tools/README instructions: 2816×1584 at 320 dpi (the 1408p car layout at 2×), clock 10:05.
# Usage: ./tools/screenshots-automotive.sh [out-dir]
set -eu
cd "$(dirname "$0")/.."
OUT="${1:-screenshots}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=pl.prywatny.dziennik.auto.automotive
APK=app/build/outputs/apk/automotive/debug/app-automotive-debug.apk
mkdir -p "$OUT"

tap() { "$ADB" shell input tap "$1" "$2"; sleep "${3:-4}"; }
# The templates host ignores the BACK key from adb; tap the header's back arrow instead.
back() { tap 112 248 3; }
LAST=""
# Waits until the screen differs from the previous shot (the host may show a stale frame for a while).
shot() {
  i=0
  while :; do
    sleep 2
    "$ADB" exec-out screencap -p > "$OUT/$1.png"
    SUM=$(md5 -q "$OUT/$1.png" 2>/dev/null || md5sum "$OUT/$1.png" | cut -d' ' -f1)
    [ "$SUM" != "$LAST" ] || [ "$i" -ge 10 ] && break
    i=$((i + 1))
  done
  LAST=$SUM
  echo "$1"
}

"$ADB" install -r --user current "$APK" >/dev/null
"$ADB" shell pm clear --user current "$PKG" >/dev/null  # Automotive OS runs apps as user 10
"$ADB" shell am force-stop com.google.android.apps.automotive.templates.host
"$ADB" shell am start -n "$PKG/androidx.car.app.activity.CarAppActivity" >/dev/null
sleep 12
shot 07-logowanie
tap 1407 993 8          # "Tryb demo"
shot 01-dzis
tap 1400 459            # Teraz / następna lekcja → plan
shot 02-plan
back
tap 1400 921            # terminarz
shot 03-terminarz
back
tap 1400 1155 5         # wiadomości
shot 04-wiadomosci
tap 1400 459 5          # pierwsza wiadomość (na postoju)
shot 05-wiadomosc
back
back
tap 1400 1350           # ostatnie oceny
shot 06-oceny
back
