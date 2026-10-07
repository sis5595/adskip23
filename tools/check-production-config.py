from pathlib import Path
root=Path(__file__).resolve().parents[1]
config=(root/'app/build/generated/source/buildConfig/production/release/org/adskip/probe/BuildConfig.java').read_text()
for value in ['APPLICATION_ID = "com.sis5595.adskip23"', 'VERSION_CODE = 50', 'VERSION_NAME = "0.13.1-beta.2"', 'RESEARCH_NETWORK_ENABLED = false', 'PRODUCTION_READ_ONLY_ENABLED = true', 'TARGET_MEDIA_PACKAGE = "tv.danmaku.bili"']:
    assert value in config, value
assert not any((root/'app/src'/name).exists() for name in ['research','harness','productionDebug'])
print('PASS production identity and build configuration')
