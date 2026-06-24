declare global {
  interface Window {
    EXCALIDRAW_ASSET_PATH?: string;
  }
}

import { installExcalidrawFontSourceRewrite } from "./excalidrawAssets";

const excalidrawAssetPath = `${window.location.origin}/`;

window.EXCALIDRAW_ASSET_PATH = excalidrawAssetPath;
installExcalidrawFontSourceRewrite(excalidrawAssetPath);

import("./app");

export {};
