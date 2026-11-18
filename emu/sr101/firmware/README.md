# SR101 firmware images

No image exists for the ring's own model `003A0061`. These are siblings (same cid `003A`, same SDK) and one format sample.
Untrusted data: read with Python only (`../fot_unpack.py`, `../fw_map.py`), never execute.
Source: `http://download.keeprapid.com/apps/smartband/keepfit/fwrelease/<model>/V<ver>.bin`, fetched 2026-11-18;
each `003A` MD5 matched its `fwupdater/en/<model>/update.json`.

| File | What | SHA-256 |
|---|---|---|
| `003A0029_V142.bin` | sibling "SR08", newest build (2026-06-04), the one analysed | `4107e64fd0843c81f43641020ab5c1c8faeb83a11cafef18609630b1c5b62423` |
| `003A0007_V106.bin` | sibling, oldest build (2025-06-10), has the `3918` PPG string | `7061d858bd273f77976cdfb59f6c2e6289304c46e9f0be173dadd96ea25f9f3c` |
| `00010017_V158.bin` | other family, a real Dialog image (`70 51`, ARM), format contrast only | `5f69ec46a0bfd6de4ad22fa68b5bef3868c3c9660f739f36308e0d30be9d0da8` |

Not stored, same URL shape (`python -I ../ota_url.py --scan` lists them):

| Model / version | SHA-256 |
|---|---|
| 003A0008 V118 | `ff0dcca5e1525c48b9389f3bc2f6f4333aa8492cea50661ff4dc6862addd01ed` |
| 003A0009 V112 | `d400cb28059e491693d6ad4b20652d1233679a5514047b8eed34c5637dd33482` |
| 003A000B V106 | `546b158509bcb358504dc74b2b2f3edfe2658806d02ae021f2d48ca739ae909c` |
| 003A000D V130 | `997db328959adb4ba1db01b7b9c4af324c749ef5fdc5a131451efaa3b3995bcb` |
| 003A0016 V122 | `d01bd4c067ab0e5de91aa19b28d587e7c171343def76a81f0cddd494fe9d8291` |
| 003A001C V136 | `800ddeab234b520df129d60cf924983f52acb71906b33435ea439f635a3d660c` |
| 003A0023 V104 | `c8255e1f79c4fa37fd53347c55ee3f285e8e8c056a6bd64c0f85e5ef466f91de` |
| 003A0024 V128 | `455311f990f23075b25e62bdef7f7e7c8caae928970a04a3ee59dbfe7750df38` |
| 003A0027 V126 | `de6490ff930f807b88d6f6b11c9465d9322b444c233b98a690198ab4c2cadd17` |
| 003A002A V138 | `c3cfb5e117e35779d7f7ff3c9ad4340419a17d7829cf7795f7cab5d972cd2aee` |
| 003A0033 V104 | `e0b265616795ce731a4b221573609235a55ba7e94754fe5ef39d5bd5a7ec8a27` |
