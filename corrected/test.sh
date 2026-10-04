#!/usr/bin/env bash
set -euo pipefail
task_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_classes="$task_script_dir/build/classes"
mkdir -p "$task_classes"
javac --release 17 -encoding UTF-8 -d "$task_classes" "$task_script_dir"/src/inconel617/*.java
javac --release 17 -encoding UTF-8 -cp "$task_classes" -d "$task_classes" \
    "$task_script_dir"/src/test/inconel617/*.java
exec java -ea -Djava.awt.headless=true -cp "$task_classes" inconel617.SimulationTest
