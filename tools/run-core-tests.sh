#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
javac -d "$output" app/src/main/java/org/adskip/core/*.java tools/RuntimeRegressionSelfTest.java tools/PlaybackCycleSelfTest.java tools/UpdatePolicySelfTest.java
java -cp "$output" RuntimeRegressionSelfTest
java -cp "$output" PlaybackCycleSelfTest
java -cp "$output" UpdatePolicySelfTest
