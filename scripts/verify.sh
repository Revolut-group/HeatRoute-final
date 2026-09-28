#!/usr/bin/env bash
# Independently verifies a saved result against its input.
# Usage: scripts/verify.sh <input.geojson> <result.geojson> [report=<result dir>/verify-report.json] [--rules rules.yaml]
# Exit code: 0 valid, 2 input error, 4 result invalid.
set -euo pipefail
source "$(dirname "$0")/common.sh"
[ $# -ge 2 ] || { sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//'; case "${1:-}" in -h|--help) exit 0;; *) exit 2;; esac; }
input="$1" result="$2"; shift 2
report="$(dirname "$result")/verify-report.json"
if [ $# -ge 1 ] && [ "${1#--}" = "$1" ]; then report="$1"; shift; fi
jar="$(find_jar)" || die "heatroute.jar not found; run scripts/build.sh --skip-tests first"
use_java11 || echo "warning: no JDK 11 found, using $(java_bin)" >&2
read -r -a opts <<< "${JAVA_OPTS:--Xmx4g}"
set +e
"$(java_bin)" "${opts[@]}" -jar "$jar" verify --input "$input" --result "$result" --report "$report" "$@"
code=$?
set -e
[ -f "$report" ] && summary "$report" && echo "report: $report"
exit $code
