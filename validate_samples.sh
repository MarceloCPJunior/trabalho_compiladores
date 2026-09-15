#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

javac Main.java
java Main samples/valid/program_ok.simple

for sample in samples/invalid/*.simple; do
  output_file="$(mktemp)"
  trap 'rm -f "$output_file"' EXIT
  java Main "$sample" >"$output_file" 2>&1 || true
  diff -u "${sample%.simple}.expected" "$output_file"
  rm -f "$output_file"
  trap - EXIT
done

missing_output="$(mktemp)"
trap 'rm -f "$missing_output"' EXIT
java Main does-not-exist.simple >"$missing_output" 2>&1 || true
grep -F "Error: could not open 'does-not-exist.simple'" "$missing_output"
rm -f "$missing_output"
trap - EXIT

echo "All sample validations passed."
