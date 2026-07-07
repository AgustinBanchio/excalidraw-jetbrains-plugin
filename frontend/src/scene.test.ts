import { beforeEach, describe, expect, it, vi } from "vitest";

const { restoreMock } = vi.hoisted(() => ({
  restoreMock: vi.fn(
    (data: { elements?: unknown[]; appState?: Record<string, unknown>; files?: Record<string, unknown> }) => ({
      elements: data.elements ?? [],
      appState: data.appState ?? {},
      files: data.files ?? {}
    })
  )
}));

vi.mock("@excalidraw/excalidraw", () => ({
  restore: restoreMock
}));

import { normalizePersistedImageStatuses, parseScene } from "./scene";

describe("parseScene", () => {
  beforeEach(() => {
    restoreMock.mockClear();
  });

  it("initializes an empty file with the preferred theme", () => {
    expect(parseScene("", "dark")).toMatchObject({
      type: "excalidraw",
      elements: [],
      appState: { theme: "dark" },
      files: {}
    });
  });

  it("preserves a saved theme", () => {
    const scene = parseScene(
      JSON.stringify({
        type: "excalidraw",
        elements: [],
        appState: { theme: "light" },
        files: {}
      }),
      "dark"
    );

    expect(scene.appState?.theme).toBe("light");
  });

  it.each([
    ["an array root", "[]"],
    ["a different file type", JSON.stringify({ type: "other", elements: [] })],
    ["a missing elements array", JSON.stringify({ type: "excalidraw" })],
    ["an invalid appState", JSON.stringify({ type: "excalidraw", elements: [], appState: [] })],
    ["invalid files", JSON.stringify({ type: "excalidraw", elements: [], files: [] })]
  ])("rejects %s", (_description, contents) => {
    expect(() => parseScene(contents, "light")).toThrow();
  });

  it("removes transient editor state", () => {
    const scene = parseScene(
      JSON.stringify({
        type: "excalidraw",
        elements: [],
        appState: {
          theme: "dark",
          selectedElementIds: { element: true },
          collaborators: { user: true }
        },
        files: {}
      }),
      "light"
    );

    expect(scene.appState).toEqual({ theme: "dark" });
  });

  it("restores persisted files through Excalidraw before returning the scene", () => {
    const files = {
      image: {
        mimeType: "image/png",
        id: "image",
        dataURL: "data:image/png;base64,aaa",
        created: 1
      }
    };

    const scene = parseScene(
      JSON.stringify({
        type: "excalidraw",
        elements: [],
        appState: {},
        files
      }),
      "light"
    );

    expect(restoreMock).toHaveBeenCalledWith(
      {
        elements: [],
        appState: {},
        files
      },
      { theme: "light" },
      null
    );
    expect(scene.files).toEqual(files);
  });

  it("marks persisted image elements as saved when their file data exists", () => {
    const scene = parseScene(
      JSON.stringify({
        type: "excalidraw",
        elements: [
          {
            id: "image-element",
            type: "image",
            status: "pending",
            fileId: "image-file"
          }
        ],
        appState: {},
        files: {
          "image-file": {
            id: "image-file",
            mimeType: "image/png",
            dataURL: "data:image/png;base64,aaa",
            created: 1
          }
        }
      }),
      "light"
    );

    expect(scene.elements?.[0]).toMatchObject({ status: "saved" });
  });

  it("leaves image element status alone when file data is missing", () => {
    expect(
      normalizePersistedImageStatuses(
        [
          {
            id: "image-element",
            type: "image",
            status: "pending",
            fileId: "missing-file"
          }
        ],
        {}
      )[0]
    ).toMatchObject({ status: "pending" });
  });

  it("leaves image element status alone when persisted file data has no data URL", () => {
    expect(
      normalizePersistedImageStatuses(
        [
          {
            id: "image-element",
            type: "image",
            status: "pending",
            fileId: "image-file"
          }
        ],
        {
          "image-file": {
            id: "image-file",
            mimeType: "image/png"
          }
        }
      )[0]
    ).toMatchObject({ status: "pending" });
  });
});
