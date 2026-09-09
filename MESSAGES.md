# Editing what Crackers says

Every piece of text the mod shows lives in one of two places: the **language
file** (no rebuild needed) or a **Java file** (rebuild needed). This is a map of
which is which.

---

## 1. The language file — easiest, no rebuild

`src/main/resources/assets/crackers/lang/en_us.json`

A plain list of `"key": "text"` pairs. Edit the text on the right, never the key
on the left. Players can also override these with a resource pack, so changes
here work without recompiling anything.

### Structure names

```json
"crackers.structure.swamp_hut": "Witch Hut",
"crackers.structure.desert_pyramid": "Desert Temple",
```

These are the names in the screen's "3x Desert Temple · 1x Shipwreck" line and
in `/cracker status`. Rename them to whatever you like. If you delete an entry
the mod falls back to tidying up the internal id, so nothing breaks.

### Seed messages

`${SEED}` is replaced by the seed itself, as a clickable copy-to-clipboard
button. Keep it in the string.

```json
"crackers.foundSeed": "World seed is ${SEED}.",
"crackers.savedSeed": "Seed already cracked, ${SEED}.",
```

### Progress messages

`%s` is replaced by a number. Keep the same number of `%s` as the original or
the placeholder just prints unfilled — it will not crash.

```json
"tmachine.startLifting": "Started lifting with %s structures. This takes a few minutes",
"tmachine.reduceSeeds": "Reducing %s structure seeds",
```

---

## 2. Java files — needs `./gradlew build`

These are hardcoded because they are built up from live numbers, or are laid out
to fit the panel exactly.

| What | File | Look for |
|---|---|---|
| **The `[Crackers]` tag** — colour and boldness | `util/Log.java` | `PREFIX` |
| **"What to do next"** panel and its headlines | `cracker/Advisor.java` | `steps.add("...")` |
| **Status lines** (the fallbacks, e.g. "Find a few more structures") | `cracker/storage/DataStorage.java` | `enum Status` |
| **`/cracker check` report text** | `cracker/solver/SolverDiagnostics.java` | `new Report(...)` |
| **Button labels, bar labels, section headers** | `gui/CrackerScreen.java` | `Component.literal("...")` |
| **Command replies** (`/cracker on`, `clear`, `status`) | `command/CrackerCommand.java` | `Log.success` / `Log.problem` |
| **Seed-found wording and the join hint** | `cracker/storage/TimeMachine.java`, `mixin/ClientPacketListenerMixin.java` | `Log.headline` / `Log.action` |

### Line length matters in the panel

Text in `Advisor.java` and `SolverDiagnostics.java` is written as one list entry
per line because the panel does not wrap. Keep lines to roughly **52 characters**
or they run off the right edge. An empty `""` entry is a blank line.

```java
steps.add("These are the only ones that count towards the");
steps.add("position bar: desert and jungle temples, igloos,");
steps.add("");
steps.add("Sailing along an ocean for shipwrecks is fastest.");
```

---

## 3. Changing chat colours

`util/Log.java` holds every chat style in one place:

| Method | Used for | Colour |
|---|---|---|
| `headline` | something just happened | white |
| `detail` | the explanation under a headline | grey, indented |
| `success` | good news | green |
| `problem` | stopped, needs you | yellow |
| `action` | clickable command link | aqua, underlined |
| `debug` | only with `/cracker debug` on | dark grey |

Colours come from `ChatFormatting`: `GOLD`, `AQUA`, `RED`, `GREEN`, `YELLOW`,
`LIGHT_PURPLE`, `WHITE`, `GRAY`, `DARK_GRAY`, `BLUE`, `DARK_AQUA` and so on.
`withStyle` takes several at once, which is how the tag is bold and orange:

```java
.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
```

---

## 4. Screen colours

`gui/CrackerScreen.java`, the block of constants at the top. They are
`0xAARRGGBB` — alpha first, then red, green, blue.

```java
private static final int ACCENT = 0xFF8B5CF6;   // purple highlights
private static final int GOOD   = 0xFF34D399;   // green, target reached
private static final int WARN   = 0xFFFBBF24;   // amber, needs attention
private static final int DANGER = 0xFFF87171;   // red, reset warning
```

---

## 5. Keeping both versions in step

Changes to any of the above apply identically to the `crackers-1.21.1` tree —
none of these files differ between the two except `CrackerScreen.java`, and even
there only the drawing calls differ, not the text. Copy the file across and
check `PORTING.md` if it is one of the few with version-specific differences.
