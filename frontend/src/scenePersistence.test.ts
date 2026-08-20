import { afterEach, describe, expect, it, vi } from "vitest";
import { ScenePersistenceScheduler, type SceneSnapshot } from "./scenePersistence";

const snapshot = (value: string): SceneSnapshot => ({
  elements: [value],
  appState: {},
  files: {}
});

describe("ScenePersistenceScheduler", () => {
  afterEach(() => vi.useRealTimers());

  it("coalesces rapid scene changes before doing expensive persistence work", () => {
    vi.useFakeTimers();
    const persist = vi.fn();
    const scheduler = new ScenePersistenceScheduler(250, persist);

    scheduler.schedule(snapshot("first"));
    scheduler.schedule(snapshot("latest"));
    vi.advanceTimersByTime(249);

    expect(persist).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);

    expect(persist).toHaveBeenCalledOnce();
    expect(persist).toHaveBeenCalledWith(snapshot("latest"), false);
  });

  it("flushes the latest scene immediately for explicit saves", () => {
    vi.useFakeTimers();
    const persist = vi.fn();
    const scheduler = new ScenePersistenceScheduler(250, persist);

    scheduler.schedule(snapshot("latest"));

    expect(scheduler.flush(true)).toBe(true);
    expect(persist).toHaveBeenCalledWith(snapshot("latest"), true);

    vi.runAllTimers();
    expect(persist).toHaveBeenCalledOnce();
  });

  it("can discard pending work when a different document is loaded", () => {
    vi.useFakeTimers();
    const persist = vi.fn();
    const scheduler = new ScenePersistenceScheduler(250, persist);

    scheduler.schedule(snapshot("stale"));
    scheduler.cancel();
    vi.runAllTimers();

    expect(persist).not.toHaveBeenCalled();
  });
});
