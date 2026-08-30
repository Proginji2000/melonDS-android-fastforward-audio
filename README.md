# melonDS Android – Fast-Forward Audio Fork

Experimental fork of [melonDS Android](https://github.com/rafaelvcaetano/melonDS-android) focused on fast-forward audio behavior on Android.

The project currently has two layers:

- **Global pitch-preserving fast-forward** for ×2 / ×3 / ×4 using SoundTouch 2.4.1.
- **Pokémon-specific BGM timing** for one strictly whitelisted Pokémon White FR revision, allowing the game to run at ×2 while background music remains at approximately ×1 real-time speed.

This repository contains the Android/frontend integration. The modified melonDS core is kept in the private submodule [`melonDS-core-fastforward-audio`](https://github.com/Proginji2000/melonDS-core-fastforward-audio).

## Current status

| Feature | Status |
| --- | --- |
| Normal-speed audio path | Preserved |
| Global fast-forward ×2 | Validated |
| Global fast-forward ×3 | Validated |
| Global fast-forward ×4 | Validated |
| Pitch-preserving SoundTouch path | Validated |
| Pokémon White FR game ×2 / BGM ×1 | Validated |
| Pokémon White FR SFX / ME / cry at game speed | Validated |
| Pokémon-specific ×3 / ×4 BGM preservation | Not implemented yet |
| Other Pokémon DS titles | Not yet profiled/whitelisted |

For non-whitelisted games, or for Pokémon White at ×3 / ×4, the emulator uses the global SoundTouch fast-forward path only.

## Validated Phase 3 result

On the exact Pokémon White FR revision currently whitelisted:

- game speed: approximately **2.000×**
- field/battle BGM: approximately **0.995× wall-clock**
- SFX: approximately **1.99×**
- ME 1303: approximately **2.002×**
- cry/UNKNOWN events: approximately **2.006×**
- post-DSP overwrite: **0** in validation runs
- stale BGM ExChannel ownership: **0**
- ARM7 JIT: validated

The current reproducible checkpoint is tagged:

```text
thor-pokemon-white-bgm-x1-phase3
```

## Architecture

The global path uses SoundTouch before the final Android/Oboe output FIFO. The Pokémon-specific path identifies BGM sequences in the guest audio engine and slows only their control-time progression before the mixed stream is processed by SoundTouch.

This keeps BGM musical time near ×1 while SFX, ME and unknown/cry events continue to follow game speed.

See:

- [Architecture](docs/ARCHITECTURE.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Building](docs/BUILDING.md)
- [Testing and validation](docs/TESTING.md)
- [Roadmap](docs/ROADMAP.md)

## Clone

This repository uses submodules.

```bash
git clone --recurse-submodules https://github.com/Proginji2000/melonDS-android-fastforward-audio.git
cd melonDS-android-fastforward-audio
git checkout thor-fastforward-audio
git submodule update --init --recursive
```

The Pokémon Phase 3 checkpoint can be checked out with:

```bash
git checkout thor-pokemon-white-bgm-x1-phase3
git submodule update --init --recursive
```

## Build

Debug APK used during development:

```powershell
.\gradlew.bat :app:assembleGitHubProdDebug
```

Release-like native build used for performance validation:

```powershell
.\gradlew.bat :app:buildCMakeRelWithDebInfo
```

Unit tests:

```powershell
.\gradlew.bat :app:testGitHubProdDebugUnitTest --rerun-tasks
```

See [BUILDING.md](docs/BUILDING.md) for details.

## Repository safety

Do **not** commit or distribute copyrighted game data or private test artifacts. In particular, keep these out of the repository:

- Nintendo DS ROMs (`*.nds`)
- SRAM/save files and savestates
- extracted SDAT/ARM binaries from commercial games
- PCM captures containing copyrighted game music/audio
- signing keystores and passwords
- device-specific private data

The implementation identifies supported software by hashes and metadata only; no game ROM or extracted copyrighted asset is required in source control.

## Upstream and licensing

This is a modified fork of melonDS Android / melonDS and retains the upstream GPLv3 licensing terms. See [`LICENSE`](LICENSE).

SoundTouch 2.4.1 is vendored under LGPL-2.1-or-later. Its source, license and redistribution notes are documented in [`app/src/main/cpp/third_party/README.md`](app/src/main/cpp/third_party/README.md).

The project is experimental and is not affiliated with Nintendo, The Pokémon Company, Game Freak or Creatures Inc.
