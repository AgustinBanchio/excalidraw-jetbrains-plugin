import React from "react";
import ReactDOM from "react-dom/client";
import { Excalidraw, serializeAsJSON } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import "./styles.css";

type Bridge = {
  ready: (payload: string) => void;
  sceneChanged: (payload: string) => void;
  save: (payload: string) => void;
  themeChanged: (payload: Theme) => void;
};

declare global {
  interface Window {
    intellijExcalidraw?: Bridge;
    excalidrawPlugin?: {
      loadFile: (contents: string, preferredTheme: Theme) => void;
    };
  }
}

type Theme = "light" | "dark";

type Scene = {
  type?: string;
  version?: number;
  source?: string;
  elements?: readonly unknown[];
  appState?: Record<string, unknown>;
  files?: Record<string, unknown>;
};

const EMPTY_SCENE: Scene = {
  type: "excalidraw",
  version: 2,
  source: "https://github.com/agustinbanchio/excalidraw-jetbrains-plugin",
  elements: [],
  appState: {},
  files: {}
};

function parseScene(contents: string, preferredTheme: Theme): Scene {
  if (!contents.trim()) {
    return {
      ...EMPTY_SCENE,
      appState: { theme: preferredTheme }
    };
  }

  const parsed = JSON.parse(contents) as Scene;
  const appState = sanitizeAppState(parsed.appState ?? {});
  return {
    ...EMPTY_SCENE,
    ...parsed,
    elements: Array.isArray(parsed.elements) ? parsed.elements : [],
    appState: {
      theme: preferredTheme,
      ...appState
    },
    files: parsed.files ?? {}
  };
}

function getTheme(appState: unknown): Theme {
  return (appState as Record<string, unknown> | undefined)?.theme === "dark" ? "dark" : "light";
}

function sanitizeAppState(appState: Record<string, unknown>): Record<string, unknown> {
  const {
    collaborators,
    selectedElementIds,
    editingElement,
    resizingElement,
    draggingElement,
    ...rest
  } = appState;

  return rest;
}

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

function App() {
  const [initialData, setInitialData] = React.useState<Scene>(EMPTY_SCENE);
  const api = React.useRef<any>(null);
  const lastSerialized = React.useRef<string>("");
  const latestSerialized = React.useRef<string>(JSON.stringify(EMPTY_SCENE, null, 2));
  const pendingTimer = React.useRef<number | undefined>(undefined);
  const loadingTimer = React.useRef<number | undefined>(undefined);
  const loadingScene = React.useRef(false);
  const lastTheme = React.useRef<Theme | undefined>(undefined);

  React.useEffect(() => {
    window.excalidrawPlugin = {
      loadFile(contents: string, preferredTheme: Theme) {
        try {
          window.clearTimeout(pendingTimer.current);
          window.clearTimeout(loadingTimer.current);
          loadingScene.current = true;

          const scene = parseScene(contents, preferredTheme);
          const nextData = {
            elements: scene.elements,
            appState: scene.appState,
            files: scene.files
          };

          setInitialData(nextData);
          api.current?.updateScene(nextData);
          lastSerialized.current = "";
          latestSerialized.current = contents;
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
          console.error("Failed to load .excalidraw file", error);
        }
      }
    };

    notifyWhenBridgeIsReady();

    return () => {
      window.clearTimeout(pendingTimer.current);
      window.clearTimeout(loadingTimer.current);
      delete window.excalidrawPlugin;
    };
  }, []);

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        window.intellijExcalidraw?.save(latestSerialized.current);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <div className="editor-shell">
      <Excalidraw
        initialData={initialData as any}
        excalidrawAPI={(nextApi: any) => {
          api.current = nextApi;
        }}
        onChange={(elements: readonly unknown[], appState: unknown, files: Record<string, unknown>) => {
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

          latestSerialized.current = serialized;
          window.clearTimeout(pendingTimer.current);
          pendingTimer.current = window.setTimeout(() => {
            lastSerialized.current = serialized;
            window.intellijExcalidraw?.sceneChanged(serialized);
          }, 350);
        }}
      />
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
