import React from "react";
import ReactDOM from "react-dom/client";
import { Excalidraw, serializeAsJSON, useHandleLibrary } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import { EditorDocumentState, type SceneUpdate } from "./documentState";
import {
  nativeMagnificationUpdate,
  type NativeMagnificationGesture
} from "./nativeMagnification";
import { nativePanUpdate, nativeWheelZoomUpdate } from "./nativeScroll";
import {
  EMPTY_SCENE,
  getTheme,
  normalizePersistedImageStatuses,
  parseScene,
  sanitizeAppState,
  type Scene,
  type Theme
} from "./scene";
import { ScenePersistenceScheduler, type SceneSnapshot } from "./scenePersistence";
import "./styles.css";

type Bridge = {
  ready: (payload: string) => void;
  beginSceneTransfer: (payload: string) => void;
  appendSceneTransferChunk: (payload: string) => void;
  completeSceneTransfer: (payload: string) => void;
  saveCurrentDocument: () => void;
  themeChanged: (payload: Theme) => void;
  browseLibrary: (url: string) => void;
  openExternalLink: (url: string) => void;
  scrollApplied: (payload: string) => void;
};

declare global {
  interface Window {
    intellijExcalidraw?: Bridge;
    excalidrawPlugin?: {
      loadFile: (contents: string, preferredTheme: Theme, revision: number) => void;
      beginMagnification: (viewportX: number, viewportY: number) => void;
      magnify: (scale: number) => void;
      endMagnification: () => void;
      scroll: (
        deltaX: number,
        deltaY: number,
        viewportX: number,
        viewportY: number,
        controlOrMeta: boolean,
        sequence: number
      ) => void;
    };
  }
}

const HELP_ATTRIBUTION_TEXT = "JetBrains Excalidraw Editor by Agustin Banchio";
const MAX_SCENE_TRANSFER_CHUNK_SIZE = 16_000;
const SCENE_PERSISTENCE_DELAY_MS = 250;

function serializeScene(elements: readonly unknown[], appState: unknown, files: Record<string, unknown>): string {
  return serializeAsJSON(
    normalizePersistedImageStatuses(elements, files) as any,
    sanitizeAppState((appState ?? {}) as Record<string, unknown>) as any,
    files as any,
    "local"
  );
}

function transmitSceneUpdate(bridge: Bridge | undefined, update: SceneUpdate, saveImmediately: boolean) {
  if (!bridge) {
    return;
  }

  const transferId = `${update.revision}:${Date.now()}:${Math.random().toString(36).slice(2)}`;
  const chunkCount = Math.max(1, Math.ceil(update.scene.length / MAX_SCENE_TRANSFER_CHUNK_SIZE));

  bridge.beginSceneTransfer(`${transferId}\n${update.revision}\n${saveImmediately ? "1" : "0"}\n${chunkCount}`);

  for (let index = 0; index < chunkCount; index += 1) {
    const start = index * MAX_SCENE_TRANSFER_CHUNK_SIZE;
    const chunk = update.scene.slice(start, start + MAX_SCENE_TRANSFER_CHUNK_SIZE);
    bridge.appendSceneTransferChunk(`${transferId}\n${chunk}`);
  }

  bridge.completeSceneTransfer(transferId);
}

function notifyWhenBridgeIsReady() {
  const sendReady = () => window.intellijExcalidraw?.ready("ready");

  if (window.intellijExcalidraw) {
    sendReady();
    return;
  }

  window.addEventListener("intellij-excalidraw-bridge-ready", sendReady, { once: true });
}

function useLibraryBrowserNavigation() {
  React.useEffect(() => {
    window.name = window.name || "excalidraw_jetbrains_editor";

    const onClick = (event: MouseEvent) => {
      const browseButton = (event.target as Element | null)?.closest<HTMLAnchorElement>(
        ".library-menu-browse-button"
      );

      if (!browseButton?.href) {
        return;
      }

      event.preventDefault();

      if (window.intellijExcalidraw?.browseLibrary) {
        window.intellijExcalidraw.browseLibrary(browseButton.href);
        return;
      }

      window.open(browseButton.href, "_blank", "noopener,noreferrer");
    };

    document.addEventListener("click", onClick);
    return () => document.removeEventListener("click", onClick);
  }, []);
}

function openExternalLink(url: string) {
  try {
    const parsed = new URL(url, window.location.href);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return;
    }

    if (window.intellijExcalidraw?.openExternalLink) {
      window.intellijExcalidraw.openExternalLink(parsed.href);
    } else {
      window.open(parsed.href, "_blank", "noopener,noreferrer");
    }
  } catch {
    // Ignore malformed or unsupported links.
  }
}

