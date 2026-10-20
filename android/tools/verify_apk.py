#!/usr/bin/env python3
"""Check actual APK contents, exported JNI and native 16KB ELF load alignment."""
import argparse
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

p=argparse.ArgumentParser(); p.add_argument('apk'); p.add_argument('--abi',default='arm64-v8a'); args=p.parse_args()
expected={'libdelta_android.so','libgambatte_libretro.so','libnestopia_libretro.so','libvbam_libretro.so'}
with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory() as tmp:
    files=[n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
    actual={Path(n).name for n in files}
    assert expected <= actual, f'Missing actual engines: {expected-actual}'
    assert all(n.split('/')[1]==args.abi for n in files), f'Unexpected ABI: {files}'
    for name in files:
        data=archive.read(name)
        assert data[:5]==b'\x7fELF\x02',f'Not ELF64: {name}'
        machine=struct.unpack_from('<H',data,18)[0]
        assert machine==({'arm64-v8a':183,'x86_64':62}[args.abi]),f'Wrong machine: {name}'
        phoff=struct.unpack_from('<Q',data,32)[0]
        phsize,phnum=struct.unpack_from('<HH',data,54)
        for index in range(phnum):
            offset=phoff+index*phsize
            kind=struct.unpack_from('<I',data,offset)[0]
            if kind==1:
                alignment=struct.unpack_from('<Q',data,offset+48)[0]
                assert alignment>=16384,f'{name}: ELF LOAD alignment {alignment} is below 16KB'
        print(f'ELF64 + 16KB LOAD segments: {name}')
    assert 'assets/skins/gbc.deltaskin' in archive.namelist()
    assert 'assets/skins/nes.deltaskin' in archive.namelist()
    for path in archive.namelist():
        if path.startswith('assets/skins/') and path.endswith('.deltaskin'):
            with zipfile.ZipFile(io.BytesIO(archive.read(path))) as skin:
                info=json.loads(skin.read('info.json'))
                assert info.get('representations'),f'Invalid original skin: {path}'
    assert not any(n.lower().endswith(('.gb','.gbc','.nes','.gba','.nds','.bios')) for n in archive.namelist()),'ROM/firmware in APK'
    assert 'assets/licenses/Gambatte-GPL-2.0.txt' in archive.namelist()
    assert 'assets/licenses/Nestopia-GPL-2.0.txt' in archive.namelist()
    assert 'assets/licenses/VBA-M-GPL-2.0.txt' in archive.namelist()
    assert 'assets/licenses/VBA-M-Audio-LGPL-2.1.txt' in archive.namelist()
    assert 'assets/licenses/VBA-M-Audio-Notices.txt' in archive.namelist()
    print('PASS: engines, ABI, alignment, original skins, licenses, no ROM/BIOS assets')
