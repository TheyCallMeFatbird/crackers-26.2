# Crackers

A client-side **seed cracking** mod for **Minecraft 26.2 (Java Edition, "Chaos Cubed")**, built on Fabric.

Crackers finds the world seed of the world you are playing on — both **singleplayer worlds** and **multiplayer servers** — and hands it to you through a clean in-game GUI opened with `/cracker`.

---

## What it does

| World type | How the seed is found | Speed |
|---|---|---|
| Singleplayer / LAN host | Read directly from your own integrated server | Instant, 100% reliable |
| Multiplayer server | Reverse-engineered from world generation (structures, dungeons, end pillars) + the server's hashed seed | Minutes, once enough data is gathered |

### Singleplayer / LAN
When you host the world (singleplayer, or "Open to LAN"), the real seed lives on the integrated server running inside your own client. Crackers just reads it and shows it to you immediately — no work required.

### Multiplayer
Servers never send you the raw seed, only a **hashed** seed that cannot be reversed on its own. Crackers does what the seed-cracking community has proven works:

1. As you explore, finders scan loaded chunks for naturally generated features (dungeons/spawners, desert temples, buried treasure, witch huts, igloos, monuments, outposts, shipwrecks, trial chambers, end cities, end pillars).
2. Each feature's exact position leaks bits of the world's **structure seed** (48 bits). Positions are fed into a lattice reducer ([LatticG](https://github.com/mjtb49/LattiCG)) and the [seedfinding](https://github.com/seedfinding) libraries to solve those 48 bits.
3. The 48-bit structure seed is then combined with the server's **hashed seed** to recover the full 64-bit **world seed**.
4. Biome data is used as a fallback when a hashed seed isn't usable.

You don't have to trigger anything — collection and cracking run automatically in the background while `Cracking` is ON.

---

## Using it

1. Install [Fabric Loader](https://fabricmc.net/use/) (0.19.3+) for Minecraft 26.2.
2. Drop `crackers-1.0.1-26.1.2.jar` into your `mods` folder. **No other dependencies are needed** — Fabric API modules and all seed-cracking libraries are bundled inside the jar.
3. Launch the game and join a world.
4. Type **`/cracker`** to open the GUI.

### The GUI
- **Progress**: two live bars — *structure bits* (candidate-checking data, needs 32) and *lifting bits* (the gate that starts the solve, needs 40 — or End pillar data instead), plus data-point count and hashed-seed status.
- **Instructions**: context-aware tips (different for singleplayer vs. multiplayer).
- **When solved**: the world seed is shown in green with a **Copy Seed** button.
- Buttons: `Copy Seed`, `Cracking: ON/OFF`, `Clear Data`, `Close`.

### Commands
- `/cracker` — open the GUI
- `/cracker on` / `/cracker off` — enable/disable cracking
- `/cracker clear` — clear all collected data, including the on-disk save (use when data got poisoned by a griefed structure)
- `/cracker bits` — print collected-bit counts to chat

### Tips for multiplayer
- Only *untouched, naturally generated* structures count — looted or griefed ones feed the solver bad data.
- The **lifting bar** is the real gate: only desert temples, jungle temples, igloos, witch huts and shipwrecks fill it (~9 bits each, so ~5 total). Boating along oceans for shipwrecks is usually fastest.
- Dungeons, emerald ore and other decorators show up as data points but add **0 bits** on modern versions (their placement uses the Xoroshiro RNG) — they only help confirm the final seed.
- **Shortcut**: if you can reach the End, just seeing the obsidian pillar layout replaces the whole lifting requirement.
- Once both gates are open, the solve runs in the background for a few minutes and prints the seed to chat.

### Progress persistence
Collected structures are **auto-saved per server** (`config/SeedCrackerX saved structures/`) as you play and **auto-restored when you rejoin**, so disconnects, crashes and restarts don't lose progress. The hashed seed is re-sent by the server on every join. `Clear Data` wipes both memory and the saved file.

---

## Building from source

Requires JDK 25 (Minecraft 26.2 needs Java 25+).

```bash
./gradlew build
```

The finished mod jar is written to `build/libs/crackers-1.0.1-26.1.2.jar`.

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
cloth-config screen were removed, a direct singleplayer seed reader was added, and a custom
`/cracker` GUI replaces the old config screen.

Distributed under the MIT license (see `LICENSE`).
