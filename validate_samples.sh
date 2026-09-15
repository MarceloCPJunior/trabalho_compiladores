#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

javac Main.java
java Main samples/valid/program_ok.simple

for sample in samples/invalid/*.simple; do
  output_file="$(mktemp)"
  java Main "$sample" >"$output_file" 2>&1 || true
  diff -u "${sample%.simple}.expected" "$output_file"
  rm -f "$output_file"
done

echo "All sample validations passed."
