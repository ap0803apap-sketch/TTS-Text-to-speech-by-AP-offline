# TTS by AP

**TTS by AP** is a modern Android Text-to-Speech application designed with a focus on accessibility, ease of use, and a sleek user interface. It leverages the latest Android APIs and follows Material Design 3 guidelines, with specific support for Samsung's One UI design language.

## 🚀 Features

*   **Text-to-Speech Engine:** High-quality speech synthesis from text input.
*   **Share-to-Speech:** Directly send text or `.txt` files from other apps (like File Managers or Browsers) to TTS by AP to read them aloud.
*   **Modern UI:** Built using Material Design 3 and One UI 6 components for a premium look and feel.
*   **Dynamic Theming:** Full support for Light and Dark modes with expressive color palettes.
*   **Biometric Security:** Integrated biometric authentication support.
*   **Foreground Service:** Reliable background processing for long text-to-speech sessions.

## 🛠 Tech Stack

*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Runtime) & XML (for Views/Themes)
*   **Design System:** One UI (via `io.github.yanndroid:oneui`)
*   **Architecture:** Android Jetpack (AppCompat, Activity, Core-KTX, ConstraintLayout)
*   **Build System:** Gradle (Kotlin DSL) with Version Catalogs (`libs.versions.toml`)

## 📦 Project Structure

```text
TTSbyAP2/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/ap/tts/      # Source code
│   │   │   ├── res/                  # UI Resources (Layouts, Themes, Icons)
│   │   │   └── AndroidManifest.xml   # App Configuration & Permissions
│   └── build.gradle.kts              # App-level build configuration
├── gradle/
│   └── libs.versions.toml             # Centralized dependency management
└── build.gradle.kts                  # Project-level build configuration
```

## ⚙️ Requirements

*   **Minimum SDK:** Android 11.0 (API 30)
*   **Target SDK:** Android 17 (API 37)
*   **Java Version:** Java 11

## 🔧 Installation

1.  Clone the repository.
2.  Open the project in **Android Studio (Ladybug or newer)**.
3.  Sync the project with Gradle files.
4.  Run the `app` module on an emulator or physical device running Android 11+.


