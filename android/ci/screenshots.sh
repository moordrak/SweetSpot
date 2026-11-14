#!/usr/bin/env bash
# Pokreće razvojnu izgradnju na emulatoru, snima zaslone s probnim podacima i provjerava da se aplikacija nije srušila.
set -u
APK="$1"
OUT="$2"
PKG=io.github.moordrak.sweetspot.debug
ACT=$PKG/io.github.moordrak.sweetspot.MainActivity
mkdir -p "$OUT"

adb wait-for-device
adb install -r "$APK"
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p" || true
done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c

shot() {
  local name="$1"; shift
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACT" "$@" > /dev/null
  sleep 5
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "snimljeno: $name ($(stat -c %s "$OUT/$name.png") B)"
}

adb shell cmd uimode night no
shot 1-pocetni      --ez demo true --es screen home
shot 2-postavke     --ez demo true --es screen settings
shot 3-priprema     --ez demo true --es screen wizard --es wizard prepare
shot 4-trazenje     --ez demo true --es screen wizard --es wizard scan
shot 5-spajanje     --ez demo true --es screen wizard --es wizard connect
shot 6-greska       --ez demo true --es screen wizard --es wizard error
shot 7-gotovo       --ez demo true --es screen wizard --es wizard done
shot 8-zapisnik     --ez demo true --es screen log
adb shell cmd uimode night yes
shot 9-pocetni-tamno --ez demo true --es screen home
adb shell cmd uimode night no
# prvo pokretanje bez postavki (stvarni tijek, bez probnih podataka)
adb shell pm clear "$PKG" > /dev/null
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do adb shell pm grant "$PKG" "android.permission.$p" || true; done
shot 0-prvo-pokretanje

if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" ; then
  echo "Aplikacija se srušila:"
  adb logcat -d | grep -A 30 "FATAL EXCEPTION"
  exit 1
fi
echo "Bez rušenja."
