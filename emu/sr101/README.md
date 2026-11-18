# SR101 emulator (sibling image `003A0029` V142)

`sr101emu.py` runs the unpacked app image's command handlers in unicorn (RV32IMC). No boot, no scheduler, no radio; nothing is sent to a ring. Findings: `docs/SR101-FIRMWARE.md`, tagged **[emu]**. The set looks like PULP RI5CY's extension (post-increment and indexed access, `p.exths`, `p.min`, `p.bclr`); that is a recollection, not checked against its manual.
```bash
python emu/sr101/sr101emu.py 0b                                    # one frame in, the reply frames out
python -m pytest -q -p no:faulthandler emu/sr101/test_sr101emu.py
```
- **Real code:** flash at `0x10000000`; the RAM code (flash `0x10000200` + 0x5C00 → `0x10C00`, as the reset code copies it); queue init `0x10007F22`, receive path `0x1000A81E`, dispatcher `0x10008144`, the handlers, the ADC read and the temperature maths.
- **Stand-ins:** ROM entries behind `tp = 0x84800`: the 13 + 13 register save / restore stubs, memset `tp-0x7C0`, memcpy `tp-0x7BC`, printf `tp-0x67C` (text kept in `log`), clock `tp-0x374` (`clock`). Any other entry raises with its offset. Replies are taken at the five senders (`0x1000801E`, `0x1000806A`, `0x10007FD4`, `0x10007F90`, `0x10008044`), which do not run. `stub(addr)` skips a function and records its `a0`.
- **Registers off `x0`:** a read returns `regs[addr]` (else `reg_default`); every access is in `io`.
- **Missing:** boot, task loop, timer tick, interrupts, data flash, I2C. `37 01` waits on the tick, `39` is answered by the task loop.

| Vendor instruction | Encoding | Meaning | Read off |
|---|---|---|---|
| load, post-increment | opcode `0x0B`, I-type, funct3 as `lb`…`lhu` | `rd = mem[rs1]; rs1 += imm` | `0x10007F56` (`lbu` run; other sizes by the same rule) |
| store, post-increment | opcode `0x2B`, S-type, funct3 as `sb`…`sw` | `mem[rs1] = rs2; rs1 += imm` | `0x10007F5C` (`sb` run) |
| store, register index | opcode `0x23`, funct3 4 / 5 / 6 = byte / half / word, funct7 0, index register in the rd field | `mem[rs1 + r] = rs2` | RAM memcpy `0x12460` (byte run) |
| extend 16 bits | opcode `0x33`, funct7 `0x08`, rs2 0, funct3 4 / 5 | sign- / zero-extend the low 16 bits | `0x1000B70A`, `0x1000B6E6` |
| minimum | opcode `0x33`, funct7 `0x02`, funct3 4 | `rd = min(rs1, rs2)`, signed | `0x1000B79C` |
| field clear / set | opcode `0x33`, funct7 `11wwwww`, funct3 3 / 4, rs2 field = bit position | clear / set `w` bits from that position | RAM `0x1627C`; width from `0x10007A30`…`0x10007A5C` (bits 20, 19, 18 in turn) |
