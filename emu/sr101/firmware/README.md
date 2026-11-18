# SR101 firmware image — not present

No firmware image for this ring (model `003A0061`, fw ver 140) could be obtained by static
means. See `../../docs/SR101-FIRMWARE.md` for the full account. In short:

- The app/SDK OTA URL (`.../fwupdater/{en,zh}/003A0061/update.json`) returns **404** — no
  firmware is published for this model. Reproduce: `python -I ../ota_url.py --probe`.
- No image is bundled in the JRing APK.
- No public mirror was found.

The only remaining acquisition paths need the ring (out of scope for a static task):
a SUOTA/SWD read off the chip, or catching a future OTA if the vendor ever publishes one.

When an image is obtained, drop it here as `003A0061_v140.bin` and record its SHA-256 in the doc.
