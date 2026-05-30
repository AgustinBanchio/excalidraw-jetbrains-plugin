import React from "react";
import ReactDOM from "react-dom/client";
import { Excalidraw, serializeAsJSON } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import "./styles.css";

type Bridge = {
  ready: (payload: string) => void;
  sceneChanged: (payload: string) => void;
  save: (payload: string) => void;
};

declare global {
  interface Window {
    intellijExcalidraw?: Bridge;
    excalidrawPlugin?: {
      loadFile: (contents: string) => void;
    };
  }
}

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

function parseScene(contents: string): Scene {
  if (!contents.trim()) {
    return EMPTY_SCENE;
  }

  const parsed = JSON.parse(contents) as Scene;
  return {
    ...EMPTY_SCENE,
    ...parsed,
    elements: Array.isArray(parsed.elements) ? parsed.elements : [],
    appState: sanitizeAppState(parsed.appState ?? {}),
    files: parsed.files ?? {}
  };
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
  const [api, setApi] = React.useState<any>(null);
  const lastSerialized = React.useRef<string>("");
  const latestSerialized = React.useRef<string>(JSON.stringify(EMPTY_SCENE, null, 2));
  const pendingTimer = React.useRef<number | undefined>(undefined);

  React.useEffect(() => {
    window.excalidrawPlugin = {
      loadFile(contents: string) {
        try {
          const scene = parseScene(contents);
          const nextData = {
            elements: scene.elements,
            appState: scene.appState,
            files: scene.files
          };

          setInitialData(nextData);
          api?.updateScene(nextData);
          lastSerialized.current = JSON.stringify(scene, null, 2);
          latestSerialized.current = lastSerialized.current;
        } catch (error) {
          console.error("Failed to load .excalidraw file", error);
        }
      }
    };

    notifyWhenBridgeIsReady();
  }, [api]);

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
        excalidrawAPI={(nextApi: any) => setApi(nextApi)}
        onChange={(elements: readonly unknown[], appState: unknown, files: Record<string, unknown>) => {
          const serialized = serializeScene(elements, appState, files);
          latestSerialized.current = serialized;

          if (serialized === lastSerialized.current) {
            return;
          }

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
