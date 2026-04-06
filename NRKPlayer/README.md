# NRK Player

A native Android TV app for NRK content, built with Kotlin, Jetpack Compose and ExoPlayer/Media3.

The project explores three ideas that don't exist in the current NRK TV app:

1. **60-second Digest mode** — on-device scene analysis picks the best 10 clips from any programme and stitches them into a one-minute highlight reel, entirely without a server.
2. **Live subtitle translation** — ML Kit translates Norwegian subtitles to any supported language in real time, rendering the translated line in gold beneath the original.
3. **Automatic credits detection** — the manifest's `endSequenceStartTime` field drives a "Skip credits →" prompt that appears a few seconds before the credits roll.

---

## Tech

| | |
|---|---|
| Language | Kotlin (2.0, K2 compiler) |
| UI | Jetpack Compose + Material 3 |
| Video | Media3 / ExoPlayer (HLS + DASH, OkHttp data source) |
| DI | Koin |
| Networking | Retrofit + kotlinx.serialization |
| Storage | Room (watch progress + digest cache) |
| ML | ML Kit Text Recognition (Norwegian) · ML Kit Translation |
| Image loading | Coil |
| Tests | JUnit 4 + Robolectric |
| Architecture | Single-activity, MVVM, unidirectional data flow |

Zero third-party UI libraries. Everything you see is hand-written Compose.

---

## Architecture

```
NRKPlayer/
├── app/src/main/java/no/nrk/player/
│   ├── core/
│   │   └── player/
│   │       ├── DigestEngine.kt          ← scene scoring + segment selection
│   │       ├── NrkPlayerController.kt   ← ExoPlayer wrapper, translation, credits
│   │       └── BitmapExtractor.kt       ← frame extraction via VideoFrameMetadataListener
│   │
│   ├── data/
│   │   ├── remote/
│   │   │   └── NrkApiService.kt         ← Retrofit service + all DTO models
│   │   ├── local/
│   │   │   └── NrkDatabase.kt           ← Room DB, DAOs, entities
│   │   └── repository/
│   │       └── NrkRepository.kt         ← cache-then-network, mapping to domain
│   │
│   ├── domain/
│   │   ├── model/
│   │   │   └── Models.kt                ← clean domain types (no Android deps)
│   │   └── usecase/                     ← (placeholder for future use-case extraction)
│   │
│   ├── ui/
│   │   ├── home/
│   │   │   ├── HomeScreen.kt            ← frontpage sections, continue-watching row
│   │   │   └── HomeViewModel.kt
│   │   └── player/
│   │       ├── PlayerScreen.kt          ← full-screen player with gesture control
│   │       └── PlayerViewModel.kt       ← orchestrates all player state
│   │
│   └── di/
│       └── AppModule.kt                 ← Koin module
│
└── app/src/test/
    └── DigestEngineTest.kt              ← histogram + χ² + segment selection tests
```

---

## Digest mode

The scene-scoring pipeline:

```
Stream URL
    │
    ▼
ExoPlayer seeked to N evenly-spaced keyframes
    │
    ▼
Bitmap captured at each position (BitmapExtractor)
    │
    ▼
64-bin HSV hue histogram per frame
    │
    ▼
χ²(H₁, H₂) = Σ (H₁ᵢ - H₂ᵢ)² / (H₁ᵢ + H₂ᵢ)   ← lighting-invariant distance
    │
    ▼
Top-N frames by score, spread to avoid clustering   ← min-gap enforcement
    │
    ▼
DigestSegment list (startMs, endMs, sceneScore, subtitleSnippet)
    │
    ▼
Persisted in Room (DigestCacheEntity) — never recomputed for same ID
```

Each segment is 6 seconds. Ten segments × 6 s = exactly 60 seconds of playback. The engine skips the first and last 5 % of the programme to avoid cold opens and credits polluting the digest.

---

## Subtitle translation

`NrkPlayerController` listens for `Player.Listener.onCues()` and forwards each cue to an `ML Kit Translator` (Norwegian → target language). The translated text appears in a gold subtitle line beneath the original.

Language model download (~15 MB per language) happens once in the background. After that, translation is fully offline.

---

## Running it

Requires Android Studio Koala or newer.

```bash
git clone https://github.com/yourusername/NRKPlayer
```

Open in Android Studio, let Gradle sync, then run on an API 26+ device or emulator.

No API keys are required. The app calls the same public NRK PS API used by nrk.no — note that some content is geo-restricted to Norway.

---

## Testing

```bash
./gradlew test
```

`DigestEngineTest` covers:
- HSV histogram normalisation and colour fidelity
- χ² distance: identity (zero for same histogram), symmetry and non-negativity
- Segment selection: count ≤ target, chronological order, valid time ranges
- Subtitle cue attachment
- Edge cases: zero-score frames, all-zero histogram, very short content

---

## What's next

- [ ] Universal accessibility: TalkBack labels on all controls, sufficient contrast ratios validated with `AccessibilityInsights`
- [ ] Offline download with `Media3 Downloader` + foreground `WorkManager` service
- [ ] Android TV / Leanback layout (D-pad navigation, focus management)
- [ ] `MediaSession` integration for notification controls and Auto
- [ ] Digest mode fine-tuned with a small on-device CoreML-style model trained on NRK broadcast patterns
- [ ] Picture-in-picture support

---

## License

MIT. Not affiliated with NRK.
