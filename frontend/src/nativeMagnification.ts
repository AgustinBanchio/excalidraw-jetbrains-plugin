export type MagnificationAppState = {
  zoom: { value: number };
  scrollX: number;
  scrollY: number;
  offsetLeft: number;
  offsetTop: number;
};

export type NativeMagnificationGesture = {
  initialZoom: number;
  viewportX: number;
  viewportY: number;
};

export type NativeMagnificationUpdate = {
  zoom: { value: number };
  scrollX: number;
  scrollY: number;
};

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 30;

function normalizedZoom(zoom: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(zoom * 1_000_000) / 1_000_000));
}

export function nativeMagnificationUpdate(
  appState: MagnificationAppState,
  gesture: NativeMagnificationGesture,
  scale: number
): NativeMagnificationUpdate | null {
  const currentZoom = appState.zoom.value;
  if (
    !Number.isFinite(scale) ||
    scale <= 0 ||
    !Number.isFinite(currentZoom) ||
    currentZoom <= 0 ||
    !Number.isFinite(gesture.initialZoom) ||
    gesture.initialZoom <= 0
  ) {
    return null;
  }

  const nextZoom = normalizedZoom(gesture.initialZoom * scale);
  const appLayerX = gesture.viewportX - appState.offsetLeft;
  const appLayerY = gesture.viewportY - appState.offsetTop;
  const baseScrollX = appState.scrollX + appLayerX - appLayerX / currentZoom;
  const baseScrollY = appState.scrollY + appLayerY - appLayerY / currentZoom;

  return {
    scrollX: baseScrollX - appLayerX + appLayerX / nextZoom,
    scrollY: baseScrollY - appLayerY + appLayerY / nextZoom,
    zoom: { value: nextZoom }
  };
}
