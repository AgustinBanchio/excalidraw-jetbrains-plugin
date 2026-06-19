export type SceneUpdate = {
  revision: number;
  scene: string;
};

export class EditorDocumentState {
  private revision = 0;
  private latestScene: string | null = null;

  beginLoad(revision: number) {
    this.revision = revision;
    this.latestScene = null;
  }

  completeLoad(contents: string) {
    this.latestScene = contents;
  }

  updateScene(scene: string) {
    if (this.latestScene !== null) {
      this.latestScene = scene;
    }
  }

  currentUpdate(): SceneUpdate | null {
    if (this.latestScene === null) {
      return null;
    }

    return { revision: this.revision, scene: this.latestScene };
  }
}

export function encodeSceneUpdate(update: SceneUpdate): string {
  return `${update.revision}\n${update.scene}`;
}
