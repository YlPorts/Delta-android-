#!/usr/bin/env python3
"""Build the real pinned cores, with deterministic output paths and 16KB ELF pages."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import hashlib
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
EXTERNAL = ROOT/'android/.external'
LOCK = json.loads((ROOT/'android/tools/core-sources.json').read_text())
MODULES = ['DeltaCore','GBCDeltaCore','NESDeltaCore','GBADeltaCore','SNESDeltaCore',
           'GPGXDeltaCore','N64DeltaCore','MelonDSDeltaCore']

def run(*args, cwd=ROOT):
    subprocess.run([str(x) for x in args],cwd=cwd,check=True)

def prepare():
    run('git','-c','url.https://github.com/.insteadOf=git@github.com:',
        'submodule','update','--init','--jobs','3',*[f'Cores/{x}' for x in MODULES])
    # Resolve public LFS objects through GitHub's media endpoint. Verify the
    # original pointer's SHA-256 and size before replacement. This also works
    # when an installation token cannot use another repository's LFS batch API.
    for module in MODULES:
        checkout = ROOT/'Cores'/module
        revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=checkout,text=True).strip()
        for skin_dir in checkout.rglob('Controller Skin'):
            for path in skin_dir.rglob('*'):
                if not path.is_file(): continue
                with path.open('rb') as stream: prefix = stream.read(200)
                if not prefix.startswith(b'version https://git-lfs.github.com/spec/v1'): continue
                fields = dict(line.split(' ',1) for line in prefix.decode().strip().splitlines())
                digest = fields['oid'].removeprefix('sha256:')
                size = int(fields['size'])
                if size > 64*1024*1024: raise RuntimeError(f'Unexpectedly large original asset: {path}')
                relative = urllib.parse.quote(str(path.relative_to(checkout)))
                url = f'https://media.githubusercontent.com/media/rileytestut/{module}/{revision}/{relative}'
                with urllib.request.urlopen(url,timeout=60) as response: data = response.read(size+1)
                if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
                    raise RuntimeError(f'Original LFS object integrity mismatch: {path}')
                path.write_bytes(data)
                print(f'Resolved verified original LFS object: {module}/{path.relative_to(checkout)}')
    source = EXTERNAL/'gambatte'
    if not source.exists(): run('git','clone',LOCK['gambatte']['url'],source)
    # This is the task-owned build cache, never a user's checkout.
    run('git','fetch','origin',LOCK['gambatte']['revision'],cwd=source)
    run('git','checkout','--detach',LOCK['gambatte']['revision'],cwd=source)
    actual = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT/'Cores/NESDeltaCore',text=True).strip()
    if actual != LOCK['nestopia']['parent_revision']:
        raise RuntimeError('Unexpected NESDeltaCore source revision; update the lock after review')
    license_dir = ROOT/'android/app/src/main/assets/licenses'
    license_dir.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source/'COPYING',license_dir/'Gambatte-GPL-2.0.txt')
    nes_license = ROOT/'Cores/NESDeltaCore/nestopia/COPYING'
    if not nes_license.exists(): nes_license = ROOT/'Cores/NESDeltaCore/nestopia/COPYING.txt'
    if not nes_license.exists(): raise RuntimeError('Missing original Nestopia license')
    shutil.copyfile(nes_license,license_dir/'Nestopia-GPL-2.0.txt')

def build(abi, jobs):
    sdk = Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
    ndk = sdk/'ndk/27.2.12479018'
    if not (ndk/'ndk-build').is_file(): raise RuntimeError('NDK 27.2.12479018 is required')
    destination = ROOT/f'android/app/src/main/jniLibs/{abi}'
    destination.mkdir(parents=True,exist_ok=True)
    for name, path in [('gambatte',EXTERNAL/'gambatte/libgambatte/libretro/jni'),
                       ('nestopia',ROOT/'Cores/NESDeltaCore/nestopia/libretro/jni')]:
        output = EXTERNAL/f'build/{name}/{abi}'
        # Nestopia's original source explicitly targets GNU C++98. Using the
        # NDK's newer default C++ standard rejects intentional byte packing.
        flags = ['APP_CPPFLAGS=-std=gnu++98'] if name == 'nestopia' else []
        run(ndk/'ndk-build','-C',path,f'APP_ABI={abi}','APP_PLATFORM=android-24',
            'APP_STL=c++_static','APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true',*flags,
            f'NDK_OUT={output}/obj',f'NDK_LIBS_OUT={output}/libs',f'-j{jobs}')
        shutil.copyfile(output/f'libs/{abi}/libretro.so',destination/f'lib{name}_libretro.so')

def host_tests(jobs):
    import sys
    test_dir = EXTERNAL/'host-tests'
    test_dir.mkdir(parents=True,exist_ok=True)
    run(sys.executable,ROOT/'android/tools/tests/diagnostic_roms.py',test_dir)
    run('make','-C',EXTERNAL/'gambatte','-f','Makefile.libretro',f'-j{jobs}')
    run('make','-C',ROOT/'Cores/NESDeltaCore/nestopia/libretro',f'-j{jobs}')
    binary = test_dir/'core-host-test'
    run('g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer',ROOT/'android/tools/tests/core_host_test.cpp','-ldl','-pthread','-o',binary)
    for name, source, rom in [('gambatte',EXTERNAL/'gambatte/gambatte_libretro.so','diagnostic.gb'),
                             ('gambatte',EXTERNAL/'gambatte/gambatte_libretro.so','diagnostic.gbc'),
                             ('gambatte',EXTERNAL/'gambatte/gambatte_libretro.so','diagnostic-rtc.gb'),
                             ('nestopia',ROOT/'Cores/NESDeltaCore/nestopia/libretro/nestopia_libretro.so','diagnostic.nes')]:
        # Reset fixtures between repeated invocations; these are generated test-only files.
        for extra in test_dir.glob(rom+'.*'): extra.unlink()
        run(binary,source,test_dir/rom,test_dir)

if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--prepare',action='store_true')
    p.add_argument('--abi',choices=['arm64-v8a','x86_64'])
    p.add_argument('--host-tests',action='store_true')
    p.add_argument('--jobs',type=int,default=2)
    args=p.parse_args()
    if args.prepare: prepare()
    if args.abi: build(args.abi,args.jobs)
    if args.host_tests: host_tests(args.jobs)
