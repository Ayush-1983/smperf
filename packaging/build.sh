#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.0
OS_NAME="$(uname -s)"
DIST=target/dist
APP=$DIST/smperf

[ -f packaging/icon.png ] || { echo "missing packaging/icon.png"; exit 1; }

echo "== 1/4 native engine"
rm -f native/bin/mperf
make -C native

echo "== 2/4 jar + dependencies"
mvn -q clean package -DskipTests

echo "== 3/4 jpackage app-image"
rm -rf "$DIST" target/jpkg-mods
mkdir -p target/jpkg-mods
cp target/mods/gson-*.jar target/jpkg-mods/
cp target/smperf-*.jar target/jpkg-mods/

if [ "$OS_NAME" = "Darwin" ]; then
    cp target/mods/javafx-*-mac*.jar target/jpkg-mods/

    # macOS jpackage requires an .icns icon
    ICONSET=target/smperf.iconset
    rm -rf "$ICONSET" target/icon.icns
    mkdir -p "$ICONSET"
    for sz in 16 32 128 256 512; do
        sips -z $sz $sz packaging/icon.png --out "$ICONSET/icon_${sz}x${sz}.png" >/dev/null 2>&1
        sz2=$((sz * 2))
        sips -z $sz2 $sz2 packaging/icon.png --out "$ICONSET/icon_${sz}x${sz}@2x.png" >/dev/null 2>&1
    done
    iconutil -c icns "$ICONSET" -o target/icon.icns

    jpackage --type app-image \
      --name SMPerf \
      --app-version "$VERSION" \
      --module-path target/jpkg-mods \
      --module com.pvb.smperf/com.pvb.smperf.App \
      --add-modules jdk.unsupported \
      --icon target/icon.icns \
      --dest "$DIST"

    echo "== 4/4 assemble layout (macOS)"
    APP_BUNDLE="$DIST/SMPerf.app"
    install -m 755 native/bin/mperf "$APP_BUNDLE/Contents/MacOS/mperf"
    mkdir -p "$APP_BUNDLE/Contents/include"
    cp native/include/*.h "$APP_BUNDLE/Contents/include/"
    cp packaging/icon.png "$APP_BUNDLE/Contents/Resources/icon.png"
    [ -f packaging/LICENSE.txt ] && cp packaging/LICENSE.txt "$APP_BUNDLE/Contents/Resources/LICENSE.txt" || true

    # Populate $APP ($DIST/smperf) with exact Linux-matching layout:
    # bin/ (mperf, smperf), include/, lib/, icon.png, LICENSE.txt
    mkdir -p "$APP/bin" "$APP/include" "$APP/lib"
    mv "$APP_BUNDLE" "$APP/lib/SMPerf.app"
    ln -sf "SMPerf.app/Contents/runtime" "$APP/lib/runtime"
    ln -sf "SMPerf.app/Contents/app" "$APP/lib/app"
    cp packaging/icon.png "$APP/lib/smperf.png"

    # Compile a native Mach-O launcher at bin/smperf that execs ../lib/SMPerf.app/Contents/MacOS/SMPerf
    cat > target/smperf_launcher.c <<'LAUNCHER_EOF'
#include <mach-o/dyld.h>
#include <limits.h>
#include <libgen.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char exe_path[PATH_MAX];
    uint32_t size = sizeof(exe_path);
    if (_NSGetExecutablePath(exe_path, &size) != 0) return 1;
    char real_exe[PATH_MAX];
    if (!realpath(exe_path, real_exe)) return 1;
    char *bin_dir = dirname(real_exe);
    char target[PATH_MAX];
    snprintf(target, sizeof(target), "%s/../lib/SMPerf.app/Contents/MacOS/SMPerf", bin_dir);
    char real_target[PATH_MAX];
    if (!realpath(target, real_target)) {
        perror("SMPerf launcher");
        return 1;
    }
    char **new_argv = malloc((size_t)(argc + 1) * sizeof(char *));
    new_argv[0] = real_target;
    for (int i = 1; i < argc; i++) new_argv[i] = argv[i];
    new_argv[argc] = NULL;
    execv(real_target, new_argv);
    perror("execv SMPerf");
    return 1;
}
LAUNCHER_EOF
    clang -O2 -Wall target/smperf_launcher.c -o "$APP/bin/smperf"
    rm -f target/smperf_launcher.c

    install -m 755 native/bin/mperf "$APP/bin/mperf"
    cp native/include/*.h "$APP/include/"
    cp packaging/icon.png "$APP/icon.png"
    [ -f packaging/LICENSE.txt ] && cp packaging/LICENSE.txt "$APP/LICENSE.txt" || true

    echo
    echo "== check: no sources shipped"
    find "$APP" \( -name '*.c' -o -name '*.java' -o -name Makefile -o -name src \) | head
    echo "(empty above = good)"
    echo
    ls "$APP" "$APP/bin" "$APP/include"
    du -sh "$APP"

    echo "== release archives (macOS)"
    REL=target/release/smperf-$VERSION-macos
    rm -rf target/release && mkdir -p "$REL"
    cp -a "$APP" "$REL/smperf"
    cp packaging/install.sh "$REL/install.sh" && chmod +x "$REL/install.sh"
    cp packaging/LICENSE.txt "$REL/LICENSE.txt"
    COPYFILE_DISABLE=1 tar -C target/release -czf "target/smperf-$VERSION-macos.tar.gz" "smperf-$VERSION-macos"
    ditto -c -k --sequesterRsrc --keepParent "$REL" "target/smperf-$VERSION-macos.zip"

    # Also build a standard macOS drag-to-Applications .dmg
    DMG_STAGE=target/dmg-stage
    rm -rf "$DMG_STAGE" "target/smperf-$VERSION-macos.dmg"
    mkdir -p "$DMG_STAGE"
    cp -a "$APP/lib/SMPerf.app" "$DMG_STAGE/SMPerf.app"
    ln -s /Applications "$DMG_STAGE/Applications"
    cp packaging/LICENSE.txt "$DMG_STAGE/LICENSE.txt"
    hdiutil create -volname "SMPerf $VERSION" -srcfolder "$DMG_STAGE" -ov -format UDZO "target/smperf-$VERSION-macos.dmg" >/dev/null
    rm -rf "$DMG_STAGE"

    ls -lh "target/smperf-$VERSION-macos.tar.gz" "target/smperf-$VERSION-macos.zip" "target/smperf-$VERSION-macos.dmg"
else
    cp target/mods/javafx-*-linux.jar target/jpkg-mods/
    jpackage --type app-image \
      --name smperf \
      --app-version "$VERSION" \
      --module-path target/jpkg-mods \
      --module com.pvb.smperf/com.pvb.smperf.App \
      --add-modules jdk.unsupported \
      --icon packaging/icon.png \
      --dest "$DIST"

    echo "== 4/4 assemble layout"
    install -m 755 native/bin/mperf "$APP/bin/mperf"
    mkdir -p "$APP/include"
    cp native/include/*.h "$APP/include/"
    cp packaging/icon.png "$APP/icon.png"
    [ -f packaging/LICENSE.txt ] && cp packaging/LICENSE.txt "$APP/LICENSE.txt" || true

    echo
    echo "== check: no sources shipped"
    find "$APP" \( -name '*.c' -o -name '*.java' -o -name Makefile -o -name src \) | head
    echo "(empty above = good)"
    echo
    ls "$APP" "$APP/bin" "$APP/include"
    du -sh "$APP"
    echo "== release archive"
    REL=target/release/smperf-$VERSION-linux
    rm -rf target/release && mkdir -p "$REL"
    cp -a "$APP" "$REL/smperf"
    cp packaging/install.sh "$REL/install.sh" && chmod +x "$REL/install.sh"
    cp packaging/LICENSE.txt "$REL/LICENSE.txt"
    tar -C target/release -czf "target/smperf-$VERSION-linux.tar.gz" "smperf-$VERSION-linux"
    ls -lh "target/smperf-$VERSION-linux.tar.gz"
fi