#!/bin/bash
# Carátulas: canciones por Bluetooth sin carátula (se buscan en internet) y un MP3 guardado con carátula incrustada.
API=${API:-30}
PKG=com.stradaos.launcher.debug
ACT=$PKG/com.stradaos.launcher.Main
OUT=salida; mkdir -p $OUT
adb wait-for-device
for i in $(seq 1 100); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 15
adb shell input keyevent 82 || true
if [ "$API" -ge 23 ]; then adb install -r -g prueba.apk | tail -1; else adb install -r prueba.apk | tail -1; fi
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
adb shell mkdir -p /sdcard/Music
adb push .github/tools/caratula/prueba.mp3 /sdcard/Music/prueba.mp3 | tail -1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/prueba.mp3 | tail -1
sleep 10
adb shell content query --uri content://media/external/audio/media --projection title:artist 2>/dev/null | head -3
shot() {
  rm -f /tmp/shot.png; rm -rf /tmp/emushot; mkdir -p /tmp/emushot
  adb shell screencap -p /data/local/tmp/s.png >/dev/null 2>&1 && adb pull /data/local/tmp/s.png /tmp/shot.png >/dev/null 2>&1
  if [ ! -s /tmp/shot.png ] || [ "$(stat -c %s /tmp/shot.png)" -lt 15000 ]; then
    adb emu screenrecord screenshot /tmp/emushot >/dev/null 2>&1; sleep 2
    f=$(ls -t /tmp/emushot/*.png 2>/dev/null | head -1); [ -n "$f" ] && cp "$f" /tmp/shot.png
  fi
  [ -s /tmp/shot.png ] && cp /tmp/shot.png "$OUT/$1.png"; echo "captura $1"
}
go() { adb shell "am start -n $ACT --es prueba '$1'" >/dev/null; sleep "${2:-5}"; }
go saltar 20; go idioma:es 6; go tema:oscuro 6
n=0
for s in "Beele|frente al mar" "Juanes|La Camisa Negra" "Karol G|Provenza" "StradaOS Test|Prueba Caratula StradaOS" "Artista Inventado Xyz|Cancion Que No Existe"; do
  n=$((n+1)); go "caratula:$s" 14; shot "$n-$(echo ${s%%|*} | tr ' ' '_')"
done
go "caratula:Juanes|La Camisa Negra" 4; shot 6-repetida-cache
go reproductor-diag 4; shot 7-diag
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Caused|\tat com.stradaos)" | tail -30 > $OUT/logcat.txt
ls -l $OUT
