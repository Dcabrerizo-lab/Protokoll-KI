"""Soll-Werte (Goldstandard) für die Host-Bewertung der Protokolle (scripts/quality/score.py).

Je Szenario aus scripts/e2e/scenarios/scenarios.py:
  tasks:      erwartete Aufgaben. match = Alternativen, je Alternative alle Wortstämme im Aufgabentext
              (klein geschrieben). owner = Namensstämme oder "@<Stimme>" (= Sprecherlabel dieser Stimme);
              due = akzeptierte Teilstrings der (vom DateResolver aufgelösten) Frist. Besprechungsdatum ist
              Montag, 23.11.2026. optional=True: zählt weder als gefunden noch als falsch.
  decisions:  erwartete Beschlüsse (match wie oben) mit optionalem Abstimmungsergebnis vote=(Ergebnis, Zahlen).
  names:      Namen/Fachbegriffe, die im Protokoll genau so geschrieben vorkommen müssen.
  sensitive:  Alternativen je sensiblem Datum (Teilstring in den gemeldeten Begriffen).
  not_sensitive: Teilnehmende/Mitarbeitende, die NICHT als sensibel gemeldet werden dürfen.
  completed / not_completed: Stämme offener Aufgaben aus der Vorsitzung, die (nicht) als erledigt gelten.
  structure:  "agenda" (Themen "TOP …"), "Fall" (Themen "Fall: …").
"""

# Offene Aufgaben aus früheren Besprechungen (wie in der App: zwei_personen und drei_personen davor)
OPEN_TASKS = [
    ("Dienstplan für Dezember fertigstellen und aushängen", "Frau Kaiser"),
    ("Alle zur Weihnachtsfeier einladen", "Frau Kaiser"),
    ("Drei Angebote für die Möbel einholen", "Frau Becker"),
    ("Telefonanlage mit dem Anbieter klären", "Herr Wagner"),
    ("Entwurf für die Teilung der Tour Nord erstellen", "Herr Wagner"),
]

