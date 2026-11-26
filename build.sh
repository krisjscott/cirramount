#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source scripts/java-runtime.sh
exec mvn -B -ntp package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib "$@"
