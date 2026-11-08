#!/bin/sh
# Instala la app empaquetada para este usuario y la añade al menú de aplicaciones.
set -e
cd "$(dirname "$0")"
d="${XDG_DATA_HOME:-$HOME/.local/share}"
[ -x dist/linux-unpacked/temvra ] || { echo "Falta el paquete: ejecuta npm run desktop desde la raíz"; exit 1; }
rm -rf "$d/temvra"
mkdir -p "$d/temvra" "$d/applications" "$d/icons/hicolor/512x512/apps"
cp -r dist/linux-unpacked/. "$d/temvra/"
cp ../resources/icon-512.png "$d/icons/hicolor/512x512/apps/temvra.png"
cat > "$d/applications/temvra.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Temvra
Comment=Temporizador, agenda, notas y registros
Exec="$d/temvra/temvra" %U
Icon=temvra
Terminal=false
Categories=Utility;
MimeType=application/json;text/calendar;application/zip;x-scheme-handler/temvra;
StartupWMClass=temvra
DESKTOP
command -v update-desktop-database >/dev/null && update-desktop-database "$d/applications" || true
# Los enlaces temvra:// (los abre la página de los enlaces compartidos) los abre Temvra
command -v xdg-mime >/dev/null && xdg-mime default temvra.desktop x-scheme-handler/temvra || true
echo "Temvra instalada en $d/temvra"
