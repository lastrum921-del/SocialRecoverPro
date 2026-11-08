#!/usr/bin/env bash
# Compila el módulo :core y ejecuta sus pruebas sin Gradle (solo necesita JDK 17+ y kotlinc).
# Uso: KOTLINC=/ruta/a/kotlinc scripts/run-core-tests.sh [-Dsrp.bigTestMb=1024]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib"
OUT="${TMPDIR:-/tmp}/srp-core-build"
rm -rf "$OUT"; mkdir -p "$OUT"
# -language-version 2.1: el mismo nivel de lenguaje que usa el proyecto Android (Kotlin 2.1)
"$KOTLINC" -language-version 2.1 -api-version 2.1 -jvm-target 17 -nowarn \
  "$ROOT/core/src/main/kotlin" -d "$OUT/core.jar"
"$KOTLINC" -language-version 2.1 -api-version 2.1 -jvm-target 17 -nowarn \
  -cp "$OUT/core.jar:$KLIB/kotlin-test.jar" \
  "$HERE/jvm-test-runner" "$ROOT/core/src/test/kotlin" -d "$OUT/tests.jar"
CLASSES=${SRP_TESTS:-$(cd "$ROOT/core/src/test/kotlin" && find . -name '*Test.kt' | sed 's#^\./##; s#\.kt$##; s#/#.#g' | sort)}
java -Dstdout.encoding=UTF-8 "$@" -cp "$OUT/core.jar:$OUT/tests.jar:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-test.jar${EXTRA_CP:+:$EXTRA_CP}" RunnerKt $CLASSES
