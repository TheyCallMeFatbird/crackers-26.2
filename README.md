# Crackers

A client-side **seed cracking** mod for **Minecraft 26.2 (Java Edition, "Chaos Cubed")**, built on Fabric.

Crackers finds the world seed of the world you are playing on — both **singleplayer worlds** and **multiplayer servers** — and hands it to you through a clean in-game GUI opened with `/cracker`.

---

## What it does

| World type | How the seed is found | Speed |
|---|---|---|
| Singleplayer / LAN host | Read directly from your own integrated server | Instant, 100% reliable |
| Multiplayer server | Reverse-engineered from world generation (structures, dungeons, end pillars) + the server's hashed seed | Under a second once enough data is gathered |

### Singleplayer / LAN
When you host the world (singleplayer, or "Open to LAN"), the real seed lives on the integrated server running inside your own client. Crackers just reads it and shows it to you immediately — no work required.

### Multiplayer
Servers never send you the raw seed, only a **hashed** seed that cannot be reversed on its own. Crackers does what the seed-cracking community has proven works:

1. As you explore, finders scan loaded chunks for naturally generated features (dungeons/spawners, desert temples, buried treasure, witch huts, igloos, monuments, outposts, shipwrecks, trial chambers, end cities, end pillars).
2. Each feature's exact position leaks bits of the world's **structure seed** (48 bits). Positions are fed to the solver, which recovers those 48 bits.
3. The 48-bit structure seed is then combined with the server's **hashed seed** to recover the full 64-bit **world seed**.
4. Biome data is used as a fallback when a hashed seed isn't usable.

You don't have to trigger anything — collection and cracking run automatically in the background while `Cracking` is ON.

---

## Using it

