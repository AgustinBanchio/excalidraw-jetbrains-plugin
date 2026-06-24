const EXCALIDRAW_CDN_FONT_SOURCE =
  /https:\/\/esm\.sh\/@excalidraw\/excalidraw@[^"')\s]+\/dist\/prod\/fonts\//g;

export function rewriteExcalidrawFontSource(source: string, assetPath: string): string {
  const normalizedAssetPath = assetPath.replace(/\/+$/, "");
  return source.replace(EXCALIDRAW_CDN_FONT_SOURCE, `${normalizedAssetPath}/fonts/`);
}

export function installExcalidrawFontSourceRewrite(assetPath: string): void {
  if (!("FontFace" in window)) {
    return;
  }

  const NativeFontFace = window.FontFace;

  window.FontFace = class ExcalidrawFontFace extends NativeFontFace {
    constructor(family: string, source: string | BufferSource, descriptors?: FontFaceDescriptors) {
      super(
        family,
        typeof source === "string" ? rewriteExcalidrawFontSource(source, assetPath) : source,
        descriptors
      );
    }
  } as typeof FontFace;
}
