# NOVA — Neural Operations & Versatile Assistant

A custom, one-of-a-kind autonomous coding agent built **natively for Samsung Galaxy S25** (and any Android 12+ device). This is a real Android app — not a web page. Install the APK and it lives on your home screen.

## What NOVA is

NOVA is a cognitive architecture, not a chatbot wrapper. The intelligence lives in how components interact, not in any single LLM call. The LLM (GLM-4.6 via Z.ai) is ONE capability among many.

### Cognitive kernel (7-stage loop)
1. **PERCEIVE** — deterministic signal extraction (no LLM). Detects code, errors, question type, complexity.
2. **RECALL** — pulls relevant memories, past mistakes, project context from local Room DB.
3. **ROUTE** — capability router decides which capabilities to invoke based on perception + strategy memory. Different question types get different processing paths.
4. **EXECUTE** — runs selected capabilities in order (code sandbox, web search, memory recall).
5. **SYNTHESIZE** — the ONE LLM call. Gets all capability outputs as context.
6. **REFLECT** — self-evaluates the answer (heuristic-based to save LLM calls).
7. **LEARN** — updates strategy memory so future routing improves.

### 5 modes
- **Chat** — conversational coding help with the cognitive kernel
- **Teach** — you teach NOVA a lesson; stored at 100% confidence
- **Auto-Learn** — NOVA researches a topic via real web search + page reader
- **Code** — generate code, execute it in the in-app sandbox, self-review
- **Mind** — view NOVA's memory, skills, evolution timeline

### Real capabilities
- **GLM-4.6 LLM** via Z.ai API (called directly from device, no server)
- **Real web search** via Z.ai's web_search function
- **Real page reader** via Z.ai's page_reader function
- **Real code execution** via Mozilla Rhino JS engine (3s timeout, captured stdout/stderr)
- **Persistent memory** via Room database (knowledge entries, skills, strategy memory, evolution events)
- **Strategy learning** — NOVA tracks which capability routings work for which question types

## Build instructions

### Prerequisites
- Android Studio (Ladybug or newer)
- JDK 17
- Android SDK 35

### Steps

1. **Clone the repo:**
   ```bash
   git clone https://github.com/thepotatoninjahost/Stupid-agent.git
   cd Stupid-agent
   ```

2. **Open in Android Studio:**
   - File → Open → select the `Stupid-agent` folder
   - Wait for Gradle sync to complete

3. **Add your Z.ai API key:**
   - Open `app/src/main/java/com/nova/agent/llm/ApiKey.kt`
   - Replace `REPLACE_WITH_YOUR_ZAI_API_KEY` with your real key
   - Get a key at https://z.ai/manage-apikey

4. **Build the APK:**
   - In Android Studio: Build → Build Bundle(s) / APK(s) → Build APK(s)
   - Or via CLI: `./gradlew assembleDebug`
   - APK output: `app/build/outputs/apk/debug/app-debug.apk`

5. **Install on your Galaxy S25:**
   - Transfer the APK to your phone (USB, Drive, etc.)
   - Open the file manager, tap the APK, allow install from unknown sources if prompted
   - Or use `adb install app/build/outputs/apk/debug/app-debug.apk`

### Build via GitHub Actions (optional)

You can also build the APK in the cloud via GitHub Actions. Add a workflow file at `.github/workflows/build.yml`:

```yaml
name: Build APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: nova-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

## Tech stack

- **Language**: Kotlin 2.0.21
- **UI**: Jetpack Compose (Material 3, Material You dark theme)
- **Architecture**: MVVM + StateFlow
- **Database**: Room (SQLite)
- **HTTP**: OkHttp + Retrofit
- **Code execution**: Mozilla Rhino (JS engine)
- **Min SDK**: 31 (Android 12) — Galaxy S25 ships Android 15
- **Target SDK**: 35 (Android 15)

## Project structure

```
app/src/main/java/com/nova/agent/
├── MainActivity.kt                  # Entry point
├── NovaViewModel.kt                # State + business logic
├── cognitive/
│   └── CognitiveKernel.kt          # perceive → route → execute → reflect → learn
├── data/
│   ├── NovaDatabase.kt              # Room database
│   ├── dao/Daos.kt                 # Data access objects
│   └── entity/Entities.kt          # DB models
├── llm/
│   ├── ApiKey.kt                   # ⚠️ PUT YOUR KEY HERE
│   └── LlmClient.kt                # GLM-4.6 + web search + page reader
├── sandbox/
│   └── CodeSandbox.kt              # Rhino JS execution sandbox
└── ui/
    ├── NovaApp.kt                  # Compose screens (Chat, Teach, Learn, Code, Mind)
    └── theme/                      # Colors, typography, theme
```

## Security note

The API key is stored in `ApiKey.kt` for simplicity. For production, inject it via `BuildConfig` from `local.properties` or a secrets file. The app only ships as an APK on your own device, so this is fine for personal use.

## License

Personal use. Built specifically for thepotatoninjahost.
