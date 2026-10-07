#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${1:-$repo_dir/app/build/outputs/apk/production/release/app-production-release-unsigned.apk}"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" ]]; then
    printf 'ANDROID_HOME or ANDROID_SDK_ROOT is required\n' >&2
    exit 1
fi
analyzer="$sdk_dir/cmdline-tools/latest/bin/apkanalyzer"
if [[ ! -x "$analyzer" || ! -f "$apk" ]]; then
    printf 'Missing apkanalyzer or APK: %s %s\n' "$analyzer" "$apk" >&2
    exit 1
fi

"$analyzer" manifest print "$apk" | python3 "$repo_dir/tools/check_manifest.py" \
    --application-id "${ADSKIP_EXPECTED_APPLICATION_ID:-com.sis5595.adskip23}"
