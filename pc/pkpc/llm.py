"""Lokales Sprachmodell über llama-server (llama.cpp), ausschließlich auf 127.0.0.1.

Gleiches Verhalten wie LocalLlm.kt der App: eingebaute Chat-Vorlage des Modells, Grammatik für
gültiges JSON, bei Qwen3-Hybridmodellen "/no_think".
"""
from __future__ import annotations

import http.client
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Callable

from .generator import Cancelled, LlmError
from .paths import app_data, resource

HOST = "127.0.0.1"


def server_binary() -> Path | None:
    env = os.environ.get("PK_LLAMA_SERVER")
    if env and Path(env).exists():
        return Path(env)
    exe = "llama-server.exe" if sys.platform == "win32" else "llama-server"
    for cand in (resource(f"llama/{exe}"), Path(sys.executable).parent / "llama" / exe):
        if cand.exists():
            return cand
    found = shutil.which(exe)
    return Path(found) if found else None


def _free_port() -> int:
    with socket.socket() as s:
        s.bind((HOST, 0))
        return s.getsockname()[1]


class LocalLlm:
    """Startet llama-server mit dem Modell. Immer mit close() beenden (belegt mehrere GB RAM)."""

    def __init__(self, model_path: Path, context: int, no_think: bool = False,
                 on_status: Callable[[str], None] | None = None):
        self.no_think = no_think
        self.context_size = context
        self._proc: subprocess.Popen | None = None
        self._cancel = threading.Event()
        binary = server_binary()
        if binary is None:
            raise LlmError("Die KI-Komponente (llama-server) fehlt. Bitte Protokoll KI neu installieren.")
        threads = max(1, min(16, (os.cpu_count() or 4) - 1))
        attempts = [["-ngl", "999"], ["-ngl", "0"]]
        if os.environ.get("PK_CPU_ONLY") == "1":
            attempts = [["-ngl", "0"]]
        last_log = ""
        for extra in attempts:
            for ctx in ([context, 8192] if context > 8192 else [context]):
                self.port = _free_port()
                log_path = app_data() / "llama-server.log"
                args = [str(binary), "-m", str(model_path), "-c", str(ctx), "--host", HOST,
                        "--port", str(self.port), "-np", "1", "-t", str(threads), *extra]
                flags = 0x08000000 if sys.platform == "win32" else 0   # CREATE_NO_WINDOW
                with open(log_path, "wb") as log:
                    self._proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                                                  stdin=subprocess.DEVNULL, creationflags=flags,
                                                  cwd=str(binary.parent))
                if on_status:
                    on_status("Lade Sprachmodell …")
                if self._wait_ready(timeout=600):
                    self.context_size = ctx
                    self._read_props()
                    return
                last_log = log_path.read_text("utf-8", errors="replace")[-1500:]
                self.close()
        raise LlmError("Das Sprachmodell konnte nicht gestartet werden. Eventuell ist zu wenig "
                       "Arbeitsspeicher frei. Bitte andere Programme schließen. " + _short(last_log))

    # ------------------------------------------------------------ HTTP

    def _conn(self, timeout: float = 30) -> http.client.HTTPConnection:
        return http.client.HTTPConnection(HOST, self.port, timeout=timeout)

    def _request(self, method: str, path: str, body: dict | None = None, timeout: float = 60):
        c = self._conn(timeout)
        data = json.dumps(body).encode() if body is not None else None
        c.request(method, path, body=data, headers={"Content-Type": "application/json"})
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, raw

    def _wait_ready(self, timeout: float) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            if self._proc is None or self._proc.poll() is not None:
                return False
            try:
                status, _ = self._request("GET", "/health", timeout=5)
                if status == 200:
                    return True
            except OSError:
                pass
            time.sleep(0.5)
        return False

    def _read_props(self) -> None:
        try:
            status, raw = self._request("GET", "/props")
            if status == 200:
                n = json.loads(raw).get("default_generation_settings", {}).get("n_ctx")
                if n:
                    self.context_size = int(n)
        except (OSError, ValueError):
            pass

    def count_tokens(self, text: str) -> int:
        status, raw = self._request("POST", "/tokenize", {"content": text, "add_special": False})
        if status != 200:
            return len(text) // 3 + 1
        return len(json.loads(raw).get("tokens", []))

    def cancel(self) -> None:
        self._cancel.set()

    def complete(self, system: str, user: str, max_tokens: int, temperature: float,
                 grammar: str | None, progress: Callable[[float], bool] | None) -> str:
        if self._cancel.is_set():
            raise Cancelled()
        prompt = f"{user} /no_think" if self.no_think else user
        body = {
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
            "max_tokens": max_tokens, "temperature": temperature, "stream": True,
            "cache_prompt": True,
        }
        if grammar:
            body["grammar"] = grammar
        c = self._conn(timeout=3600)
        c.request("POST", "/v1/chat/completions", body=json.dumps(body).encode(),
                  headers={"Content-Type": "application/json"})
        r = c.getresponse()
        if r.status != 200:
            msg = r.read().decode("utf-8", "replace")
            c.close()
            if "context" in msg.lower() and ("exceed" in msg.lower() or "too long" in msg.lower()):
                raise LlmError("Text zu lang für das Sprachmodell")
            raise LlmError("Fehler im Sprachmodell: " + _short(msg))
        out: list[str] = []
        tokens = 0
        if progress and not progress(0.05):
            c.close()
            raise Cancelled()
        try:
            for line in r:
                if self._cancel.is_set():
                    raise Cancelled()
                line = line.strip()
                if not line.startswith(b"data:"):
                    continue
                payload = line[5:].strip()
                if payload == b"[DONE]":
                    break
                try:
                    ev = json.loads(payload)
                except ValueError:
                    continue
                for ch in ev.get("choices", []):
                    piece = (ch.get("delta") or {}).get("content") or ""
                    if piece:
                        out.append(piece)
                        tokens += 1
                if progress and tokens % 8 == 0:
                    if not progress(0.4 + 0.6 * min(1.0, tokens / max(1, max_tokens))):
                        raise Cancelled()
        finally:
            c.close()
        text = "".join(out)
        return re.sub(r"(?s)<think>.*?</think>", "", text).strip()

    def close(self) -> None:
        p, self._proc = self._proc, None
        if p and p.poll() is None:
            p.terminate()
            try:
                p.wait(10)
            except subprocess.TimeoutExpired:
                p.kill()


def _short(s: str) -> str:
    s = " ".join(s.split())
    return ("(" + s[-300:] + ")") if s else ""
