# Accuracy benchmark

The target is at most 2% word error rate (WER), which equals at least 98% exact-word
accuracy. WER counts word substitutions, deletions, and insertions after lowercase
Unicode normalization and punctuation removal.

## Fixtures

- Synthetic Czech call: 13.1 seconds and 21 reference words.
- Archival Czech speech: 45 seconds and 94 reference words from the matching Czech
  subtitle track.

## Results

| Model | Synthetic WER | Synthetic accuracy | Archival WER | Archival accuracy |
| --- | ---: | ---: | ---: | ---: |
| Local Whisper Small INT8 on Android | 9.52% | 90.48% | 24.47% | 75.53% |
| Qwen3-ASR-1.7B on a host GPU | 9.52% | 90.48% | 26.60% | 73.40% |

Local Whisper also transcribed the 21-word English smoke fixture without a word
error. That result does not measure Czech accuracy.

The measured Czech results do not meet the 98% target. Qwen3-ASR-1.7B was not added
to the Android app because it did not improve either Czech fixture and is not viable
in the 2 GB test emulator. The test set is too small to predict general accuracy.

Cloud models require provider credentials, which were not available in the test
environment. Transcriber supports OpenAI GPT Transcribe, Groq Whisper Large V3,
Gemini, and xAI Speech to Text so each user can test the best option for their own
recordings. Verify important transcripts against the recording.
