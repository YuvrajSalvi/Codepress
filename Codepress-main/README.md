CodePress
=========

CodePress is an open-source AI coding agent for Android, in the spirit of
Claude Code and Codex. Describe what you want. CodePress reads, writes and
edits files in your project, runs commands, previews your work and can even
build an installable APK, all on your phone. It asks before it changes
anything.


DOWNLOAD THE APP
----------------
The latest release APK is uploaded on the Releases page of this repository.
You do not need to build anything:

  1. Open the Releases page of this repo on GitHub.
  2. Download the newest CodePress .apk from "Assets".
  3. Open the file on your phone and allow "Install unknown apps" if Android asks.

Link: https://github.com/YuvrajSalvi/Codepress/releases/latest
Minimum Android version: 9 (API 28).


BRING YOUR OWN BRAIN (AI PROVIDERS)
-----------------------------------
  Claude (Anthropic)     Your Anthropic API key
  Google Gemini          Your Gemini API key. The models your key can use
                         load automatically, just tap one.
  OpenAI-compatible      OpenAI, OpenRouter, Groq, DeepSeek, Gemini (OpenAI
                         endpoint), or any server such as Ollama / LM Studio
                         through a base URL. Presets are built in.
  On-device              Run a .gguf model fully offline with llama.cpp.
                         Import a file from your phone or download from a
                         direct link.
  Tilak                  Our own AI model, coming soon.

You can add several providers and switch between them from the model
switcher next to the chat box. API keys are stored encrypted (Android
Keystore).


FEATURES
--------
Chat and agent
  - Agent loop with streaming answers and tool calls: list_dir, read_file,
    write_file, edit_file, search_files, run_command, build_apk
  - Approval prompt before every write, edit or command (Allow, Deny,
    Always allow). Can be switched off in Settings.
  - Copy, share, select text, regenerate, edit and resend messages,
    inspect code blocks
  - Model list fetched from your provider
  - Notifications for AI replies, new files, approval requests and
    downloads

Projects (saved on your phone)
  - The first time the app opens it creates a CodePress folder on the phone.
    Every project you create gets its own folder named exactly like the
    project name you typed:

        CodePress/
          projects.json
          <your project name>/
            chat.json
            workspace/        (the project's files)

  - Chats are saved automatically and come back when you reopen the
    project.
  - Rename, delete, import a ZIP as a new project.
  - No account and no login. Nothing leaves your phone except what you
    send to the AI provider you chose.

Files, editor and terminal
  - Files tab: folders, ZIP extract and create, copy, move, multi-select,
    download, search inside files
  - Editor tab: syntax colours, line numbers, undo/redo, find and replace,
    go to line, format JSON, run HTML/SVG, "Ask AI" on the open file
  - Shell tab: a real terminal, plus an AI command log. Built-in unzip,
    zip and nano.
  - Optional Alpine Linux (via PRoot): tap Install in the Terminal tab and
    you get apk, python, git, node, gcc and more. Your project is mounted
    at /work. Turn on "Use Linux for AI commands" in Settings so the AI uses
    it too.

Preview and build
  - Live preview of web pages inside the app
  - "View on browser (LAN)" opens your project from a laptop or another
    phone on the same Wi-Fi
  - build_apk: the AI can create an Android project and build an
    installable APK in the cloud with your GitHub account (GitHub Actions).
    The APK is saved in the apk/ folder of the Files tab.

Connectors (Settings > Connectors)
  - GitHub      Username and a token (repo + workflow). Used for APK builds
                and for git inside Alpine.
  - Firebase    Paste your Firebase SDK config (the firebaseConfig snippet
                or a whole google-services.json). The AI uses it when it
                builds web or Android apps that need Firebase.
  - Supabase    Enter your project URL and anon key. The AI uses them for
                database, login and storage.
  - ImgBB       Enter your ImgBB API key. The AI can upload images and use
                the links in your apps.
  Connector values are stored encrypted on the phone. When you chat, the AI
  provider you chose receives them so it can use them in your apps. Never
  paste a Supabase service_role key.

Skills (Settings > Skills)
  - A library of ready-made skills: imgbb.js, rea, awesome design md,
    freellmapi, html game skill.
  - Tap Install: the skill is downloaded and unzipped into the "skills"
    folder of your open project. The AI reads a skill when a task matches.
  - Add custom skill: paste any GitHub repository link (HTTPS) and install
    it the same way. Public repos need no login. For a private repo, save
    your GitHub token in Connectors first.
  - Update or remove a skill any time.


BUILD FROM SOURCE
-----------------
Requirements: Android Studio (or JDK 17 + Android SDK), NDK 29.0.13113456,
CMake 3.31.6 (install both from SDK Manager > SDK Tools).

    ./gradlew assembleDebug

The APK is at app/build/outputs/apk/debug/. The first build compiles
llama.cpp and takes a while.

No local setup? Push this repository to GitHub. The Build workflow runs
the unit tests and uploads the debug APK as an artifact. It also downloads
the PRoot binaries needed for Alpine Linux (see ALPINE_SETUP.md).

This is an open-source repository. There is no google-services.json and no
Google sign-in any more, so nothing secret has to be added before building.


PROJECT LAYOUT
--------------
  app/          The CodePress app (Kotlin, Jetpack Compose)
    agent/      tool sandbox, tool executor, agent loop, shell runner
    llm/        Claude, Gemini, OpenAI-compatible and on-device providers
    data/       encrypted settings, project storage, connectors, skills
                installer, local models, Alpine environment, APK builder
    ui/         screens
  lib/          JNI wrapper around llama.cpp
  llama.cpp/    trimmed copy of llama.cpp (only what the Android build needs)
  termexec/, emulatorview/   terminal support
  scripts/      CI helper scripts


THINGS TO KNOW
--------------
  - Project files live in the app's private storage (the CodePress folder
    above), which is what lets Linux, git and npm work. To get files out,
    use Files > Download project as ZIP.
  - File tools are confined to the project. run_command is not a hard
    sandbox, so keep approvals on unless you trust the model.
  - Small on-device models are much weaker at multi-step coding than cloud
    models.
  - The release APK is signed with a debug key so it installs for
    sideloading. Use your own keystore before publishing to a store.


LICENSE
-------
MIT for CodePress. llama.cpp is MIT as well (see llama.cpp/LICENSE).
Third-party notices (PRoot, Alpine, BusyBox and others) are in NOTICE.
