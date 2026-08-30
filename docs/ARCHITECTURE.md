# Architecture

## Scope

This fork adds two related fast-forward audio mechanisms to melonDS Android:

1. a **global pitch-preserving fast-forward processor** for ×2 / ×3 / ×4;
2. a **Pokémon White-specific BGM time-domain gate** that keeps background music near ×1 wall-clock while the game runs at ×2.

The second mechanism is deliberately strict and game-specific. Unsupported games always fall back to the global mechanism.

## Phase 2: global pitch-preserving fast-forward

The Android frontend inserts `FastForwardAudioProcessor` in the audio output path before final Oboe consumption.

Conceptually:

```text
SPU mix
  ↓
melonDS audio output processor hook
  ↓
SoundTouch
  tempo = fast-forward multiplier
  pitch = 1
  rate  = 1
  ↓
post-DSP FIFO
  ↓
Oboe callback
```

SoundTouch 2.4.1 is configured for stereo floating-point processing. The processor accepts the emulated PCM stream and emits a reduced number of frames according to the selected integer tempo while preserving pitch.

The supported DSP tempos are currently ×2, ×3 and ×4. Other modes retain the historical path.

### Performance build

For Android `RelWithDebInfo`, the project promotes the normal `-O2` flags to `-O3` and enables ThinLTO for the core, SoundTouch and Android frontend. This was required to expose the actual performance headroom of the target device.

## Phase 3: Pokémon White BGM time domain

Pitch preservation alone cannot keep only BGM at ×1 while other game audio accelerates. The Pokémon path therefore introduces a second time domain before the final mix.

At ×2:

```text
BGM control time      → gated to 1/2 cadence
SFX / ME / UNKNOWN    → normal guest cadence
                         ↓
                    guest SPU mix
                         ↓
                SoundTouch tempo ×2
                         ↓
BGM ≈ ×1 wall-clock
SFX/ME/UNKNOWN ≈ ×2 wall-clock
```

The implementation does **not** halve SPU sample playback frequency or channel timers. That would alter pitch. Instead, it gates guest-side sequence/control progression only.

## Strict ROM recognition

The Pokémon classifier is enabled only after all of the following match:

- exact ROM size and header metadata;
- exact full-ROM SHA-256;
- exact ARM7 SHA-1;
- exact SDAT location/size and SHA-256;
- successful SDAT cache construction.

Any mismatch leaves the classifier disabled and the emulator uses the normal Phase 2 path.

See [COMPATIBILITY.md](COMPATIBILITY.md) for the current fingerprint.

## Runtime classification

The SDAT cache resolves SSEQ data to sequence ID and INFO player data.

Current class rules are:

```text
INFO player 1..5  → SFX
seqId 1300..1331  → ME
INFO player 0/6   → BGM
otherwise         → UNKNOWN
```

ME is checked before BGM because ME sequences can use INFO player 0.

Ambiguous SSEQ pointer matches resolve conservatively to `UNKNOWN` rather than selecting an arbitrary candidate.

## Dynamic ownership

Physical DS SPU channels are not permanently assigned to BGM or SFX. Ownership is therefore reconstructed dynamically:

```text
SeqPlayer
   ↓
Track
   ↓
ExChannel
   ↓
physical SPU channel
```

When a channel is released or reallocated, ownership is re-evaluated. No fixed physical channel number is treated as a BGM channel.

This was explicitly stress-tested to prevent stale BGM ownership from leaking into SFX, ME or UNKNOWN/cry events.

## SeqPlayer gate

For runtime players classified as BGM, the guest sequence parser is allowed to advance on approximately one out of every two control ticks while Pokémon BGM preservation is enabled.

Non-BGM classes are never gated by this path.

## ExChannel control gate

SeqPlayer gating alone is insufficient because envelopes, LFO state and fades continue advancing at guest speed in the lower-level ExChannel path.

The implementation therefore also gates BGM-owned ExChannel **temporal control updates** at approximately one-half cadence.

It intentionally does not slow PCM/sample playback frequency or the SPU hardware timer.

## Atomic transition state

The gate is published as one packed `atomic<u32>`:

```text
bit 0      = enabled
bits 1..31 = epoch/generation
```

Transitions publish a new packed state atomically. Consumers perform one acquire load and compare the observed epoch with their local phase state.

If the epoch changed, their local gate phase is reset before any skip/advance decision is made.

This prevents a consumer from observing a new enable state with an old phase during OFF→ON, ON→OFF, reset, savestate rebuild or ROM replacement.

## ARM7 interpreter and JIT

The classifier hooks the exact ARM7 sound-driver addresses only after the ROM whitelist has passed.

The integration supports:

- ARM7 interpreter execution;
- AArch64 JIT;
- x86-64 JIT.

JIT blocks are split at relevant hook addresses so the hook executes at the same guest control points as the interpreter path.

## Activation policy

The frontend currently requests Pokémon BGM preservation only when:

```text
fast-forward enabled
AND multiplier == 2.0
AND the core classifier has accepted the ROM
```

Therefore:

- whitelisted Pokémon White + ×2 → semantic BGM-preserving mode;
- whitelisted Pokémon White + ×3/×4 → global SoundTouch only;
- any non-whitelisted ROM → global SoundTouch only.

No external Android debug property is required in the final Phase 3 implementation.
