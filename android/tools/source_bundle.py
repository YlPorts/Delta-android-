#!/usr/bin/env python3
"""Corresponding source for this development build, with original notices intact."""
import json
from pathlib import Path
import subprocess
import tarfile
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'android/build/corresponding-source'; OUT.mkdir(parents=True,exist_ok=True)
repos=[('Delta-android',ROOT),('Gambatte',ROOT/'android/.external/gambatte'),('VBA-M',ROOT/'android/.external/vbam')]
for name in ['DeltaCore','NESDeltaCore','GBCDeltaCore','GBADeltaCore','SNESDeltaCore','GPGXDeltaCore','N64DeltaCore','MelonDSDeltaCore']:
    repos.append((name,ROOT/'Cores'/name))
manifest={}
for name,path in repos:
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip()
    tracked=subprocess.check_output(['git','ls-files','-z'],cwd=path).decode().split('\0')
    with tarfile.open(OUT/(name+'.tar.gz'),'w:gz') as archive:
        for relative in tracked:
            if relative and (path/relative).exists():
                archive.add(path/relative,arcname=relative,recursive=False)
    manifest[name]=revision
(OUT/'revisions.json').write_text(json.dumps(manifest,indent=2)+'\n')
(OUT/'BUILD.txt').write_text('Extract Delta-android, then each original *DeltaCore archive into its Cores/<name> directory.\nExtract Gambatte into android/.external/gambatte and VBA-M into android/.external/vbam.\nAssets and licenses are already materialized, including original LFS skin objects.\nUse python3 android/tools/build_cores.py --abi arm64-v8a, then gradle --project-dir android :app:assembleDebug.\nDo not run --prepare or regenerate assets in the extracted archives; no .git metadata is included.\nSee android/README.md and tools/core-sources.json for build dependencies and exact revisions.\nGenerated APKs are development builds, not stable releases. No commercial ROMs/BIOS.\n')
print(OUT)
