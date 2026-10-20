"""Original CC0 diagnostic programs. Generated only in test output, never APK assets.
No commercial ROM bytes, Nintendo logo, firmware or BIOS are included.
"""
from pathlib import Path


def gb(color=False, rtc=False) -> bytes:
    rom = bytearray(32768)
    rom[0x100:0x104] = bytes([0xc3,0x50,0x01,0x00])
    rom[0x134:0x144] = b'DELTA TEST'.ljust(16,b'\0')
    rom[0x143] = 0x80 if color else 0
    rom[0x147:0x14a] = bytes([0x10 if rtc else 3,0,2]) # MBC1 + battery + 8KB RAM
    checksum = 0
    for value in rom[0x134:0x14d]: checksum = (checksum-value-1)&255
    rom[0x14d] = checksum
    # Disable LCD; enable battery RAM; initialize tile 0 and palette; enable LCD.
    program = bytearray.fromhex('f3 31 ff df af e0 40 3e 0a ea 00 00 3e 42 ea 00 a0 af ea 00 c0 3e e4 e0 47 21 00 98 01 00 04 af 22 0b 78 b1 20 f9 21 00 80 06 08 78 22 2f 22 05 20 f9 3e 80 e0 68 3e ff e0 69 3e 7f e0 69 3e 1f e0 69 3e 00 e0 69 3e e0 e0 69 3e 03 e0 69 3e 00 e0 69 3e 7c e0 69 3e 80 e0 26 3e 77 e0 24 3e 11 e0 25 3e 80 e0 11 3e f0 e0 12 3e 00 e0 13 3e 87 e0 14 3e 91 e0 40')
    loop = 0x150 + len(program)
    program.extend(bytes.fromhex('f0 44 fe 90 38 fa fa 00 c0 3c ea 00 c0 ea 01 a0 e0 42 3e 20 e0 00 f0 00 2f e6 0f ea 02 a0 3e 10 e0 00 f0 00 2f e6 0f ea 03 a0 f0 44 fe 90 30 fa'))
    program.extend([0xc3,loop&255,loop>>8])
    rom[0x150:0x150+len(program)] = program
    return bytes(rom)


def nes() -> bytes:
    rom = bytearray(b'NES\x1a'+bytes([1,1,2])+bytes(9)) # NROM + battery RAM
    prg = bytearray([0xea])*16384
    # Disable interrupts/rendering, enable APU pulse tone, write SRAM and palette.
    p = bytearray.fromhex('78 d8 a2 ff 9a e8 8e 00 20 8e 01 20 a9 42 8d 00 60 a9 00 8d 01 60 a9 01 8d 15 40 a9 bf 8d 00 40 a9 fd 8d 02 40 a9 08 8d 03 40')
    # Palette first entry; enable background. Each vblank updates counter/color.
    p.extend(bytes.fromhex('a9 3f 8d 06 20 a9 00 8d 06 20 a9 21 8d 07 20 a9 08 8d 01 20'))
    loop = 0x8000+len(p)
    p.extend(bytes.fromhex('2c 02 20 10 fb ee 01 60 ad 01 60 29 3f 48 a9 3f 8d 06 20 a9 00 8d 06 20 68 8d 07 20 a9 00 8d 05 20 8d 05 20'))
    # Read the first joypad bit (A) into battery RAM[2].
    p.extend(bytes.fromhex('a9 01 8d 16 40 a9 00 8d 16 40 ad 16 40 29 01 8d 02 60'))
    p.extend([0x4c,loop&255,loop>>8])
    prg[:len(p)] = p
    for offset in [0x3ffa,0x3ffc,0x3ffe]: prg[offset:offset+2] = bytes([0,0x80])
    rom.extend(prg)
    rom.extend(bytes([0xaa,0x55])*4096) # authored checkerboard CHR
    return bytes(rom)

def gba() -> bytes:
    # ARM7TDMI program assembled from gba-diagnostic.s. No proprietary logo.
    import struct
    rom = bytearray(8192)
    struct.pack_into('<I',rom,0,0xea00002e) # branch from cartridge entry to 0xc0
    rom[0xa0:0xb2] = b'DELTA TEST  DLTA00'
    rom[0xb2] = 0x96
    rom[0xbd] = (-sum(rom[0xa0:0xbd])-0x19)&255
    program = bytes.fromhex('0103a0e398109fe5b010c0e10e54a0e34210a0e30010c5e50040a0e30140c5e58010a0e3b418c0e178109fe5b018c0e10210a0e3b218c0e16c109fe5b216c0e1871ca0e3b416c0e10664a0e35c709fe50010a0e3b210c6e0011081e2070056e1fbffff1ab610d0e1a00051e3fcffff3a014084e20140c5e534809fe5b010d8e10110e0e1011001e20310c5e50664a0e3b040c6e1b610d0e1a00051e3fcffff2aefffffea030400007711000080f00000002c0106300100040000000000000000000000000000000000000000000000000000000000000000000000000000000000000000')
    rom[0xc0:0xc0+len(program)] = program
    rom[0x1000:0x1009] = b'SRAM_V110'
    return bytes(rom)

if __name__ == '__main__':
    import sys
    destination = Path(sys.argv[1]); destination.mkdir(parents=True,exist_ok=True)
    (destination/'diagnostic.gb').write_bytes(gb())
    (destination/'diagnostic.gbc').write_bytes(gb(color=True))
    (destination/'diagnostic-rtc.gb').write_bytes(gb(rtc=True))
    (destination/'diagnostic.nes').write_bytes(nes())
    (destination/'diagnostic.gba').write_bytes(gba())