function useExternalLinkNavigation() {
  React.useEffect(() => {
    const onClick = (event: MouseEvent) => {
      const anchor = (event.target as Element | null)?.closest<HTMLAnchorElement>("a[href]");
      if (!anchor || anchor.matches(".library-menu-browse-button")) {
        return;
      }

      try {
        const parsed = new URL(anchor.href, window.location.href);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
          return;
        }

        event.preventDefault();
        event.stopPropagation();
        openExternalLink(parsed.href);
      } catch {
        event.preventDefault();
      }
    };

    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, []);
}

function addHelpDialogAttribution() {
  const helpDialog = document.querySelector(".excalidraw .Dialog.HelpDialog");
  const header = helpDialog?.querySelector(".HelpDialog__header");

  if (!helpDialog || !header || helpDialog.querySelector(".excalidraw-plugin-help-attribution")) {
    return;
  }

  const attribution = document.createElement("div");
  attribution.className = "excalidraw-plugin-help-attribution";
  attribution.textContent = HELP_ATTRIBUTION_TEXT;
  header.insertAdjacentElement("afterend", attribution);
}

function useHelpDialogAttribution() {
  React.useEffect(() => {
    addHelpDialogAttribution();

    const observer = new MutationObserver(addHelpDialogAttribution);
    observer.observe(document.body, { childList: true, subtree: true });

    return () => observer.disconnect();
  }, []);
}

const SCROLLABLE_OVERFLOW = new Set(["auto", "scroll", "overlay"]);

function scrollDomAncestors(target: Element | null, deltaX: number, deltaY: number) {
  let remainingX = deltaX;
  let remainingY = deltaY;
  let element: Element | null = target;

  while (element && (remainingX !== 0 || remainingY !== 0)) {
    if (element instanceof HTMLElement) {
      const style = window.getComputedStyle(element);
      if (SCROLLABLE_OVERFLOW.has(style.overflowX) && element.scrollWidth > element.clientWidth) {
        const previous = element.scrollLeft;
        const next = Math.min(element.scrollWidth - element.clientWidth, Math.max(0, previous + remainingX));
        element.scrollLeft = next;
        remainingX -= next - previous;
      }
      if (SCROLLABLE_OVERFLOW.has(style.overflowY) && element.scrollHeight > element.clientHeight) {
        const previous = element.scrollTop;
        const next = Math.min(element.scrollHeight - element.clientHeight, Math.max(0, previous + remainingY));
        element.scrollTop = next;
        remainingY -= next - previous;
      }
    }
    element = element.parentElement;
  }

  return { deltaX: remainingX, deltaY: remainingY };
}

function acknowledgeScroll(sequence: number) {
  window.intellijExcalidraw?.scrollApplied(String(sequence));
}

