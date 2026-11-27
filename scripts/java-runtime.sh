#!/usr/bin/env bash
# Shared by run.sh and build.sh. Only alters the current launch environment.
cloudmount_java_major() {
  local cloudmount_properties cloudmount_major
  cloudmount_properties="$("$1" -XshowSettings:properties -version 2>&1)" || return 1
  cloudmount_major="$(awk -F '= ' '/^[[:space:]]*java.specification.version[[:space:]]*=/ {print $2; exit}' <<< "$cloudmount_properties")"
  [[ "$cloudmount_major" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$cloudmount_major"
}

cloudmount_select_java() {
  local cloudmount_candidate cloudmount_major cloudmount_resolved
  local -a cloudmount_candidates=()
  if [[ -n "${CLOUDMOUNT_JAVA_HOME:-}" ]]; then
    cloudmount_candidates=("$CLOUDMOUNT_JAVA_HOME/bin/java")
  else
    if [[ -n "${JAVA_HOME:-}" ]]; then cloudmount_candidates+=("$JAVA_HOME/bin/java"); fi
    if command -v java >/dev/null 2>&1; then cloudmount_candidates+=("$(command -v java)"); fi
    cloudmount_candidates+=(/usr/lib/jvm/*/bin/java /opt/java/*/bin/java)
    cloudmount_candidates+=("${HOME}/.sdkman/candidates/java/"*/bin/java)
  fi
  for cloudmount_candidate in "${cloudmount_candidates[@]}"; do
    [[ -x "$cloudmount_candidate" ]] || continue
    cloudmount_major="$(cloudmount_java_major "$cloudmount_candidate")" || continue
    (( cloudmount_major >= 25 )) || continue
    cloudmount_resolved="$(readlink -f -- "$cloudmount_candidate")" || continue
    # A full JDK is required so automatic builds use the same Java as launch.
    [[ -x "$(dirname -- "$cloudmount_resolved")/javac" ]] || continue
    export JAVA_HOME
    JAVA_HOME="$(dirname -- "$(dirname -- "$cloudmount_resolved")")"
    export PATH="$JAVA_HOME/bin:$PATH"
    CLOUDMOUNT_JAVA="$JAVA_HOME/bin/java"
    export CLOUDMOUNT_JAVA
    return 0
  done
  printf '%s\n' 'CirraMount requires JDK 25 or newer (the app is compiled for Java 25).' >&2
  if [[ -n "${CLOUDMOUNT_JAVA_HOME:-}" ]]; then
    printf 'CLOUDMOUNT_JAVA_HOME does not point to a compatible JDK: %s\n' "$CLOUDMOUNT_JAVA_HOME" >&2
  else
    printf '%s\n' 'No compatible JDK was found in JAVA_HOME, PATH, /usr/lib/jvm, /opt/java or SDKMAN.' >&2
  fi
  printf '%s\n' 'Example: CLOUDMOUNT_JAVA_HOME=/usr/lib/jvm/jdk-25.0.2-oracle-x64 ./run.sh' >&2
  return 1
}

cloudmount_select_java

cloudmount_select_java
