# Lessons and operating notes

Hard-won details from building and running the AI Lab. Keep this current; it is
the first file to read when a session resumes.

## Repository layout

- `app/` — native Android app (Java/XML, no Gradle, no AndroidX).
- `bridge/lab_bridge.py` — dependency-free Python control plane on port 8787.
- `build.sh` — compiles, dexes, aligns and signs the APK with `aapt2`, JDK 17,
  `r8.jar` and `apksigner.jar` from `tools/`.
- `dist/AiLabCockpit-0.1.0.apk` — signed release APK.
- `signing/` — keystore and `keystore.properties`. **Back these up**; upgrades
  must use the same key.

## Android build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./build.sh
```

- Debian's `aapt2` cannot parse API 35 resources; keep using
  `tools/android-28.jar` (`-I`) even though `targetSdkVersion` is 35.
- There is no Gradle, AndroidX, adb or emulator on this host. A successful build
  is not runtime evidence. Only an install + launch + logcat on a phone proves
  the app works.
- Package: `io.github.herbalblitz.ailabcockpit`; version 0.1.0 (code 1);
  minSdk 26 / targetSdk 35.
- `tools/*.jar` are ~88 MB. Do not commit them to the public repo; document how
  to fetch them or add them as release assets.
- Check the APK with `aapt2 dump badging` and `apksigner verify --print-certs`.
  Current signer SHA-256: `36a38be151ba0a5d0be6b7e0ab98ce211b851dc5a69435b7cbbb45e73fdbe2c2`.

## Bridge

- Config: `~/.ai-lab-cockpit/bridge.json` (the token is secret, mode 600).
- Start: `python3 bridge/lab_bridge.py` (defaults to port 8787).
- `/health` is intentionally unauthenticated. Everything under `/api/` needs
  `Authorization: Bearer <token>`; a missing or wrong token must return 401.
- Endpoints: `GET /api/lab/status`, `GET /api/templates`, `GET /api/alerts`,
  `POST /api/chat`, `GET|POST /api/experiments`,
  `GET /api/experiments/<id>`, `.../log`, `POST .../cancel`, and the
  `/api/hermes/*` proxy.
- Experiments are arbitrary shell commands run by the bridge user. Treat the
  token as full host access. GPU jobs use SSH (`gpu.ssh_host`, key auth) and
  fail clearly when unconfigured; there is no fake success path.
- Jobs persist in `jobs.json`; logs live next to it. `max_parallel` defaults to 1.
- The `stack` probe checks Ollama `/api/tags`, vLLM `/v1/models`, the OpenHands
  URL, and the AI-Scientist-v2 / Evolver / OpenHands directories. Results are
  cached for 20 s so the dashboard does not stall.
- Test auth with 401, then health, chat, a CPU smoke job, log streaming, cancel
  and `/api/alerts`. A 200 from `/health` alone proves nothing about Hermes.
- `/api/lab/status` must stay 200 when Hermes is down. The first implementation
  returned 502 because `Hermes.health()` let `HermesError` escape; fixed by
  returning `{ok:false,error}`.
- `/api/models` lists Ollama tags and vLLM models; `/api/models/chat` proxies a
  real local chat and must fail honestly when the engine is down.
- `/api/skills` reads promoted skills from `~/AI-Lab/skills`; `/api/chat` with
  `"skill": "lab-operator"` injects the compact runtime file. Full SKILL.md is
  too large for the current Hermes gpt-oss budget: Hermes already sends ~6.7k
  prompt tokens, and adding the full skill triggers "conversation has grown too
  large". Keep `SKILL.compact.md` for runtime, full file for evaluation.

## Hermes Agent

- Use the source clone at `/root/workspace/hermes-agent`, launcher
  `./.hermes/bin/hermes`. The older installed copy under `/root/.hermes/hermes-agent`
  had a real `IndentationError`; do not trust its version banner.
- Gateway: `127.0.0.1:8642`; API key in `/root/.hermes/.env` as
  `API_SERVER_KEY`. Never print or commit it.
- Source build reports `0.22.7+195.g1fcc57e`; the gateway health endpoint may
  report `0.22.7`.
- Real checks used: `POST /v1/runs`, `POST /v1/chat/completions`, and
  `GET /health/detailed`.

## Cloudflare free AI

- Two paths: Workers AI's OpenAI-compatible endpoint, optionally through AI
  Gateway with the `cf-aig-gateway-id` header.
- Account ID, token and gateway ID live in `/root/.hermes/.env`; profiles are
  switched with `/root/workspace/ai-lab/bin/lab-model` (`cloudflare`,
  `cloudflare-gw`) and `/root/workspace/ai-lab/bin/lab-emrg-model`.
- Tool calling was verified on
  `@cf/meta/llama-3.3-70b-instruct-fp8-fast`; `@cf/openai/gpt-oss-120b` works
  for plain generation.
- EMRG's `emrg/server/llm.py` still needs the AI Gateway header wired in; do not
  claim it is integrated until a real EMRG request succeeds.

## EMRG

- Target repo `herbalblitz/emrg`, source `/root/workspace/emrg`.
- Daemon/TUI work; a real Groq test returned `OK`.
- Full suite: 6061 passed, 22 failed, 38 skipped, 44 errors — mostly git
  environment issues. Read the failures; do not report a clean suite.
- Fixed a real bug in `emrg/server/daemon.py`:
  `Path.is_dir(follow_symlinks=False)` does not exist. That fix is uncommitted.
- Finish EMRG before moving attention fully to the phone app.

## State at the time of writing

- Done: EMRG run, Hermes source gateway run, Cloudflare direct test, bridge
  runtime tests (health/401/chat/CPU job/timeout/GPU-unconfigured/alerts/stack/
  templates/models/skills), APK build + sign + static verification.
- App has five tabs: Lab, Chat, Runs, Stack, Setup. Chat sends the promoted
  `lab-operator` skill; Stack launches configured bridge templates.
- Open: stack probe UI, custom templates in the app, public GitHub push,
  phone install/logcat, Cloudflare Tunnel/Tailscale, EMRG gateway header.
- Blocked: no adb/emulator/KVM/phone, no cloudflared/Tailscale, and no
  AI-Scientist-v2/Evolver/OpenHands/Ollama/vLLM installed. Only detection is
  testable until those exist.

## Secrets and safety

- Never print `/root/.hermes/.env`, `bridge.json`, `keystore.properties` or the
  keystore. Do not put tokens in commands that end up in logs.
- The bridge token equals remote shell access. Use TLS (Cloudflare Tunnel or
  Tailscale) outside a trusted LAN.
- Keep the phone's token in Android Keystore (`SecureStore`), not in plain
  SharedPreferences.
