#!/usr/bin/env bash
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
V="$DIR/../../.claude-plugin/plugin/scripts/validate.sh"
fail=0
bash "$V" "$DIR/fixtures/good-plugin"; rc=$?
[ "$rc" -eq 0 ] || { echo "EXPECTED good=0 got $rc"; fail=1; }
bash "$V" "$DIR/fixtures/bad-plugin"; rc=$?
[ "$rc" -ne 0 ] || { echo "EXPECTED bad!=0 got 0"; fail=1; }
[ "$fail" -eq 0 ] && echo "ALL PASS" || { echo "TESTS FAILED"; exit 1; }
