# CodePress

An open-source AI coding agent for Android, in the spirit of Claude Code and Codex.
Describe what you want; CodePress reads, writes and edits files in its workspace and runs shell commands until the job is done, and asks before it changes anything.

**Bring your own brain**

| Mode | What you need |
|---|---|
| Anthropic (Claude) | Your Anthropic API key |
| OpenAI-compatible | Key for OpenAI, OpenRouter, Groq, DeepSeek, Gemini, or a base URL for Ollama / LM Studio |
| On-device | A `.gguf` model file (runs offline through [llama.cpp](https://github.com/ggml-org/llama.cpp)) |

## Features
- Agent loop with streaming answers and tool calls (`list_dir`, `read_file`, `write_file`, `edit_file`, `search_files`, `run_command`)
- Approval prompt before every write, edit or command (can be switched off in Settings)
- Model list fetched from your provider, no need to remember model names
- Local model manager: import a `.gguf` from storage or download one from a direct link
- Workspace browser, import a folder/files from the phone, export everything as a ZIP
- API keys stored encrypted (Android Keystore)

## Build
Requirements: Android Studio (or JDK 17 + Android SDK), NDK `29.0.13113456`, CMake `3.31.6` (install both from SDK Manager > SDK Tools).

```
./gradlew assembleDebug
```
The APK is at `app/build/outputs/apk/debug/`. The first build compiles llama.cpp for two ABIs and takes a while.

No local setup? Push this repository to GitHub: the **Build** workflow runs the unit tests and uploads the debug APK as an artifact.

Minimum Android version: 13 (API 33), because of the bundled llama.cpp Android library.

## Project layout
- `app/` - the CodePress app (Kotlin, Jetpack Compose)
  - `agent/` tool sandbox, tool executor, agent loop, text tool protocol for local models
  - `llm/` Anthropic, OpenAI-compatible and on-device providers
  - `data/` encrypted settings, model manager, workspace import/export
  - `ui/` screens
- `lib/` - JNI wrapper around llama.cpp (from `examples/llama.android` in llama.cpp)
- `llama.cpp/` - trimmed copy of llama.cpp (only what the Android build needs)

## Things to know
- The workspace lives in the app's private storage. Use **Files** to import projects and export results.
- The shell is Android's `sh` with toybox tools. There is no git, python or node unless you add them.
- File tools are confined to the workspace. `run_command` is not a hard sandbox, so keep approvals on unless you trust the model.
- Small on-device models are far weaker at multi-step coding than cloud models. The native context is 8192 tokens.
- Chats are not saved after the app process is closed.
- The release build is signed with the debug key so it installs for sideloading. Use your own keystore before publishing to a store.

## License
MIT for CodePress. llama.cpp is MIT as well (see `llama.cpp/LICENSE`).

## Editor, folders and ZIP
Projects have their own files and chat. The Editor tab opens any text file with syntax colours, line numbers, undo/redo, find and replace, Run for HTML/SVG, and Ask AI. The Files tab supports folders, ZIP extract/create, copy, move, multi-select, download and search. The shell understands `unzip`, `zip` and `nano <file>` without extra tools, and blocks commands that leave the project folder.
