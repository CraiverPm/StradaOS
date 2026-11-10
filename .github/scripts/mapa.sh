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
shot() {
  # en emuladores de Android 4.1 screencap sale negro: entonces se usa la captura del propio emulador
  rm -f /tmp/shot.png; rm -rf /tmp/emushot; mkdir -p /tmp/emushot
  adb shell screencap -p /data/local/tmp/s.png >/dev/null 2>&1 && adb pull /data/local/tmp/s.png /tmp/shot.png >/dev/null 2>&1
  if [ ! -s /tmp/shot.png ] || [ "$(stat -c %s /tmp/shot.png)" -lt 15000 ]; then
    adb emu screenrecord screenshot /tmp/emushot >/dev/null 2>&1; sleep 2
    f=$(ls -t /tmp/emushot/*.png 2>/dev/null | head -1)
    [ -n "$f" ] && cp "$f" /tmp/shot.png
  fi
  [ -s /tmp/shot.png ] && cp /tmp/shot.png "$OUT/$1.png"; echo "captura $1"
}
go() { adb shell am start -n $ACT --es prueba "$1" >/dev/null; sleep "${2:-5}"; }
adb shell am start -n $ACT --es prueba saltar >/dev/null; sleep 20
go idioma:es 6; go unidad:kmh 4
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
# ── descarga desde la app: lista de continentes y países, descarga con pausa y reanudación ──
go mapa-lista 12; shot descarga-1-continentes; adb shell input keyevent 4; sleep 2
go mapa-lista:south-america/ 12; shot descarga-2-paises; adb shell input keyevent 4; sleep 2
go mapa-descargar:south-america/guyana.map 6
adb shell am start -n $ACT --es prueba mapa-pausar >/dev/null; sleep 3
echo "en pausa:"; adb shell ls -l /sdcard/Download/StradaOS/ 2>&1
go mapa-continuar 2
for i in $(seq 1 60); do
  adb shell ls /sdcard/Download/StradaOS/guyana.map >/dev/null 2>&1 && ! adb shell ls /sdcard/Download/StradaOS/ | grep -q part && break
  [ $i = 3 ] && { go ajustes:5 3; adb shell input swipe 600 500 600 100 400; sleep 1; adb shell input swipe 600 500 600 100 400; sleep 2; shot descarga-3-progreso; }
  sleep 3
done
echo "terminado:"; adb shell ls -l /sdcard/Download/StradaOS/ 2>&1
adb shell input keyevent 4; sleep 2
kill $GEO 2>/dev/null
( for i in $(seq 1 12); do adb emu geo fix -58.1551 6.8013 >/dev/null 2>&1; sleep 2; done ) &
GY=$!
sleep 8; go mapa-zoom:16 20; shot descarga-4-mapa-guyana
wait $GY
( while true; do adb emu geo fix -75.5680 6.2442 >/dev/null 2>&1; adb emu geo fix -75.5680 6.2442 1495 8 1 >/dev/null 2>&1; sleep 2; done ) &
GEO=$!
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
