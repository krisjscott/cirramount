#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/java-runtime.sh

version="${1:?Usage: scripts/package-deb.sh VERSION}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Use a numeric release version, such as 0.2.0.' >&2; exit 1; }
[[ "$(uname -m)" == x86_64 ]] || { echo 'This package script builds Linux amd64 only.' >&2; exit 1; }
grep -q "<artifactId>cloudmount</artifactId><version>${version}</version>" pom.xml || {
  echo "pom.xml version does not match ${version}." >&2
  exit 1
}
[[ -x tools/rclone ]] || { echo 'tools/rclone is missing; run scripts/setup-rclone.sh first.' >&2; exit 1; }

rm -rf target/lib
mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

mkdir -p target/release
stage="$(mktemp -d "target/release/.cirramount-${version}.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
app="$stage/opt/cirramount"
install -d "$app/lib" "$app/tools" "$stage/usr/bin" \
  "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/512x512/apps" "$stage/DEBIAN"
install -m 644 "target/cloudmount-${version}.jar" "$app/app.jar"
cp target/lib/*.jar "$app/lib/"
install -m 755 tools/rclone "$app/tools/rclone"
install -m 644 tools/RCLONE-LICENSE.txt "$app/tools/RCLONE-LICENSE.txt"
install -m 644 packaging/cirramount.png "$stage/usr/share/icons/hicolor/512x512/apps/cirramount.png"

if [[ -n "${CIRRAMOUNT_RUNTIME_DIR:-}" ]]; then
  [[ -x "$CIRRAMOUNT_RUNTIME_DIR/bin/java" ]] || { echo 'CIRRAMOUNT_RUNTIME_DIR must contain bin/java.' >&2; exit 1; }
  cp -a "$CIRRAMOUNT_RUNTIME_DIR" "$app/runtime"
else
  "$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec \
    --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$app/runtime"
fi

cat > "$stage/usr/bin/cirramount" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
app_root="${CIRRAMOUNT_INSTALL_DIR:-/opt/cirramount}"
cd -- "$app_root"
modules=(lib/javafx-*-linux.jar)
printf -v module_path '%s:' "${modules[@]}"
exec runtime/bin/java --enable-native-access=javafx.graphics --module-path "${module_path%:}" \
  --add-modules javafx.controls -cp 'app.jar:lib/*' dev.cloudmount.Launcher "$@"
LAUNCHER
chmod 755 "$stage/usr/bin/cirramount"

cat > "$stage/usr/share/applications/cirramount.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=CirraMount
Comment=Mount cloud storage as local folders
Exec=cirramount
Icon=cirramount
Terminal=false
Categories=Utility;FileTools;
DESKTOP

cat > "$stage/DEBIAN/control" <<CONTROL
Package: cirramount
Version: ${version}
Section: utils
Priority: optional
Architecture: amd64
Maintainer: Krish Kumar <krishkumar6566@gmail.com>
Depends: fuse3, libgtk-3-0 | libgtk-3-0t64, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, libfontconfig1, libc6 (>= 2.28)
Homepage: https://krisjscott.github.io/cirramount-website/
Description: Mount cloud storage on the Linux desktop
 CirraMount manages cloud accounts and local FUSE mounts using rclone.
CONTROL

output="target/release/cirramount_${version}_amd64.deb"
dpkg-deb --build --root-owner-group "$stage" "$output"
(cd target/release && sha256sum "$(basename "$output")" > "$(basename "$output").sha256")
echo "Built $output"
