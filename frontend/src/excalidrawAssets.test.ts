import { describe, expect, it } from "vitest";
import { rewriteExcalidrawFontSource } from "./excalidrawAssets";

describe("rewriteExcalidrawFontSource", () => {
  it("rewrites Excalidraw CDN font fallbacks to the bundled asset path", () => {
    const source =
      "url('https://esm.sh/@excalidraw/excalidraw@0.18.1/dist/prod/fonts/Xiaolai/Xiaolai-Regular.woff2') format('woff2')";

    expect(rewriteExcalidrawFontSource(source, "https://excalidraw-jetbrains-plugin/")).toBe(
      "url('https://excalidraw-jetbrains-plugin/fonts/Xiaolai/Xiaolai-Regular.woff2') format('woff2')"
    );
  });

  it("leaves non-Excalidraw font sources unchanged", () => {
    const source = "url('https://example.com/font.woff2') format('woff2')";

    expect(rewriteExcalidrawFontSource(source, "https://excalidraw-jetbrains-plugin/")).toBe(source);
  });
});
