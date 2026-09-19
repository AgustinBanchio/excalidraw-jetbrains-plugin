import { exportToBlob, exportToSvg, loadFromBlob } from "@excalidraw/excalidraw";
import { parseScene, type Scene, type Theme } from "./scene";

export type DrawingFormat = "json" | "svg" | "png";

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 16_384) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 16_384));
  }
  return btoa(binary);
}

export function base64ToBytes(contents: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(contents), (character) => character.charCodeAt(0));
}

export async function decodeDrawing(contents: string, format: DrawingFormat, theme: Theme): Promise<Scene> {
  if (format === "json" || !contents) return parseScene(contents, theme);
  const bytes = format === "png" ? base64ToBytes(contents) : new TextEncoder().encode(contents);
  const text = new TextDecoder().decode(bytes);
  // Like the VS Code extension, accept a JSON drawing renamed to an image format.
  if (text.trimStart().startsWith("{")) return parseScene(text, theme);
  try {
    const scene = await loadFromBlob(new Blob([bytes], {
      type: format === "png" ? "image/png" : "image/svg+xml"
    }), null, null);
    return parseScene(JSON.stringify(scene), theme);
  } catch {
    throw new Error("This image does not contain readable Excalidraw scene data. Use an image saved with ‘Embed scene’ enabled.");
  }
}

export async function encodeDrawing(sceneJson: string, format: DrawingFormat): Promise<string> {
  if (format === "json") return sceneJson;
  const scene = JSON.parse(sceneJson);
  const appState = {
    ...scene.appState,
    // Export appearance is independent of the IDE/editor theme.
    exportBackground: scene.appState?.exportBackground ?? true,
    exportWithDarkMode: scene.appState?.exportWithDarkMode ?? false,
    exportEmbedScene: true
  };
  const options = {
    elements: scene.elements.filter((element: { isDeleted?: boolean }) => !element.isDeleted),
    appState,
    files: scene.files ?? {}
  };
  if (format === "svg") return (await exportToSvg(options)).outerHTML;
  const scale = [1, 2, 3].includes(appState.exportScale) ? appState.exportScale : 1;
  const blob = await exportToBlob({
    ...options,
    mimeType: "image/png",
    getDimensions: (width: number, height: number) => ({ width: width * scale, height: height * scale, scale })
  });
  return bytesToBase64(new Uint8Array(await blob.arrayBuffer()));
}
