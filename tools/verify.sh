#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
tools/run-core-tests.sh
"${GRADLE_BIN:-gradle}" --no-daemon "$@" :app:assembleProductionDebug :app:assembleProductionRelease :app:lintProductionRelease
tools/check-production-apk.sh
python3 tools/check_manifest.py --application-id com.sis5595.adskip23 --debug app/build/intermediates/merged_manifests/productionDebug/AndroidManifest.xml
python3 tools/check-production-config.py
