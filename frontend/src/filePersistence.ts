import type { DrawingFormat } from "./fileCodec";
import type { SceneUpdate } from "./documentState";

/** Serializes async exports and invalidates work from an earlier load/revert. */
export class FilePersistence {
  private generation = 0;
  private tail: Promise<void> = Promise.resolve();
  private lastJson: string | null = null;
  private lastContents = "";
  private revision = 0;
  private format: DrawingFormat = "json";

  constructor(
    private readonly encode: (json: string, format: DrawingFormat) => Promise<string>,
    private readonly publish: (update: SceneUpdate, save: boolean) => void
  ) {}

  reset(revision: number, format: DrawingFormat) {
    this.generation++;
    this.revision = revision;
    this.format = format;
    this.lastJson = null;
    // Old exports may finish, but cannot publish into the new document.
    this.tail = Promise.resolve();
  }

  seed(json: string, contents: string) {
    this.lastJson = json;
    this.lastContents = contents;
  }

  persist(json: string, save: boolean): Promise<void> {
    const generation = this.generation;
    const revision = this.revision;
    const format = this.format;
    const operation = this.tail.then(async () => {
      if (generation !== this.generation) throw new Error("The drawing was reloaded before saving completed.");
      const changed = json !== this.lastJson;
      const contents = changed ? await this.encode(json, format) : this.lastContents;
      if (generation !== this.generation) throw new Error("The drawing was reloaded before saving completed.");
      if (changed || save) this.publish({ revision, scene: contents }, save);
      this.lastJson = json;
      this.lastContents = contents;
    });
    // A failed export must not poison later retries.
    this.tail = operation.catch(() => {});
    return operation;
  }
}
