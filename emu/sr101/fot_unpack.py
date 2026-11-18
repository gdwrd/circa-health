#!/usr/bin/env python3
"""Unpack a keeprapid 003A-family OTA image (Dialog-style header + Bluetrum FOT package) to its flash image.

Usage: python -I emu/sr101/fot_unpack.py <image.bin> [out_dir]   (prints the layout; writes app.bin/updater.bin if out_dir)
       python -I emu/sr101/fot_unpack.py                          (self-check on emu/sr101/firmware/*.bin)
Cipher and magic keys: github.com/kagaimiq/bluetrum-tools (LFSR x32+x30+x26+x25, CRC-16/CCITT init FFFF).
"""
import glob, hashlib, lzma, os, struct, sys, zlib

LVMG, XUPD, XPKG = 0x474D564C, 0x44505558, 0x474B5058

_T = []
for _r in range(256):
    for _ in range(8):
        _r = (_r >> 1) ^ (0xA3000000 if _r & 1 else 0)
    _T.append(_r)
_C = []
for _i in range(256):
    _c = _i << 8
    for _ in range(8):
        _c = ((_c << 1) ^ 0x1021) & 0xFFFF if _c & 0x8000 else (_c << 1) & 0xFFFF
    _C.append(_c)


def lfsr(data: bytes, key: int) -> bytes:
    out = bytearray(data)
    for i in range(len(out)):
        out[i] ^= key & 0xFF
        key = (key >> 8) ^ _T[key & 0xFF]
    return bytes(out)


def crc16(data: bytes, c: int = 0xFFFF) -> int:
    for b in data:
        c = ((c << 8) & 0xFFFF) ^ _C[(c >> 8) ^ b]
    return c


def unpack(img: bytes) -> dict:
    """Return the parsed fields plus 'app' (plain flash image) and 'updater' (boot stub + updater, plain)."""
    sig, size, crc, ver = struct.unpack_from("<2s2xII16s", img, 0)
    cid, did, hdr = struct.unpack_from("<HHI", img, 0x38)
    assert sig == b"\x70\x61" and hdr == 0x40 and size == len(img) - 0x40, "not a 003A-family image"
    assert zlib.crc32(img[0x40:]) == crc, "outer CRC-32"
    pkg = img[0x80:]
    assert pkg[:4] == b"POT\0" and crc16(pkg[:0xFE]) == struct.unpack_from("<H", pkg, 0xFE)[0], "package CRC-16"
    tags, o = {}, 8
    while o < 0xF0 and pkg[o] & 0x80:
        n = struct.unpack_from("<I", pkg, o + 4)[0]
        tags[bytes(b & 0x7F for b in pkg[o:o + 4]).rstrip(b"\0").decode()] = pkg[o + 8:o + 8 + n]
        o += 8 + n
    assert tags["INFO"][:4] == b"XBOX", "chip code name"
    d = struct.unpack("<16I", tags["DATA"])
    app_off, app_len, upd_off, upd_len, key, app_size = d[0], d[1], d[6], d[7], d[12] ^ XPKG, d[13]
    enc = pkg[app_off:app_off + app_len]
    packed = b"".join(lfsr(enc[i:i + 512], key ^ ((i >> 9) + 1)) for i in range(0, app_len, 512))
    lc, dict_size = packed[0] % 9, struct.unpack_from("<I", packed, 1)[0]
    lp, pb = packed[0] // 9 % 5, packed[0] // 45
    dec = lzma.LZMADecompressor(lzma.FORMAT_RAW, filters=[
        {"id": lzma.FILTER_LZMA1, "dict_size": dict_size, "lc": lc, "lp": lp, "pb": pb}])
    app = dec.decompress(packed[5:], app_size)
    assert len(app) == app_size, "LZMA size"
    u = pkg[upd_off:upd_off + upd_len]
    uh = lfsr(u[:0x40], LVMG)
    assert crc16(uh[:62]) == struct.unpack_from("<H", uh, 62)[0], "updater header CRC"
    bcrc = struct.unpack_from("<H", uh, 0x1C)[0]
    upd = bytearray(uh + u[0x40:0x200])
    for i in range(0x200, 0x600, 512):
        upd += lfsr(u[i:i + 512], LVMG ^ (0x10001 * bcrc) ^ (i >> 9))
    for i in range(0x600, upd_len, 512):
        upd += lfsr(u[i:i + 512], XUPD ^ ((i - 0x600) >> 9))
    assert crc16(upd[0x200:0x600]) == bcrc, "boot stub CRC"
    assert crc16(upd[0x600:]) == struct.unpack_from("<H", upd, 0x4C)[0], "updater CRC"
    # the app is a Bluetrum flash image: 0x40 header, then two 16-byte region entries
    magic, chip, load, entry, boff, bsz, acrc = struct.unpack_from("<4s8sIIIIH", app, 0)
    regions = [struct.unpack_from("<IIIH", app, 0x40 + 16 * i) for i in range(2)]
    return dict(model=f"{cid:04X}{did:04X}", version=ver.rstrip(b"\0\xff").decode(), size=len(img), crc32=crc,
                chip=chip[:4].decode(), key=key, lzma=(lc, lp, pb, dict_size), packed=app_len,
                boot=(load, entry, boff, bsz, acrc), regions=regions, app=app, updater=bytes(upd))


def report(path: str, out_dir: str | None = None) -> None:
    img = open(path, "rb").read()
    r = unpack(img)
    print(f"{os.path.basename(path)}: sha256 {hashlib.sha256(img).hexdigest()}")
    print(f"  model {r['model']} version {r['version']} size {r['size']} crc32 {r['crc32']:08x} (ok) chip {r['chip']}")
    print(f"  app: {r['packed']} bytes LZMA lc/lp/pb/dict {r['lzma']} key {r['key']:08x} -> {len(r['app'])} bytes,"
          f" sha256 {hashlib.sha256(r['app']).hexdigest()[:16]}")
    load, entry, boff, bsz, bcrc = r["boot"]
    print(f"  boot code: file {boff:#x}+{bsz:#x} -> RAM {load:#x}, entry {entry:#x}, crc16 {bcrc:04x}"
          f" ({'ok' if crc16(r['app'][boff:boff + bsz]) == bcrc else 'BAD'})")
    for i, (off, size, _, crc) in enumerate(r["regions"]):
        print(f"  region {i}: file {off:#x} size {size:#x} crc16 {crc:04x}")
    print(f"  updater: {len(r['updater'])} bytes, sha256 {hashlib.sha256(r['updater']).hexdigest()[:16]}")
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        open(os.path.join(out_dir, "app.bin"), "wb").write(r["app"])
        open(os.path.join(out_dir, "updater.bin"), "wb").write(r["updater"])


if __name__ == "__main__":
    assert crc16(b"123456789") == 0x29B1 and lfsr(lfsr(b"abc", 7), 7) == b"abc"
    if len(sys.argv) > 1:
        report(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        for f in sorted(glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), "firmware", "003A*.bin"))):
            report(f)
