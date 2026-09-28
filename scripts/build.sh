#!/usr/bin/env bash
# Builds and tests the service with Maven.
# Usage: scripts/build.sh [--offline] [--skip-tests]
# MVN overrides the Maven command; a project-local .m2 repository is used when present.
set -euo pipefail
source "$(dirname "$0")/common.sh"
offline=0
skip_tests=0
for arg in "$@"; do
  case "$arg" in
    --offline) offline=1 ;;
    --skip-tests) skip_tests=1 ;;
    -h|--help) sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $arg (see --help)" ;;
  esac
done
use_java11 || die "JDK 11 is required for the build; set JAVA_HOME to a JDK 11"
echo "Java: $JAVA_HOME"
cd "$ROOT"
if [ -n "${MVN:-}" ]; then
  read -r -a mvn_cmd <<< "$MVN"
elif command -v mvn >/dev/null 2>&1; then
  mvn_cmd=(mvn)
else
  mvn_cmd=(sh ./mvnw)
fi
mvn_args=(-B)
[ -d "$ROOT/.m2/repository" ] && mvn_args+=("-Dmaven.repo.local=$ROOT/.m2/repository")
[ "$offline" = 1 ] && mvn_args+=(-o)
if [ "$skip_tests" = 1 ]; then
  mvn_args+=(-DskipTests package)
else
  mvn_args+=(verify)
fi
echo "+ ${mvn_cmd[*]} ${mvn_args[*]}"
"${mvn_cmd[@]}" "${mvn_args[@]}"
echo "Maven jar: $ROOT/target/heatroute.jar"
