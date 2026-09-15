#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$REPO_DIR"

javac Main.java
valid_output="$TMP_DIR/program_ok.out"
java Main samples/valid/program_ok.simple >"$valid_output"
diff -u samples/valid/program_ok.expected "$valid_output"

for sample in samples/invalid/*.simple; do
  output_file="$TMP_DIR/$(basename "${sample%.simple}").out"
  if java Main "$sample" >"$output_file" 2>&1; then
    echo "Falha esperada não ocorreu para o exemplo inválido: $sample" >&2
    exit 1
  fi
  diff -u "${sample%.simple}.expected" "$output_file"
done

missing_output="$TMP_DIR/missing.out"
java Main does-not-exist.simple >"$missing_output" 2>&1 || true
grep -F "Erro: não foi possível abrir 'does-not-exist.simple'" "$missing_output"

echo "Todas as validações dos exemplos passaram."
