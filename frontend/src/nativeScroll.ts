import { nativeMagnificationUpdate, type MagnificationAppState } from "./nativeMagnification";

export type NativeScrollUpdate = {
  scrollX: number;
  scrollY: number;
};

const WHEEL_ZOOM_MAX_DELTA = 10;

export function nativePanUpdate(
  appState: MagnificationAppState,
  deltaX: number,
  deltaY: number
): NativeScrollUpdate | null {
  const zoom = appState.zoom.value;
  if (
    !Number.isFinite(deltaX) ||
    !Number.isFinite(deltaY) ||
    !Number.isFinite(zoom) ||
    zoom <= 0
  ) {
    return null;
  }

  return {
    scrollX: appState.scrollX - deltaX / zoom,
    scrollY: appState.scrollY - deltaY / zoom
  };
}

export function nativeWheelZoomUpdate(
  appState: MagnificationAppState,
  deltaY: number,
  viewportX: number,
  viewportY: number
) {
  const currentZoom = appState.zoom.value;
  if (
    !Number.isFinite(deltaY) ||
    deltaY === 0 ||
    !Number.isFinite(currentZoom) ||
    currentZoom <= 0 ||
    !Number.isFinite(viewportX) ||
    !Number.isFinite(viewportY)
  ) {
    return null;
  }

  const sign = Math.sign(deltaY);
  const absoluteDelta = Math.abs(deltaY);
  const boundedDelta = Math.min(absoluteDelta, WHEEL_ZOOM_MAX_DELTA) * sign;
  let nextZoom = currentZoom - boundedDelta / 100;
  nextZoom +=
    Math.log10(Math.max(1, currentZoom)) * -sign * Math.min(1, absoluteDelta / 20);

  return nativeMagnificationUpdate(
    appState,
    { initialZoom: currentZoom, viewportX, viewportY },
    nextZoom / currentZoom
  );
}
