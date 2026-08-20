import { describe, expect, it } from "vitest";
import { nativePanUpdate, nativeWheelZoomUpdate } from "./nativeScroll";

const appState = {
  zoom: { value: 2 },
  scrollX: 100,
  scrollY: 200,
  offsetLeft: 10,
  offsetTop: 20
};

describe("nativePanUpdate", () => {
  it("converts viewport deltas into scene scrolling at the current zoom", () => {
    expect(nativePanUpdate(appState, 20, -40)).toEqual({ scrollX: 90, scrollY: 220 });
  });

  it("rejects invalid input", () => {
    expect(nativePanUpdate(appState, Number.NaN, 0)).toBeNull();
    expect(nativePanUpdate({ ...appState, zoom: { value: 0 } }, 1, 1)).toBeNull();
  });
});

describe("nativeWheelZoomUpdate", () => {
  it("zooms around the supplied viewport point", () => {
    const update = nativeWheelZoomUpdate(appState, -5, 110, 120);

    expect(update?.zoom.value).toBeGreaterThan(appState.zoom.value);
    expect(update?.scrollX).not.toBe(appState.scrollX);
    expect(update?.scrollY).not.toBe(appState.scrollY);
  });

  it("rejects zero and invalid deltas", () => {
    expect(nativeWheelZoomUpdate(appState, 0, 110, 120)).toBeNull();
    expect(nativeWheelZoomUpdate(appState, Number.NaN, 110, 120)).toBeNull();
  });
});
