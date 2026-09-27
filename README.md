# Ko TTS

Natural-sounding **Korean text-to-speech for Android** that runs on your phone. It's built for de-Googled phones (GrapheneOS, CalyxOS, LineageOS), which otherwise have no usable Korean voice.

Ko TTS is a standard Android TTS engine. Once it's selected, every app that reads aloud uses it: e-book readers, OsmAnd, screen readers, language apps.

- **On-device neural voices.** [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) runs through ONNX Runtime, with 10 voices and 31 languages. Korean is the focus.
- **Tiny APK, about 7 MB.** The voice model is downloaded once from Hugging Face, pinned to a commit and verified with SHA-256:
  - compact int8: 190 MB
  - full fp32: 401 MB
- **Korean numbers read correctly.** 3시 30분 → 세 시 삼십 분, 38.2도 → 삼십팔 점 이 도, 010-1234-5678 → 공일공…, ₩35,000 → 삼만 오천 원, 3개 → 세 개.
- **Optional online providers with your own key:**
  - any OpenAI-compatible `/v1/audio/speech`: OpenAI, PPQ.AI, or a self-hosted server
  - Google Cloud Text-to-Speech
  - ElevenLabs

  Nothing leaves the phone unless you pick one of these.

## Install

Grab the APK for your phone's CPU from [Releases](https://github.com/robbie-med/ko_tts/releases):
- `arm64-v8a` covers every Pixel and almost every phone from the last 7 years.
- `universal` works everywhere.

Then:
1. Open **Ko TTS** and tap **Download compact model**. Use Wi-Fi; it's only needed once.
2. **Open Android speech settings** and set **Preferred engine** to Ko TTS.
3. Tap **Speak** to try it.

An app can use Ko TTS without it being the default by asking for it by package name:

```java
new TextToSpeech(context, listener, "org.robbiemed.kotts");
```

## How it works

| Part | Where |
|---|---|
| Supertonic inference: NFKD jamo → duration → flow matching → vocoder | [`engine/Supertonic.java`](app/src/main/java/org/robbiemed/kotts/engine/Supertonic.java) |
| Korean number/unit normalization | [`engine/KoreanNormalizer.java`](app/src/main/java/org/robbiemed/kotts/engine/KoreanNormalizer.java) |
| Android `TextToSpeechService` | [`KoTtsService.java`](app/src/main/java/org/robbiemed/kotts/KoTtsService.java) |
| Model download + SHA-256 check | [`ModelManager.java`](app/src/main/java/org/robbiemed/kotts/ModelManager.java) |
| Online providers | [`provider/`](app/src/main/java/org/robbiemed/kotts/provider) |

The engine code is plain Java plus ONNX Runtime, with no AndroidX and no Kotlin, which keeps the APK small. [`tools/`](tools) has:
- `DesktopTest.java`: runs the same Java engine on a PC.
- `quantize.py`: builds the compact model.
- `drop_probe.py`: measures how often syllables get dropped across random seeds, using Whisper.

### The compact model

[`tools/quantize.py`](tools/quantize.py) applies dynamic int8 quantization to the text encoder and vector estimator (MatMul and non-depthwise Conv).

The vocoder stays fp32 because quantizing it destroys the audio. In a Whisper round-trip test, 87% of characters came out wrong with an int8 vocoder, against 5% at fp32. The compact model matches fp32 accuracy (4.6% vs 5.3% character error) and runs 2.6× faster on a phone.

## Performance

Pixel 10a (Tensor G4), 4 threads, 6 steps, three short sentences (7.5 s of speech):

| Model | First audio | Synthesis speed |
|---|---|---|
| compact (int8) | 0.33 s | 6× faster than real time |
| full (fp32) | 0.70 s | 2.4× faster than real time |

Loading the model takes about 2 s, once per engine start. The first sentence is synthesized on its own so playback starts right away; the rest is generated while it plays.

## Build

```bash
./gradlew assembleRelease          # APKs in app/build/outputs/apk/release/
```

Needs JDK 17 and the Android SDK (compileSdk 36). Release signing reads `keystore.properties`, which isn't committed. Without it you get unsigned APKs.

## License

Ko TTS code: MIT, see [LICENSE](LICENSE).

The Supertonic 3 model is © Supertone Inc. and licensed under the [BigScience OpenRAIL-M license](https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE). It isn't bundled; the app downloads it from Supertone's Hugging Face repository. The license's use restrictions (no impersonation, no disinformation, no harming people, and so on) apply to anyone using the voices, and the compact int8 files are a derivative covered by the same license.

ONNX Runtime is MIT-licensed by Microsoft.
