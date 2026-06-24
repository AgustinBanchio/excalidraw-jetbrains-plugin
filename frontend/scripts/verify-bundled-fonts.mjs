import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = fileURLToPath(new URL("..", import.meta.url));
const distDir = join(frontendDir, "dist");
const assetsDir = join(distDir, "assets");
const indexPath = join(distDir, "index.html");

function fail(message) {
  console.error(message);
  process.exitCode = 1;
}

if (!existsSync(indexPath)) {
  fail("frontend/dist/index.html does not exist. Run `npm run build` first.");
  process.exit();
}

const indexHtml = readFileSync(indexPath, "utf8");
if (!/connect-src[^"]*'self'/.test(indexHtml)) {
  fail("Content-Security-Policy connect-src must allow 'self' for Excalidraw's runtime font fetches.");
}

const entryScriptMatch = indexHtml.match(/<script[^>]+type="module"[^>]+src="\.\/assets\/([^"]+\.js)"/);
if (!entryScriptMatch) {
  fail("Could not find the built module entry script in dist/index.html.");
}

const entryScriptPath = entryScriptMatch ? join(assetsDir, entryScriptMatch[1]) : "";
const entryScript = entryScriptPath && existsSync(entryScriptPath) ? readFileSync(entryScriptPath, "utf8") : "";
const assetPathIndex = entryScript.indexOf("EXCALIDRAW_ASSET_PATH");
const firstImportIndex = entryScript.indexOf("import(");

if (assetPathIndex < 0) {
  fail("The built module entry script does not define window.EXCALIDRAW_ASSET_PATH.");
}

if (firstImportIndex >= 0 && assetPathIndex > firstImportIndex) {
  fail("window.EXCALIDRAW_ASSET_PATH must be assigned before the entry script imports the Excalidraw app.");
}

const scriptContents = readdirSync(assetsDir)
  .filter((file) => file.endsWith(".js"))
  .map((file) => readFileSync(join(assetsDir, file), "utf8"))
  .join("\n");

const fontReferences = new Set(
  [...scriptContents.matchAll(/\.\/(fonts\/[^"'`),]+\.woff2)/g)].map((match) => match[1]),
);

if (fontReferences.size === 0) {
  fail("No Excalidraw runtime font references were found in the built JavaScript.");
}

for (const fontReference of fontReferences) {
  const fontPath = join(distDir, fontReference);
  if (!existsSync(fontPath)) {
    fail(`Missing bundled Excalidraw font asset: ${fontReference}`);
  }
}

if (!process.exitCode) {
  console.log(`Verified ${fontReferences.size} bundled Excalidraw runtime font assets.`);
}
