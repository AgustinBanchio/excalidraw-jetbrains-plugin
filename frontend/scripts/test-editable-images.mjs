import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { createServer } from "vite";

const server = await createServer({ server: { host: "127.0.0.1", port: 0 } });
await server.listen();
const browser = await chromium.launch({ headless: true });
let page;
try {
  page = await browser.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.addInitScript(() => {
    window.testBridge = { transfers: {}, updates: [], saves: [], dirty: [] };
    window.intellijExcalidraw = {
      ready() {}, themeChanged() {}, browseLibrary() {}, openExternalLink() {}, scrollApplied() {},
      saveCurrentDocument() {},
      sceneDirty(revision) { window.testBridge.dirty.push(revision); },
      saveFinished(payload) { window.testBridge.saves.push(payload); },
      beginSceneTransfer(payload) {
        const [id, revision, save, chunks] = payload.split("\n");
        window.testBridge.transfers[id] = { revision: Number(revision), save, chunks: Number(chunks), content: "" };
      },
      appendSceneTransferChunk(payload) {
        const separator = payload.indexOf("\n");
        window.testBridge.transfers[payload.slice(0, separator)].content += payload.slice(separator + 1);
      },
      completeSceneTransfer(id) { window.testBridge.updates.push(window.testBridge.transfers[id]); }
    };
  });
  await page.goto(server.resolvedUrls.local[0]);
  await page.waitForFunction(() => !!window.excalidrawPlugin);

  for (const format of ["svg", "png"]) {
    const bytes = await readFile(new URL(`../../samples/vscode/example.excalidraw.${format}`, import.meta.url));
    const contents = bytes.toString(format === "png" ? "base64" : "utf8");
    const result = await page.evaluate(async ({ contents, format }) => {
      const { decodeDrawing, encodeDrawing, base64ToBytes } = await import("/src/fileCodec.ts");
      const scene = await decodeDrawing(contents, format, "light");
      const count = scene.elements.length;
      const fileIds = Object.keys(scene.files);
      scene.elements[0] = { ...scene.elements[0], x: scene.elements[0].x + 123 };
      const output = await encodeDrawing(JSON.stringify(scene), format);
      const reopened = await decodeDrawing(output, format, "light");
      const blob = new Blob([format === "png" ? base64ToBytes(output) : output], {
        type: format === "png" ? "image/png" : "image/svg+xml"
      });
      const image = new Image();
      const url = URL.createObjectURL(blob);
      image.src = url;
      await image.decode();
      URL.revokeObjectURL(url);
      return {
        count, reopenedCount: reopened.elements.length,
        expectedX: scene.elements[0].x, actualX: reopened.elements[0].x,
        fileIds, reopenedFileIds: Object.keys(reopened.files),
        changed: output !== contents,
        width: image.naturalWidth, height: image.naturalHeight
      };
    }, { contents, format });
    assert.ok(result.count > 0);
    assert.equal(result.reopenedCount, result.count);
    assert.equal(result.expectedX, result.actualX);
    assert.deepEqual(result.reopenedFileIds, result.fileIds);
    assert.ok(result.changed && result.width > 0 && result.height > 0);
    console.log(`${format}: upstream example loads, edits, renders and reopens (${result.count} elements, ${result.fileIds.length} embedded assets).`);

    // Exercise the real React editor and bridge, including unchanged saves and Ctrl+S.
    await page.evaluate(async ({ contents, format }) => {
      await window.excalidrawPlugin.loadFile(contents, "light", format === "svg" ? 10 : 20, format);
    }, { contents, format });
    await page.waitForTimeout(250);
    await page.evaluate(() => window.excalidrawPlugin.flushAndSave("unchanged"));
    const unedited = await page.evaluate(() => window.testBridge.updates.at(-1).content);
    assert.equal(unedited, contents, "An unchanged image should retain its original bytes");
    await page.getByTitle(/Rectangle/).click();
    await page.mouse.move(500, 420);
    await page.mouse.down();
    await page.mouse.move(600, 490, { steps: 4 });
    await page.mouse.up();
    await page.keyboard.press("Control+s");
    await page.waitForFunction(({ old, revision }) => window.testBridge.updates.some((update) => update.revision === revision && update.save === "1" && update.content !== old), { old: contents, revision: format === "svg" ? 10 : 20 });
    const saved = await page.evaluate(async ({ format, contents }) => {
      const { decodeDrawing } = await import("/src/fileCodec.ts");
      const updates = window.testBridge.updates.filter((update) => update.revision === (format === "svg" ? 10 : 20));
      const update = updates.filter((item) => item.save === "1").at(-1);
      const old = await decodeDrawing(contents, format, "light");
      const scene = await decodeDrawing(update.content, format, "light");
      return { oldCount: old.elements.length, newCount: scene.elements.length };
    }, { format, contents });
    assert.equal(saved.newCount, saved.oldCount + 1);
    console.log(`${format}: editor drawing and immediate Ctrl+S save the latest shape.`);
  }

  const edgeCases = await page.evaluate(async () => {
    const { decodeDrawing, encodeDrawing, bytesToBase64 } = await import("/src/fileCodec.ts");
    const outcomes = [];
    const renamed = [];
    const json = JSON.stringify({ type: "excalidraw", version: 2, elements: [], appState: {}, files: {} });
    for (const format of ["svg", "png"]) {
      const empty = await decodeDrawing("", format, "dark");
      const output = await encodeDrawing(JSON.stringify(empty), format);
      const reopened = await decodeDrawing(output, format, "light");
      outcomes.push(reopened.elements.length);
      const renamedScene = await decodeDrawing(format === "png" ? bytesToBase64(new TextEncoder().encode(json)) : json, format, "light");
      renamed.push((await decodeDrawing(await encodeDrawing(JSON.stringify(renamedScene), format), format, "light")).elements.length);
    }
    let rejected = false;
    try { await decodeDrawing('<svg xmlns="http://www.w3.org/2000/svg"/>', "svg", "light"); }
    catch { rejected = true; }
    const canvas = document.createElement("canvas");
    const ordinaryPng = canvas.toDataURL("image/png").split(",")[1];
    let pngRejected = false;
    try { await decodeDrawing(ordinaryPng, "png", "light"); } catch { pngRejected = true; }
    return { outcomes, renamed, rejected, pngRejected, jsonUnchanged: await encodeDrawing(json, "json") === json };
  });
  assert.deepEqual(edgeCases.outcomes, [0, 0]);
  assert.ok(edgeCases.rejected);
  assert.ok(edgeCases.pngRejected);
  assert.ok(edgeCases.jsonUnchanged);
  assert.deepEqual(edgeCases.renamed, [0, 0]);
  assert.deepEqual(errors, []);
  console.log("Empty and renamed drawings round-trip; ordinary images are rejected; JSON is unchanged; no browser errors.");
} catch (error) {
  console.error(await page?.evaluate(() => ({
    saves: window.testBridge.saves,
    dirty: window.testBridge.dirty,
    updates: window.testBridge.updates.map(({ revision, save, content }) => ({ revision, save, length: content.length })),
    alerts: [...document.querySelectorAll('[role="alert"]')].map((element) => element.textContent)
  })));
  await page?.screenshot({ path: "../.local/image-test-failure.png" });
  throw error;
} finally {
  await browser.close();
  await server.close();
}
