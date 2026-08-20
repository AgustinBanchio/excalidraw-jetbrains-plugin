import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { createReadStream, cpSync, existsSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

function excalidrawFontsDir() {
  const packageRoot = dirname(dirname(dirname(require.resolve("@excalidraw/excalidraw"))));
  return resolve(packageRoot, "dist/prod/fonts");
}

function serveFontAsset(url: string | undefined, response: any, next: () => void) {
  if (!url?.startsWith("/fonts/")) {
    next();
    return;
  }

  let relativePath: string;
  try {
    relativePath = decodeURIComponent(url.slice("/fonts/".length).split(/[?#]/, 1)[0]);
  } catch {
    response.statusCode = 400;
    response.end("Bad font request");
    return;
  }

  if (!relativePath || relativePath.includes("\\") || relativePath.split("/").includes("..")) {
    response.statusCode = 404;
    response.end("Font not found");
    return;
  }

  const fontsDir = excalidrawFontsDir();
  const filePath = resolve(fontsDir, relativePath);
  if (!filePath.startsWith(fontsDir) || !filePath.endsWith(".woff2") || !existsSync(filePath) || !statSync(filePath).isFile()) {
    response.statusCode = 404;
    response.end("Font not found");
    return;
  }

  response.setHeader("Content-Type", "font/woff2");
  response.setHeader("Cache-Control", "no-cache");
  createReadStream(filePath).pipe(response);
}

function excalidrawFontAssets() {
  return {
    name: "excalidraw-font-assets",
    configureServer(server: any) {
      server.middlewares.use((request: any, response: any, next: () => void) => {
        serveFontAsset(request.url, response, next);
      });
    },
    configurePreviewServer(server: any) {
      server.middlewares.use((request: any, response: any, next: () => void) => {
        serveFontAsset(request.url, response, next);
      });
    },
    closeBundle() {
      const source = excalidrawFontsDir();
      const target = resolve("dist/fonts");

      if (!existsSync(source)) {
        throw new Error(`Could not find Excalidraw font assets at ${source}`);
      }

      cpSync(source, target, { recursive: true });
    }
  };
}

export default defineConfig({
  base: "./",
  plugins: [react(), excalidrawFontAssets()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    chunkSizeWarningLimit: 2500
  }
});
