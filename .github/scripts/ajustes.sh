#!/bin/bash
# Ajustes: al cambiar un ajuste la página debe quedarse donde estaba, sin parpadeo. Graba la pantalla mientras se
# tocan interruptores y opciones en varias secciones (ya desplazadas) y deja los videos en salida/.
PKG=com.stradaos.launcher.debug
ACT=$PKG/com.stradaos.launcher.Main
OUT=salida; mkdir -p $OUT
adb wait-for-device
for i in $(seq 1 100); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 15
adb shell input keyevent 82 || true
adb install -r -g prueba.apk | tail -1
adb shell am start -n $ACT --es prueba saltar >/dev/null; sleep 20
go() { adb shell am start -n $ACT --es prueba "$1" >/dev/null; sleep "${2:-5}"; }
go idioma:es 6; go tema:oscuro 6
# centro de un texto en pantalla (uiautomator): «x y»
pos() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'P'
import re, sys
x = open('/tmp/ui.xml', encoding='utf-8').read()
best = None
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1) == sys.argv[1]:
        a, b, c, d = map(int, m.groups()[1:])
        if b > 40 and d < 590: best = ((a + c) // 2, (b + d) // 2)   # el último visible (el de más abajo)
print("%d %d" % best if best else "")
P
}
tap() { p=$(pos "$1"); if [ -n "$p" ]; then echo "toca «$1» en $p"; adb shell input tap $p; else echo "NO ENCONTRADO: $1"; fi; sleep 2; }
# interruptor de la fila cuyo título es $1 (está a la derecha de la fila)
sw() { p=$(pos "$1"); if [ -n "$p" ]; then y=${p#* }; echo "interruptor «$1» en y=$y"; adb shell input tap 957 $y; else echo "NO ENCONTRADO: $1"; fi; sleep 2; }
rec() { adb shell screenrecord --time-limit "$2" --bit-rate 6000000 /sdcard/$1.mp4 & RP=$!; sleep 2; }
fin() { wait $RP; adb pull /sdcard/$1.mp4 $OUT/$1.mp4 >/dev/null; echo "video $1"; }
down() { adb shell input swipe 640 480 640 ${1:-180} 500; sleep 2; }

go ajustes:2 6; down
rec apariencia 24
tap "24 h"; tap "12 h"; tap "mph"; tap "km/h"; tap "Compacta"; tap "Normal"; sw "Tarjeta del vehículo"; sw "Tarjeta del vehículo"
fin apariencia

go ajustes:5 6; down; down 300
rec mapa 22
tap "Alto"; tap "Bajo"; tap "Medio"; sw "Mapa en vivo"; sw "Mapa en vivo"; tap "Según tu dirección"; tap "Norte arriba"
fin mapa

go ajustes:6 6; down
rec musica 14
sw "Volver al Bluetooth al desconectar Android Auto"; sw "Volver al Bluetooth al desconectar Android Auto"; sw "Abrir la app de música Bluetooth al conectar el celular"; sw "Abrir la app de música Bluetooth al conectar el celular"
fin musica

go ajustes:1 6; down 330
rec vehiculo 10
sw "Recordatorio de cambio de aceite"; sw "Recordatorio de cambio de aceite"
fin vehiculo

go ajustes:7 6; down 300
rec sistema 10
sw "Abrir StradaOS al encender"; sw "Abrir StradaOS al encender"
fin sistema
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Caused|\tat com.stradaos)" | tail -30 > $OUT/logcat.txt
ls -l $OUT
