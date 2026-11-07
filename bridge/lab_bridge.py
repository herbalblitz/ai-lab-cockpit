#!/usr/bin/env python3
"""AI-Lab Cockpit bridge.

A small, dependency-free control plane for the Android cockpit. It runs on the
always-on CPU host next to Hermes Agent and exposes:

  * host metrics (CPU / RAM / disk)
  * Hermes gateway health and a chat proxy
  * a real experiment queue that runs commands locally or over SSH on a GPU host
  * completion alerts for the phone

Everything is authenticated with a bearer token. The token and the Hermes key
never leave the host except in the Authorization header of the upstream request.

Usage:
    python3 lab_bridge.py --init          # write ~/.ai-lab-cockpit/bridge.json
    python3 lab_bridge.py                 # run with that config
    python3 lab_bridge.py --config FILE --port 8787

The API is documented in ../README.md. Python 3.11+; standard library only.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import queue
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

VERSION = "0.1.0"
DEFAULT_HOME = Path(os.environ.get("LAB_COCKPIT_HOME", Path.home() / ".ai-lab-cockpit"))
SKILLS_HOME = Path(os.environ.get("AI_LAB_SKILLS", Path.home() / "AI-Lab" / "skills"))
CONFIG_NAME = "bridge.json"
JOBS_NAME = "jobs.json"
MAX_BODY = 1_048_576
MAX_JOBS = 200
LOG_TAIL_DEFAULT = 24_000
CHAT_TIMEOUT = 300


# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _read_dotenv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return out
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key.strip()] = value
    return out


def _hermes_key() -> str:
    env = os.environ.get("API_SERVER_KEY", "").strip()
    if env:
        return env
    dotenv = _read_dotenv(Path.home() / ".hermes" / ".env")
    return dotenv.get("API_SERVER_KEY", "").strip()


def _skill_versions(name: str) -> list[tuple[int, Path]]:
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,40}", name or ""):
        return []
    root = SKILLS_HOME / name
    if not root.is_dir():
        return []
    versions: list[tuple[int, Path]] = []
    for entry in root.iterdir():
        if entry.is_dir() and re.fullmatch(r"v\d+", entry.name):
            versions.append((int(entry.name[1:]), entry))
    return sorted(versions, reverse=True)


def list_skills() -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    if not SKILLS_HOME.is_dir():
        return out
    for root in sorted(SKILLS_HOME.iterdir()):
        if not root.is_dir():
            continue
        versions = _skill_versions(root.name)
        if not versions:
            continue
        version, path = versions[0]
        manifest: dict[str, Any] = {}
        try:
            manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            pass
        out.append({
            "name": root.name,
            "version": f"v{version}",
            "status": manifest.get("status", "promoted"),
            "score": manifest.get("candidate_score"),
            "baseline_score": manifest.get("baseline_score"),
            "model": manifest.get("model"),
            "path": str(path),
        })
    return out


def read_skill(name: str) -> tuple[str, str]:
    versions = _skill_versions(name)
    if not versions:
        raise KeyError(name)
    version, path = versions[0]
    runtime = path / "SKILL.compact.md"
    source = runtime if runtime.is_file() else path / "SKILL.md"
    text = source.read_text(encoding="utf-8", errors="replace")
    return f"v{version}", text[:20000]


def default_config() -> dict[str, Any]:
    return {
        "host": "0.0.0.0",
        "port": 8787,
        "token": base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="),
        "hermes": {
            "base_url": "http://127.0.0.1:8642",
            "api_key": _hermes_key(),
            "model": "hermes-agent",
        },
        "cpu": {
            "workdir": str(Path.home() / "ai-lab"),
            "shell": "/bin/bash",
        },
        "gpu": {
            "ssh_host": "",
            "ssh_user": "",
            "ssh_port": 22,
            "workdir": "~/ai-lab",
        },
        "jobs": {
            "max_parallel": 1,
            "default_timeout_sec": 3600,
            "max_timeout_sec": 86400,
        },
        "stack": {
            "ollama_url": "http://127.0.0.1:11434",
            "vllm_url": "http://127.0.0.1:8000",
            "openhands_url": "http://127.0.0.1:3000",
            "ai_scientist_dir": str(Path.home() / "AI-Scientist-v2"),
            "evolver_dir": str(Path.home() / "evolver"),
            "openhands_dir": str(Path.home() / "OpenHands"),
            "vllm_api_key": "",
        },
        # Optional extra experiment presets, shown as chips in the Android app.
        # Each item: {"name": "...", "title": "...", "command": "...",
        #             "target": "cpu|gpu", "timeout_sec": 3600}
        "templates": [],
    }


def load_config(path: Path, *, create: bool) -> dict[str, Any]:
    if not path.is_file():
        if not create:
            raise SystemExit(f"config not found: {path}\nRun: python3 {sys.argv[0]} --init")
        cfg = default_config()
        save_config(path, cfg)
        os.chmod(path, 0o600)
        print(f"created {path} (mode 600)")
    cfg = json.loads(path.read_text(encoding="utf-8"))
    # Fill in keys added by later versions without overwriting user values.
    base = default_config()
    for key, value in base.items():
        if key not in cfg:
            cfg[key] = value
        elif isinstance(value, dict) and isinstance(cfg[key], dict):
            for sub, subvalue in value.items():
                cfg[key].setdefault(sub, subvalue)
    return cfg


def save_config(path: Path, cfg: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(cfg, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


# --------------------------------------------------------------------------- #
# Host metrics
# --------------------------------------------------------------------------- #


def _meminfo() -> dict[str, int]:
    out: dict[str, int] = {}
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            key, _, rest = line.partition(":")
            if key in {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}:
                out[key] = int(rest.strip().split()[0]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return out


class Metrics:
    """CPU usage from /proc/stat deltas, kept across requests."""

    def __init__(self) -> None:
        self._last: tuple[int, int] | None = None
        self._lock = threading.Lock()

    def _cpu_times(self) -> tuple[int, int]:
        try:
            parts = Path("/proc/stat").read_text(encoding="utf-8").splitlines()[0].split()
            values = [int(v) for v in parts[1:]]
            idle = values[3] + (values[4] if len(values) > 4 else 0)
            total = sum(values)
            return idle, total
        except (OSError, ValueError, IndexError):
            return 0, 0

    def cpu_percent(self) -> float:
        with self._lock:
            idle, total = self._cpu_times()
            if self._last is None or total <= self._last[1]:
                self._last = (idle, total)
                load = os.getloadavg()[0] if hasattr(os, "getloadavg") else 0.0
                cores = os.cpu_count() or 1
                return round(min(100.0, max(0.0, load / cores * 100.0)), 1)
            d_idle = idle - self._last[0]
            d_total = total - self._last[1]
            self._last = (idle, total)
            if d_total <= 0:
                return 0.0
            return round(min(100.0, max(0.0, (1.0 - d_idle / d_total) * 100.0)), 1)

    def snapshot(self) -> dict[str, Any]:
        mem = _meminfo()
        disk = shutil.disk_usage(str(Path.home()))
        try:
            uptime = float(Path("/proc/uptime").read_text(encoding="utf-8").split()[0])
        except (OSError, ValueError, IndexError):
            uptime = 0.0
        return {
            "hostname": socket.gethostname(),
            "platform": sys.platform,
            "python": sys.version.split()[0],
            "cores": os.cpu_count() or 1,
            "uptime_sec": int(uptime),
            "cpu_percent": self.cpu_percent(),
            "load": [round(v, 2) for v in (os.getloadavg() if hasattr(os, "getloadavg") else (0, 0, 0))],
            "mem_used_bytes": max(0, mem.get("MemTotal", 0) - mem.get("MemAvailable", 0)),
            "mem_total_bytes": mem.get("MemTotal", 0),
            "swap_used_bytes": max(0, mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)),
            "swap_total_bytes": mem.get("SwapTotal", 0),
            "disk_used_bytes": disk.used,
            "disk_total_bytes": disk.total,
            "disk_free_bytes": disk.free,
        }


METRICS = Metrics()


# --------------------------------------------------------------------------- #
# Hermes client
# --------------------------------------------------------------------------- #


class HermesError(RuntimeError):
    def __init__(self, message: str, status: int = 502) -> None:
        super().__init__(message)
        self.status = status


def _http_json(url: str, *, method: str = "GET", body: dict | None = None,
               key: str = "", timeout: float = 8.0) -> tuple[int, dict[str, Any]]:
    data = None
    headers = {"Accept": "application/json", "User-Agent": f"ai-lab-bridge/{VERSION}"}
    if key:
        headers["Authorization"] = "Bearer " + key
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise HermesError(f"cannot reach upstream: {exc}") from exc
    try:
        payload = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        payload = {"raw": raw.decode("utf-8", errors="replace")[:2000]}
    return status, payload


class Hermes:
    def __init__(self, cfg: dict[str, Any]) -> None:
        self.base = str(cfg.get("base_url", "")).rstrip("/")
        self.key = str(cfg.get("api_key", ""))
        self.model = str(cfg.get("model", "hermes-agent"))

    def health(self) -> dict[str, Any]:
        try:
            status, body = _http_json(self.base + "/health/detailed", key=self.key, timeout=5)
        except HermesError as exc:
            return {"ok": False, "error": str(exc)}
        if status == 200:
            return {
                "ok": True,
                "status": body.get("status", "ok"),
                "version": body.get("version"),
                "gateway_state": body.get("gateway_state"),
                "readiness": body.get("readiness"),
                "api_server": body.get("api_server"),
            }
        # Older gateways may not authenticate /health/detailed but always expose /health.
        try:
            status2, body2 = _http_json(self.base + "/health", timeout=5)
        except HermesError as exc:
            return {"ok": False, "error": str(exc)}
        if status2 == 200:
            return {
                "ok": True,
                "status": body2.get("status", "ok"),
                "version": body2.get("version"),
                "detail_error": body.get("error"),
            }
        return {"ok": False, "error": body.get("error") or f"HTTP {status}"}

    def chat(self, messages: list[dict[str, Any]], model: str | None = None) -> dict[str, Any]:
        payload = {
            "model": model or self.model,
            "messages": messages,
            "stream": False,
        }
        status, body = _http_json(self.base + "/v1/chat/completions", method="POST",
                                  body=payload, key=self.key, timeout=CHAT_TIMEOUT)
        if status != 200:
            message = ""
            if isinstance(body, dict):
                err = body.get("error")
                message = err.get("message") if isinstance(err, dict) else str(err or body)
            raise HermesError(message or f"Hermes returned HTTP {status}", 502)
        try:
            choice = (body.get("choices") or [])[0]
            reply = (choice.get("message") or {}).get("content") or ""
        except (IndexError, AttributeError, TypeError):
            raise HermesError("Hermes returned an unexpected chat response")
        return {
            "reply": reply,
            "model": body.get("model"),
            "usage": body.get("usage") or {},
            "session": body.get("id"),
        }


# --------------------------------------------------------------------------- #
# Experiment queue
# --------------------------------------------------------------------------- #


@dataclass
class Job:
    id: str
    title: str
    command: str
    target: str
    timeout_sec: int
    status: str = "queued"
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    exit_code: int | None = None
    error: str = ""
    log_path: str = ""
    proc: subprocess.Popen | None = field(default=None, repr=False, compare=False)

    def public(self) -> dict[str, Any]:
        size = 0
        try:
            size = os.path.getsize(self.log_path) if self.log_path else 0
        except OSError:
            pass
        return {
            "id": self.id,
            "title": self.title,
            "command": self.command,
            "target": self.target,
            "timeout_sec": self.timeout_sec,
            "status": self.status,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "exit_code": self.exit_code,
            "error": self.error,
            "log_bytes": size,
        }

    def to_json(self) -> dict[str, Any]:
        data = self.public()
        data.pop("log_bytes", None)
        data["log_path"] = self.log_path
        return data

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> "Job":
        return cls(
            id=str(data["id"]), title=str(data.get("title", "")),
            command=str(data.get("command", "")), target=str(data.get("target", "cpu")),
            timeout_sec=int(data.get("timeout_sec", 3600)),
            status=str(data.get("status", "queued")),
            created_at=float(data.get("created_at", time.time())),
            started_at=data.get("started_at"), finished_at=data.get("finished_at"),
            exit_code=data.get("exit_code"), error=str(data.get("error", "")),
            log_path=str(data.get("log_path", "")),
        )


class JobManager:
    def __init__(self, cfg: dict[str, Any], home: Path) -> None:
        self.cfg = cfg
        self.home = home
        self.logs = home / "logs"
        self.logs.mkdir(parents=True, exist_ok=True)
        self.path = home / JOBS_NAME
        self.lock = threading.RLock()
        self.jobs: dict[str, Job] = {}
        self.order: list[str] = []
        self.queue: queue.Queue[str] = queue.Queue()
        self.running: dict[str, Job] = {}
        self._load()
        workers = max(1, int(cfg.get("jobs", {}).get("max_parallel", 1)))
        for index in range(workers):
            threading.Thread(target=self._worker, name=f"lab-job-{index}", daemon=True).start()

    # -- persistence ------------------------------------------------------- #

    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        for raw in data.get("jobs", []):
            try:
                job = Job.from_json(raw)
            except (KeyError, TypeError, ValueError):
                continue
            if job.status in {"queued", "running"}:
                job.status = "failed"
                job.error = "bridge restarted while this job was active"
                job.finished_at = time.time()
            self.jobs[job.id] = job
            self.order.append(job.id)

    def _save(self) -> None:
        with self.lock:
            data = {"version": 1, "jobs": [self.jobs[j].to_json() for j in self.order[-MAX_JOBS:]]}
            tmp = self.path.with_name(self.path.name + ".tmp")
            tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)

    # -- API ----------------------------------------------------------------- #

    def create(self, title: str, command: str, target: str, timeout_sec: int) -> Job:
        if target not in {"cpu", "gpu"}:
            raise ValueError("target must be cpu or gpu")
        jobs_cfg = self.cfg.get("jobs", {})
        default_timeout = int(jobs_cfg.get("default_timeout_sec", 3600))
        max_timeout = int(jobs_cfg.get("max_timeout_sec", 86400))
        timeout_sec = max(10, min(int(timeout_sec or default_timeout), max_timeout))
        job_id = "exp_" + uuid.uuid4().hex[:12]
        job = Job(id=job_id, title=title.strip()[:120] or "Untitled experiment",
                  command=command.strip(), target=target, timeout_sec=timeout_sec,
                  log_path=str(self.logs / f"{job_id}.log"))
        with self.lock:
            self.jobs[job.id] = job
            self.order.append(job.id)
            self._save()
        self.queue.put(job.id)
        return job

    def list(self) -> list[dict[str, Any]]:
        with self.lock:
            return [self.jobs[j].public() for j in reversed(self.order)]

    def get(self, job_id: str) -> Job | None:
        with self.lock:
            return self.jobs.get(job_id)

    def alerts(self, since: float) -> list[dict[str, Any]]:
        with self.lock:
            return [j.public() for j in self.jobs.values()
                    if j.finished_at and j.finished_at > since][-50:]

    def summary(self) -> dict[str, int]:
        with self.lock:
            counts = {"total": 0, "queued": 0, "running": 0, "succeeded": 0, "failed": 0, "cancelled": 0}
            for job in self.jobs.values():
                counts["total"] += 1
                if job.status in counts:
                    counts[job.status] += 1
            return counts

    def log_tail(self, job_id: str, limit: int) -> tuple[str, int]:
        job = self.get(job_id)
        if job is None:
            raise KeyError(job_id)
        limit = max(0, min(limit, 200_000))
        try:
            with open(job.log_path, "rb") as fh:
                fh.seek(0, os.SEEK_END)
                size = fh.tell()
                fh.seek(max(0, size - limit))
                data = fh.read()
        except OSError:
            data = b""
            size = 0
        return data.decode("utf-8", errors="replace"), size

    def cancel(self, job_id: str) -> bool:
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                raise KeyError(job_id)
            if job.status not in {"queued", "running"}:
                return False
            if job.proc is not None and job.proc.poll() is None:
                try:
                    os.killpg(job.proc.pid, signal.SIGTERM)
                except OSError:
                    job.proc.terminate()
            job.status = "cancelled"
            job.finished_at = time.time()
            job.error = "cancelled by operator"
            self._save()
            return True

    # -- execution ----------------------------------------------------------- #

    def _worker(self) -> None:
        while True:
            job_id = self.queue.get()
            job = self.get(job_id)
            if job is None or job.status != "queued":
                continue
            try:
                self._run(job)
            except Exception as exc:  # never kill the worker
                with self.lock:
                    job.status = "failed"
                    job.error = f"{type(exc).__name__}: {exc}"
                    job.finished_at = time.time()
                    self._save()
                self._append(job, f"\n[bridge] worker crashed: {exc}\n")
            finally:
                self.running.pop(job_id, None)
                self.queue.task_done()

    def _command_for(self, job: Job) -> list[str]:
        if job.target == "gpu":
            gpu = self.cfg.get("gpu", {})
            host = str(gpu.get("ssh_host", "")).strip()
            if not host:
                raise ValueError("GPU target is not configured: set gpu.ssh_host in bridge.json")
            user = str(gpu.get("ssh_user", "")).strip()
            dest = f"{user}@{host}" if user else host
            port = int(gpu.get("ssh_port", 22) or 22)
            workdir = str(gpu.get("workdir", "~/ai-lab"))
            remote = f"cd {shlex.quote(workdir)} && {job.command}"
            return [
                "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                "-p", str(port), dest, "--", "bash", "-lc", remote,
            ]
        shell = str(self.cfg.get("cpu", {}).get("shell", "/bin/bash"))
        workdir = str(self.cfg.get("cpu", {}).get("workdir", str(Path.home() / "ai-lab")))
        if not Path(workdir).is_dir():
            workdir = str(Path.home())
        return [shell, "-lc", job.command]

    def _append(self, job: Job, text: str) -> None:
        try:
            with open(job.log_path, "a", encoding="utf-8", errors="replace") as fh:
                fh.write(text)
                fh.flush()
        except OSError:
            pass

    def _run(self, job: Job) -> None:
        with self.lock:
            job.status = "running"
            job.started_at = time.time()
            job.proc = None
            self.running[job.id] = job
            self._save()
        self._append(job, f"# {job.title}\n# id: {job.id}\n# target: {job.target}\n"
                          f"# started: {_now_iso()}\n# command:\n{job.command}\n\n")
        try:
            argv = self._command_for(job)
        except ValueError as exc:
            with self.lock:
                job.status = "failed"
                job.error = str(exc)
                job.finished_at = time.time()
                self._save()
            self._append(job, f"[bridge] {exc}\n")
            return
        try:
            proc = subprocess.Popen(
                argv,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                start_new_session=True,
                bufsize=1,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except OSError as exc:
            with self.lock:
                job.status = "failed"
                job.error = str(exc)
                job.finished_at = time.time()
                self._save()
            self._append(job, f"[bridge] cannot start: {exc}\n")
            return
        with self.lock:
            job.proc = proc
            self._save()

        def pump() -> None:
            assert proc.stdout is not None
            for line in proc.stdout:
                self._append(job, line)

        reader = threading.Thread(target=pump, name=f"log-{job.id}", daemon=True)
        reader.start()
        timed_out = False
        try:
            proc.wait(timeout=job.timeout_sec)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                proc.kill()
            proc.wait(timeout=10)
        reader.join(timeout=5)
        with self.lock:
            # A cancel() that raced the process exit wins.
            if job.status == "cancelled":
                pass
            elif timed_out:
                job.status = "failed"
                job.error = f"timed out after {job.timeout_sec}s"
                job.exit_code = -9
            else:
                job.exit_code = proc.returncode
                job.status = "succeeded" if proc.returncode == 0 else "failed"
                if proc.returncode != 0:
                    job.error = f"exit code {proc.returncode}"
            job.finished_at = time.time()
            job.proc = None
            self._save()
        self._append(job, f"\n# finished: {_now_iso()} status={job.status} exit={job.exit_code}\n")


# --------------------------------------------------------------------------- #
# Lab stack probe
# --------------------------------------------------------------------------- #

STACK_CACHE_TTL = 20.0


def _probe_json(url: str, timeout: float = 2.0, key: str = "") -> dict[str, Any]:
    """GET a JSON endpoint and return a small, UI-friendly result."""
    if not url:
        return {"ok": False, "detail": "not configured"}
    headers = {
        "Accept": "application/json",
        "User-Agent": f"ai-lab-bridge/{VERSION}",
    }
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(1_000_000)
            status = getattr(resp, "status", 200)
    except urllib.error.HTTPError as exc:
        return {"ok": False, "detail": f"HTTP {exc.code}"}
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        text = str(reason) or exc.__class__.__name__
        return {"ok": False, "detail": text[:120]}
    if status != 200:
        return {"ok": False, "detail": f"HTTP {status}"}
    try:
        body = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        body = {}
    return {"ok": True, "body": body}


class StackProbe:
    """Detects the five lab components without blocking the dashboard for long."""

    def __init__(self, cfg: dict[str, Any]) -> None:
        self.cfg = cfg or {}
        self._lock = threading.Lock()
        self._cached: dict[str, Any] | None = None
        self._checked = 0.0

    @staticmethod
    def _dir(path_str: str) -> tuple[bool, str]:
        if not path_str:
            return False, "not configured"
        path = Path(path_str).expanduser()
        try:
            if not path.is_dir():
                return False, "not installed"
            count = sum(1 for _ in path.iterdir())
            return True, f"{path.name} · {count} entries"
        except OSError as exc:
            return False, str(exc)[:120]

    def _probe(self, hermes: dict[str, Any]) -> list[dict[str, Any]]:
        cfg = self.cfg
        ollama = _probe_json(str(cfg.get("ollama_url", "")).rstrip("/") + "/api/tags")
        vllm = _probe_json(str(cfg.get("vllm_url", "")).rstrip("/") + "/v1/models")
        openhands_url = str(cfg.get("openhands_url", "")).rstrip("/")
        openhands_http = _probe_json(openhands_url + "/")

        ai_scientist_ok, ai_scientist_detail = self._dir(str(cfg.get("ai_scientist_dir", "")))
        evolver_ok, evolver_detail = self._dir(str(cfg.get("evolver_dir", "")))
        openhands_ok, openhands_detail = self._dir(str(cfg.get("openhands_dir", "")))

        models: list[str] = []
        if ollama["ok"]:
            for item in (ollama.get("body", {}).get("models") or [])[:4]:
                name = item.get("name") or item.get("model")
                if name:
                    models.append(str(name))
        if vllm["ok"]:
            for item in (vllm.get("body", {}).get("data") or [])[:4]:
                name = item.get("id")
                if name:
                    models.append(str(name))
        serving_ok = bool(ollama["ok"] or vllm["ok"])

        if models:
            serving_detail = ", ".join(models[:3])
            if len(models) > 3:
                serving_detail += f" +{len(models) - 3}"
        elif serving_ok:
            serving_detail = "reachable · no models loaded"
        elif ollama["detail"] == vllm["detail"]:
            serving_detail = ollama["detail"]
        else:
            serving_detail = f"ollama: {ollama['detail']} · vLLM: {vllm['detail']}"

        hermes_ok = bool(hermes.get("ok"))
        hermes_detail = (f"v{hermes.get('version')}" if hermes.get("version")
                         else hermes.get("error", "not reachable"))
        if hermes_ok and hermes.get("gateway_state"):
            hermes_detail += f" · {hermes['gateway_state']}"

        openhands_detail_out = ""
        if openhands_http["ok"]:
            openhands_detail_out = "server online"
            openhands_ok = True
        elif openhands_ok:
            openhands_detail_out = f"installed · server offline ({openhands_http['detail']})"
        else:
            openhands_detail_out = f"not installed · {openhands_http['detail']}"
        if openhands_ok and openhands_detail:
            openhands_detail_out = openhands_detail_out.replace("installed", f"installed ({openhands_detail})", 1)

        return [
            {"id": "hermes", "name": "Hermes Agent", "role": "Main agent",
             "ok": hermes_ok, "detail": hermes_detail},
            {"id": "ai-scientist", "name": "AI-Scientist-v2", "role": "Research",
             "ok": ai_scientist_ok, "detail": ai_scientist_detail},
            {"id": "evolver", "name": "Evolver", "role": "Self-improvement",
             "ok": evolver_ok, "detail": evolver_detail},
            {"id": "openhands", "name": "OpenHands", "role": "Coding",
             "ok": openhands_ok, "detail": openhands_detail_out},
            {"id": "serving", "name": "Ollama / vLLM", "role": "Model serving",
             "ok": serving_ok, "detail": serving_detail},
        ]

    def status(self, hermes: dict[str, Any]) -> dict[str, Any]:
        now = time.time()
        with self._lock:
            if self._cached is not None and now - self._checked < STACK_CACHE_TTL:
                return self._cached
            components = self._probe(hermes)
            self._checked = now
            self._cached = {
                "components": components,
                "ok_count": sum(1 for c in components if c["ok"]),
                "total": len(components),
                "checked_at": now,
                "urls": {
                    "ollama": str(self.cfg.get("ollama_url", "")),
                    "vllm": str(self.cfg.get("vllm_url", "")),
                    "openhands": str(self.cfg.get("openhands_url", "")),
                },
            }
            return self._cached


# --------------------------------------------------------------------------- #
# HTTP server
# --------------------------------------------------------------------------- #


class Bridge:
    def __init__(self, cfg: dict[str, Any], home: Path) -> None:
        self.cfg = cfg
        self.home = home
        self.jobs = JobManager(cfg, home)
        self.hermes = Hermes(cfg.get("hermes", {}))
        self.stack = StackProbe(cfg.get("stack", {}))
        self.token = str(cfg.get("token", ""))
        self.started = time.time()

    def templates(self) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        raw = self.cfg.get("templates", [])
        if not isinstance(raw, list):
            return out
        for item in raw[:24]:
            if not isinstance(item, dict):
                continue
            command = str(item.get("command", "")).strip()
            if not command:
                continue
            target = str(item.get("target", "cpu")).strip().lower()
            out.append({
                "name": str(item.get("name") or item.get("title") or "Custom")[:40],
                "title": str(item.get("title") or item.get("name") or "Custom experiment")[:120],
                "command": command[:8000],
                "target": target if target in {"cpu", "gpu"} else "cpu",
                "timeout_sec": max(10, min(int(item.get("timeout_sec", 3600) or 3600), 86400)),
            })
        return out

    def models(self) -> dict[str, Any]:
        stack = self.cfg.get("stack", {})
        ollama_url = str(stack.get("ollama_url", "")).rstrip("/")
        vllm_url = str(stack.get("vllm_url", "")).rstrip("/")
        vllm_key = str(stack.get("vllm_api_key", ""))

        ollama_probe = _probe_json(ollama_url + "/api/tags")
        ollama_models: list[dict[str, Any]] = []
        if ollama_probe["ok"]:
            for item in (ollama_probe.get("body", {}).get("models") or []):
                name = item.get("name") or item.get("model")
                if name:
                    ollama_models.append({
                        "name": str(name),
                        "size": item.get("size"),
                        "modified_at": item.get("modified_at"),
                    })

        vllm_probe = _probe_json(vllm_url + "/v1/models", key=vllm_key)
        vllm_models: list[dict[str, Any]] = []
        if vllm_probe["ok"]:
            for item in (vllm_probe.get("body", {}).get("data") or []):
                name = item.get("id")
                if name:
                    vllm_models.append({"name": str(name), "size": None,
                                        "modified_at": item.get("created")})

        return {
            "ok": True,
            "ollama": {
                "url": ollama_url,
                "ok": bool(ollama_probe["ok"]),
                "models": ollama_models,
                "error": "" if ollama_probe["ok"] else ollama_probe["detail"],
            },
            "vllm": {
                "url": vllm_url,
                "ok": bool(vllm_probe["ok"]),
                "models": vllm_models,
                "error": "" if vllm_probe["ok"] else vllm_probe["detail"],
            },
        }

    def local_chat(self, provider: str, model: str, messages: list[dict[str, str]]) -> dict[str, Any]:
        stack = self.cfg.get("stack", {})
        if provider == "ollama":
            base = str(stack.get("ollama_url", "")).rstrip("/")
            if not base:
                raise ValueError("ollama_url is not configured")
            status, body = _http_json(base + "/api/chat", method="POST", body={
                "model": model,
                "messages": messages,
                "stream": False,
            }, timeout=CHAT_TIMEOUT)
            if status != 200:
                detail = body.get("error") if isinstance(body, dict) else ""
                raise HermesError(str(detail or f"Ollama returned HTTP {status}"))
            reply = (body.get("message") or {}).get("content") or ""
            usage = {
                "prompt_tokens": body.get("prompt_eval_count"),
                "completion_tokens": body.get("eval_count"),
            }
        elif provider == "vllm":
            base = str(stack.get("vllm_url", "")).rstrip("/")
            if not base:
                raise ValueError("vllm_url is not configured")
            status, body = _http_json(base + "/v1/chat/completions", method="POST", body={
                "model": model,
                "messages": messages,
                "stream": False,
            }, key=str(stack.get("vllm_api_key", "")), timeout=CHAT_TIMEOUT)
            if status != 200:
                err = body.get("error") if isinstance(body, dict) else ""
                detail = err.get("message") if isinstance(err, dict) else str(err or "")
                raise HermesError(detail or f"vLLM returned HTTP {status}")
            try:
                choice = (body.get("choices") or [])[0]
                reply = (choice.get("message") or {}).get("content") or ""
            except (IndexError, AttributeError, TypeError):
                raise HermesError("vLLM returned an unexpected chat response")
            usage = body.get("usage") or {}
        else:
            raise ValueError("provider must be ollama or vllm")
        return {"reply": reply, "engine": provider, "model": model, "usage": usage}

    def status(self) -> dict[str, Any]:
        hermes = self.hermes.health()
        gpu = self.cfg.get("gpu", {})
        return {
            "ok": True,
            "service": "ai-lab-bridge",
            "version": VERSION,
            "time": time.time(),
            "started_at": self.started,
            "host": METRICS.snapshot(),
            "hermes": hermes,
            "stack": self.stack.status(hermes),
            "experiments": self.jobs.summary(),
            "recent": self.jobs.list()[:5],
            "templates": self.templates(),
            "gpu": {
                "mode": "ssh" if str(gpu.get("ssh_host", "")).strip() else "unconfigured",
                "ssh_host": str(gpu.get("ssh_host", "")),
                "workdir": str(gpu.get("workdir", "")),
            },
            "config": {
                "hermes_url": self.hermes.base,
                "home": str(self.home),
            },
        }


class Handler(BaseHTTPRequestHandler):
    server_version = f"ai-lab-bridge/{VERSION}"
    protocol_version = "HTTP/1.1"
    bridge: Bridge  # set by Server

    # -- plumbing ----------------------------------------------------------- #

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def _send(self, status: int, body: bytes, content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        self._send(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _error(self, status: int, message: str) -> None:
        self._json(status, {"ok": False, "error": message})

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        expected = self.bridge.token
        if not expected:
            return False
        if not hmac.compare_digest(token.encode(), expected.encode()):
            return False
        return True

    def _body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            raise ValueError("invalid Content-Length")
        if length <= 0:
            return {}
        if length > MAX_BODY:
            raise ValueError("request body too large")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid JSON: {exc}")
        if not isinstance(data, dict):
            raise ValueError("request body must be a JSON object")
        return data

    def _path(self) -> tuple[str, dict[str, list[str]]]:
        parsed = urllib.parse.urlparse(self.path)
        return parsed.path.rstrip("/") or "/", urllib.parse.parse_qs(parsed.query)

    # -- methods ------------------------------------------------------------ #

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def do_DELETE(self) -> None:
        self._dispatch("DELETE")

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Allow", "GET, POST, DELETE, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _dispatch(self, method: str) -> None:
        try:
            path, query = self._path()
            if path == "/health":
                self._json(200, {"ok": True, "service": "ai-lab-bridge", "version": VERSION})
                return
            if not self._authorized():
                self._error(401, "invalid or missing bearer token")
                return
            if path == "/api/lab/status" and method == "GET":
                self._json(200, self.bridge.status())
                return
            if path == "/api/templates" and method == "GET":
                self._json(200, {"object": "list", "data": self.bridge.templates()})
                return
            if path == "/api/skills" and method == "GET":
                self._json(200, {"object": "list", "data": list_skills()})
                return
            if path == "/api/models" and method == "GET":
                self._json(200, self.bridge.models())
                return
            if path == "/api/models/chat" and method == "POST":
                self._local_chat()
                return
            if path == "/api/alerts" and method == "GET":
                since = float((query.get("since") or ["0"])[0] or 0)
                self._json(200, {"object": "list", "data": self.bridge.jobs.alerts(since)})
                return
            if path == "/api/chat" and method == "POST":
                self._chat()
                return
            if path == "/api/experiments":
                if method == "GET":
                    self._json(200, {"object": "list", "data": self.bridge.jobs.list()})
                    return
                if method == "POST":
                    self._create_experiment()
                    return
            match = re.fullmatch(r"/api/experiments/([A-Za-z0-9_]+)", path)
            if match and method == "GET":
                job = self.bridge.jobs.get(match.group(1))
                if job is None:
                    self._error(404, "experiment not found")
                else:
                    self._json(200, job.public())
                return
            match = re.fullmatch(r"/api/experiments/([A-Za-z0-9_]+)/log", path)
            if match and method == "GET":
                try:
                    limit = int((query.get("tail") or [str(LOG_TAIL_DEFAULT)])[0])
                except ValueError:
                    limit = LOG_TAIL_DEFAULT
                try:
                    text, size = self.bridge.jobs.log_tail(match.group(1), limit)
                except KeyError:
                    self._error(404, "experiment not found")
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("X-Log-Bytes", str(size))
                body = text.encode("utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            match = re.fullmatch(r"/api/experiments/([A-Za-z0-9_]+)/cancel", path)
            if match and method == "POST":
                try:
                    changed = self.bridge.jobs.cancel(match.group(1))
                except KeyError:
                    self._error(404, "experiment not found")
                    return
                self._json(200, {"ok": True, "cancelled": changed})
                return
            if path.startswith("/api/hermes/") and method in {"GET", "POST"}:
                self._proxy_hermes(method, path)
                return
            self._error(404, "not found")
        except ValueError as exc:
            self._error(400, str(exc))
        except HermesError as exc:
            self._error(exc.status, str(exc))
        except Exception:
            traceback.print_exc()
            self._error(500, "bridge internal error")

    # -- endpoints ---------------------------------------------------------- #

    def _chat(self) -> None:
        body = self._body()
        messages = body.get("messages")
        if not isinstance(messages, list) or not messages:
            message = str(body.get("message", "")).strip()
            if not message:
                raise ValueError("messages[] or message is required")
            messages = [{"role": "user", "content": message}]
        cleaned: list[dict[str, str]] = []
        for item in messages[-30:]:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "user"))
            content = str(item.get("content", ""))[:8000]
            if role not in {"user", "assistant", "system"} or not content:
                continue
            cleaned.append({"role": role, "content": content})
        if not cleaned:
            raise ValueError("no usable messages")
        skill_name = str(body.get("skill", "")).strip()
        skill_version = None
        if skill_name:
            try:
                skill_version, skill_text = read_skill(skill_name)
            except KeyError:
                raise ValueError(f"unknown skill: {skill_name}")
            cleaned = [{
                "role": "system",
                "content": "Follow this operating skill exactly:\n\n" + skill_text,
            }] + cleaned
        result = self.bridge.hermes.chat(cleaned, body.get("model") or None)
        payload: dict[str, Any] = {"ok": True, **result}
        if skill_name:
            payload["skill"] = f"{skill_name}/{skill_version}"
        self._json(200, payload)

    def _local_chat(self) -> None:
        body = self._body()
        provider = str(body.get("provider", "")).strip().lower()
        model = str(body.get("model", "")).strip()
        if provider not in {"ollama", "vllm"}:
            raise ValueError("provider must be ollama or vllm")
        if not model:
            raise ValueError("model is required")
        messages = body.get("messages")
        if not isinstance(messages, list) or not messages:
            raise ValueError("messages[] is required")
        cleaned: list[dict[str, str]] = []
        for item in messages[-30:]:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "user"))
            content = str(item.get("content", ""))[:8000]
            if role not in {"user", "assistant", "system"} or not content:
                continue
            cleaned.append({"role": role, "content": content})
        if not cleaned:
            raise ValueError("no usable messages")
        result = self.bridge.local_chat(provider, model, cleaned)
        self._json(200, {"ok": True, **result})

    def _create_experiment(self) -> None:
        body = self._body()
        title = str(body.get("title", "")).strip()
        command = str(body.get("command", "")).strip()
        target = str(body.get("target", "cpu")).strip().lower()
        timeout_sec = int(body.get("timeout_sec", 0) or 0)
        if not command:
            raise ValueError("command is required")
        if len(command) > 8000:
            raise ValueError("command is too long (max 8000 chars)")
        job = self.bridge.jobs.create(title, command, target, timeout_sec)
        self._json(201, {"ok": True, "experiment": job.public()})

    def _proxy_hermes(self, method: str, path: str) -> None:
        target = self.bridge.hermes.base + path[len("/api/hermes"):]
        data = None
        headers = {"Accept": "application/json", "User-Agent": f"ai-lab-bridge/{VERSION}"}
        if self.bridge.hermes.key:
            headers["Authorization"] = "Bearer " + self.bridge.hermes.key
        if method == "POST":
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
            except ValueError:
                raise ValueError("invalid Content-Length")
            if 0 < length <= MAX_BODY:
                data = self.rfile.read(length)
                headers["Content-Type"] = "application/json"
        req = urllib.request.Request(target, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=CHAT_TIMEOUT) as resp:
                body = resp.read()
                status = resp.status
                ctype = resp.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as exc:
            body = exc.read()
            status = exc.code
            ctype = exc.headers.get("Content-Type", "application/json")
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise HermesError(f"cannot reach Hermes: {exc}") from exc
        self._send(status, body, ctype)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], bridge: Bridge) -> None:
        self.bridge = bridge
        handler = type("BoundHandler", (Handler,), {"bridge": bridge})
        super().__init__(address, handler)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="AI-Lab Cockpit bridge")
    parser.add_argument("--config", type=Path, default=DEFAULT_HOME / CONFIG_NAME)
    parser.add_argument("--init", action="store_true", help="create a config and exit")
    parser.add_argument("--host", default="")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--print-token", action="store_true", help="print the token (local console only)")
    args = parser.parse_args(argv)

    if args.init:
        if args.config.exists():
            print(f"config already exists: {args.config}")
        else:
            save_config(args.config, default_config())
            os.chmod(args.config, 0o600)
            print(f"created {args.config} (mode 600)")
        print("edit hermes.api_key and gpu.ssh_host, then run the bridge")
        return 0

    cfg = load_config(args.config, create=True)
    if args.host:
        cfg["host"] = args.host
    if args.port:
        cfg["port"] = args.port
    home = args.config.parent
    bridge = Bridge(cfg, home)
    if args.print_token:
        print(cfg.get("token", ""))
        return 0
    server = Server((str(cfg.get("host", "0.0.0.0")), int(cfg.get("port", 8787))), bridge)
    print(f"AI-Lab bridge {VERSION} on http://{cfg['host']}:{cfg['port']} "
          f"(config {args.config})", flush=True)
    if not bridge.token:
        print("warning: empty token; set one before exposing the bridge", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
