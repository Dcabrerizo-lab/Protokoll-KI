"""Zusätzliche Kontroll-Szenarien („neu“) für die Host-Bewertung: andere Branchen, andere Formulierungen.
Sie prüfen, ob Prompts und Regeln allgemein greifen und nicht nur auf die Emulator-Szenarien passen.
Format wie scripts/e2e/scenarios/scenarios.py, Erwartungen wie gold.py."""

HOLDOUT = {
    "neu_elternabend": dict(
        template="ALLGEMEIN",
        lines=[
            ("A", "Guten Abend, ich eröffne den Elternabend der Klasse vier b. Erstes Thema ist die Klassenfahrt."),
            ("B", "Die Jugendherberge in Bad Harzburg hätte im Mai noch Plätze, die Kosten liegen bei etwa hundertachtzig Euro pro Kind."),
            ("C", "Das ist für manche Familien viel. Gibt es einen Zuschuss vom Förderverein?"),
            ("A", "Herr Demir, könnten Sie beim Förderverein nachfragen?"),
            ("C", "Klar, das kläre ich bis nächsten Donnerstag."),
            ("A", "Wer ist für die Fahrt nach Bad Harzburg? Das ist die große Mehrheit, dagegen niemand. Damit ist die Klassenfahrt beschlossen."),
            ("B", "Ich sammle das Geld ein, bitte bis Ende März überweisen."),
            ("A", "Frau Albers übernimmt die Elternbriefe. Vielen Dank für Ihr Kommen."),
        ],
        participants=["Herr Demir", "Frau Albers"],
        vocabulary=["Demir", "Albers", "Bad Harzburg"],
        gold=dict(
            tasks=[
                dict(match=[["förderverein"], ["zuschuss"]], owner=["demir"], due=["26.11.2026", "donnerstag"]),
                dict(match=[["geld"], ["einsammeln"], ["überweis"]], owner=["@B"], due=["31.03.2027", "ende märz"]),
                dict(match=[["elternbrief"]], owner=["albers"], due=[]),
            ],
            decisions=[dict(match=[["harzburg"], ["klassenfahrt"]])],
            names=["Demir", "Albers", "Bad Harzburg"], not_sensitive=["demir", "albers"],
        ),
    ),
    "neu_lager": dict(
        template="DIENSTBESPRECHUNG",
        lines=[
            ("A", "Moin zusammen. Kurzer Stand zur Inventur am Samstag."),
            ("B", "Die Lagerliste ist noch nicht vollständig, mir fehlen die Ersatzteile aus Halle zwei."),
            ("A", "Frau Petersen, übernehmen Sie bitte Halle zwei bis Freitagmittag."),
            ("C", "Geht klar."),
            ("B", "Außerdem ist der Gabelstapler wieder defekt. Ich rufe heute noch den Kundendienst an."),
            ("A", "Gut. Und wir haben entschieden, dass ab Januar die Frühschicht um sechs Uhr beginnt."),
            ("C", "Dann muss der Aushang geändert werden, das mache ich morgen."),
        ],
        participants=["Frau Petersen"],
        vocabulary=["Petersen", "Gabelstapler"],
        gold=dict(
            tasks=[
                dict(match=[["halle"]], owner=["petersen", "@C"], due=["27.11.2026", "freitag"]),
                dict(match=[["kundendienst"], ["gabelstapler"]], owner=["@B"], due=["23.11.2026", "heute"]),
                dict(match=[["aushang"]], owner=["petersen", "@C"], due=["24.11.2026", "morgen"]),
            ],
            decisions=[dict(match=[["frühschicht"]])],
            names=["Petersen"], not_sensitive=["petersen"],
        ),
    ),
    "neu_sportverein": dict(
        template="VEREINSSITZUNG",
        agenda=["Kassenbericht", "Neue Trikots", "Sonstiges"],
        lines=[
            ("A", "Ich eröffne die Sitzung, wir sind neun Mitglieder und damit beschlussfähig."),
            ("B", "Der Kassenbericht zeigt einen Überschuss von dreihundert Euro."),
            ("A", "Zum Thema Trikots: Der Antrag lautet, zwanzig neue Trikots für achthundert Euro zu kaufen. Wer stimmt dafür? Sieben. Wer stimmt dagegen? Zwei. Enthaltungen keine. Der Antrag ist angenommen."),
            ("C", "Ich hole bis zum fünften Februar drei Angebote ein."),
            ("A", "Danke, Frau Okonkwo. Dann noch die Entlastung des Kassenwarts. Alle dafür? Das ist einstimmig angenommen."),
        ],
        participants=["Frau Okonkwo"],
        vocabulary=["Okonkwo"],
        gold=dict(
            tasks=[dict(match=[["angebot"]], owner=["okonkwo"], due=["05.02.2027", "5. februar", "fünften februar"])],
            decisions=[dict(match=[["trikot"]], vote=("angenommen", ["7", "2"])),
                       dict(match=[["entlastung"]], vote=("angenommen", []))],
            structure=["agenda"],
            names=["Okonkwo"], not_sensitive=["okonkwo"],
        ),
    ),
}
