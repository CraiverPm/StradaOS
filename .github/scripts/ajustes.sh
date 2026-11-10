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
# Las páginas se desplazan hasta el final, así que las posiciones son fijas (pantalla 1024x600).
tap() { echo "toca $1 ($2,$3)"; adb shell input tap $2 $3; sleep 2; }
rec() { adb shell screenrecord --time-limit "$2" --bit-rate 6000000 /sdcard/$1.mp4 & RP=$!; sleep 2; }
fin() { wait $RP; adb pull /sdcard/$1.mp4 $OUT/$1.mp4 >/dev/null; echo "video $1"; }
down() { adb shell input swipe 640 480 640 ${1:-180} 500; sleep 2; }

go ajustes:2 6; down; down
rec apariencia 24
tap "24 h" 955 517; tap "12 h" 902 517; tap "mph" 955 409; tap "km/h" 898 409; tap "Compacta" 870 185; tap "Normal" 792 185
tap "Tarjeta del vehículo" 958 118; tap "Tarjeta del vehículo" 958 118
fin apariencia
rec apariencia2 16
tap "color azul" 857 300; tap "color rojo" 643 300; tap "Izquierda" 941 58; tap "Derecha" 860 58; tap "Auto (hora)" 849 517
fin apariencia2

go ajustes:5 6; down; down
rec mapa 14
tap "Brillo Alto" 958 154; tap "Brillo Bajo" 844 154; tap "Brillo Medio" 901 154
fin mapa

go ajustes:6 6; down; down
rec musica 18
tap "Volver al Bluetooth" 957 168; tap "Volver al Bluetooth" 957 168; tap "Abrir la app" 957 228; tap "Abrir la app" 957 228; tap "4 s" 957 348; tap "2,5 s" 906 348
fin musica

go ajustes:1 6
go demo-aceite 5; go ajustes:1 6; down; down
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png $OUT/vehiculo.png >/dev/null
go ajustes:4 6; down; down
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png $OUT/accesos.png >/dev/null
go ajustes:3 6; down; down
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png $OUT/conexion.png >/dev/null
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Caused|\tat com.stradaos)" | tail -30 > $OUT/logcat.txt
ls -l $OUT
