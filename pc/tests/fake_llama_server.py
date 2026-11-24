#!/usr/bin/env python3
"""Ersatz für llama-server in Tests: gleiche Endpunkte, feste Antworten."""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

args = sys.argv[1:]
port = int(args[args.index("--port") + 1])
ctx = int(args[args.index("-c") + 1])

SUMMARY = {
    "zusammenfassung": "Das Team besprach den Umzug und die Tourenplanung.",
    "themen": [{"titel": "Umzug", "punkte": ["Der Mietvertrag ist unterschrieben. [1]", "Neue Möbel werden benötigt. [2]"]},
               {"titel": "Tourenplanung", "punkte": ["Die Touren im Norden sind zu lang. [4]"]}],
    "beschluesse": ["Ab März wird die Tour Nord geteilt. [6]"],
    "offene_punkte": ["Zusätzliche Pflegekraft klären. [6]"],
    "aufgaben": [{"aufgabe": "Drei Angebote für Möbel einholen [2]", "verantwortlich": "Frau Becker", "frist": "Freitag"},
                 {"aufgabe": "Telefonanlage klären [4]", "verantwortlich": "Herr Wagner", "frist": "Ende Januar"}],
    "erledigte_aufgaben": [], "sensible_daten": [],
}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _j(self, o, code=200):
        b = json.dumps(o).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/health":
            return self._j({"status": "ok"})
        if self.path == "/props":
            return self._j({"default_generation_settings": {"n_ctx": ctx}})
        self._j({}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/tokenize":
            return self._j({"tokens": list(range(len(body["content"]) // 4 + 1))})
        if self.path == "/v1/chat/completions":
            user = body["messages"][1]["content"]
            if '"zusammenfassung"' in user:
                out = json.dumps(SUMMARY, ensure_ascii=False)
            elif "Extrahiere alle Aufgaben" in user:
                out = json.dumps({"aufgaben": SUMMARY["aufgaben"], "erledigte_aufgaben": [], "sensible_daten": []}, ensure_ascii=False)
            else:
                out = "- Notizen [1]"
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for i in range(0, len(out), 7):
                ev = {"choices": [{"delta": {"content": out[i:i + 7]}}]}
                self.wfile.write(b"data: " + json.dumps(ev).encode() + b"\n\n")
            self.wfile.write(b"data: [DONE]\n\n")
            self.close_connection = True
            return
        self._j({}, 404)


ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
