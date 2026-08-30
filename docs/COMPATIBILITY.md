# Compatibility

## Global fast-forward audio

The Phase 2 SoundTouch path is intended to be game-agnostic.

| Mode | Audio behavior |
| --- | --- |
| Normal speed | Historical melonDS path |
| ×2 | SoundTouch tempo 2, pitch 1 |
| ×3 | SoundTouch tempo 3, pitch 1 |
| ×4 | SoundTouch tempo 4, pitch 1 |

For games without a specific semantic audio profile, all mixed audio follows the selected fast-forward speed while SoundTouch preserves pitch.

## Pokémon White FR profile

The semantic BGM-preserving mode is currently limited to one exact Pokémon White French revision.

### Header / ROM fingerprint

```text
Title:        POKEMON W
Game code:    IRAF
Revision:     0
ROM size:     268435456 bytes
ROM SHA-256:  0A7D6E87D9878C2FB903BCCA01ECC8F9A186D0EED14DDBC87912AE126FAA0BDB
```

### ARM7 fingerprint

```text
ARM7 SHA-1:   364259352FFFDCD15B75C2D9E997D2ED0D877190
```

### SDAT fingerprint

```text
Offset:       0x0033E800
Size:         51533312 bytes
SHA-256:      062073D5E29BC953979D75F1BA4F5F26D53CB3FD41130660FE1BCA6041189E13
```

The mode is disabled if any fingerprint or structural validation fails.

## Current behavior matrix

| Game/profile | ×2 | ×3 | ×4 |
| --- | --- | --- | --- |
| Exact Pokémon White FR profile | Game ×2, BGM ≈×1, SFX/ME/cry ≈×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |
| Other Pokémon White revisions/regions | Global SoundTouch ×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |
| Pokémon Black | Global SoundTouch ×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |
| Black 2 / White 2 | Global SoundTouch ×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |
| Gen IV Pokémon games | Global SoundTouch ×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |
| Other DS games | Global SoundTouch ×2 | Global SoundTouch ×3 | Global SoundTouch ×4 |

"Global SoundTouch" means pitch is preserved, but BGM is not semantically held at ×1.

## Validated Pokémon White sequences/events

The current validation set includes:

- field BGM: SSEQ 1018 (`SEQ_BGM_T_01`)
- battle BGM: SSEQ 1133 (`SEQ_BGM_VS_RIVAL`)
- BGM: SSEQ 1161
- persistent victory music: SSEQ 1149 (`SEQ_BGM_WIN2`), intentionally kept classified as BGM
- ME: SSEQ 1303 (`SEQ_ME_KEYITEM`)
- attack SFX including SFX 1380
- dynamic UNKNOWN/cry events

### Runtime class rules

```text
INFO player 1..5  → SFX
seqId 1300..1331  → ME
INFO player 0/6   → BGM
otherwise         → UNKNOWN
```

The sequence name alone is not used as the classification rule.

## Unsupported / future semantic profiles

No semantic BGM ×1 guarantee is currently made for:

- Pokémon Black;
- Black 2 / White 2;
- Diamond / Pearl / Platinum;
- HeartGold / SoulSilver;
- other regions or revisions of Pokémon White.

These are candidates for future profiling. New support must be based on verified fingerprints and runtime mapping rather than guessed address reuse.

## ROM data policy

The repository stores only metadata and cryptographic fingerprints required for safe recognition.

No commercial ROM, SDAT dump, ARM binary, save file or other copyrighted game asset should be committed to this project.
