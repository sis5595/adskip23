#!/usr/bin/env python3
"""Structural production boundary checks, also reusable for negative regression tests."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'

def check(xml, app_id, debug=False):
    root=ET.fromstring(xml)
    assert root.get('package')==app_id, 'unexpected application ID'
    permissions=root.findall('uses-permission')
    assert len(permissions)==3 and {p.get(ANDROID+'name') for p in permissions}=={
        'android.permission.INTERNET','android.permission.POST_NOTIFICATIONS',
        'android.permission.REQUEST_INSTALL_PACKAGES'}, 'unexpected permissions'
    assert not root.findall('uses-permission-sdk-23'), 'unexpected SDK-scoped permissions'
    app=root.find('application')
    assert app is not None
    assert app.get(ANDROID+'debuggable','false')==str(debug).lower(), 'debuggable mismatch'
    assert app.get(ANDROID+'allowBackup')=='false', 'backup must remain disabled'
    for field in ['icon','fullBackupContent','dataExtractionRules']:
        assert app.get(ANDROID+field), 'missing '+field
    assert len(app.findall('activity'))==1 and not app.findall('activity-alias'), 'activity mismatch'
    assert app.find('activity').get(ANDROID+'name')=='org.adskip.probe.MainActivity', 'unexpected activity'
    providers=app.findall('provider')
    assert len(providers)==1, 'unexpected provider count'
    provider=providers[0]
    assert provider.get(ANDROID+'name')=='org.adskip.probe.UpdateApkProvider', 'unexpected provider'
    assert provider.get(ANDROID+'authorities')==app_id+'.updates', 'unexpected update authority'
    assert provider.get(ANDROID+'exported')=='false', 'update provider must remain private'
    assert provider.get(ANDROID+'grantUriPermissions')=='true', 'installer requires a temporary URI grant'
    services=app.findall('service')
    assert len(services)==1, 'unexpected service count'
    listener=services[0]
    assert listener.get(ANDROID+'name')=='org.adskip.probe.BiliNotificationListener', 'listener mismatch'
    assert listener.get(ANDROID+'permission')=='android.permission.BIND_NOTIFICATION_LISTENER_SERVICE', 'listener permission'
    assert listener.get(ANDROID+'exported')=='false', 'listener export changed'
    receivers=app.findall('receiver')
    assert len(receivers)==1, 'receiver count mismatch'
    by_name={r.get(ANDROID+'name'):r for r in receivers}
    undo=by_name.get('org.adskip.probe.UndoActionReceiver')
    assert undo is not None and undo.get(ANDROID+'exported')=='false', 'Undo receiver must not be exported'
    return root.get(ANDROID+'versionCode')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--application-id',default='org.adskip.probe')
    parser.add_argument('--debug',action='store_true')
    parser.add_argument('manifest',nargs='?')
    args=parser.parse_args()
    xml=Path(args.manifest).read_text(encoding='utf-8') if args.manifest else sys.stdin.read()
    try:version=check(xml,args.application_id,args.debug)
    except (AssertionError,ET.ParseError) as error:raise SystemExit('FAIL manifest: '+str(error))
    print(f'PASS structural manifest: {args.application_id}, version {version}, debug={args.debug}')

if __name__=='__main__':main()
