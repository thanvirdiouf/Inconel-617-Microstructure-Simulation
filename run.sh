#!/usr/bin/env bash
set -euo pipefail
task_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_classes="$task_script_dir/build/classes"
mkdir -p "$task_classes"
javac --release 17 -encoding UTF-8 -d "$task_classes" "$task_script_dir"/src/inconel617/*.java
exec java -Djava.awt.headless=true -Dinconel.output.root="$task_script_dir/runs" \
    -cp "$task_classes" inconel617.Main "$@"
