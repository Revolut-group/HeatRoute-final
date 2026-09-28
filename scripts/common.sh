# Shared helpers for the bash scripts; sourced, not executed.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
die() { echo "error: $*" >&2; exit 2; }

java_major() { "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | head -1; }
java_home_of() { [ -n "$1" ] && "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | head -1; }

# Selects a Java 11 runtime: JAVA_HOME, then java on PATH, then common install locations.
use_java11() {
  local candidate
  for candidate in "${JAVA_HOME:-}" "$(java_home_of "$(command -v java 2>/dev/null)")" \
      "$(/usr/libexec/java_home -v 11 2>/dev/null || true)" /opt/homebrew/opt/openjdk@11 /usr/local/opt/openjdk@11 \
      /usr/lib/jvm/java-11-openjdk* /usr/lib/jvm/temurin-11* /usr/lib/jvm/java-11*; do
    [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || continue
    if [ "$(java_major "$candidate/bin/java")" = "11" ]; then export JAVA_HOME="$candidate" PATH="$candidate/bin:$PATH"; return 0; fi
  done
  return 1
}

java_bin() { if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; else command -v java || die "java not found; install JDK 11"; fi; }

# The executable jar: HEATROUTE_JAR or Maven output.
find_jar() {
  local jar
  for jar in "${HEATROUTE_JAR:-}" "$ROOT/target/heatroute.jar"; do
    [ -n "$jar" ] && [ -f "$jar" ] && { echo "$jar"; return 0; }
  done
  return 1
}

# Prints "valid=... connected=C/N score=S length=L m" for the rank-1 variant of a verify report.
summary() {
  awk '
    /"valid" *:/ && valid == "" { v=$0; sub(/.*"valid" *: */, "", v); sub(/[ ,]*$/, "", v); valid=v }
    /"variants" *: *\[/ { inv=1 }
    inv && /"length" *:/ && len == "" { v=$0; sub(/.*: */, "", v); sub(/[ ,]*$/, "", v); len=v }
    inv && /"score" *:/ && score == "" { v=$0; sub(/.*: */, "", v); sub(/[ ,]*$/, "", v); score=v }
    inv && /"connected_demands" *:/ && conn == "" { v=$0; sub(/.*: */, "", v); sub(/[ ,]*$/, "", v); conn=v }
    inv && /"unconnected_oks_ids" *:/ && unc == "" { v=$0; sub(/.*: *\[/, "", v); sub(/\].*/, "", v); gsub(/ /, "", v); unc=(v == "" ? 0 : split(v, a, ",")); uncset=1 }
    END {
      if (conn == "") { printf "valid=%s (no verified variant in report)\n", valid; exit }
      printf "valid=%s connected=%d/%d score=%s length=%.1f m\n", valid, conn, conn + unc, score, len
    }' "$1"
}
