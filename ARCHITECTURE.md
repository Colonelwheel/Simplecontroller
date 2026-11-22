# Source map

This portable map describes the current `main`/`dev` directory structure. Dev preserves Android behavior from floating-controls snapshot `1ea6ffa`, including Macro Mode/U; main preserves the prior public release. This layout work does not rebuild the supplied binaries, deploy an app, or change device data. See [branch compatibility and promotion](BRANCHES.md).

## Android controller

Open [source/android](source/android/) in Android Studio. It contains the Gradle wrapper, build configuration, `app` module, Android resources, and JVM tests.

| Responsibility | Source |
| --- | --- |
| App UI and lifecycle | [MainActivity.kt](source/android/app/src/main/java/com/example/simplecontroller/MainActivity.kt) |
| Control configuration and saved fields | [model/Control.kt](source/android/app/src/main/java/com/example/simplecontroller/model/Control.kt) |
| Payload modifiers and timing syntax | [model/PayloadModifiers.kt](source/android/app/src/main/java/com/example/simplecontroller/model/PayloadModifiers.kt) |
| Timed payload ownership/execution | [ui/ButtonAimPayloadExecutor.kt](source/android/app/src/main/java/com/example/simplecontroller/ui/ButtonAimPayloadExecutor.kt) |
| Finite Button Macro Mode | [ui/ButtonMacroController.kt](source/android/app/src/main/java/com/example/simplecontroller/ui/ButtonMacroController.kt) |
| Scoped phone output coordination | [PhoneOutputCoordinator.kt](source/android/app/src/main/java/com/example/simplecontroller/PhoneOutputCoordinator.kt) |
| UDP transport and ordering | [UdpClient.kt](source/android/app/src/main/java/com/example/simplecontroller/UdpClient.kt) |
| Profile encoding and compatibility | [io/LayoutStorage.kt](source/android/app/src/main/java/com/example/simplecontroller/io/LayoutStorage.kt), [io/ProfileBackup.kt](source/android/app/src/main/java/com/example/simplecontroller/io/ProfileBackup.kt) |

Control handlers and property editors remain under `source/android/app/src/main/java/com/example/simplecontroller/ui`. Android tests remain in `source/android/app/src/test`; instrumentation sources remain in `app/src/androidTest`, but running connected-device tasks requires explicit approval.

The [Android README](source/android/README.md) provides build commands. [Distribution settings](source/android/PLAY_RELEASE.md) distinguish source branches from the Sideload Debug/Play Release package variants. Preserve the [profile-transfer contract](source/android/PROFILE_TRANSFER_FORMAT.md), output ownership, Hold, cleanup, and packet-ordering behavior during future changes.

## Windows receiver

The canonical entrypoint is [source/receiver/simple_controller_receiver.py](source/receiver/simple_controller_receiver.py). Adjacent companions implement:

- [camera_follow.py](source/receiver/camera_follow.py): receiver-side camera assistance.
- [phone_output_protocol.py](source/receiver/phone_output_protocol.py): scoped phone output and ownership.
- [relative_mouse.py](source/receiver/relative_mouse.py): the relative Game Mouse backend.
- [face_input_server.py](source/receiver/face_input_server.py): optional facial-input integration.
- [voice_input_server.py](source/receiver/voice_input_server.py): optional voice-input protocol and timing.

[SimpleControllerReceiver.spec](source/receiver/SimpleControllerReceiver.spec) packages the receiver with the adjacent modules and bundled ViGEmClient DLLs. Root [SimpleControllerReceiver.exe](SimpleControllerReceiver.exe) is an existing published artifact; changing Python does not update it. See [receiver build/run guidance](source/receiver/README.md). Retained feature packaging helpers live under `source/receiver/tools`, with the voice wrapper under `voiceattack`; their current paths are described in the receiver guide. Dev includes receiver regression tests in that same source directory; source/test inspection is distinct from native input, camera, speech, or game verification.

## Optional integrations and documentation

- [facial-input](facial-input/README.md) is the separate optional facial app, with its own settings and packaging recipe.
- [voiceattack](voiceattack/USER_GUIDE.md) contains the shared inline sender and user setup; [development notes](voiceattack/DEVELOPMENT.md) cover mock checks and receiver helper paths.
- [USER_GUIDE.md](USER_GUIDE.md) is the concise controller guide. The [detailed development guide](source/android/SIMPLECONTROLLER_USER_GUIDE.md), [floating controls](source/android/FLOATING_CONTROLS.md), and [Macro Mode/U contract](source/android/MACRO_MODE_AND_PAYLOAD_PORTING.md) provide additional detail.

Older architecture transcripts, local restore records, legacy firmware sketches, old binary collections, and generated files remain in their preserved original folders/history. They are not current source locations or deployment instructions for this layout. Private workspace maps, signing material, credentials, and device backups are kept outside public project docs.
