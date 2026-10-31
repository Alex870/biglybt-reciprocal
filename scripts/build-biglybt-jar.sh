#!/usr/bin/env bash
# Builds libs/BiglyBT.jar (the compile-time API jar) from BiglyBT's source, for machines
# without a BiglyBT install (e.g. CI). Only the classes the plugin references are compiled.
#
# Usage: scripts/build-biglybt-jar.sh <path-to-BiglyBT-source-checkout>
#
# If you have BiglyBT installed, you can skip this and copy its BiglyBT.jar into libs/.
set -euo pipefail

src="${1:?usage: $0 <path-to-BiglyBT-source>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cp_libs="$(find "$src/core/lib" -name '*.jar' -printf '%p:' 2>/dev/null || true)"
if ! javac -proc:none -nowarn -encoding UTF-8 --release 8 \
  -cp "$cp_libs" -sourcepath "$src/core/src" -d "$work/classes" \
  $(find "$root/src/main/java" -name '*.java') 2> "$work/javac.log"; then
  grep -v '^Note:' "$work/javac.log" >&2
  echo "javac failed" >&2
  exit 1
fi

rm -rf "$work/classes/io"   # drop the plugin's own classes
mkdir -p "$root/libs"
(cd "$work/classes" && jar cf "$root/libs/BiglyBT.jar" .)
echo "Wrote $root/libs/BiglyBT.jar"
