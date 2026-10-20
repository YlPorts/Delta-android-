#!/usr/bin/env python3
"""Package unmodified original artwork/skins, recording source SHA-256 hashes.
Run after initializing the original Delta core submodules. No ROMs or BIOS.
"""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'android/app/src/main'
CATALOG = ROOT / 'Resources/Assets.xcassets'
SOURCES = {}

def copy(source, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if source.read_bytes().startswith(b'version https://git-lfs.github.com/spec/v1'):
        raise ValueError(f'Unresolved original Git LFS asset: {source}')
    shutil.copyfile(source, dest)
    SOURCES[str(dest.relative_to(OUT))] = {'source':str(source.relative_to(ROOT)),
                                         'sha256':hashlib.sha256(source.read_bytes()).hexdigest()}

for original, android in [('BoxArt','box_art'), ('DeltaPlaceholder','delta_placeholder'),
 ('SaveSaveState','save_state'), ('LoadSaveState','load_state'), ('CheatCodes','cheat_codes'),
 ('Pause','pause'), ('FastForward','fast_forward'), ('SustainButtons','sustain_buttons'), ('Screenshot','screenshot')]:
    directories = list(CATALOG.rglob(original+'.imageset'))
    if len(directories) != 1:
        raise ValueError(f'Expected exactly one original {original} imageset')
    directory = directories[0]
    for item in json.loads((directory/'Contents.json').read_text())['images']:
        if item.get('filename') and item.get('scale') in ('1x','2x','3x'):
            density = {'1x':'mdpi','2x':'xhdpi','3x':'xxhdpi'}[item['scale']]
            copy(directory/item['filename'], OUT/f'res/drawable-{density}/delta_{android}.png')
copy(CATALOG/'SettingsButton.imageset/gear_1.pdf', OUT/'assets/ios/settings.pdf')
for system, module, subdir in [('gbc','GBCDeltaCore','GBCDeltaCore'),
 ('nes','NESDeltaCore','NESDeltaCore'), ('gba','GBADeltaCore','GBADeltaCore'),
 ('snes','SNESDeltaCore','SNESDeltaCore'), ('n64','N64DeltaCore','N64DeltaCore'),
 ('ds','MelonDSDeltaCore','MelonDSDeltaCore'), ('genesis','GPGXDeltaCore','Sources/GPGXDeltaCore/Resources')]:
    copy(ROOT/f'Cores/{module}/{subdir}/Controller Skin/Standard.deltaskin',
         OUT/f'assets/skins/{system}.deltaskin')
copy(ROOT/'COPYING', OUT/'assets/licenses/Delta-AGPL-3.0.txt')
(OUT/'assets/ios/provenance.json').write_text(json.dumps(SOURCES, indent=2)+'\n')
print(f'Packaged {len(SOURCES)} original resources; see assets/ios/provenance.json')
