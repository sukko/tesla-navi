# Tesla protocol attribution

`protocol/src/main/proto/*.proto` originates from:

- Repository: https://github.com/teslamotors/vehicle-command
- Revision: `a4b43c1eff0e09d77deb9f2dce97031141fe8c8a`
- Original directory: `pkg/protocol/protobuf`
- License: Apache-2.0, included in `TESLA-LICENSE` and in the APK assets.
- Local modification: `option java_multiple_files = true;` and a modification notice were added to generate individual Java classes. Protocol field numbers and types are unchanged.

`TeslaCrypto.kt` is a Kotlin adaptation of `internal/authentication/{native,metadata,peer,signer}.go`. It uses Java cryptography and Android-compatible keys, a monotonic session clock, and explicit response validation.

The fixed AES-GCM and leading-zero ECDH test vectors in `ProtocolTest.kt` are adapted from the upstream `internal/authentication/{protocol_doc,ecdh}_test.go` tests. Other tests and application code are local implementations.

The app is a personal third-party application and is not an official Tesla product.

## Kakao navigation SDK

The Android app uses the published binary dependency `com.kakaomobility.knsdk:knsdk:1.12.8-hotfix03` from Kakao Mobility's official Maven repository. The binary is not vendored into source control. SDK use is subject to the provider's terms, account permissions, and applicable usage limits.

`app/src/main/res/values/kakao_sdk_compat.xml` overrides the SDK's user-agent header resource with equivalent positional string placeholders to satisfy Android resource validation; rendered header contents are unchanged.

Official integration documentation: https://developers.kakaomobility.com/guide/android/start.html

## Gemini speech service

Optional guidance uses Google's Gemini REST API, without bundling a speech model or third-party inference runtime. Voice IDs follow https://ai.google.dev/gemini-api/docs/speech-generation#voice-options. Service use is subject to Google's terms and project quotas. See `GEMINI.md` for credential storage and usage.
