# Excalidraw Editor for JetBrains IDEs

An unofficial, free, open-source JetBrains IDE plugin for opening and editing
`.excalidraw` drawings without leaving the IDE.

The plugin registers the `.excalidraw` file type and opens drawings in a custom
JCEF-backed editor tab. It bundles the official Excalidraw React component,
loads drawing JSON from the IDE document, and sends canvas changes back to the
document so they participate in normal IDE save behavior and version control.

This repository is a modern Kotlin implementation built with the IntelliJ
Platform Gradle Plugin 2.x. It is not a fork of an older Excalidraw plugin.

## Features

- Open and edit `.excalidraw` files in a dedicated IDE editor tab.
- Create Excalidraw drawings from the IDE's New menu.
- Create initialized Excalidraw scratch files.
- Use the familiar Excalidraw canvas and drawing tools.
- Load drawings created by Excalidraw and other compatible editors.
- Sync canvas changes into the IDE document automatically.
- Save through `Ctrl+S` / `Cmd+S`, Save All, or normal IDE autosave.
- Keep drawings local, portable, and version-control friendly.
- Run without a cloud account.

Canvas changes are sent to the IDE document after a short debounce. They become
persisted on disk when the IDE saves the document.

## Compatibility

The current `0.2.1` release targets JetBrains Platform build `253`, corresponding
to JetBrains IDEs version `2025.3`. The compatible build range is configured in
`gradle.properties`.

The embedded editor requires a JetBrains Runtime with JCEF, which is included
with normal JetBrains IDE installations.

## Current Stack

- Kotlin `2.3.21` and JVM toolchain 21.
- Gradle `9.3.0`.
- IntelliJ Platform Gradle Plugin `2.16.0`.
- IntelliJ IDEA `2025.3` as the development platform.
- JCEF for the embedded web editor.
- Excalidraw `0.18.1`.
- React `19.2.7`.
- Vite `8.0.16`.
- TypeScript `6.0.3`.
- MIT license.

Frontend dependencies are pinned in `frontend/package.json` and
`frontend/package-lock.json`. Narrow npm overrides keep Excalidraw transitive
dependencies compatible with React 19 and free of known production
vulnerabilities.

## How It Works

1. `plugin.xml` registers the `.excalidraw` file type and
   `ExcalidrawFileEditorProvider`.
2. Opening a drawing creates `ExcalidrawFileEditor` and a `JBCefBrowser`.
3. A JCEF scheme handler serves the bundled Vite build from
   `https://excalidraw-jetbrains-plugin/index.html`.
4. Kotlin sends the initial IDE document JSON to the React app.
5. React renders Excalidraw and sends debounced scene changes back to Kotlin.
6. Kotlin updates the IDE document; explicit save commands persist it to disk.

The frontend is built during Gradle resource processing and bundled inside the
plugin ZIP. Generated `frontend/dist` files are not committed.

## Requirements

- JDK or JetBrains Runtime 21.
- Node.js `20.19+`, `22.12+`, or a newer supported Node.js release.
- npm.
- The Gradle wrapper included in this repository.

If Java is not on `PATH`, set `JAVA_HOME` before running Gradle.

Windows PowerShell:

```powershell
$env:JAVA_HOME='<path-to-jbr-21>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

macOS or Linux:

```bash
export JAVA_HOME="<path-to-jbr-21>"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Repository Structure

- `src/main/kotlin/com/agustinbanchio/excalidraw` - Kotlin plugin source.
- `src/main/resources/META-INF/plugin.xml` - plugin descriptor and Marketplace
  description.
- `src/main/resources/META-INF/pluginIcon*.svg` - light and dark plugin icons.
- `src/main/resources/icons` - `.excalidraw` file type icon.
- `src/main/resources/fileTemplates/internal` - blank drawing template used by
  new files and scratch files.
- `frontend` - Vite, React, and Excalidraw frontend.
- `samples/hello.excalidraw` - sample drawing for manual testing.

## Run in a Sandbox IDE

macOS or Linux:

```bash
./gradlew runIde
```

Windows PowerShell:

```powershell
.\gradlew.bat runIde
```

Gradle runs `npm ci`, builds the frontend, bundles it into the plugin resources,
and launches an isolated development IDE with the plugin installed.

In the sandbox IDE:

