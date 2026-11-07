# Build and runtime evidence

## APK

- File: `dist/AiLabCockpit-0.1.0.apk`
- Size: 310 KB
- SHA-256: `2be5a8589421f7663ca1d598af9b1fb6c411d2bca918599d023369d876eb9710`
- Package: `io.github.herbalblitz.ailabcockpit`
- Version: `0.1.0` (versionCode 1)
- minSdk 26, targetSdk 35, compileSdk 28 (resource linking workaround)
- Launchable activity: `io.github.herbalblitz.ailabcockpit.MainActivity`
- Signature: APK Signature Scheme v2 + v3
- Signer SHA-256: `36a38be151ba0a5d0be6b7e0ab98ce211b851dc5a69435b7cbbb45e73fdbe2c2`

Build log: `docs/build.log`.

```text
== 1/7 compile resources
== 2/7 link resources + manifest
== 3/7 compile Java
== 4/7 dex
== 5/7 package + align
== 6/7 sign
== 7/7 verify
OK: dist/AiLabCockpit-0.1.0.apk
```

`unzip -l` shows `classes.dex`, `AndroidManifest.xml`, resources and the
launcher; no tool jars are packaged.

## Bridge runtime tests (real host)

Bridge run on `127.0.0.1:8788` with a test token and Hermes gateway on
`127.0.0.1:8642`.

| Test | Result |
|---|---|
| `GET /health` without token | 200 `{"ok":true}` |
| authenticated endpoint without token | 401 |
| `GET /api/lab/status` with Hermes down | 200, `hermes.ok=false`; host/stack still returned (bug fixed) |
| `GET /api/lab/status` with Hermes up | 200, `hermes.ok=true`, version 0.22.7 |
| `GET /api/skills` | 200, `lab-operator` v1, score 0.894 |
| `GET /api/models` | 200, Ollama and vLLM states reported honestly (both down at test time) |
| `POST /api/models/chat` with Ollama down | 502 `cannot reach upstream` (no fake reply) |
| `POST /api/chat` with unknown skill | 400 `unknown skill` |
| `POST /api/chat` plain | 200 `PLAIN_LIVE_OK` |
| `POST /api/chat` with full skill | 200 but Hermes rejected the oversized conversation |
| `POST /api/chat` with compact skill | 200 `COMPACT_SKILL_OK`, `skill=lab-operator/v1`, 7008 prompt tokens |
| `POST /api/experiments` CPU smoke | 201, job ran, exit 0, real log |
| CPU timeout job | failed correctly |
| GPU job with `gpu.ssh_host` empty | failed with a clear "not configured" error |
| `GET /api/templates` | 200, custom templates returned |
| `GET /api/alerts` | 200, finished jobs returned |
| cancel job | accepted; status changed |

The `/api/lab/status` 502 was a real bug: `Hermes.health()` let `HermesError`
escape, so a down gateway hid the whole lab state. It now returns
`{"ok": false, "error": ...}` and status stays 200.

## Skill evaluation

`~/AI-Lab`:

- Full skill v1: 89.4% vs 63.7% baseline (+25.6%), 0 critical safety failures.
- Compact runtime skill: 86.2% vs 66.9% baseline (+19.4%), 0 critical failures.
- v2 rejected: 84.4%, worse than v1, not promoted.
- Live compact chat verified; full skill exceeded the gateway conversation
  budget because Hermes already sends ~6.7k prompt tokens.

## Not verified

There is no adb, emulator, KVM or attached phone in this environment. The APK
has not been installed, launched or tested on a real device. No screenshots or
logcat evidence exist yet. Build success and bridge tests do not prove the
Android UI works.

Still required on a phone:

1. `adb install -r dist/AiLabCockpit-0.1.0.apk`
2. Launch and check `adb logcat` for crashes.
3. Set bridge URL/token in Setup; verify `Lab` status and `Chat`.
4. Queue the CPU smoke template; confirm log and completion notification.
5. Test over LAN/Tailscale and a Cloudflare Tunnel URL.
