# Roadmap

## Current baseline

Phase 3 is the first validated semantic audio profile:

```text
Pokémon White FR rev.0
fast-forward ×2
→ game ≈×2
→ BGM ≈×1
→ SFX / ME / cry ≈×2
```

Checkpoint:

```text
thor-pokemon-white-bgm-x1-phase3
```

## Next milestones

### 1. Extend semantic BGM preservation to ×3 / ×4

Generalize the existing time-domain gate while preserving the same architecture:

```text
BGM control cadence = 1 / N
SoundTouch tempo    = N
pitch               = 1
```

Each multiplier must be validated independently. Until then, ×3 / ×4 use the global SoundTouch path only.

### 2. Pokémon Black / White family profiling

Start with the closest sibling profiles:

- Pokémon Black FR
- additional White revisions/regions
- other Black/White regions

Do not reuse addresses merely because two titles are related. Each profile must prove:

- ROM/header fingerprint;
- ARM7 compatibility;
- SDAT fingerprint and player layout;
- hook addresses;
- BGM/ME/SFX classification behavior;
- runtime ownership/reallocation safety.

### 3. Black 2 / White 2

Audit BW2 as a separate profile family. Reuse the common classifier/gate engine where possible, but keep profile-specific fingerprints and mappings isolated.

### 4. Generation IV

Profile:

- Diamond / Pearl
- Platinum
- HeartGold / SoulSilver

The high-level Nitro audio concepts are reusable, but sound-driver addresses, structures and player conventions may differ.

### 5. Replace game-specific naming with profile abstraction

The current core class is intentionally named around the first validated target. Once a second profile is proven, refactor toward a shared architecture such as:

```text
PokemonDsAudioProfile
  ├── BW profile
  ├── BW2 profile
  └── Gen IV profile
```

The common engine should own:

- runtime classification cache;
- SeqPlayer → Track → ExChannel ownership;
- atomic gate state;
- phase handling;
- JIT/interpreter hook dispatch.

Profiles should contain only verified data that genuinely varies by game/revision.

### 6. Automated compatibility matrix

Maintain a developer-facing matrix containing at least:

```text
ROM recognized
ARM7 compatible
SDAT mapped
hooks validated
BGM ×1
SFX ×N
ME ×N
cry/UNKNOWN ×N
JIT ON
status
```

A failed fingerprint or unproven hook must result in `NEEDS PORT`, never an approximate whitelist.

### 7. CI / reproducible builds

Add GitHub Actions for:

- submodule checkout;
- Android debug build;
- unit tests;
- whitespace/diff checks where practical;
- optional release-like native build.

CI must not require ROMs or copyrighted test fixtures.

### 8. Public-release preparation

Before changing the repository from private to public:

- audit the complete Git history for ROMs, savestates, dumps, private paths and credentials;
- verify GPLv3 attribution and source obligations;
- verify SoundTouch LGPL notice, source and relinking requirements;
- document build/reproduction steps;
- ensure no proprietary game assets are embedded in test data or screenshots;
- clearly separate validated compatibility from planned support.

## Non-goals for the immediate next phase

Avoid broad refactors before the current architecture is validated on at least one additional Pokémon title. The current priority is controlled portability and evidence, not speculative generalization.
