# Parked feature ideas (record only)

Status: not planned, not in the ledger (user, 2026-11-10: "we will do them later"). Each needs its own plan before any
code. Research is from web searches on 2026-11-10; "none found" means those searches found none, not that none exists.
Code facts below come from the ledger and `docs/PLAN8.md`, not from reading the code again.

## I'm OK: a check-in that needs no tap

- **What:** when the ring shows no heartbeat and the phone no screen-on by the usual wake time plus a margin, the app
  first asks the wearer ("Tap if you're fine"), then messages one trusted person. The same thing covers a parent living
  alone: a $20 ring on their finger, their phone running the app.
- **Why:** the manual version ("Are You Dead?" / Demumu: tap daily, an email goes to a contact after two missed days)
  topped China's paid App Store chart in January 2026 ([Sixth Tone](https://www.sixthtone.com/news/1018076),
  [RNZ](https://www.rnz.co.nz/news/world/584056/are-you-dead-chinese-app-for-single-living-goes-viral)). A daily tap
  is the homework this app avoids; the ring already knows the wearer woke up.
- **Prior art found:** dedicated senior wearables that learn a routine and alert family
  ([Carely](https://www.digitaltrends.com/wearables/carelys-new-wearable-watches-over-aging-parents-so-you-dont-have-to/)),
  alert pendants, fall detection on watches. No ring or tracker app with a passive check-in.
- **What it needs here:**
  - A timer in the backend. Everything today runs on a request (ingest, startup backfill, `PATCH /settings`); silence
    cannot trigger anything, so this needs a periodic check (a task started in `lifespan`).
  - One outbound channel, because notifications are only pulled by the phone after a ring sync
    (`/notifications/pending`): stdlib `smtplib`, or one `httpx` POST to ntfy / Telegram. Address and credentials in
    `infra/.env`.
  - Inputs that exist: the newest `biometric_hr_bpm > 0` sample (worn), phone screen events (only when "Phone as sleep
    sensor" is on), the usual wake time from the stored `inputs.bed_end`.
  - A second person = a second backend container with its own `circa.db` (labels are dropped, so one database cannot
    hold two people). No multi-user change.
- **Risks / open questions:**
  - A dead ring battery, a phone that is off, or no route from the phone to the homelab all look the same as trouble.
    The ladder (ask the wearer first, wait, then one message worded like "no data since 07:10, may be nothing") is the
    feature; without it the contact learns to ignore the alerts.
  - The ask-first step fails when the phone is the thing that is off; the backend must still escalate after the wait.
  - Margin and wait are unknown: pick them from the real spread of morning sync times in circa.db, not by guess.
  - Copy never diagnoses and never says "emergency".

## Who woke whom: two rings in one bed

- **What:** two people who share a bed each wear a ring. The app lines up both nights and reports who wakes first in
  shared wake-ups ("he woke first in 7 of 9"), how each sleeps on nights apart, and, for new parents, both sleep debts
  side by side to show whose turn the night is.
- **Why:** no product found that does it; roundups of couples' trackers list per-person tracking only, and research on
  couples' sleep with wrist monitors is still recruiting
  ([Columbia DREAM-COUPLES](https://recruit.cumc.columbia.edu/studyinfopage/3086)). Practical only because a second
  ring costs about $20.
- **Prior art found:** none for attributing one partner's wake-ups to the other.
- **What it needs here:**
  - A second ring, a second phone with the app, a second backend container with its own `circa.db` (as above).
  - One read path across the two: an endpoint on one backend that fetches the other's nights (`httpx`, a second token),
    or one process that opens both databases read-only.
  - Inputs that exist per person: sleep stage points with timestamps (awake = wire stage 4, ledger BG1) and sleep debt.
    Both rings get their clock from their phone on every sync (ledger T1), so the two timelines compare to the minute.
  - Maths (pure, `insights.py`): for each of A's awake starts, did one of B's fall in the N minutes before; compare the
    count with the same test on mismatched nights (A's Monday against B's Tuesday), so chance overlaps are not reported
    as cause.
  - A "slept apart" journal kind for together-versus-apart, using the existing with/without maths.
- **Risks / open questions:**
  - Gate first: how fine is this ring's awake detection? Two real nights existed on 2026-11-10 (24 and 37 awake
    minutes). Count the awake segments per night and their lengths before planning; one or two coarse blocks a night
    cannot carry the attribution.
  - Needs the partner's consent and their data on the same homelab; each person sees only what both agreed to share.
  - "Whose turn" shows both debts side by side, never a verdict about a person.
  - The largest lift of the 2026-11-10 ideas.
