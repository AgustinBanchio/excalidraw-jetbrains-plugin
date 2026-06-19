export type Theme = "light" | "dark";

export type Scene = {
  type?: string;
  version?: number;
  source?: string;
  elements?: readonly unknown[];
  appState?: Record<string, unknown>;
  files?: Record<string, unknown>;
};

export const EMPTY_SCENE: Scene = {
  type: "excalidraw",
  version: 2,
  source: "https://github.com/agustinbanchio/excalidraw-jetbrains-plugin",
  elements: [],
  appState: {},
  files: {}
};

export function parseScene(contents: string, preferredTheme: Theme): Scene {
  if (!contents.trim()) {
    return {
      ...EMPTY_SCENE,
      appState: { theme: preferredTheme }
    };
  }

  const parsed = JSON.parse(contents) as Scene;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("The file must contain an Excalidraw JSON object.");
  }
  if (parsed.type !== undefined && parsed.type !== "excalidraw") {
    throw new Error("The file is not an Excalidraw drawing.");
  }
  if (!Array.isArray(parsed.elements)) {
    throw new Error("The drawing does not contain a valid elements array.");
  }
  if (parsed.appState !== undefined && !isRecord(parsed.appState)) {
    throw new Error("The drawing contains an invalid appState object.");
  }
  if (parsed.files !== undefined && !isRecord(parsed.files)) {
    throw new Error("The drawing contains an invalid files object.");
  }

  const appState = sanitizeAppState(parsed.appState ?? {});
  return {
    ...EMPTY_SCENE,
    ...parsed,
    elements: parsed.elements,
    appState: {
      theme: preferredTheme,
      ...appState
    },
    files: parsed.files ?? {}
  };
}

export function getTheme(appState: unknown): Theme {
  return (appState as Record<string, unknown> | undefined)?.theme === "dark" ? "dark" : "light";
}

export function sanitizeAppState(appState: Record<string, unknown>): Record<string, unknown> {
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
