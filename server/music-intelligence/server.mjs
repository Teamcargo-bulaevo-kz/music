import http from "node:http";

const PORT = Number(process.env.PORT || 8787);
const GROQ_API_KEY = process.env.GROQ_API_KEY || "";
const NOVA_CLIENT_TOKEN = process.env.NOVA_CLIENT_TOKEN || "";
const MODEL = process.env.GROQ_MODEL || "openai/gpt-oss-120b";
const MAX_BODY = 256 * 1024;
const MOODS = ["energetic", "calm", "happy", "sad", "romantic", "dance", "focus", "epic", "dark"];

function json(res, status, value) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(value));
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", chunk => {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY) {
        reject(new Error("BODY_TOO_LARGE"));
        req.destroy();
      }
    });
    req.on("end", () => {
      try { resolve(JSON.parse(body || "{}")); } catch { reject(new Error("INVALID_JSON")); }
    });
    req.on("error", reject);
  });
}
function extractJson(text) {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) throw new Error("MODEL_RETURNED_NO_JSON");
  return JSON.parse(text.slice(start, end + 1));
}
function normalizeResult(item, allowedKeys) {
  if (!item || !allowedKeys.has(String(item.key))) return null;
  const rawScores = item.moodScores && typeof item.moodScores === "object" ? item.moodScores : {};
  const scores = Object.fromEntries(MOODS.map(m => [m, Math.max(0, Math.min(100, Math.round(Number(rawScores[m]) || 0)))]));
  const total = Object.values(scores).reduce((a, b) => a + b, 0);
  if (!total) return null;
  const normalized = Object.fromEntries(MOODS.map(m => [m, Math.round(scores[m] * 100 / total)]));
  const sum = Object.values(normalized).reduce((a, b) => a + b, 0);
  const top = MOODS.slice().sort((a, b) => normalized[b] - normalized[a]);
  normalized[top[0]] += 100 - sum;
  const mood = MOODS.includes(item.mood) ? item.mood : top[0];
  const energy = Math.max(1, Math.min(5, Math.round(Number(item.energy) || 3)));
  const confidence = Math.max(0, Math.min(100, Math.round(Number(item.confidence) || 0)));
  const genre = String(item.genre || "other").toLowerCase().slice(0, 32);
  return {
    key: String(item.key), genre, genreLabel: String(item.genreLabel || genre).slice(0, 64),
    mood, energy, confidence, moodScores: normalized,
    description: String(item.description || "").slice(0, 240),
    tempo: String(item.tempo || "").slice(0, 48),
    sources: Array.isArray(item.sources) ? item.sources.slice(0, 4).map(String) : []
  };
}

async function analyze(tracks) {
  if (!GROQ_API_KEY) throw new Error("SERVER_GROQ_KEY_MISSING");
  const prompt = [
    "Ты музыкальный исследователь. Используй browser_search для поиска сведений о каждой композиции.",
    "Сопоставляй по названию, исполнителю и альбому. Не выдумывай факты; если совпадение сомнительно, confidence должен быть ниже 55.",
    "Верни только JSON-объект вида { results: [...] }, без Markdown.",
    "Для каждого входного key верни key, genre (ключ: pop, rock, hiphop, electronic, classical, jazz, chanson, folk, metal, rnb, indie, country, soundtrack, reggae, blues, other), genreLabel, mood (один из ключей), energy (1..5), confidence (0..100), moodScores (все девять ключей, сумма около 100), description, tempo, sources (URL или названия найденных источников).",
    "moodScores — относительное распределение выраженности девяти настроений, не вероятности.",
    "Не анализируй аудиофайлы: у тебя только метаданные. Учитывай контекст песни и известные сведения о звучании.",
    "Композиции:"
  ].join("\n") + "\n" + JSON.stringify(tracks);
  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${GROQ_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: MODEL,
      messages: [
        { role: "system", content: "Отвечай строго валидным JSON. Не добавляй пояснений вне JSON." },
        { role: "user", content: prompt }
      ],
      tools: [{ type: "browser_search" }],
      tool_choice: "required",
      temperature: 0.1,
      max_completion_tokens: 6000
    }),
    signal: AbortSignal.timeout(90000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`GROQ_HTTP_${response.status}: ${JSON.stringify(data).slice(0, 500)}`);
  const content = data.choices?.[0]?.message?.content || "";
  const parsed = extractJson(content);
  const allowed = new Set(tracks.map(t => String(t.key)));
  const results = Array.isArray(parsed.results) ? parsed.results.map(x => normalizeResult(x, allowed)).filter(Boolean) : [];
  return { results, model: MODEL };
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") return json(res, 200, { ok: true, service: "nova-music-intelligence" });
  if (req.method !== "POST" || req.url !== "/v1/analyze") return json(res, 404, { error: "NOT_FOUND" });
  if (NOVA_CLIENT_TOKEN && req.headers.authorization !== `Bearer ${NOVA_CLIENT_TOKEN}`) return json(res, 401, { error: "UNAUTHORIZED" });
  try {
    const body = await readBody(req);
    if (!Array.isArray(body.tracks) || body.tracks.length < 1 || body.tracks.length > 20) {
      return json(res, 400, { error: "TRACKS_MUST_CONTAIN_1_TO_20_ITEMS" });
    }
    const tracks = body.tracks.map(t => ({
      key: String(t.key || "").slice(0, 160),
      title: String(t.title || "").slice(0, 180),
      artist: String(t.artist || "").slice(0, 180),
      album: String(t.album || "").slice(0, 180)
    }));
    if (tracks.some(t => !t.key || !t.title)) return json(res, 400, { error: "TRACK_KEY_AND_TITLE_REQUIRED" });
    return json(res, 200, await analyze(tracks));
  } catch (error) {
    const message = String(error?.message || "UNKNOWN_ERROR").slice(0, 600);
    const status = message === "INVALID_JSON" ? 400 : message === "BODY_TOO_LARGE" ? 413 : 502;
    return json(res, status, { error: message });
  }
});

server.listen(PORT, "0.0.0.0", () => console.log(`NOVA Music Intelligence listening on ${PORT}`));
