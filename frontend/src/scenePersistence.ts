export type SceneSnapshot = {
  elements: readonly unknown[];
  appState: unknown;
  files: Record<string, unknown>;
};

export class ScenePersistenceScheduler {
  private pending: SceneSnapshot | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly delayMs: number,
    private readonly persist: (snapshot: SceneSnapshot, saveImmediately: boolean) => void
  ) {}

  schedule(snapshot: SceneSnapshot) {
    this.pending = snapshot;
    this.clearTimer();
    this.timer = setTimeout(() => this.flush(false), this.delayMs);
  }

  flush(saveImmediately: boolean): boolean {
    this.clearTimer();
    const snapshot = this.pending;
    this.pending = null;

    if (!snapshot) {
      return false;
    }

    this.persist(snapshot, saveImmediately);
    return true;
  }

  cancel() {
    this.clearTimer();
    this.pending = null;
  }

  private clearTimer() {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }
}