1. Open this repository folder.
2. Open `samples/hello.excalidraw`.
3. Edit the drawing.
4. Press `Ctrl+S` or `Cmd+S`.
5. Close and reopen the file to confirm the changes persisted.

The sandbox IDE uses separate settings and installed plugins from your normal
IDE.

## Frontend Development

Run the frontend by itself for faster React and Excalidraw UI iteration:

```bash
cd frontend
npm install
npm run dev
```

The standalone Vite app does not have the IntelliJ bridge. Plugin builds use
`npm ci` and the committed lockfile.

After changing dependencies, update the lockfile and verify the frontend:

```bash
cd frontend
npm outdated
npm audit --omit=dev
npm run build
```

## Build and Install Locally

Build the installable plugin ZIP:

```bash
./gradlew buildPlugin
```

On Windows, use `.\gradlew.bat buildPlugin`. The ZIP is written to
`build/distributions`.

To install it in a regular JetBrains IDE:

1. Open **Settings | Plugins**.
2. Open the gear menu and choose **Install Plugin from Disk...**.
3. Select the generated ZIP.
4. Restart the IDE when prompted.

## Sign the Plugin

Signing is configured through environment variables. Set `PLUGIN_SIGNING_DIR`
to a directory outside the repository containing:

```text
chain.crt
private.pem
```

The private key must be encrypted. On Windows PowerShell:

```powershell
$env:PLUGIN_SIGNING_DIR='<path-to-signing-directory>'

$securePassword = Read-Host 'Private key password' -AsSecureString
$env:PRIVATE_KEY_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

.\gradlew.bat signPlugin
.\gradlew.bat verifyPluginSignature

Remove-Item Env:PRIVATE_KEY_PASSWORD
Remove-Item Env:PLUGIN_SIGNING_DIR
```

The signed ZIP is written under `build/distributions`. Never commit private
keys, certificates, passwords, or signing environment variables. Keep a secure
backup of the private key and password so future releases use the same signing
identity.

## Verify a Release

Before publishing:

```bash
cd frontend
npm outdated
npm audit --omit=dev
npm run build
cd ..
./gradlew buildPlugin
./gradlew verifyPlugin
```

For a signed release, also run `./gradlew verifyPluginSignature`.

Expected results:

- `npm outdated` produces no dependency table.
- `npm audit --omit=dev` reports zero vulnerabilities.
- Frontend and plugin builds succeed.
- Plugin Verifier reports compatibility with the configured IDE target.
- Signature verification succeeds for the signed ZIP.

## Version and Publish

The plugin version is set in `build.gradle.kts`. Use semantic versioning and
update the version before building each release. The generated ZIP filename and
Marketplace version are derived from that value.

The first JetBrains Marketplace publication must be uploaded manually:

1. Update the version and release notes as needed.
2. Complete the release verification steps.
3. Build and sign the plugin.
4. Log in to JetBrains Marketplace and choose **Add new plugin** from your
   profile.
5. Upload the signed ZIP from `build/distributions`.
6. Complete the Marketplace listing, including the MIT license and source
   repository URL.

The Marketplace description comes directly from
`src/main/resources/META-INF/plugin.xml`. Plugin and file type icons use the
official Excalidraw favicon with attribution in `THIRD_PARTY_NOTICES.md`.

Automated Marketplace publishing with `publishPlugin` is not configured yet.
After the first manual publication, it can be added using a JetBrains
Marketplace personal access token stored outside the repository.

See JetBrains' official
[Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
and
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
documentation for the current Marketplace process.

## Current Scope

Included:

- `.excalidraw` file type registration.
- New drawing and scratch file creation.
- JCEF-backed custom file editor.
- Bundled current Excalidraw React frontend.
- Kotlin-to-JavaScript and JavaScript-to-Kotlin bridge.
- Initial file loading, scene synchronization, and IDE document saving.
- Marketplace description, icons, and environment-based plugin signing.

Not included as plugin-specific integrations:

- Excalidraw cloud sync or collaboration.
- Custom image export actions.
- Automated Marketplace publishing.

## License and Attribution

This project is licensed under the MIT License. See `LICENSE`.

The plugin uses the current official Excalidraw favicon from the active
`excalidraw/excalidraw` repository. Excalidraw and its favicon are MIT licensed;
see `THIRD_PARTY_NOTICES.md`.

This is an unofficial community plugin and is not endorsed by or affiliated
with Excalidraw.
