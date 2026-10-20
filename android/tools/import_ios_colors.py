#!/usr/bin/env python3
"""Read the exact named sRGB colors referenced by UIColor+Delta.swift.

Xcode writes byte components as decimal integers or 0xNN, and normalized
components as decimals. Missing/invalid originals fail the build.
"""
from __future__ import annotations
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'Resources/Assets.xcassets/Colors'
OUTPUT = ROOT / 'android/app/src/main/java/com/ylports/deltaandroid/OriginalDeltaColors.kt'


def component(value: object) -> int:
    s = str(value).strip()
    if re.fullmatch(r'0[xX][0-9a-fA-F]{2}', s):
        return int(s, 16)
    if re.fullmatch(r'\d+', s) and int(s) > 1:
        n = int(s)
    elif re.fullmatch(r'(?:0(?:\.\d+)?|1(?:\.0+)?|\.\d+)', s):
        n = int(float(s) * 255 + 0.5)
    else:
        raise ValueError(f'Invalid Xcode component: {s!r}')
    if not 0 <= n <= 255:
        raise ValueError(f'Out of range component: {s!r}')
    return n


def resolve(name: str) -> tuple[tuple[int, ...], str]:
    path = CATALOG / f'{name}.colorset/Contents.json'
    candidates = [c for c in json.loads(path.read_text())['colors']
                  if c.get('idiom') == 'universal' and not c.get('appearances')]
    color = candidates[0]['color']
    if color['color-space'] != 'srgb':
        raise ValueError(f'{path}: an explicit color-space conversion is required')
    return tuple(component(color['components'][k]) for k in ('red','green','blue')), str(path.relative_to(ROOT))


def main() -> None:
    output = ['// GENERATED from the original iOS catalogs; do not edit.',
              'package com.ylports.deltaandroid', '', 'import android.graphics.Color', '',
              'object OriginalDeltaColors {']
    for name in ('Purple','LightPurple','DarkGray'):
        rgb, source = resolve(name)
        output.extend([f'    // {source}',
                       f'    val {name[0].lower()+name[1:]}: Int = Color.rgb({", ".join(map(str,rgb))})'])
        print(f'{name}: {rgb} ({source})')
    OUTPUT.write_text('\n'.join(output + ['}', '']))

if __name__ == '__main__':
    main()
