# AI-Lab Cockpit

One Android app to control an autonomous AI lab: **Hermes Agent** for planning
and tool use, **AI-Scientist-v2** for research, **Evolver** for
self-improvement, **OpenHands** for coding, and **Ollama / vLLM** for model
serving. The phone talks to a small bridge on the lab host; the bridge proxies
Hermes and runs real experiment jobs locally or over SSH on a GPU host.

This is a native Java/XML app built without Gradle or AndroidX. It is not a mock
or a demo: every screen is wired to the bridge API, and experiments run real
shell commands with real logs and exit codes.

## Screens

| Tab | What it does |
|---|---|
| **Lab** | Host CPU/RAM/disk, Hermes gateway, lab-stack readiness, experiment queue, GPU target |
| **Chat** | Hermes chat, with the promoted `lab-operator` skill injected when available |
| **Runs** | Queue CPU or GPU experiments, watch live logs, cancel, load templates |
| **Stack** | Per-component status and launch actions for Hermes, AI-Scientist-v2, Evolver, OpenHands, Ollama/vLLM |
| **Setup** | Bridge URL + token (Keystore), Hermes model, completion alerts |

The app also polls finished experiments and posts completion/failure
notifications, including while the app is closed.

## Architecture

```text
Android app ── HTTP + bearer token ──> lab_bridge.py :8787
                                           ├── Hermes gateway :8642
                                           ├── experiment queue (CPU / SSH GPU)
                                           ├── Ollama :11434 / vLLM :8000
                                           └── ~/AI-Lab/skills (lab-operator)
```

- `app/` — Android sources and resources.
- `bridge/lab_bridge.py` — standard-library Python bridge.
- `docs/LESSONS.md` — operating notes and pitfalls from real runs.
- `build.sh` — compile, dex, align and sign the APK.
- `dist/` — signed APK and SHA-256.

## Build

Requirements:

- JDK 17
- `aapt2`, `zipalign`
- Android platform `android-28.jar` (used for resource linking even though the
  app targets SDK 35; Debian's `aapt2` cannot parse the API 35 resource table)
- `r8.jar` and `apksigner.jar`
- A signing keystore in `signing/` or let the script create one

Put the jars in `tools/`:

```text
tools/android-28.jar
tools/r8.jar
tools/apksigner.jar
```

Then:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./build.sh
```

Output: `dist/AiLabCockpit-0.1.0.apk` plus `.idsig` and `.sha256`.

Verify:

```bash
aapt2 dump badging dist/AiLabCockpit-0.1.0.apk
apksigner verify --print-certs dist/AiLabCockpit-0.1.0.apk
sha256sum dist/AiLabCockpit-0.1.0.apk
```

Package `io.github.herbalblitz.ailabcockpit`, version 0.1.0 (code 1), minSdk 26,
targetSdk 35.

## Bridge setup

```bash
python3 bridge/lab_bridge.py --init
# edit ~/.ai-lab-cockpit/bridge.json
python3 bridge/lab_bridge.py
```

Minimal config:

```json
{
  "host": "0.0.0.0",
  "port": 8787,
  "token": "long-random-token",
  "hermes": {
    "base_url": "http://127.0.0.1:8642",
    "api_key": "from ~/.hermes/.env API_SERVER_KEY",
    "model": "hermes-agent"
  },
  "gpu": {"ssh_host": "", "ssh_user": "", "ssh_port": 22, "workdir": "~/ai-lab"},
  "stack": {
    "ollama_url": "http://127.0.0.1:11434",
    "vllm_url": "http://127.0.0.1:8000",
    "openhands_url": "http://127.0.0.1:3000",
    "ai_scientist_dir": "~/AI-Scientist-v2",
    "evolver_dir": "~/evolver",
    "openhands_dir": "~/OpenHands"
  },
  "templates": []
}
```

`templates` adds launch chips in **Runs** and **Stack**:

```json
{"name": "AI-Scientist smoke", "title": "Research smoke",
 "command": "cd ~/AI-Scientist-v2 && python3 launch_scientist_bfts.py ...",
 "target": "gpu", "timeout_sec": 86400}
```

`gpu.ssh_host` must be set for GPU jobs; otherwise the bridge returns a clear
"GPU target is not configured" error instead of pretending to run.

## API

All `/api/*` routes require `Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (no auth) |
| GET | `/api/lab/status` | host, Hermes, stack, queue, templates |
| GET | `/api/skills` | promoted skills from `~/AI-Lab/skills` |
| POST | `/api/chat` | Hermes chat; optional `skill` |
| POST | `/api/models/chat` | direct Ollama/vLLM chat |
| GET | `/api/models` | Ollama tags + vLLM model list |
| GET/POST | `/api/experiments` | list / queue jobs |
| GET | `/api/experiments/<id>` | job detail |
| GET | `/api/experiments/<id>/log` | plain-text log |
| POST | `/api/experiments/<id>/cancel` | stop a job |
| GET | `/api/alerts?since=` | finished jobs for notifications |
| GET | `/api/templates` | configured experiment templates |

The bridge token is equivalent to shell access on the host. Use Tailscale or a
Cloudflare Tunnel outside a trusted LAN. The app stores the token with Android
Keystore, not plain preferences.

## AI skill

The promoted `lab-operator` skill lives in `~/AI-Lab/skills/lab-operator/v1/`.
The bridge reads its compact runtime variant and injects it into chat when the
app sends `"skill": "lab-operator"`. The full skill and the 8-case evaluation are
in `~/AI-Lab`; see `docs/LESSONS.md` for the context-budget finding that led to
the compact variant.

## Runtime status

This repository contains a signed APK, but runtime installation has **not** been
verified on a phone in this environment: there is no adb, emulator, KVM or
attached device here. Build success is not the same as a working app. See
`docs/LESSONS.md` for the exact evidence that is still missing.

## License

MIT
