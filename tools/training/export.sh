#!/bin/bash
# Builds the training exporter (the app's core code + tools/training/exporter) and runs it:
#   tools/training/export.sh DOCS_DIR SAMPLES.jsonl [seed]
# Needs Java 17. Uses kotlinc from PATH, or downloads Kotlin 2.0.21 (the version the app builds with).
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${KOTLIN_WORK:-$ROOT/build/training}"
mkdir -p "$WORK"
if ! command -v kotlinc >/dev/null; then
  if [ ! -x "$WORK/kotlinc/bin/kotlinc" ]; then
    curl -sSL -o "$WORK/kotlinc.zip" https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
    (cd "$WORK" && unzip -q -o kotlinc.zip)
  fi
  export PATH="$WORK/kotlinc/bin:$PATH"
fi
JAR="$WORK/exporter.jar"
if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/core/src/main/kotlin" "$ROOT/tools/training/exporter" -newer "$JAR" -name '*.kt' | head -1)" ]; then
  kotlinc "$ROOT/core/src/main/kotlin" "$ROOT/tools/training/exporter" -module-name core -jvm-target 17 -include-runtime -d "$JAR" 2>&1 | grep -v '^warning' || true
fi
java -Xmx4g -cp "$JAR:$ROOT/core/src/main/resources" com.kitchenreceipts.training.TrainingExportKt "$@"
