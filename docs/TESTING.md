# Testing and validation

## Validation philosophy

The project distinguishes between:

- **global fast-forward correctness**: output pacing, pitch preservation, underflow/overwrite and performance;
- **Pokémon semantic correctness**: BGM remains near ×1 while non-BGM audio follows game speed.

Temporary diagnostics used during development were removed from the final Phase 3 product path.

## Phase 2 global fast-forward

On the AYN Thor target, optimized ×2 validation produced approximately:

```text
1.9995×
1.9998×
2.0000×
```

with post-DSP overwrite at 0.

The global SoundTouch path was also validated at ×3 and ×4 with pitch held near the original frequency.

## Phase 3 Pokémon White ×2

### Game speed

Field BGM scene (1018):

```text
30 s runs: 1.999751 / 1.999510 / 1.999671×
mean:      1.999644×
60 s:      2.000011×
```

Battle BGM scene (1133):

```text
30 s runs: 2.000220 / 1.999466 / 2.000309×
mean:      1.999998×
60 s:      2.000100×
```

### BGM

Validated BGM timing:

```text
1018: ≈0.995× wall-clock
1133: ≈0.995× wall-clock
1149: 0.9975× wall-clock
```

Pitch remained near the original pitch in the matched measurements.

### SFX

Measured SFX speed:

```text
mean:   1.9913×
median: 1.9966×
range:  1.9619–2.0155×
```

SFX 1380 produced a matched pitch result of 0 cents with correlation 0.972.

### ME

SSEQ 1303 (`SEQ_ME_KEYITEM`):

```text
2.0019×
```

Although this sequence uses INFO player 0, runtime classification is `ME`, so it is correctly excluded from the BGM gates.

### Victory music 1149

SSEQ 1149 (`SEQ_BGM_WIN2`) is intentionally retained as BGM:

```text
class: BGM
speed: 0.9975×
pitch: approximately +5 cents in the validation sample
```

The observed behavior was persistent victory music rather than a short ME event.

### Cry / UNKNOWN

Dynamic UNKNOWN events associated with cry/attack behavior measured approximately:

```text
mean: 2.006×
```

They were not gated and inherited no stale BGM ownership.

## ADSR / LFO / fades

### ADSR

Acoustic envelope validation passed with correlation 0.977.

Representative measurements:

```text
attack:  40 ms → 35 ms
decay:   85 ms → 70 ms
release: 85 ms → 70 ms
```

The difference was considered compatible with the 5 ms measurement resolution and spectral isolation method used.

### LFO

Status: **NON MESURABLE**.

The best candidate produced 3.0 Hz at ×1 and 2.5 Hz in Pokémon ×2 mode, but the ×2 coherence was only 0.507. No artificial PASS was declared.

### Fades

Representative fade measurement:

```text
x1:          430 ms
Pokémon ×2:  460 ms
```

Difference: +30 ms / approximately +7%.

Result: PASS, with no stuck note or resume corruption observed.

## Ownership and transition stress

A Phase 3G stress test executed:

```text
100 ON transitions
100 OFF transitions
```

with active BGM.

Results:

- stable process;
- no crash;
- maximum post-DSP overwrite: 0;
- no stale gate phase observed.

Stale BGM ownership validation:

```text
attack scenario: 106 reallocations, 0 stale
ME scenario:      29 reallocations, 0 stale
```

## SoundTouch ratios and DSP cost

Representative ×2 Pokémon runs:

```text
SoundTouch input/output ratio:
30 s ≈ 2.00288
60 s ≈ 2.00145
```

Representative DSP wall-time cost:

```text
~0.60–0.62%
```

Steady-state classifier/gate overhead was not distinguishable from measurement noise in the validation runs.

## Underflow / overwrite

Across the final optimized Phase 3 performance runs:

```text
overwrite: 0
```

A small number of full/partial underflows remained concentrated at startup, without growth over 60-second runs and without audible instability.

## JIT

Functional validation was performed with ARM7 JIT enabled. The final hook design also includes interpreter, AArch64 JIT and x86-64 JIT integration.

## Regression checks

Final Phase 3G checks included:

- Pokémon White ×2 semantic mode;
- Pokémon White ×3/×4 Phase 2 fallback;
- non-whitelisted Pokémon Ranger fallback;
- reset;
- savestate rebuild;
- ROM unload/reload;
- 100 gate transitions;
- three Android ABIs;
- `git diff --check`;
- debug instrumentation string audit.

The final release-like binaries contained none of the temporary `THOR3C` / `THOR3D` markers or the prototype `debug.melonds.pokemon_bgm_x1` property.
