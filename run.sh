#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source scripts/java-runtime.sh
if [[ ! -d target/lib || ! -f target/classes/dev/cloudmount/Launcher.class ]]; then
  mvn -B -ntp package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
fi
javafx_modules=(target/lib/javafx-*-linux*.jar)
printf -v javafx_path '%s:' "${javafx_modules[@]}"
exec "$CLOUDMOUNT_JAVA" --enable-native-access=javafx.graphics --module-path "${javafx_path%:}" \
  --add-modules javafx.controls -cp 'target/classes:target/lib/*' dev.cloudmount.Launcher "$@"
