import test from "node:test";
import assert from "node:assert/strict";
import { parseMovieFields } from "../importer.js";

const block = [
  "Series slug: the-last-coffee-ceremony",
  "Title: The Last Coffee Ceremony",
  "Genre: Family",
  "Description: Three siblings reunite at their childhood home after receiving an unexpected message from their grandmother.",
  "Sort order: 3",
  "Status: published",
  "Distribution rights verified: yes",
  "",
  "Episode series slug: the-last-coffee-ceremony",
  "Episode number: 1",
  "Episode title: Homecoming",
  "Duration seconds: 0",
  "Status: published",
  "Distribution rights verified: yes"
].join("\n");

test("parses the existing ChatGPT copy-all format into a complete draft", () => {
  assert.deepEqual(parseMovieFields(block), {
    slug: "the-last-coffee-ceremony",
    title: "The Last Coffee Ceremony",
    genre: "Family",
    description: "Three siblings reunite at their childhood home after receiving an unexpected message from their grandmother.",
    sortOrder: 3, episodeTitle: "Homecoming", episodeNumber: 1, durationSeconds: 0,
    status: "draft", licensed: false
  });
});

test("supports markdown-formatted labels and multiline description", () => {
  const draft = parseMovieFields([
    "**Title:** The Last Coffee Ceremony",
    "**Genre:** Family",
    "**Description:** The family gathers.",
    "A secret unfolds.",
    "Episode title: Homecoming"
  ].join("\n"));
  assert.equal(draft.slug, "the-last-coffee-ceremony");
  assert.equal(draft.description, "The family gathers. A secret unfolds.");
  assert.equal(draft.episodeTitle, "Homecoming");
});

test("supports compact structured JSON with a series and an episode", () => {
  const movie = parseMovieFields(JSON.stringify({
    series: { slug: "new-story", title: "New Story", genre: "Drama", description: "An original story." },
    episode: { number: 2, title: "The Decision", durationSeconds: 125 },
    status: "published", licensed: true
  }));
  assert.equal(movie.episodeNumber, 2);
  assert.equal(movie.episodeTitle, "The Decision");
  assert.equal(movie.status, "draft");
  assert.equal(movie.licensed, false);
});

test("rejects incomplete copied text", () => {
  assert.throws(() => parseMovieFields("Title: Hello"), /Missing genre/);
  assert.throws(() => parseMovieFields(""), /Copy movie details first/);
});

test("rejects mismatched episode slug and unsafe IDs", () => {
  assert.throws(() => parseMovieFields(block.replace("Episode series slug: the-last-coffee-ceremony",
    "Episode series slug: someone-else")), /do not match/);
  assert.throws(() => parseMovieFields(block.replace("Series slug: the-last-coffee-ceremony",
    "Series slug: ../../admin")), /slug/);
});

test("rejects very long or invalid numeric input", () => {
  assert.throws(() => parseMovieFields(block.replace("Duration seconds: 0","Duration seconds: -12")), /Invalid duration/);
  assert.throws(() => parseMovieFields(block.replace("Episode number: 1","Episode number: 0")), /must start at 1/);
  assert.throws(() => parseMovieFields("x".repeat(20001)), /too long/);
});
