import { describe, expect, it } from "vitest";
import { nativeMagnificationUpdate, type MagnificationAppState } from "./nativeMagnification";

const appState: MagnificationAppState = {
  zoom: { value: 1 },
  scrollX: 0,
  scrollY: 0,
  offsetLeft: 0,
  offsetTop: 0
};

describe("nativeMagnificationUpdate", () => {
  it("zooms around the native gesture anchor", () => {
    const update = nativeMagnificationUpdate(
      appState,
      { initialZoom: 1, viewportX: 200, viewportY: 100 },
      2
    );

    expect(update).toEqual({
      zoom: { value: 2 },
      scrollX: -100,
      scrollY: -50
    });
  });

  it("preserves the anchor across cumulative gesture updates", () => {
    const first = nativeMagnificationUpdate(
      appState,
      { initialZoom: 1, viewportX: 200, viewportY: 100 },
      1.5
    );
    const second = nativeMagnificationUpdate(
      { ...appState, ...first! },
      { initialZoom: 1, viewportX: 200, viewportY: 100 },
      2
    );

    expect(second).toEqual({
      zoom: { value: 2 },
      scrollX: -100,
      scrollY: -50
    });
  });

  it("uses Excalidraw's zoom bounds", () => {
    expect(
      nativeMagnificationUpdate(appState, { initialZoom: 1, viewportX: 0, viewportY: 0 }, 100)?.zoom.value
    ).toBe(30);
    expect(
      nativeMagnificationUpdate(appState, { initialZoom: 1, viewportX: 0, viewportY: 0 }, 0.001)?.zoom.value
    ).toBe(0.1);
  });

  it("rejects invalid scale values", () => {
    expect(
      nativeMagnificationUpdate(appState, { initialZoom: 1, viewportX: 0, viewportY: 0 }, Number.NaN)
    ).toBeNull();
  });
});
