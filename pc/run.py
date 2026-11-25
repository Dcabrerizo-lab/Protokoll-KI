"""Startpunkt: Protokoll KI für den PC.

  ProtokollKI.exe                 Programmfenster öffnen
  ProtokollKI.exe datei.protokoll Datei übernehmen (auch per Doppelklick / „Öffnen mit“)
  ProtokollKI.exe --cli AUDIO     ohne Fenster auswerten (für automatische Tests)
"""
from __future__ import annotations

import http.client
import json
import logging
import os
import sys
from pathlib import Path


def _setup_logging() -> None:
    from pkpc.paths import app_data
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.FileHandler(app_data() / "protokoll-ki.log", encoding="utf-8")]
        + ([logging.StreamHandler()] if sys.stderr else []),
    )
    logging.getLogger("fontTools").setLevel(logging.WARNING)

    # Unerwartete Fehler (auch in Hintergrund-Threads) ins Protokoll schreiben, damit sich Abstürze
    # nachvollziehen lassen. Die Datei bleibt auf dem PC; Inhalte von Besprechungen stehen nicht darin.
    import threading

    def _hook(exc_type, exc, tb):
        logging.critical("Unerwarteter Fehler", exc_info=(exc_type, exc, tb))

    sys.excepthook = _hook
    threading.excepthook = lambda a: logging.critical(
        "Unerwarteter Fehler im Thread %s", a.thread.name if a.thread else "?",
        exc_info=(a.exc_type, a.exc_value, a.exc_traceback))


def _running_instance():
    from pkpc.paths import app_data
    f = app_data() / "instance.json"
    try:
        info = json.loads(f.read_text())
        c = http.client.HTTPConnection("127.0.0.1", info["port"], timeout=2)
        c.request("GET", "/health")
        r = c.getresponse()
        if r.status == 200 and json.loads(r.read()).get("app") == "protokoll-ki":
            return info
    except Exception:  # noqa: BLE001
        return None
    return None


def _post(info: dict, path: str, body: dict) -> None:
    c = http.client.HTTPConnection("127.0.0.1", info["port"], timeout=30)
    c.request("POST", path, body=json.dumps(body), headers={"X-Token": info["token"], "Content-Type": "application/json"})
    c.getresponse().read()


_APP_BROWSERS = ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable",
                 "microsoft-edge", "microsoft-edge-stable", "brave-browser", "vivaldi")