1. Install [Fabric Loader](https://fabricmc.net/use/) (0.19.3+) for Minecraft 26.2.
2. Drop `crackers-1.1.5-26.2.jar` into your `mods` folder. **No other dependencies are needed** — Fabric API modules and all seed-cracking libraries are bundled inside the jar.
3. Launch the game and join a world.
4. Type **`/cracker`** to open the GUI.

### The screen
- **Two bars.** *Position data* is the gate that starts the search — only desert
  and jungle temples, igloos, witch huts, shipwrecks and trial chambers fill it.
  *Confirmation data* is everything else, and decides which candidate is right.
- **What to do next.** The screen works out what you are actually missing and
  says so in plain words: how many more position structures to find, which ones
  count, and when to just go to the End instead.
- **Footer.** What you have found so far, and whether the server gave a usable
  hashed seed.
- **When solved.** The seed, plus whether it was *confirmed against the server's
  hashed seed* or rests on structure data alone.

The old screen showed "lifting bits: 0/40" next to a healthy structure count and
left you to work out that eleven ocean monuments contribute nothing to it. That
is now a sentence rather than a puzzle.

### Commands
- `/cracker` (or `/crackers`) — open the screen
- `/cracker status` — print progress and what to do next
- `/cracker check` — **check your collected structures for conflicts** (see below)
- `/cracker on` / `/cracker off` — start/stop collecting
- `/cracker clear` — wipe everything collected here, including the saved file
- `/cracker debug` — toggle verbose logging

### "It found structures but no seed"
Run **`/cracker check`**. Almost every failed crack is caused by one structure
that has been looted, moved or rebuilt since the world generated, and that one
bad position makes the whole data set unsolvable.

The check sieves your data once with each structure removed in turn. Because the
sieve is a *necessary* condition, an empty result is proof that no seed can
produce all of your structures — and whichever structure has to be dropped to
make the data solvable again is the culprit. In testing it names the exact
offender in 8 cases out of 10 and narrows it to two or three in the rest.

It also distinguishes the case where an entire structure *type* disagrees with
the others, which is what a server-side placement randomiser looks like.

### Tips for multiplayer
- Only *untouched, naturally generated* structures count — looted or griefed ones feed the solver bad data.
- The **lifting bar** is the real gate: desert temples, jungle temples, igloos, witch huts, shipwrecks and trial chambers fill it (~9 bits each, so ~5 total). Boating along oceans for shipwrecks is usually fastest.
- Dungeons, emerald ore and other decorators show up as data points but add **0 bits** toward the structure seed on modern versions (their placement uses the Xoroshiro RNG) — they still help confirm the final world seed, and they are the fallback when a server hides its hashed seed.
- **Shortcut**: if you can reach the End, just seeing the obsidian pillar layout replaces the whole lifting requirement.
- Once both gates are open the solve finishes in well under a second on a typical machine, and prints the seed to chat.

### Progress persistence
Collected structures are **auto-saved per server** (`config/SeedCrackerX saved structures/`) as you play and **auto-restored when you rejoin**, so disconnects, crashes and restarts don't lose progress. The hashed seed is re-sent by the server on every join. `Clear Data` wipes both memory and the saved file.

---

## How the solver works

The multiplayer path is the interesting part. A structure at a known chunk fixes two `nextInt(bound)` results of a `java.util.Random` seeded with `baseRegionSeed + structureSeed`, where `baseRegionSeed` is known. Every structure is therefore a constraint on one unknown 48-bit number.

**Stage 1 — adaptive-radix congruence sieve.** `nextInt(bound)` returns `bits % bound`, where `bits` is bits 17..47 of the RNG state. When `2^t` divides `bound`, the bottom `t` bits of the result *are* bits 17..17+t-1 of the state — and those depend only on the bottom `17+t` bits of the seed. Enumerating the bottom `17+T` bits therefore tests every structure at once for a few million cheap operations. `T` is chosen per data set from the deepest any collected structure supports, and each structure contributes at its own depth; a temple, igloo or witch hut reaches `T = 3`.

**Stage 2 — incremental upper scan.** With the bottom `17+T` bits fixed, consecutive candidates differ by exactly `2^(17+T)` in the seed. The LCG is affine, so consecutive RNG states differ by a *constant*. Iterating in the driver structure's own xor coordinates reduces the inner loop to two additions, a shift, a multiply-high and a compare — no division, no 64-bit multiply. Only the single structure with the most selectivity remaining after the sieve drives that loop.

**Stage 3 — verification.** Every surviving seed is re-checked with the seedfinding library itself before it is accepted, and a startup self-test round-trips every structure type through both implementations. The fast path can therefore only ever cost speed, never correctness.

Measured against the implementation it replaces, on the same data and the same machine: **2.7x** on the inner loop, **2x** on the search space, **5.4x** per candidate overall. End to end a typical five-structure data set now solves in about **260 ms**.

---

## Building from source

Requires JDK 25 (Minecraft 26.2 needs Java 25+).

```bash
./gradlew build
```

The finished mod jar is written to `build/libs/crackers-1.1.5-26.2.jar`.

To test in a development client:

```bash
./gradlew runClient
```

### Toolchain (Minecraft 26.2)
- Fabric Loom `1.17`, Gradle `9.5.1`
- Fabric Loader `0.19.3`, Fabric API `0.155.2+26.2`
- Mojang official mappings (26.x is unobfuscated — no Yarn)
- Java 25, Mixin compatibility level `JAVA_25`

---

## Credits & license

The core cracking engine (finders, TimeMachine, LatticG/seedfinding integration) is based on
**[SeedcrackerX](https://github.com/19MisterX98/SeedcrackerX)** by KaptainWutax and 19MisterX98,
which is the proven, battle-tested seed cracker for modern Minecraft. Crackers adapts that engine
for a self-contained, GUI-first experience: rendering/x-ray, the online seed database, and the
cloth-config screen were removed, a direct singleplayer seed reader was added, a custom
`/cracker` GUI replaces the old config screen, and the structure-seed solver was rewritten
(see *How the solver works* above).

Distributed under the MIT license (see `LICENSE`).
