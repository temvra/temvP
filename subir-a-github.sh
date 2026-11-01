#!/usr/bin/env bash
# Sube el proyecto a tu repositorio de GitHub. GitHub compila la APK sola.
set -e
cd "$(dirname "$0")"
command -v git >/dev/null || { echo "Instala git primero:  sudo apt install git"; exit 1; }
read -rp "Tu usuario de GitHub: " U
read -rp "Nombre del repositorio [Temvra]: " R; R=${R:-Temvra}
[ -d .git ] || git init -q
git checkout -B main -q
git config user.name >/dev/null || git config user.name "$U"
git config user.email >/dev/null || git config user.email "$U@users.noreply.github.com"
git add -A
git commit -qm "Temvra $(date +%F)" || echo "(sin cambios nuevos)"
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$U/$R.git"
echo
echo "Cuando pida 'Password', pega tu TOKEN de GitHub (no tu contraseña)."
git push -u origin main
echo
echo "✔ Subido. Mira la compilación en: https://github.com/$U/$R/actions"
echo "  En unos 5-8 minutos tendrás la APK en: https://github.com/$U/$R/releases"
