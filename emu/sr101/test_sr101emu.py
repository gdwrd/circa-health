"""The SR101 sibling image 003A0029 V142 under sr101emu, one test per behaviour. Nothing is sent to a ring."""
import os
import struct
import sys
import zlib

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sr101emu as S  # noqa: E402

REAL_CAPS = bytes.fromhex("20 62 EC 07 38 09 0A BE BB 02 00 1B 00")  # our ring, docs/SR101.md section 9
REAL_38 = bytes.fromhex("38 1C 0C 20 0E")                            # our ring, docs/SR101.md section 10


def frame(text: str) -> bytes:
    return bytes.fromhex(text).ljust(20, b"\0")


def body(surface: int) -> int:
    return (surface - 2800) * 125 // 900 + 3575 if 2800 <= surface <= 3699 else surface


def temps(reply: bytes) -> tuple[int, int]:
    assert reply[0] == 0x38 and reply[5:] == bytes(15)
    return struct.unpack_from("<HH", reply, 1)


def test_ram_code_and_queues_come_from_the_firmware() -> None:
    e = S.Emu()
    assert e.uc.mem_read(0x10C00, 0x5C00) == e.uc.mem_read(S.FLASH + 0x200, 0x5C00)
    # the firmware's own init: receive queue 8 x 20 bytes, reply queue 32 x 20 bytes
    assert struct.unpack("<5I", e.uc.mem_read(0x18680, 20)) == (0, 0, 8, 20, 0x185E0)
    assert struct.unpack("<5I", e.uc.mem_read(0x185CC, 20)) == (0, 0, 32, 20, 0x1834C)


def test_receive_path_queues_the_frame_and_the_dispatcher_takes_it() -> None:
    e = S.Emu()
    e.uc.mem_write(0x1F000, frame("0b 01 02"))
    e.call(S.RECEIVE, 0x1F000)
    assert (e.rd(0x18680), e.rd(0x18684)) == (1, 0) and e.uc.mem_read(0x185E0, 20) == frame("0b 01 02")
    e.call(S.DISPATCH)
    assert (e.rd(0x18680), e.rd(0x18684)) == (1, 1)


@pytest.mark.parametrize("pct,state", [(77, 1), (100, 0), (3, 2)])
def test_battery_reply_carries_the_values_in_ram(pct: int, state: int) -> None:
    e = S.Emu()
    e.wr(S.BATTERY_PCT, pct, 1)
    e.wr(S.BATTERY_STATE, state, 1)
    assert e.send(b"\x0b") == [bytes([0x0B, pct, state]).ljust(20, b"\0")]


def test_info_reply_version_model_mac_uptime_and_crc() -> None:
    e = S.Emu()
    mac = bytes.fromhex("a1b2c3d4e5f6")
    e.uc.mem_write(S.MAC, mac)
    e.clock = 0x123450
    (r,) = e.send(b"\x0c")
    ver, cid, did = struct.unpack_from("<H", r, 1)[0], struct.unpack_from("<H", r, 9)[0], struct.unpack_from("<H", r, 11)[0]
    assert (r[0], ver, r[3:9], cid, did) == (0x0C, 142, mac, 0x3A, 0x29)
    assert int.from_bytes(r[13:16], "little") == 0x123450 * 5 >> 4       # uptime ms: why two replies differ
    assert struct.unpack_from("<I", r, 16)[0] == zlib.crc32(r[1:16], 0x4A59AF55)


