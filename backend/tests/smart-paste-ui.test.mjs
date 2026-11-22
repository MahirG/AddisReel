import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { adminHtml } from "../admin.js";
import { parseMovieFields } from "../importer.js";

const movie = [
  "Series slug: the-last-coffee-ceremony",
  "Title: The Last Coffee Ceremony",
  "Genre: Family",
  "Description: Three siblings reunite for a traditional coffee ceremony.",
  "Episode series slug: the-last-coffee-ceremony",
  "Episode number: 1",
  "Episode title: Homecoming",
  "Duration seconds: 0"
].join("\n");
const template = {
  id: "the-last-coffee-ceremony",
  title: "The Last Coffee Ceremony",
  genre: "Family",
  description: "Original stored description",
  status: "draft",
  licensed: 0,
  sort_order: 3
};
const ep = {
  series_id: "the-last-coffee-ceremony",
  number: 1,
  title: "Homecoming",
  status: "draft",
  licensed: 0,
  duration_seconds: 0
};

function makeStudio(series, episodes) {
  const elements = new Map();
  const writes = [];
  const element = () => ({
    value: "", textContent: "", checked: false, disabled: false,
    files: [], append() {}, replaceChildren() {}, addEventListener() {},
    focus() {}, onclick: null
  });
  const el = id => {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  };
  const inline = adminHtml.match(/<script>\s*([\s\S]*?)<\/script>/);
  assert.ok(inline, "Content Studio must contain its inline controller");
  const fetch = async (path, options = {}) => {
    const method = options.method || "GET";
    if (method === "GET" && path === "/v1/admin/series") {
      return { ok: true, status: 200, async json() {return {series,episodes};} };
    }
    if (method === "POST") {
      const payload = JSON.parse(options.body);
      writes.push({path,payload});
      if (path.endsWith("/series")) series.push({...payload});
      if (path.endsWith("/episodes")) episodes.push({
        series_id: payload.seriesId, number: payload.number, title: payload.title
      });
      return { ok: true, status: 200, async json() { return {saved:true}; } };
    }
    throw Error("Unexpected admin request " + method + " " + path);
  };
  vm.runInNewContext(inline[1], {
    document: {getElementById:el,createElement:element},
    window: {scrollTo() {}},
    navigator: {},
    fetch, parseMovieFields, console
  });
  return {el,writes};
}

async function importAndSave(studio) {
  studio.el("token").value = "a-test-secret-that-never-contacts-cloudflare";
  await studio.el("connect").onclick();
  studio.el("importText").value = movie;
  studio.el("fillMovie").onclick();
  assert.equal(studio.el("saveImported").disabled, false);
  await studio.el("saveImported").onclick();
  return studio.el("importStatus").textContent;
}

test("Smart Paste recognizes existing saved series and episode without overwriting", async () => {
  const original = {...template,status:"published",licensed:1};
  const episode = {...ep,status:"published",licensed:1};
  const studio = makeStudio([original],[episode]);
  const status = await importAndSave(studio);
  assert.match(status, /Already saved/i);
  assert.equal(studio.writes.length, 0);
  assert.equal(original.status, "published");
  assert.equal(episode.licensed, 1);
});

test("Smart Paste adds a missing draft episode to an existing series only", async () => {
  const studio = makeStudio([{...template}],[]);
  const status = await importAndSave(studio);
  assert.match(status, /Reused the existing series/);
  assert.equal(studio.writes.length,1);
  assert.equal(studio.writes[0].path, "/v1/admin/episodes");
  assert.equal(studio.writes[0].payload.status, "draft");
  assert.equal(studio.writes[0].payload.licensed, false);
});

test("Smart Paste creates a new series and episode as drafts", async () => {
  const studio = makeStudio([],[]);
  const status = await importAndSave(studio);
  assert.match(status, /saved as drafts/);
  assert.deepEqual(studio.writes.map(w=>w.path), ["/v1/admin/series","/v1/admin/episodes"]);
  assert.ok(studio.writes.every(w=>w.payload.status==="draft" && w.payload.licensed===false));
});

test("Smart Paste refuses to overwrite a different series using the same slug", async () => {
  const studio = makeStudio([{...template,title:"Different existing title"}],[]);
  const status = await importAndSave(studio);
  assert.match(status, /different title already uses this series ID/i);
  assert.equal(studio.writes.length, 0);
});
