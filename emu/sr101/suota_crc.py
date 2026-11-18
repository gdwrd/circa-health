#!/usr/bin/env python3
"""SUOTA transport integrity used by JRing: a single XOR byte over the whole image.

Evidence [app]: com.jaga.ibraceletplus.jyring.ota.File.calculateCrc() XORs every byte and
appends the result as one trailing byte before upload (setType(1)). There is NO signature.
The Dialog bootloader separately checks the image's own AN-B-001 header CRC in flash.

Usage: python -I emu/sr101/suota_crc.py <firmware.bin>   (prints the XOR byte)
"""
import sys

def xor_crc(data: bytes) -> int:
    c = 0
    for b in data:
        c ^= b
    return c & 0xFF

def _selftest():
    assert xor_crc(b"\x00") == 0x00
    assert xor_crc(b"\x12\x34\x56") == (0x12 ^ 0x34 ^ 0x56)
    assert xor_crc(b"\xff\xff") == 0x00
    print("selftest OK")

if __name__ == "__main__":
    if len(sys.argv) == 1:
        _selftest()
    else:
        with open(sys.argv[1], "rb") as f:
            d = f.read()
        print(f"len={len(d)}  xor_crc=0x{xor_crc(d):02x}")