def test_capability_bitmap_against_the_real_ring() -> None:
    (r,) = S.Emu().send(b"\x20")
    assert r == frame("20 6a ec 0f 38 09 0a bc bb 62 00 1b")
    bits = lambda f: {k for k in range(96) if f[1 + k // 8] >> k % 8 & 1}  # noqa: E731
    assert bits(r) - bits(REAL_CAPS) == {3, 19, 69, 70} and bits(REAL_CAPS) - bits(r) == {49}
    assert {6, 10, 29, 63, 81, 83, 84} <= bits(r) and 86 not in bits(r)   # the features the app reads: same on both


@pytest.mark.parametrize("clock,surface", [(0, 3100), (4, 3200), (8, 3300), (12, 3100)])
def test_temperature_under_30_is_replaced_by_31_32_33(clock: int, surface: int) -> None:
    e = S.Emu()
    e.clock = clock                                                      # uptime ms = clock * 5 / 16; placeholder = ms % 3
    r = e.temperature(880)
    assert e.rd(S.TEMP_RAW, 2) == 271                                    # the sensor said 27.1 C
    assert temps(r) == (surface, {3100: 3616, 3200: 3630, 3300: 3644}[surface])
    if clock == 0:
        assert r[:5] == REAL_38


def test_temperature_above_30_passes_with_body_from_the_formula() -> None:
    e = S.Emu()
    e.clock = 4
    table = struct.unpack("<33H", e.uc.mem_read(S.TEMP_TABLE, 66))
    assert (table[12], table[13]) == (889, 902)                          # 30.0 C and 35.0 C
    raw = (890 - 889) * 50 // (902 - 889) + 12 * 50 - 300
    assert temps(e.temperature(890)) == (raw * 10, body(raw * 10)) == (3030, 3606)
    assert temps(e.temperature(900)) == (3420, 3661)


def test_temperature_adjustments_and_clamp() -> None:
    e = S.Emu()
    e.temperature(800)                                                   # -1.4 C by the table
    assert e.rd(S.TEMP_RAW, 2) == 36                                     # 20.0 or less: +5.0
    e.temperature(868)
    assert e.rd(S.TEMP_RAW, 2) == 218                                    # 20.1 to 37.9: unchanged
    assert temps(e.temperature(920)) == (3800, 3800)                     # 43.0 by the table, above 37.9: -5.0; body = surface
    assert temps(e.temperature(1000)) == (4200, 4200)                    # 65.0: surface is capped at 42.00


def test_no_adc_result_leaves_the_initial_31() -> None:
    e = S.Emu()
    e.clock = 4
    assert e.rd(S.TEMP_RAW, 2) == 310                                    # initial value in the RAM image
    assert temps(e.temperature(700)) == (3100, 3616)                     # below the table: nothing stored
    e.regs[S.ADC_BUSY] = 0x8000                                          # channel 16 waits on flag 15
    e.io.clear()
    assert temps(e.temperature(900)) == (3100, 3616)
    assert ("r", S.ADC_BUSY, 0x8000) in e.io and all(a != S.ADC_TEMP for _, a, _ in e.io)


@pytest.mark.parametrize("key,byte9,mode", [
    ("fe dc ba 98 76 54 32 10", 0, [0]), ("fe dc ba 98 76 54 32 10", 1, [1]),
    ("12 34 12 34 12 34 12 34", 0, [0]), ("12 34 12 34 12 34 12 34", 1, [0]),
    ("12 12 12 12 12 12 12 12", 0, []), ("12 12 12 12 12 12 12 12", 1, [2]),
    ("12 34 56 78 fe dc ba 98", 1, None), ("12 34 56 78 9a bc de f0", 1, None),  # the app's OTA and restart keys
])
def test_device_mode_keys_select_the_action(key: str, byte9: int, mode: list[int] | None) -> None:
    e = S.Emu()
    prepared, action = e.stub(S.MODE_PREPARE), e.stub(S.MODE_ACTION)     # never run: they reach flash and reset
    f = frame("0e " + key + f" {byte9:02x}")
    assert e.send(f) == [f]                                              # every key is echoed
    assert (len(prepared), action) == ((1, mode) if mode is not None else (0, []))


def test_unidentified_rom_entry_and_vendor_instruction_raise() -> None:
    e = S.Emu()
    with pytest.raises(NotImplementedError, match=r"ROM entry tp-0x5a0"):
        e.call(S.TP - 0x5A0)
    e.wr(0x1F100, 0x10056533)
    with pytest.raises(NotImplementedError, match=r"vendor instruction 10056533 at 0x1f100"):
        e.call(0x1F100)
