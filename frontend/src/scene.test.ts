import { describe, expect, it } from "vitest";
import { parseScene } from "./scene";

describe("parseScene", () => {
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
});
