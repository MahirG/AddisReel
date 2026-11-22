/**
 * Turn one copied, labeled movie-and-episode block into validated draft form values.
 * This function is deliberately standalone: the Worker serves the exact same
 * function to Content Studio, while Node tests import it directly.
 * Never trust clipboard text for licensing or publication authorization.
 */
export function parseMovieFields(input) {
  if (typeof input !== "string" || !input.trim()) throw new Error("Copy movie details first.");
  if (input.length > 20000) throw new Error("Copied text is too long (20 KB maximum).");
  const raw = input.trim();
  const fields = {};
  if (raw[0] === "{") {
    let item;
    try { item = JSON.parse(raw); } catch { throw new Error("Invalid movie JSON."); }
    if (!item || typeof item !== "object" || Array.isArray(item))
      throw new Error("A movie JSON object is required.");
    const series = item.series && typeof item.series === "object" ? item.series : item;
    const episode = item.episode && typeof item.episode === "object" ? item.episode : item;
    Object.assign(fields, {
      slug: series.slug ?? series.seriesSlug ?? series.id,
      title: series.title ?? series.movieTitle,
      genre: series.genre,
      description: series.description ?? series.synopsis,
      sortOrder: series.sortOrder,
      episodeTitle: episode.episodeTitle ?? (item.episode ? episode.title : undefined),
      episodeNumber: episode.episodeNumber ?? (item.episode ? episode.number : undefined),
      durationSeconds: episode.durationSeconds
    });
  } else {
    const keys = {
      "series slug":"slug","episode series slug":"episodeSlug","series id":"slug","slug":"slug",
      "movie title":"title","series title":"title","title":"title",
      "genre":"genre","category":"genre","description":"description","synopsis":"description",
      "sort order":"sortOrder","sort":"sortOrder",
      "episode number":"episodeNumber","episode title":"episodeTitle",
      "duration seconds":"durationSeconds","duration (seconds)":"durationSeconds",
      "duration":"durationSeconds"
    };
    let continuation = "";
    for (const original of raw.replace(/\r\n?/g,"\n").split("\n")) {
      let line = original.trim().replace(/^[-•]\s*/,"");
      line = line.replace(/^\*\*([^*:]+):\*\*\s*/,"$1: ").replace(/^\*\*([^*]+)\*\*\s*:/,"$1:");
      const match = /^([^:]{1,65}):\s*(.*)$/.exec(line);
      const key = match ? keys[match[1].toLowerCase().trim().replace(/\s+/g," ")] : undefined;
      if (key) {
        fields[key] = match[2].trim();
        continuation = key === "description" ? key : "";
      } else if (!line || match) {
        // Unknown labels (for example Status or Distribution rights) must
        // never be swallowed into a multiline description.
        continuation = "";
      } else if (continuation) {
        fields[continuation] += " " + line;
      }
    }
  }
  const text = (value, max, label, required) => {
    const result = typeof value === "string" ? value.trim() : "";
    if (required && !result) throw new Error("Missing " + label + ". Copy the complete movie details.");
    if (result.length > max) throw new Error(label + " exceeds " + max + " characters.");
    return result;
  };
  const title = text(fields.title, 150, "title", true);
  const genre = text(fields.genre, 80, "genre", true);
  const description = text(fields.description, 2000, "description", true);
  let slug = fields.slug ?? fields.episodeSlug;
  if (slug != null && typeof slug !== "string") throw new Error("Invalid series slug.");
  slug = (slug || title.toLowerCase().normalize("NFKD")
    .replace(/[\u0300-\u036f]/g,"")
    .replace(/[^a-z0-9]+/g,"-")
    .replace(/^-+|-+$/g,"")
    .slice(0,64)).trim();
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(slug))
    throw new Error("Series slug must use lowercase letters, numbers and hyphens.");
  if (fields.episodeSlug && fields.episodeSlug !== slug)
    throw new Error("Series and episode slugs do not match.");
  const number = (value, defaultValue, max, label) => {
    if (value === undefined || value === null || String(value).trim() === "") return defaultValue;
    const n = Number(value);
    if (!Number.isInteger(n) || n < 0 || n > max)
      throw new Error("Invalid " + label + ".");
    return n;
  };
  const episodeNumber = number(fields.episodeNumber, 1, 10000, "episode number");
  if (episodeNumber < 1) throw new Error("Episode number must start at 1.");
  const durationSeconds = number(fields.durationSeconds, 0, 360000, "duration seconds");
  const sortOrder = number(fields.sortOrder, 0, 100000, "sort order");
  const episodeTitle = text(fields.episodeTitle || "Episode " + episodeNumber, 150, "episode title", true);
  return {slug, title, genre, description, sortOrder, episodeTitle, episodeNumber, durationSeconds,
    status: "draft", licensed: false};
}
