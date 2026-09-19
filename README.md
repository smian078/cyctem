# CYSTEM

CYSTEM is a private, local-first Android AI command center. It is designed as one intelligent system rather than a plain chat screen: conversations, tools, sources, attachments, settings and model routing are coordinated behind a single product surface.

## Build status

The repository is implemented in six phases and each phase is committed only after its source is reviewed and the CI workflow is used as the build/test gate.

Phase 1 is the current foundation checkpoint:
- Android/Compose shell with edge-to-edge layout.
- Local SQLite storage with schema versioning, indexes, foreign keys and corruption recovery.
- Preferences DataStore for settings.
- Android Keystore AES-GCM encryption for provider keys.
- Share-to-CYSTEM entry point for text.
- Release build configuration with shrinking and CI.

The remaining phases extend the same architecture; they do not change the product contract.

## Product architecture

UI -> ViewModel -> Coordinator -> services and tools -> repositories.

The UI never owns provider HTTP calls. Model clients, search, attachments and phone tools are isolated services. Storage is behind repositories. Request cancellation and error handling are propagated through every layer.

## Pinned stack

Versions were checked against official documentation in September 2026.

- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- JDK 17
- Kotlin 2.4.20
- Compose BOM 2026.09.00
- Activity 1.13.0
- Lifecycle 2.11.0
- Core 1.17.0
- DataStore 1.2.1
- Coroutines 1.11.0
- compileSdk 36
- targetSdk 36
- minSdk 26

AGP 9 has built-in Kotlin support, so the Android Kotlin plugin is intentionally not applied. The Compose compiler Gradle plugin is applied with the same Kotlin version.

## Provider model IDs

The requested model IDs are centralized in ModelCatalog so they can be changed without touching routing code.

NVIDIA:
- nvidia/nemotron-3-super-120b-a12b
- nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
- nvidia/nemotron-3-ultra-550b-a55b

Gemini:
- gemini-3.8-flash
- gemini-3.1-flash-image

These IDs were verified against current NVIDIA NIM and Gemini documentation. The app treats model availability as a runtime capability: a missing/renamed model produces a clear provider error rather than silently switching to a different model.

## Privacy and secrets

There is no telemetry or analytics dependency.

Provider keys are encrypted with AES-GCM using a key stored in Android Keystore. The encrypted values are stored in Preferences DataStore. App backup and device-transfer rules exclude the local database and preference files. On devices that expose secure hardware for the Keystore key, the app can report that fact in Settings.

The phrase "private" means local application data stays on the device except for request content explicitly sent to an enabled provider.

## Build and CI

CI runs on every push and pull request. It installs JDK 17 and Gradle 9.1.0, runs unit tests and builds a shrunk release APK. The repository does not depend on a locally installed Android Studio for CI.

Android Studio can import the root Gradle project directly. The repository contains Gradle wrapper properties; the wrapper JAR can be regenerated with Gradle 9.1.0 in an Android/Gradle environment.

## Research sources

Android and build tooling:
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/develop/ui/compose/bom
- https://kotlinlang.org/docs/whatsnew2420.html

Persistence and lifecycle:
- https://developer.android.com/jetpack/androidx/releases/datastore
- https://developer.android.com/jetpack/androidx/releases/lifecycle
- https://developer.android.com/jetpack/androidx/releases/activity

NVIDIA:
- https://docs.nvidia.com/nim/large-language-models/latest/reference/api-reference.html
- https://docs.nvidia.com/nim/large-language-models/2.0.4/turbo/get-started-nemotron-3-super-120b-a12b.html
- https://build.nvidia.com/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning/modelcard
- https://build.nvidia.com/nvidia/nemotron-3-ultra-550b-a55b/modelcard

Gemini:
- https://ai.google.dev/gemini-api/docs/models
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash
- https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-image
- https://ai.google.dev/gemini-api/docs/generate-content/google-search
- https://ai.google.dev/gemini-api/docs/image-generation
