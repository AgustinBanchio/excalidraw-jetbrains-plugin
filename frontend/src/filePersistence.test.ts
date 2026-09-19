import { describe, expect, it, vi } from "vitest";
import { FilePersistence } from "./filePersistence";

describe("image persistence", () => {
  it.each(["svg", "png"] as const)("automatically saves changed %s images but leaves unchanged images alone", async (format) => {
    const encode = vi.fn().mockResolvedValue("edited-image");
    const publish = vi.fn();
    const persistence = new FilePersistence(encode, publish);
    persistence.reset(1, format);
    persistence.seed("original-scene", "original-image");
    await persistence.persist("original-scene", false);
    expect(publish).not.toHaveBeenCalled();
    await persistence.persist("edited-scene", false);
    await persistence.persist("edited-scene", false);
    expect(encode).toHaveBeenCalledTimes(1);
    expect(publish.mock.calls).toEqual([[{ revision: 1, scene: "edited-image" }, true]]);
  });

  it("keeps JSON edits on the normal IDE document save path", async () => {
    const publish = vi.fn();
    const persistence = new FilePersistence(async (json) => json, publish);
    persistence.reset(1, "json");
    await persistence.persist("edited-scene", false);
    expect(publish.mock.calls).toEqual([[{ revision: 1, scene: "edited-scene" }, false]]);
  });

  it("orders slow exports before the latest explicit save", async () => {
    let release!: (value: string) => void;
    const encode = vi.fn().mockImplementationOnce(() => new Promise<string>((resolve) => { release = resolve; }))
      .mockResolvedValueOnce("second-image");
    const publish = vi.fn();
    const persistence = new FilePersistence(encode, publish);
    persistence.reset(3, "png");
    const first = persistence.persist("first-scene", false);
    await Promise.resolve();
    const second = persistence.persist("second-scene", true);
    expect(encode).toHaveBeenCalledTimes(1);
    release("first-image");
    await Promise.all([first, second]);
    expect(publish.mock.calls).toEqual([
      [{ revision: 3, scene: "first-image" }, true],
      [{ revision: 3, scene: "second-image" }, true]
    ]);
  });

  it("rejects an old export after external reload without overwriting the new file", async () => {
    let release!: (value: string) => void;
    const publish = vi.fn();
    const persistence = new FilePersistence(() => new Promise((resolve) => { release = resolve; }), publish);
    persistence.reset(1, "svg");
    const old = persistence.persist("old", true);
    await Promise.resolve();
    persistence.reset(2, "svg");
    release("obsolete-image");
    await expect(old).rejects.toThrow("reloaded");
    expect(publish).not.toHaveBeenCalled();
  });

  it("retries failed exports and preserves original bytes for an unchanged file", async () => {
    const encode = vi.fn().mockRejectedValueOnce(new Error("render failed")).mockResolvedValue("new-image");
    const publish = vi.fn();
    const persistence = new FilePersistence(encode, publish);
    persistence.reset(1, "png");
    persistence.seed("original-scene", "original-image");
    await persistence.persist("original-scene", true);
    expect(encode).not.toHaveBeenCalled();
    await expect(persistence.persist("edited", true)).rejects.toThrow("render failed");
    await persistence.persist("edited", true);
    expect(publish.mock.calls).toEqual([
      [{ revision: 1, scene: "original-image" }, true],
      [{ revision: 1, scene: "new-image" }, true]
    ]);
  });
});