function App() {
  const [initialData, setInitialData] = React.useState<Scene>(EMPTY_SCENE);
  const [excalidrawApi, setExcalidrawApi] = React.useState<any>(null);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const api = React.useRef<any>(null);
  const documentState = React.useRef(new EditorDocumentState());
  const lastSerialized = React.useRef<string>("");
  const loadingTimer = React.useRef<number | undefined>(undefined);
  const loadingScene = React.useRef(false);
  const lastTheme = React.useRef<Theme | undefined>(undefined);
  const nativeMagnification = React.useRef<NativeMagnificationGesture | null>(null);
  const persistenceScheduler = React.useRef<ScenePersistenceScheduler | null>(null);

  if (!persistenceScheduler.current) {
    persistenceScheduler.current = new ScenePersistenceScheduler(
      SCENE_PERSISTENCE_DELAY_MS,
      (snapshot: SceneSnapshot, saveImmediately: boolean) => {
        const serialized = serializeScene(snapshot.elements, snapshot.appState, snapshot.files);
        const changed = serialized !== lastSerialized.current;

        if (changed) {
          lastSerialized.current = serialized;
          documentState.current.updateScene(serialized);
        }

        if (changed || saveImmediately) {
          const update = documentState.current.currentUpdate();
          if (update) {
            transmitSceneUpdate(window.intellijExcalidraw, update, saveImmediately);
          } else if (saveImmediately) {
            window.intellijExcalidraw?.saveCurrentDocument();
          }
        }
      }
    );
  }

  useLibraryBrowserNavigation();
  useExternalLinkNavigation();
  useHelpDialogAttribution();
  useHandleLibrary({ excalidrawAPI: excalidrawApi });

  React.useEffect(() => {
    window.excalidrawPlugin = {
      loadFile(contents: string, preferredTheme: Theme, revision: number) {
        window.clearTimeout(loadingTimer.current);
        persistenceScheduler.current?.cancel();
        loadingScene.current = true;
        lastSerialized.current = "";
        documentState.current.beginLoad(revision);

        try {
          const scene = parseScene(contents, preferredTheme);
          const nextData = {
            elements: scene.elements,
            appState: scene.appState,
            files: scene.files
          };

          setInitialData(nextData);
          setLoadError(null);
          api.current?.updateScene({
            elements: nextData.elements,
            appState: nextData.appState
          });
          api.current?.addFiles?.(Object.values(nextData.files ?? {}));
          documentState.current.completeLoad(contents);
          lastTheme.current = getTheme(scene.appState);

          loadingTimer.current = window.setTimeout(() => {
            if (api.current) {
              lastSerialized.current = serializeScene(
                api.current.getSceneElements(),
                api.current.getAppState(),
                api.current.getFiles()
              );
            }
            loadingScene.current = false;
          }, 100);
        } catch (error) {
          loadingScene.current = false;
          setLoadError(error instanceof Error ? error.message : "The drawing could not be loaded.");
          console.error("Failed to load .excalidraw file", error);
        }
      },
      beginMagnification(viewportX: number, viewportY: number) {
        const appState = api.current?.getAppState();
        const initialZoom = appState?.zoom?.value;
        if (
          !Number.isFinite(viewportX) ||
          !Number.isFinite(viewportY) ||
          !Number.isFinite(initialZoom) ||
          initialZoom <= 0
        ) {
          nativeMagnification.current = null;
          return;
        }

        nativeMagnification.current = { initialZoom, viewportX, viewportY };
      },
      magnify(scale: number) {
        const gesture = nativeMagnification.current;
        const appState = api.current?.getAppState();
        if (!gesture || !appState) {
          return;
        }

        const update = nativeMagnificationUpdate(appState, gesture, scale);
        if (update) {
          api.current.updateScene({ appState: update });
        }
      },
      endMagnification() {
        nativeMagnification.current = null;
      },
      scroll(
        deltaX: number,
        deltaY: number,
        viewportX: number,
        viewportY: number,
        controlOrMeta: boolean,
        sequence: number
      ) {
        try {
          const target = document.elementFromPoint(viewportX, viewportY);
          const isCanvasTarget =
            target instanceof HTMLCanvasElement ||
            target instanceof HTMLTextAreaElement ||
            target instanceof HTMLIFrameElement;

          if (isCanvasTarget) {
            const appState = api.current?.getAppState();
            if (!appState) {
              return;
            }

            const update = controlOrMeta
              ? nativeWheelZoomUpdate(appState, deltaY, viewportX, viewportY)
              : nativePanUpdate(appState, deltaX, deltaY);
            if (update) {
              api.current.updateScene({ appState: update });
            }
          } else if (!controlOrMeta) {
            scrollDomAncestors(target, deltaX, deltaY);
          }
        } finally {
          acknowledgeScroll(sequence);
        }
      }
    };

    notifyWhenBridgeIsReady();

    return () => {
      window.clearTimeout(loadingTimer.current);
      persistenceScheduler.current?.cancel();
      nativeMagnification.current = null;
      delete window.excalidrawPlugin;
    };
  }, []);

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (!persistenceScheduler.current?.flush(true)) {
          const update = documentState.current.currentUpdate();
          if (update) {
            transmitSceneUpdate(window.intellijExcalidraw, update, true);
          } else {
            window.intellijExcalidraw?.saveCurrentDocument();
          }
        }
      }
    };

    const flushAfterPointerUp = () => {
      window.setTimeout(() => persistenceScheduler.current?.flush(false), 0);
    };
    const flushBeforeHiding = () => {
      if (document.visibilityState === "hidden") {
        persistenceScheduler.current?.flush(false);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("pointerup", flushAfterPointerUp);
    document.addEventListener("visibilitychange", flushBeforeHiding);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("pointerup", flushAfterPointerUp);
      document.removeEventListener("visibilitychange", flushBeforeHiding);
    };
  }, []);

  return (
    <div className={loadError ? "editor-shell editor-shell--error" : "editor-shell"}>
      {loadError ? (
        <div className="load-error" role="alert">
          <h1>Unable to open this drawing</h1>
          <p>{loadError}</p>
          <p>The file has not been changed. Fix its JSON content and reopen it.</p>
        </div>
      ) : (
        <Excalidraw
          initialData={initialData as any}
          libraryReturnUrl={`${window.location.origin}${window.location.pathname}`}
          validateEmbeddable={false}
          renderEmbeddable={() => null}
          onLinkOpen={(element: unknown, event: Event) => {
            const link = (element as { link?: unknown }).link;
            if (typeof link === "string" && /^https?:\/\//i.test(link)) {
              event.preventDefault();
              openExternalLink(link);
            }
          }}
          excalidrawAPI={(nextApi: any) => {
            api.current = nextApi;
            setExcalidrawApi(nextApi);
          }}
          onChange={(elements: readonly unknown[], appState: unknown, files: Record<string, unknown>) => {
            if (!documentState.current.currentUpdate()) {
              return;
            }

            const theme = getTheme(appState);
            if (!loadingScene.current && lastTheme.current && theme !== lastTheme.current) {
              window.intellijExcalidraw?.themeChanged(theme);
            }
            lastTheme.current = theme;

            if (loadingScene.current || !lastSerialized.current) {
              return;
            }

            persistenceScheduler.current?.schedule({ elements, appState, files });
          }}
        />
      )}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
