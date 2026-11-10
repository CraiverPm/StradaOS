#!/bin/bash
# Mapa sin conexión en el emulador: copia colombia.map, corta el internet y toma capturas del mapa
# (Medellín como ciudad de ejemplo) en oscuro y claro, a varios zooms, y del diagnóstico.
API=${API:-30}
PKG=com.stradaos.launcher.debug
ACT=$PKG/com.stradaos.launcher.Main
OUT=salida; mkdir -p $OUT
adb wait-for-device
for i in $(seq 1 100); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 15
adb shell input keyevent 82 || true
if [ "$API" -ge 23 ]; then adb install -r -g prueba.apk | tail -1; else adb install -r prueba.apk | tail -1; fi
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
adb shell mkdir -p /sdcard/Download
t0=$(date +%s); adb push colombia.map /sdcard/Download/colombia.map | tail -1; echo "copia: $(( $(date +%s) - t0 )) s"
adb shell ls -l /sdcard/Download/
( while true; do adb emu geo fix -75.5680 6.2442 >/dev/null 2>&1; adb emu geo fix -75.5680 6.2442 1495 8 1 >/dev/null 2>&1; sleep 2; done ) &
GEO=$!
shot() { adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; echo "captura $1"; }
go() { adb shell am start -n $ACT --es prueba "$1" >/dev/null; sleep "${2:-5}"; }
adb shell am start -n $ACT --es prueba saltar >/dev/null; sleep 20
go idioma:es 6; go unidad:kmh 4
# sin internet: así se ve que el mapa sale del archivo
adb shell svc wifi disable >/dev/null 2>&1; adb shell svc data disable >/dev/null 2>&1
go tema:oscuro 6
go mapa-archivo:/sdcard/Download/colombia.map 8
shot 0-oscuro-sin-archivo-aun
for z in 14 15 16 17; do go mapa-zoom:$z 25; shot oscuro-z$z; done
go mapa-diag 4; shot diag-oscuro; adb shell input keyevent 4; sleep 2
go tema:claro 6; go mapa-zoom:16 25; shot claro-z16
go mapa-zoom:15 20; shot claro-z15
# movimiento: 20 posiciones (una por segundo); capturas seguidas para ver que avanza entre lecturas
kill $GEO 2>/dev/null
go tema:oscuro 6; go mapa-zoom:17 15
adb shell am start -n $ACT --es prueba mapa-mover >/dev/null
sleep 6; for i in 1 2 3 4; do shot mover-$i; done
sleep 4
go ajustes:5 6; adb shell input swipe 600 500 600 100 400; sleep 2; adb shell input swipe 600 500 600 100 400; sleep 3; shot ajustes-mapa
adb shell input keyevent 4; sleep 2
go mapa-diag 4; shot diag-final
adb logcat -d | grep -iE "AndroidRuntime|FATAL|stradaos" | tail -40 > $OUT/logcat.txt
ls -l $OUT
