# Přepis design

## Goal

The Android app gets persistent read access to one user-selected Samsung Voice Recorder folder. It lists M4A files newest first and creates Czech transcripts that can be opened and copied.

All UI copy is English. Czech is used only as the transcription language (`cs`).

## Rozsah první verze

- Kotlin, Jetpack Compose, Material 3, minSdk 26, targetSdk 36.
- Jedna vybraná složka přes `ACTION_OPEN_DOCUMENT_TREE` a trvalé read-only oprávnění.
- Přímé soubory `.m4a` v této složce, řazené podle `lastModified` sestupně.
- Automatická sériová fronta chybějících přepisů přes WorkManager.
- Lokální `Whisper small` multilingual INT8 přes `sherpa-onnx` 1.13.4, jazyk `cs`, úloha `transcribe`.
- Optional manual cloud versions from OpenAI `gpt-4o-transcribe`, Google `gemini-3.7-flash`, xAI `/v1/stt`, and Groq `whisper-large-v3`.
- API keys encrypted with an Android Keystore AES-GCM key. Keys never enter WorkManager data, logs, backups, source code, or provider prompts.
- Cloud audio upload occurs only after the user chooses a configured provider from a recording detail. Provider results are separate files and never replace the local transcript.
- Jednorázové stažení tří připnutých modelových souborů, kontrola velikosti a SHA-256, potom offline provoz.
- Dekódování M4A přes Android `MediaExtractor` a `MediaCodec`. Převod více kanálů na mono. Sherpa-onnx provede převzorkování.
- Dlouhé nahrávky se zpracují po 25 sekundách. Text se mechanicky očistí a rozdělí do odstavců po nejvýše třech větách.
- Přepis se uloží atomicky jako UTF-8 text v privátním úložišti aplikace. Klíč zahrnuje URI, velikost a čas změny, takže změněný soubor nepoužije starý přepis.

## Obrazovky

1. První spuštění: nadpis „Vyberte složku s nahrávkami“, krátké vysvětlení, tlačítko „Vybrat složku“.
2. Seznam: horní lišta „Přepis“, menu „Změnit složku“, nativní `ListItem` bez karet, stav textem a symbolem, prázdný stav uprostřed.
3. Detail: back, recording name, clean paragraphs, source selector, copy, local re-transcription, and optional cloud transcription.
4. Advanced settings: plain provider rows with masked API-key entry, Save, and Remove. A privacy/cost notice states when audio leaves the device.

Použijí se systémové dynamické barvy, systémový světlý/tmavý režim, výchozí font a dotykové plochy nejméně 48 dp. Stav nikdy neurčuje jen barva. Nebudou gradienty, dashboard, chat, onboarding carousel ani dekorativní animace.

## Chyby a soukromí

- Ztracené SAF oprávnění vrátí uživatele k výběru složky.
- Neplatný nebo nepodporovaný zvuk dostane stav chyby; fronta pokračuje další nahrávkou.
- Chybějící internet při prvním stažení modelu použije retry s backoff. Hotový model síť nepotřebuje.
- Local transcription never sends audio away. Cloud transcription sends one selected recording to one explicitly selected provider.
- Provider endpoints are hardcoded HTTPS allowlists. API keys are never included in prompts or error messages.
- Provider responses are bounded, parsed as untrusted text, and displayed only as text.
- Dlouhá práce má viditelnou foreground notifikaci vyžadovanou Androidem.

## Vědomé omezení

Local transcription does not distinguish speakers, scan subfolders, or play audio. Direct cloud uploads reject files above the provider-safe limit instead of silently splitting and spending on multiple requests.

## Ověření

- JVM test: filtr M4A, stabilní klíč, odstavce a prázdný text.
- `testDebugUnitTest`, `lintDebug`, `assembleDebug`.
- Emulator only: folder selection, sorting, local transcript detail, empty folder, malformed M4A, advanced settings, encrypted-key roundtrip, and cloud validation/error paths without paid keys.
