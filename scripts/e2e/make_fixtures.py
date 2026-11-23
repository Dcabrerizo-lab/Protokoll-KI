"""Fertig verarbeitete Besprechungen (meeting.json + kurzes Audio) für UI-/Klicktests."""
import json, math, os, struct, sys, time, uuid, wave

OUT = sys.argv[1]
now = int(time.time() * 1000)
def wav(path, secs=20):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * 220 * i / 16000))) for i in range(16000 * secs)))

def meeting(title, days_ago, template="ALLGEMEIN", imported=None):
    mid = str(uuid.uuid4()); d = f"{OUT}/{mid}"; os.makedirs(d, exist_ok=True)
    segs = [{"s": i % 3, "a": i * 6.0, "b": i * 6.0 + 5.5, "t": t} for i, t in enumerate([
        "Guten Morgen, wir beginnen mit dem Umzug.", "Der Mietvertrag ist unterschrieben.",
        "Frau Becker, holen Sie bitte bis Freitag drei Angebote ein?", "Ja, das mache ich.",
        "Ab März wird die Tour Nord geteilt.", "Herr Wagner klärt die Telefonanlage bis Ende Januar."])]
    m = {"id": mid, "title": title, "createdAt": now - days_ago * 86400000, "durationSec": 36.0,
         "audioFile": f"/data/data/de.protokollki/files/meetings/{mid}/audio.wav", "status": "DONE", "mode": "ERGEBNIS",
         "expectedSpeakers": 0, "error": None, "agenda": ["Umzug", "Touren"], "sensitiveTerms": ["Frau Schulz"],
         "audioDeleted": imported is not None, "template": template, "location": "Büro", "chair": "Herr Wagner",
         "minuteTaker": "Frau Becker", "importedFrom": imported, "followUps": [],
         "segments": segs,
         "speakers": [{"id": 0, "name": "Herr Wagner", "profileId": None}, {"id": 1, "name": "Frau Becker", "profileId": None},
                      {"id": 2, "name": "Person 3", "profileId": None}],
         "summary": {"overview": "Umzug und Tourenplanung wurden besprochen.",
                     "topics": [{"title": "TOP 1: Umzug", "points": ["Mietvertrag unterschrieben.", "Angebote werden eingeholt."], "refs": [1, 2]},
                                {"title": "TOP 2: Touren", "points": ["Tour Nord wird ab März geteilt."], "refs": [4]}],
                     "decisions": ["Ab März wird die Tour Nord geteilt."], "openPoints": ["Zusätzliche Pflegekraft?"],
                     "decisionRefs": [4], "openPointRefs": [-1]},
         "tasks": [{"text": "Drei Angebote einholen", "owner": "Frau Becker", "due": "Fr., 27.11.2026", "done": False, "id": mid + "-0", "ref": 2, "doneNote": None},
                   {"text": "Telefonanlage klären", "owner": "Herr Wagner", "due": "Sa., 31.01.2027", "done": False, "id": mid + "-1", "ref": 5, "doneNote": None}]}
    json.dump(m, open(f"{d}/meeting.json", "w"), ensure_ascii=False)
    if imported is None: wav(f"{d}/audio.wav")

meeting("Teamsitzung", 1)
meeting("Vorstandssitzung", 7, "VEREINSSITZUNG")
meeting("Altes Protokoll März", 200, imported="protokoll_maerz.pdf")
print("Fixtures:", os.listdir(OUT))
