# Changelog

## 1.1.5

Small tidy-up release.

- The **[Crackers]** tag in chat is now **bold orange** instead of magenta.
- Structure names shown in the mod can now be renamed without editing any code —
  call a Swamp Hut a Witch Hut if you prefer. See `MESSAGES.md`.
- Removed 15 leftover text entries for features that no longer exist, so the
  language file only lists things the mod actually says.
- Added `MESSAGES.md`, a guide to changing any text or colour in the mod.

## 1.1.4

This release is mostly an apology. The mod was blaming your structures for
bugs of its own.

### Trial chambers were being found and then thrown away
Every trial chamber the scanner detected was silently discarded before it could
be stored, because of a crash inside the mod that nothing was reporting. They
were never "not detected" — they were detected and dropped, every time. Fixed,
and the class of crash that hid it is now always logged.

### "No seed matches your data" when a seed did match
The final safety check compared candidate seeds against structures the search
had never actually used. Any structure the solver could not model became an
invisible extra condition that rejected every correct answer — and that was
reported to you as your data being contradictory. It now checks against exactly
what it searched, and if its own two halves ever disagree it says so instead of
blaming your world.

### It was accusing innocent structures
When a search failed, Crackers picked a structure and told you it had been
griefed. It worked that out by dropping each structure in turn and seeing if the
rest agreed — but below about seven structures, dropping any one leaves too
little behind to disagree with anything, so *every* structure looked guilty and
whichever came out alone got the blame. Measured: at five structures it named
the right one **0 times out of 10**.

It now refuses to name anything until it has enough data to be sure, and says so
plainly. Measured after the fix: no false accusations at any amount of data, and
8–10 out of 10 correct once there are seven or more structures.

### "Your data is bad" then "all your structures agree"
Both could be true at once and neither was explained. Contradictory data and
*not enough* data are now clearly different messages.

### The coordinates it gave were not a location
When it flagged a structure it printed "block X, Z" — which was the **corner of
the chunk**, not the structure. Teleporting there landed you on nothing. All the
solver ever knows is which chunk a structure starts in, so it now says the chunk
and its block range, and says which it is.

### Trial chambers were never being collected
The trial chamber scanner had a broken line that could throw on every chunk, and
finder errors were only logged with debug enabled — so it silently collected
nothing and looked identical to "there aren't any nearby". The broken check is
gone (it never did anything even when it worked), and finder failures are now
always reported in the log.

### Honest numbers about how many structures you need
The mod said about five. That was wrong. Measured over random worlds, the number
of possible seeds still matching:

| Structures | Seeds still possible |
|---|---|
| 5 | ~6,800 |
| 6 | ~440 |
| 7 | ~86 |
| 8 | ~60 |
| 10 | ~8 |

The requirement is now about **six** to start, and the mod tells you that more is
better instead of implying five will do.

There is also a floor: structures alone never get you to one seed. A handful of
different seeds generate *identical* structures everywhere, so the last step
always comes from the server's hashed seed, or from dungeons, ores and biomes.
If a server hides its hashed seed, you need those — the mod now says so instead
of just waiting.

### Verified
The mod's model of 26.2 world generation was checked directly against the game's
own worldgen data. Every structure salt, spacing and separation matches.

## 1.1.1

Fixes for everything reported against 1.1.0.

- **"Reset data" now asks first**, and says what it is about to erase.
- **The screen scrolls.** Long advice used to run underneath the buttons with no
  way to reach it. The body is now scrollable with a scrollbar when it overflows.
- **Resetting no longer looks like it broke the mod.** Nothing re-sends chunks
  the game already has, so after a reset nothing was scanned until you walked
  somewhere new. Resetting — and turning collecting back on — now re-scans
  everything already loaded around you.
- **The mod no longer contradicts itself.** It could say "no seed matches your
  data" and then "all your structures agree with each other" in the same breath.
  The consistency check was only testing part of the problem; it now runs a real
  search before calling your data consistent.
- **When one structure is the problem, it is now dropped for you.** Previously
  it named the culprit and left it in place, so your only option was to reset —
  which immediately re-collected the same bad structure. It now stops using it
  and tries again, and remembers to keep ignoring it.
- **The screen shows the specific diagnosis** instead of generic advice while
  chat showed the real reason.
- **`/crackers` is a proper alias.** `/crackers check`, `/crackers status` and
  the rest now work; before, the alias silently ignored everything after it.
- `/cracker check` no longer wastes time testing dungeons, which the search does
  not use. On a world with thirty of them the check was thirty times slower than
  it needed to be.
- Renamed the second bar from "Confirmation data" to "Total data", since position
  data counts towards it too and showing the same number twice was confusing.

## 1.1.0

The short version: it no longer crashes your game on exit, it tells you what to
do instead of leaving you guessing, and the search is about five times faster.

### Fixed the crash on closing the game
Crackers left background threads running after you quit, so Minecraft would hang
on shutdown until it force-crashed itself. If you ever saw a crash report right
after closing the game, this was it. Fixed.

### It now tells you what to do next
The screen used to show a "lifting bits: 0 / 40" bar with no explanation of what
filled it. If you had eleven ocean monuments you had eleven structures and zero
progress, and nothing said so.

Now the screen works out what you are actually missing and says it in plain
words — how many more structures to find, which ones count, and when you should
just go to the End instead.

### New: `/cracker check`
Found loads of structures and it still will not crack? Run this.

Almost every failed crack is one structure that has been looted or rebuilt since
the world generated, and one bad structure makes the whole thing unsolvable.
The check works out which one it is and names it, usually exactly. It will also
tell you when the problem is the server rather than your data.

### It now tells you how much to trust the answer
Before, a seed proven correct and a seed that was only a good guess looked
identical. Now it says which — either *confirmed against the server's hashed
seed*, or *structure data only*.

### Detects servers that fight back
If a server hides or fakes its hashed seed, or shuffles where structures
generate, Crackers now recognises it and says so instead of silently failing.

### About five times faster
The seed search was rewritten. Once you have enough data it now finishes in
well under a second instead of the "few minutes" the old version warned about.
Chunk scanning is also dramatically cheaper, so exploring should feel smoother.

### Screen redesign
New layout, clearer labels, and it no longer draws text over its own buttons
when you have found a lot of structures.

Renamed a few confusing things while I was there:
- "Lifting bits" is now **Position data** — the bar that starts the search
- "Structure bits" is now **Confirmation data** — the bar that proves which seed
- "Clear Data" is now **Reset data**

### Smaller things
- Trial chambers now count towards Position data. They always should have.
- Type `/crackers` as well as `/cracker`, and a message on joining a server
  tells you the command exists.
- `/cracker status` gives a full readable summary in chat.
- Emerald ore collection is on by default now that it is cheap to scan for.
- End city and buried treasure scanning had a broken height filter that made
  them scan the entire world column. Fixed.
- Shipwreck scanning checked the same chunk twice and missed a neighbouring one.
- Biomes that did not exist when the original code was written (dripstone caves,
  lush caves, deep dark, cherry grove and others) were being fed to the search as
  bad data. Fixed.
- A saved seed could be recalled for the wrong world on servers that hide their
  hashed seed. It now re-checks before trusting a saved seed.
- Various crashes and freezes from background threads reading the world while it
  was changing.
