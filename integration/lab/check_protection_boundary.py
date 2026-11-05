"""Compare real Kotlin and Swift policy fixtures; no clocks/devices/settings changed."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def compare(mac, android):
    policy = json.loads((ROOT/'shared/session-policy-v1.json').read_text())
    for key in ('schemaVersion','baseWorkSeconds','pauseSeconds','maxProtectionSeconds',
                'expiryFromStartSeconds','secondPauseAccepted','restrictedAt'):
        if mac[key] != android[key]:
            raise ValueError(f'Cross-device protection mismatch: {key}')
    if mac['baseWorkSeconds'] != policy['sessionMinutes']*60:
        raise ValueError('Nominal work target differs from agreed default')
    if mac['pauseSeconds'] != policy['pauseSeconds'] or mac['maxProtectionSeconds'] != policy['maxProtectionWallSeconds']:
        raise ValueError('Pause/total bound differs from shared policy')
    expected={'599':True,'600':False,'899':False,'900':True,'1500':True,'1799':True,'1800':False}
    if mac['restrictedAt'] != expected or mac['secondPauseAccepted'] is not False:
        raise ValueError('Boundary/once-only pause contract failed')
    if mac['expiryFromStartSeconds'] != 1800:
        raise ValueError('Protection would end early or exceed agreed bound')
    if mac['observedWorkSeconds'] != 1500 or mac['remainingWorkSeconds'] != 0:
        raise ValueError('Pause was counted as work or nominal work target was miscounted')
    return {'matched':True,'workSeconds':1500,'pauseSeconds':300,'protectionBoundSeconds':1800,
            'protectionAtMinute25':True,'protectionAtMinute30':False,'liveSynchronized':False}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mac',required=True,type=Path)
    p.add_argument('--android',required=True,type=Path)
    args=p.parse_args()
    print(json.dumps(compare(json.loads(args.mac.read_text()),json.loads(args.android.read_text())),indent=2))


if __name__=='__main__':main()
