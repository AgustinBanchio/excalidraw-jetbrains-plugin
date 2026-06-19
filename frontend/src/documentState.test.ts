import { describe, expect, it } from "vitest";
import { EditorDocumentState, encodeSceneUpdate } from "./documentState";

describe("EditorDocumentState", () => {
  it("does not expose stale content while a document is loading", () => {
    const state = new EditorDocumentState();
    state.beginLoad(1);
    state.completeLoad("first");
    state.updateScene("edited");

    state.beginLoad(2);

    expect(state.currentUpdate()).toBeNull();
  });

  it("keeps malformed documents unsaveable through the scene bridge", () => {
    const state = new EditorDocumentState();
    state.beginLoad(4);

    expect(state.currentUpdate()).toBeNull();
  });

  it("associates scene updates with the loaded document revision", () => {
    const state = new EditorDocumentState();
    state.beginLoad(7);
    state.completeLoad("original");
    state.updateScene("updated");

    expect(encodeSceneUpdate(state.currentUpdate()!)).toBe("7\nupdated");
  });
});
