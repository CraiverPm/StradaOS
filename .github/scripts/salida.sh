#!/bin/bash
# Prueba «Desactivar StradaOS»: debe volver el launcher de fábrica y StradaOS no debe poder abrirse.
P=com.stradaos.launcher.debug
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 10
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r'); echo "API $API"
adb install -r prueba.apk | tail -1
inicio() { adb shell "dumpsys activity activities" | grep -E "mResumedActivity|ResumedActivity:" | head -1; }
# StradaOS como pantalla de inicio
if [ "$API" -ge 29 ]; then adb shell cmd role add-role-holder android.app.role.HOME $P 2>&1 | tail -1
elif [ "$API" -ge 24 ]; then adb shell cmd package set-home-activity $P/com.stradaos.launcher.Main; fi
adb shell am start -n $P/com.stradaos.launcher.Main --es prueba saltar >/dev/null; sleep 8
adb shell input keyevent 3; sleep 4
echo "Antes  → al frente: $(inicio)"
adb shell am start -n $P/com.stradaos.launcher.Main --es prueba desactivar >/dev/null; sleep 8
echo "Después → al frente: $(inicio)"
adb shell input keyevent 3; sleep 4
echo "Tecla Inicio → al frente: $(inicio)"
echo "Componentes desactivados:"; adb shell dumpsys package $P | grep -A6 -i "disabledComponents" | sed 's/^/   /'
echo "Abrir StradaOS a mano:"; adb shell am start -n $P/com.stradaos.launcher.Main 2>&1 | grep -iE "error|exception|does not exist|unable" | head -2 | sed 's/^/   /'
FRENTE=$(inicio)
if echo "$FRENTE" | grep -q "$P"; then echo "FALLÓ: StradaOS sigue al frente"; exit 1; fi
echo "OK: StradaOS quedó desactivado y volvió el launcher de fábrica"
