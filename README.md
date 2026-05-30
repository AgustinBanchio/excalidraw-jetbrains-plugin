# Excalidraw JetBrains Plugin

MVP IntelliJ Platform plugin for opening and editing `.excalidraw` files inside IntelliJ-based IDEs.

The plugin registers a custom `.excalidraw` file type and opens matching files in a custom JCEF editor tab. The editor bundles a Vite + React frontend using `@excalidraw/excalidraw`, loads file JSON into Excalidraw, listens for scene changes, and writes updated JSON back to the IDE document so normal IDE save behavior persists it to disk.

This repository is a clean, modern Kotlin rewrite rather than a fork of the older abandoned implementations. It uses the IntelliJ Platform Gradle Plugin 2.x project layout and keeps the Excalidraw web UI as a separate frontend app.

## Current Stack

- Kotlin plugin code.
- IntelliJ Platform Gradle Plugin 2.x.
- Recent IntelliJ Platform target: `2025.3` / build `253`.
- JCEF for the embedded editor UI.
- Vite 8, React 19, TypeScript 6.
- `@excalidraw/excalidraw` 0.18.1.
- npm package overrides for a few Excalidraw transitive dependencies so installs are clean and `npm audit --omit=dev` reports no vulnerabilities.
- MIT license.

## How It Works

1. `plugin.xml` registers the `.excalidraw` file type and a custom `FileEditorProvider`.
2. Opening a `.excalidraw` file creates `ExcalidrawFileEditor`.
3. The editor starts a `JBCefBrowser`.
4. A small JCEF scheme handler serves the bundled frontend from:

   ```text
   https://excalidraw-jetbrains-plugin/index.html
   ```

5. Kotlin sends the initial file JSON into the React app.
6. The React app renders Excalidraw and sends debounced scene JSON changes back to Kotlin.
7. Kotlin writes those changes into the IDE document. Pressing `Ctrl+S`, Save All, or the IDE's normal save flow persists the document to disk.

## Requirements

- JDK/JBR 21. A JetBrains Runtime with JCEF is recommended for `runIde`.
- Node.js 20.19+ or 22.12+.
- npm.
- The Gradle wrapper from this repository.

On Windows, if Java is not on `PATH`, point `JAVA_HOME` at a JBR 21 installation before running Gradle:

```powershell
$env:JAVA_HOME='C:\Program Files\JetBrains\JetBrains Rider 2025.3.3\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

## Repository Structure

- `src/main/kotlin/com/agustinbanchio/excalidraw` - Kotlin plugin source.
- `src/main/resources/META-INF/plugin.xml` - IntelliJ plugin descriptor.
- `src/main/resources/icons` - file type icon.
- `frontend` - Vite + React app using `@excalidraw/excalidraw`.
- `samples` - sample drawings for manual testing.

## Run in a Sandbox IDE

```bash
./gradlew runIde
```

On Windows PowerShell:

```powershell
.\gradlew.bat runIde
```

Gradle runs the frontend build before processing plugin resources, so `runIde` will install npm dependencies with `npm ci`, build the Vite app, bundle `frontend/dist`, and launch a sandbox IDE.

In the sandbox IDE:

1. Open this repository folder.
2. Open `samples/hello.excalidraw`.
3. Draw or edit something in the Excalidraw editor.
4. Press `Ctrl+S`.
5. Close and reopen the file to confirm the changes persisted.

The sandbox IDE is an isolated test IDE used for plugin development. It does not modify your normal IntelliJ settings or installed plugins.

## Build

```bash
./gradlew buildPlugin
```

The packaged plugin ZIP is written under `build/distributions`.

## Verification

Useful checks before committing:

```bash
cd frontend
npm outdated
npm audit --omit=dev
cd ..
./gradlew buildPlugin
./gradlew runIde --dry-run
```

Expected status:

- `npm outdated` has no output.
- `npm audit --omit=dev` reports `found 0 vulnerabilities`.
- `buildPlugin` succeeds.
- `runIde --dry-run` succeeds.

## Frontend Development

You can run the frontend alone while iterating on React UI:

```bash
cd frontend
npm install
npm run dev
```

The standalone Vite app will not have the IntelliJ bridge, but it is useful for React and Excalidraw UI iteration. For plugin packaging, Gradle uses `npm ci` and the lockfile.

## MVP Scope

Included:

- Kotlin plugin using IntelliJ Platform Gradle Plugin 2.x.
- `.excalidraw` file type registration.
- `FileEditorProvider` and JCEF-backed `FileEditor`.
- Bundled Vite + React frontend with Excalidraw.
- Custom JCEF resource scheme for bundled frontend assets.
- Basic Kotlin-to-JS and JS-to-Kotlin bridge for initial file load, scene updates, and save.

Not included yet:

- Cloud sync.
- Collaboration.
- Image export.
- Marketplace publishing metadata.

## Notes

- The plugin currently targets `.excalidraw` JSON files only.
- Canvas changes are synced into the IntelliJ document automatically, but disk persistence follows IDE save behavior.
- The dependency overrides in `frontend/package.json` are intentionally narrow. They keep the MVP on the latest Excalidraw package while avoiding known vulnerable transitive versions and React 19 peer warning noise.
- The plugin uses the official Excalidraw logo mark from `excalidraw/excalidraw-logo`, which is MIT licensed. See `THIRD_PARTY_NOTICES.md`.
- This is an unofficial plugin and is not endorsed by Excalidraw.
