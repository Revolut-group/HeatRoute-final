#!/usr/bin/env bash
# Solves an input and independently verifies the result.
# Usage: scripts/solve.sh <input.geojson> [output_dir=work/solve] [extra solve options, e.g. --variants 1 --rules config/rules-current.yaml]
# Writes <output_dir>/result.geojson, diagnostics.json and verify-report.json; with --stage depth also the separate
# depth set result-depth.geojson and verify-report-depth.json (verified by solve itself).
# JAVA_OPTS defaults to -Xmx4g (1g works with lower quality); HEATROUTE_JAR overrides the jar.
set -euo pipefail
source "$(dirname "$0")/common.sh"
usage() { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; }
case "${1:-}" in -h|--help) usage; exit 0;; "") usage; exit 2;; esac
input="$1"; shift
out="work/solve"
if [ $# -ge 1 ] && [ "${1#--}" = "$1" ]; then out="$1"; shift; fi
[ -f "$input" ] || die "input not found: $input"
jar="$(find_jar)" || die "heatroute.jar not found; run scripts/build.sh --skip-tests first"
use_java11 || echo "warning: no JDK 11 found, using $(java_bin)" >&2
java="$(java_bin)"
read -r -a opts <<< "${JAVA_OPTS:--Xmx4g}"
mkdir -p "$out"
echo "+ java ${opts[*]} -jar $jar solve --input $input --output $out/result.geojson $*" >&2
start=$(date +%s)
"$java" "${opts[@]}" -jar "$jar" solve --input "$input" --output "$out/result.geojson" --diagnostics "$out/diagnostics.json" "$@"
echo "solve finished in $(( $(date +%s) - start )) s" >&2
rules=()
for ((i=1; i<=$#; i++)); do [ "${!i}" = "--rules" ] && { j=$((i+1)); rules=(--rules "${!j}"); }; done
set +e
"$java" "${opts[@]}" -jar "$jar" verify --input "$input" --result "$out/result.geojson" --report "$out/verify-report.json" ${rules[@]+"${rules[@]}"}
code=$?
set -e
summary "$out/verify-report.json"
echo "outputs: $out/result.geojson $out/diagnostics.json $out/verify-report.json"
case " $* " in *" --stage depth "*) echo "depth set: $out/result-depth.geojson $out/verify-report-depth.json";; esac
exit $code