GOLD = {
    "zwei_personen": dict(
        tasks=[
            dict(match=[["dienstplan"]], owner=["kaiser"], due=["25.11.2026", "mittwoch"]),
            dict(match=[["einlad"], ["weihnacht"], ["laden", "alle"], ["lädt", "alle"]], owner=["kaiser"], due=["27.11.2026", "ende der woche"]),
            dict(match=[["nachtdienst"]], owner=["@T"], due=[], optional=True),
        ],
        names=["Kaiser"], not_sensitive=["kaiser"],
    ),
    "drei_personen": dict(
        tasks=[
            dict(match=[["möbel"], ["angebot"]], owner=["becker"], due=["27.11.2026", "freitag"]),
            dict(match=[["telefon"]], owner=["wagner"], due=["31.01.2027", "ende januar"]),
            dict(match=[["entwurf"], ["tour", "teil"], ["tour", "plan"]], owner=["wagner"], due=["nächste sitzung", "nächsten sitzung", "15.01.2027"]),
            dict(match=[["pflegekraft"]], optional=True),
            dict(match=[["anbieter"]], optional=True),
        ],
        decisions=[dict(match=[["tour", "märz"], ["tour", "teil"], ["tour", "aufgeteilt"]])],
        names=["Becker", "Wagner", "Tour Nord"], not_sensitive=["becker", "wagner"],
    ),
    "folgesitzung": dict(
        tasks=[
            dict(match=[["bestell"]], owner=["becker"], due=["30.11.2026", "montag"]),
            dict(match=[["telefon"], ["rückruf"], ["anbieter"]], optional=True),
        ],
        decisions=[dict(match=[["holzmann"]])],
        completed=["angebote für die möbel"], not_completed=["telefonanlage", "tour nord", "dienstplan", "weihnacht"],
        names=["Becker", "Wagner", "Holzmann"], not_sensitive=["becker", "wagner"],
    ),
    "vereinssitzung": dict(
        tasks=[
            dict(match=[["ordnungsamt"], ["genehmigung"]], owner=["schröder"], due=["31.03.2027", "ende märz"]),
            dict(match=[["artikel"], ["vereinszeitung"]], owner=["@E"], due=["10.01.2027", "zehnten januar", "10. januar"]),
        ],
        decisions=[
            dict(match=[["sommerfest"]], vote=("angenommen", ["5", "1"])),
            dict(match=[["beitrag"]], vote=("abgelehnt", ["2", "4"])),
        ],
        structure=["agenda"],
        names=["Schröder", "Ordnungsamt"], not_sensitive=["schröder"],
    ),
    "fallbesprechung": dict(
        tasks=[
            dict(match=[["höherstuf"], ["pflegegrad"], ["pflegekasse"]], owner=["schmidt"], due=["20.12.2026", "20. dezember", "zwanzigsten dezember"]),
            dict(match=[["rollator"]], owner=["yilmaz"], due=["diese woche", "27.11.2026"]),
            dict(match=[["hausbesuch"]], optional=True),
            dict(match=[["überprüf"], ["fallbesprechung"]], optional=True),
        ],
        structure=["Fall"],
        names=["Krüger", "Schmidt", "Yilmaz"],
        sensitive=[["krüger"], ["lindenstr", "linden str"], ["0170", "null eins sieben", "1234567", "1 2 3 4"]],
        not_sensitive=["schmidt", "yilmaz"],
    ),
    "uebergabe": dict(
        tasks=[
            dict(match=[["fieber"]], owner=["@R"], due=["14", "23.11.2026", "heute"]),
            dict(match=[["medikament"], ["lieferung"]], owner=["@R", "spätdienst"], due=["24.11.2026", "morgen"]),
            dict(match=[["flüssigkeit"], ["trink"]], owner=["@R", "spätdienst"], due=[], optional=True),
            dict(match=[["arzt"], ["hausarzt"]], optional=True),
        ],
        names=["Lehmann", "Becker"],
        sensitive=[["lehmann"], ["becker"]],
    ),
    # ---------------------------------------------------------------- harte Szenarien
    "stress_durcheinander": dict(
        tasks=[
            dict(match=[["ersatzwagen"], ["nachbardienst"]], owner=["wagner"], due=["23.11.2026", "heute"]),
            dict(match=[["urlaubsliste"], ["urlaub", "eintrag"]], owner=["kaiser"], due=["27.11.2026", "freitag"]),
            dict(match=[["werkstatt"]], optional=True),
        ],
        decisions=[dict(match=[["juli"], ["august"]])],
        names=["Wagner", "Kaiser", "Becker"], not_sensitive=["wagner", "kaiser", "becker"],
    ),
    "stress_grossteam": dict(
        tasks=[
            dict(match=[["wäschewagen"]], owner=["lange"], due=["30.11.2026", "monatsende"]),
            dict(match=[["arbeitsagentur"], ["stellenanzeige"]], owner=["brandt"], due=["25.11.2026", "mittwoch"]),
            dict(match=[["hauptuntersuchung"], ["fahrzeug"]], optional=True),
            dict(match=[["datenschutz"]], optional=True),
        ],
        decisions=[dict(match=[["online"]])],
        names=["Lange", "Brandt", "Qualitätszirkel"], not_sensitive=["lange", "brandt"],
    ),
    "stress_fachvokabular": dict(
        tasks=[
            dict(match=[["folgeverordnung"], ["verordnung"]], owner=["wolf"], due=[]),
            dict(match=[["wechseldruck"], ["matratze"]], owner=["wolf"], due=[]),
            dict(match=[["assessment"], ["evaluation"], ["pflegeplanung"]], owner=["neumann"], due=["07.12.2026", "zwei wochen"]),
            dict(match=[["vitalzeichen"], ["flüssigkeitsbilanz"]], owner=["@R"], due=["täglich"]),
            dict(match=[["sturzprotokoll"]], optional=True),
            dict(match=[["lagerung"]], optional=True),
            dict(match=[["medikationsplan"]], optional=True),
        ],
        decisions=[dict(match=[["verordnung"], ["wechseldruck"], ["matratze"]])],
        structure=["Fall"],
        names=["Hoffmann", "Wolf", "Neumann", "Torasemid", "Metformin", "Wechseldruckmatratze"],
        sensitive=[["hoffmann"], ["diabetes"], ["demenz"], ["herzinsuffizienz"]],
        not_sensitive=["wolf", "neumann"],
    ),
    "stress_akzente": dict(
        tasks=[
            dict(match=[["einarbeitungsplan"], ["einarbeitung"]], owner=["wagner"], due=["27.11.2026", "freitag"]),
            dict(match=[["sprachkurs"]], owner=["@B2"], due=["15."]),
            dict(match=[["praxisanleit"]], optional=True),
        ],
        names=["Petrović", "Okafor", "Wagner"],
    ),
}

NORMAL = ["zwei_personen", "drei_personen", "folgesitzung", "vereinssitzung", "fallbesprechung", "uebergabe"]
HARD = ["lang", "stress_durcheinander", "stress_grossteam", "stress_fachvokabular", "stress_akzente",
        "asr_drei_personen", "asr_vereinssitzung", "asr_fallbesprechung"]
