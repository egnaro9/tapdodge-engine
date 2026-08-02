// Minimal static server for the assembled site. No dependencies, no magic:
// what deploy_site.sh wrote into .site/ is exactly what the browser gets.
// All request parsing lives INSIDE the try — a malformed percent-escape used to
// throw outside it, kill the process, and fail every remaining test with
// ERR_CONNECTION_REFUSED (cold-critic finding).
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("./.site", import.meta.url));
const MIME = {
  ".html": "text/html", ".js": "text/javascript", ".css": "text/css",
  ".ogg": "audio/ogg", ".png": "image/png", ".json": "application/json",
};

createServer(async (req, res) => {
  try {
    const path = normalize(decodeURIComponent(new URL(req.url, "http://x").pathname));
    const file = join(ROOT, path === "/" ? "index.html" : path);
    if (file !== ROOT && !file.startsWith(ROOT + sep)) { res.writeHead(403).end(); return; }
    const body = await readFile(file);
    res.writeHead(200, { "content-type": MIME[extname(file)] || "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
}).listen(4173, "127.0.0.1");
