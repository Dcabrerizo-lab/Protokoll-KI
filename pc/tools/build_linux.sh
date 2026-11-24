#!/usr/bin/env bash
# Baut "Protokoll KI für Linux": .deb (Ubuntu, Mint, Debian: Doppelklick) und AppImage (alle anderen).
#
#   cd pc && bash tools/build_linux.sh            (nach: pip install -r requirements.txt)
#
# Voraussetzung (Ubuntu): sudo apt install libportaudio2 fonts-dejavu-core libvulkan1 dpkg-dev
# Ergebnis: dist/protokoll-ki_<version>_amd64.deb und dist/ProtokollKI-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${PK_VERSION:-1.0.0}"
LIBDIR=/usr/lib/x86_64-linux-gnu

echo "== KI-Komponente (llama-server)"
[ -x build/llama/llama-server ] || python tools/get_llama.py build/llama linux
# Vulkan-Lader mitliefern: Grafikkarten-Treiber kommen vom System, ohne Treiber läuft es auf dem Prozessor
[ -f "$LIBDIR/libvulkan.so.1" ] && cp -L "$LIBDIR/libvulkan.so.1" build/llama/ || true

echo "== Mikrofon (PortAudio) und Schriften mitliefern"
mkdir -p build/lib build/fonts
cp -L "$LIBDIR/libportaudio.so.2" build/lib/
# Abhängigkeiten von PortAudio, die nicht auf jedem System sind (z. B. JACK); Grundbibliotheken bleiben weg
ldd "$LIBDIR/libportaudio.so.2" | awk '/=> \// {print $3}' | while read -r dep; do
  case "$(basename "$dep")" in
    libc.so*|libm.so*|libdl.so*|librt.so*|libpthread.so*|ld-linux*|libasound.so*|libgcc_s.so*|libstdc++.so*) ;;
    *) cp -L "$dep" build/lib/ ;;
  esac
done
for f in DejaVuSans.ttf DejaVuSans-Bold.ttf DejaVuSans-Oblique.ttf; do
  cp -L "/usr/share/fonts/truetype/dejavu/$f" build/fonts/ 2>/dev/null || true
done
ls build/lib build/fonts

echo "== Programmsymbol"
python tools/make_icon.py build/icon.png

echo "== Programm bauen (PyInstaller)"
python -m PyInstaller --noconfirm --clean ProtokollKI.spec
chmod +x dist/ProtokollKI/ProtokollKI
du -sh dist/ProtokollKI

DESKTOP='[Desktop Entry]
Type=Application
Name=Protokoll KI
GenericName=Besprechungsprotokolle
Comment=Besprechungen aufnehmen und offline Protokolle erstellen
Exec=EXEC %f
Icon=protokoll-ki
Terminal=false
Categories=Office;AudioVideo;
MimeType=application/x-protokoll-ki;
StartupNotify=true
Keywords=Protokoll;Besprechung;Transkript;Aufnahme;'

MIME='<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-protokoll-ki">
    <comment>Protokoll-KI-Besprechung</comment>
    <glob pattern="*.protokoll"/>
  </mime-type>
</mime-info>'

echo "== .deb"
D=build/deb
rm -rf "$D"
mkdir -p "$D/DEBIAN" "$D/opt/protokoll-ki" "$D/usr/bin" "$D/usr/share/applications" \
         "$D/usr/share/mime/packages" "$D/usr/share/icons/hicolor/256x256/apps"
cp -a dist/ProtokollKI/. "$D/opt/protokoll-ki/"
ln -s /opt/protokoll-ki/ProtokollKI "$D/usr/bin/protokoll-ki"
echo "${DESKTOP//EXEC/protokoll-ki}" > "$D/usr/share/applications/protokoll-ki.desktop"
echo "$MIME" > "$D/usr/share/mime/packages/protokoll-ki.xml"
python - "$D/usr/share/icons/hicolor/256x256/apps/protokoll-ki.png" <<'EOF'
import sys
from PIL import Image
Image.open("build/icon.png").resize((256, 256), Image.LANCZOS).save(sys.argv[1])
EOF
SIZE=$(du -sk "$D" | cut -f1)
cat > "$D/DEBIAN/control" <<EOF
Package: protokoll-ki
Version: $VERSION
Section: office
Priority: optional
Architecture: amd64
Installed-Size: $SIZE
Recommends: libvulkan1, xdg-utils
Maintainer: Protokoll KI <noreply@protokoll-ki.invalid>
Homepage: https://github.com/Dcabrerizo-lab/Protokoll-KI
Description: Besprechungen aufnehmen und offline Protokolle erstellen
 Protokoll KI nimmt Besprechungen auf (PC-Mikrofon oder Handy) und erstellt
 Transkript, Ergebnisprotokoll und Aufgaben mit lokaler KI. Nichts verlässt den PC.
EOF
cat > "$D/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
command -v update-mime-database >/dev/null && update-mime-database /usr/share/mime || true
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t /usr/share/icons/hicolor || true
exit 0
EOF
chmod 755 "$D/DEBIAN/postinst"
cp "$D/DEBIAN/postinst" "$D/DEBIAN/postrm"
dpkg-deb --root-owner-group -Zxz --build "$D" "dist/protokoll-ki_${VERSION}_amd64.deb"

echo "== AppImage"
A=build/AppDir
rm -rf "$A"
mkdir -p "$A/usr/lib/protokoll-ki" "$A/usr/share/applications" "$A/usr/share/mime/packages" \
         "$A/usr/share/icons/hicolor/256x256/apps"
cp -a dist/ProtokollKI/. "$A/usr/lib/protokoll-ki/"
cat > "$A/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/lib/protokoll-ki/ProtokollKI" "$@"
EOF
chmod +x "$A/AppRun"
echo "${DESKTOP//EXEC/ProtokollKI}" > "$A/protokoll-ki.desktop"
cp "$A/protokoll-ki.desktop" "$A/usr/share/applications/"
echo "$MIME" > "$A/usr/share/mime/packages/protokoll-ki.xml"
cp "$D/usr/share/icons/hicolor/256x256/apps/protokoll-ki.png" "$A/protokoll-ki.png"
cp "$A/protokoll-ki.png" "$A/usr/share/icons/hicolor/256x256/apps/"
ln -sf protokoll-ki.png "$A/.DirIcon"
if [ ! -x build/appimagetool ]; then
  curl -fsSL --retry 5 -o build/appimagetool \
    https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x build/appimagetool
fi
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 build/appimagetool --no-appstream "$A" dist/ProtokollKI-x86_64.AppImage
chmod +x dist/ProtokollKI-x86_64.AppImage
ls -la dist
