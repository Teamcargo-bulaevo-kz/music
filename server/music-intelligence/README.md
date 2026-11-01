# NOVA Music Intelligence API

Small server-side Groq adapter. The Groq key stays on the server.

## Run

Requires Node.js 20+.

```sh
GROQ_API_KEY=... NOVA_CLIENT_TOKEN=... PORT=8787 npm start
```

- `GET /health`
- `POST /v1/analyze` with JSON `{"tracks":[{"key":"...","title":"...","artist":"...","album":"..."}]}`
- Maximum 20 tracks per request and 256 KiB request body.
- Configure `GROQ_API_KEY` and `NOVA_CLIENT_TOKEN` as deployment secrets.
- The Android build receives the HTTPS endpoint and client token through CI environment variables `MUSIC_INTELLIGENCE_URL` and `MUSIC_INTELLIGENCE_TOKEN`.

The client token is an access gate, not a substitute for provider-side rate limits: values embedded in an APK can be extracted. Deploy behind a platform with request throttling and monitor usage. Do not expose the Groq key to the Android client.

The server searches public web sources through Groq's browser_search tool. It receives track metadata only; it never receives or streams audio files. Mood scores are normalized to 100 as relative descriptive scores, not calibrated probabilities.
