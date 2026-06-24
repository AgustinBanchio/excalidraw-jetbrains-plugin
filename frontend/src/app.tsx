import React from "react";
import ReactDOM from "react-dom/client";
import { Excalidraw, serializeAsJSON, useHandleLibrary } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import { EditorDocumentState, encodeSceneUpdate } from "./documentState";
import { EMPTY_SCENE, getTheme, parseScene, sanitizeAppState, type Scene, type Theme } from "./scene";
import "./styles.css";

type Bridge = {
  ready: (payload: string) => void;
  sceneChanged: (payload: string) => void;
  save: (payload: string) => void;
  saveCurrentDocument: () => void;
  themeChanged: (payload: Theme) => void;
  browseLibrary: (url: string) => void;
  openExternalLink: (url: string) => void;
};

declare global {
  interface Window {
    intellijExcalidraw?: Bridge;
    excalidrawPlugin?: {
      loadFile: (contents: string, preferredTheme: Theme, revision: number) => void;
    };
  }
}

const HELP_ATTRIBUTION_TEXT = "JetBrains Excalidraw Editor by Agustin Banchio";

function serializeScene(elements: readonly unknown[], appState: unknown, files: Record<string, unknown>): string {
  return serializeAsJSON(
    elements as any,
    sanitizeAppState((appState ?? {}) as Record<string, unknown>) as any,
    files as any,
    "local"
  );
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

  useLibraryBrowserNavigation();
  useExternalLinkNavigation();
  useHelpDialogAttribution();
  useHandleLibrary({ excalidrawAPI: excalidrawApi });

  React.useEffect(() => {
    window.excalidrawPlugin = {
      loadFile(contents: string, preferredTheme: Theme, revision: number) {
        window.clearTimeout(loadingTimer.current);
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
          api.current?.updateScene(nextData);
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
      }
    };

    notifyWhenBridgeIsReady();

    return () => {
      window.clearTimeout(loadingTimer.current);
      delete window.excalidrawPlugin;
    };
  }, []);

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        const update = documentState.current.currentUpdate();
        if (update) {
          window.intellijExcalidraw?.save(encodeSceneUpdate(update));
        } else {
          window.intellijExcalidraw?.saveCurrentDocument();
        }
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
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

            const serialized = serializeScene(elements, appState, files);

            if (loadingScene.current || !lastSerialized.current) {
              lastSerialized.current = serialized;
              return;
            }

            if (serialized === lastSerialized.current) {
              return;
            }

            lastSerialized.current = serialized;
            documentState.current.updateScene(serialized);
            const update = documentState.current.currentUpdate();
            if (update) {
              window.intellijExcalidraw?.sceneChanged(encodeSceneUpdate(update));
            }
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