def open_ui(url: str) -> None:
    """Linux/macOS: als eigenes Fenster (App-Modus von Chrome/Chromium/Edge), sonst im Standardbrowser."""
    import shutil
    import subprocess
    from pkpc.paths import clean_env
    if os.environ.get("BROWSER") == "echo":       # automatische Tests: nichts öffnen
        print(url, flush=True)
        return
    if sys.platform == "win32":
        import webbrowser
        webbrowser.open(url)
        return
    env = clean_env()
    if os.environ.get("PK_BROWSER") != "1":
        for b in _APP_BROWSERS:
            exe = shutil.which(b, path=env.get("PATH"))
            if exe:
                try:
                    subprocess.Popen([exe, f"--app={url}", "--window-size=1280,860"], env=env,
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, start_new_session=True)
                    return
                except OSError:
                    pass
    opener = "open" if sys.platform == "darwin" else "xdg-open"
    try:
        subprocess.Popen([opener, url], env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        import webbrowser
        webbrowser.open(url)


def cli(args: list[str]) -> int:
    """Wertet eine Datei ohne Fenster aus und schreibt die Ergebnisse in den Ausgabeordner."""
    import time
    from pkpc import catalog, core
    from pkpc.paths import app_data, documents_dir
    src = Path(args[0])
    template = args[1] if len(args) > 1 else "ALLGEMEIN"
    s = catalog.recommended()
    if not catalog.is_installed(s):
        print(f"Lade KI-Modelle ({s.id}) …", flush=True)
        d = catalog.Downloader()
        d.start(s)
        while d.snapshot()["running"] or not d.snapshot()["finished"]:
            if d.snapshot()["error"]:
                print(d.snapshot()["error"])
                if os.environ.get("PK_CLI_OUT"):
                    Path(os.environ["PK_CLI_OUT"]).write_text(json.dumps({"error": d.snapshot()["error"]}), "utf-8")
                return 2
            time.sleep(2)
    store = core.SettingsStore(app_data())
    store.update(model_set=s.id)
    lib = core.Library(app_data())
    proc = core.Processor(lib, store, documents_dir())
    m, done = core.import_file(lib, src, template)
    if not done:
        proc.enqueue(m.id)
        last = ""
        while True:
            time.sleep(1)
            cur = lib.get(m.id)
            f, text = lib.progress.get(m.id, (0, ""))
            if text != last:
                print(f"{int(f * 100):3d} %  {text}", flush=True)
                last = text
            if cur.status in ("DONE", "ERROR") and m.id not in proc.queued:
                break
    m = lib.get(m.id)
    if m.status == "ERROR":
        print("FEHLER:", m.error)
        if os.environ.get("PK_CLI_OUT"):
            Path(os.environ["PK_CLI_OUT"]).write_text(json.dumps({"error": m.error}, ensure_ascii=False), "utf-8")
        return 1
    files = lib.extra.get(m.id, {}).get("files") or proc.write_outputs(m)
    result = {"segments": len(m.segments), "speakers": len(m.speakers), "tasks": [t.__dict__ for t in m.tasks],
                      "overview": m.summary.overview, "topics": [t.title for t in m.summary.topics],
                      "files": files}
    print(json.dumps(result, ensure_ascii=False, indent=1))
    if os.environ.get("PK_CLI_OUT"):
        Path(os.environ["PK_CLI_OUT"]).write_text(json.dumps(result, ensure_ascii=False, indent=1), "utf-8")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if sys.stdout is None or sys.stderr is None:      # Programm ohne Konsole (Windows)
        from pkpc.paths import app_data
        out = open(app_data() / "konsole.log", "a", encoding="utf-8", buffering=1)  # noqa: SIM115
        sys.stdout = sys.stdout or out
        sys.stderr = sys.stderr or out
    _setup_logging()
    if args and args[0] == "--cli":
        return cli(args[1:])
    files = [a for a in args if not a.startswith("--") and Path(a).exists()]
    info = _running_instance()
    if info:
        # Programm läuft schon: Datei dort übernehmen und Fenster nach vorne holen
        for f in files:
            _post(info, "/api/import-path", {"path": str(Path(f).resolve())})
        _post(info, "/api/focus", {})
        if sys.platform != "win32" and not files:     # Browser-Fenster lässt sich nicht nach vorne holen
            open_ui(f"http://127.0.0.1:{info['port']}/?t={info['token']}")
        return 0
    from pkpc.server import App, serve
    app = App()
    srv = serve(app)
    for f in files:
        try:
            app.import_path(Path(f).resolve())
        except Exception:  # noqa: BLE001
            logging.exception("Datei %s", f)
    url = f"http://127.0.0.1:{srv.server_address[1]}/?t={app.token}"
    if sys.platform != "win32" or "--browser" in args or os.environ.get("PK_BROWSER") == "1":
        open_ui(url)
        app.quit_when_idle()          # beendet sich, wenn das Fenster zu ist und nichts mehr läuft
        try:
            import threading
            threading.Event().wait()
        except KeyboardInterrupt:
            pass
        return 0
    try:
        import webview
    except ImportError:
        open_ui(url)
        app.quit_when_idle()
        import threading
        threading.Event().wait()
        return 0
    win = webview.create_window("Protokoll KI", url, width=1280, height=860, min_size=(900, 640),
                                background_color="#F5F6F8", text_select=True)
    app.window = win

    def on_closing():
        if app.rec.active:
            app.stop_recording()
        return True
    win.events.closing += on_closing
    webview.start(private_mode=False, storage_path=str(Path(os.environ.get("LOCALAPPDATA", Path.home())) / "ProtokollKI" / "webview"))
    if app.rec.active:
        app.stop_recording()
    app.proc.cancel()
    return 0


if __name__ == "__main__":
    sys.exit(main())
