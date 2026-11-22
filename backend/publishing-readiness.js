/**
 * Advisory preflight for AddisReel Studio. This does not authorize publication:
 * the Worker must independently enforce licensing, publication and media checks.
 */
export function publishingReadiness(series, episode, options = {}) {
  const issues = [];
  const nonempty = value => typeof value === "string" && value.trim().length > 0;
  if (!series || typeof series !== "object") return {ready:false,issues:["Select a series."]};
  if (!nonempty(series.title)) issues.push("Add a movie title.");
  if (!nonempty(series.genre)) issues.push("Choose a genre.");
  if (!nonempty(series.description)) issues.push("Add a synopsis.");
  if (series.licensed !== true && series.licensed !== 1) issues.push("Verify distribution rights for the series.");
  if (options.posterPresent !== true) issues.push("Upload and verify the movie poster.");
  if (!episode || typeof episode !== "object") issues.push("Select an episode.");
  else {
    if (!Number.isInteger(Number(episode.number)) || Number(episode.number) < 1) issues.push("Enter a valid episode number.");
    if (!nonempty(episode.title)) issues.push("Add an episode title.");
    if (episode.licensed !== true && episode.licensed !== 1) issues.push("Verify distribution rights for this episode.");
    if (options.hlsMasterPresent !== true) issues.push("Upload a valid master.m3u8 playlist.");
  }
  return {ready:issues.length===0,issues};
}
