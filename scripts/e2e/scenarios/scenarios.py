"""Testszenarien für Protokoll-KI: Dialoge, Erwartungen und Audio-Erzeugung.

Stimmen (Piper, sherpa-onnx): Männer T=thorsten, K=karlsson, P=pavoque; Frauen B=kerstin, E=eva_k, R=ramona.
Jedes Szenario beschreibt Dialog, Vorlage, Tagesordnung und prüfbare Erwartungen.
"""

VOICES = {"T": "thorsten-medium", "K": "karlsson-low", "P": "pavoque-low",
          "B": "kerstin-low", "E": "eva_k-x_low", "R": "ramona-low"}

SCENARIOS = {
    # ------------------------------------------------------------------ 2 Personen, kurz
    "zwei_personen": dict(
        template="DIENSTBESPRECHUNG",
        lines=[
            ("T", "Hallo Frau Kaiser, kurze Abstimmung zum Dienstplan für Dezember."),
            ("E", "Gerne. Mir fehlen noch zwei Nachtdienste in der zweiten Woche."),
            ("T", "Die übernehme ich selbst. Können Sie den Dienstplan bis Mittwoch fertig machen und aushängen?"),
            ("E", "Ja, bis Mittwoch hängt er aus. Außerdem ist die Weihnachtsfeier am achtzehnten Dezember im Gemeindesaal."),
            ("T", "Prima, dann laden Sie bitte alle bis Ende der Woche ein. Danke Ihnen."),
        ],
        expect=dict(speakers=[2, 2], keywords=["Dienstplan", "Weihnachtsfeier"],
                    task_owners=["Kaiser"], min_tasks=1, decisions_any=[]),
    ),
    # ------------------------------------------------------------------ 3 Personen (Basis)
    "drei_personen": dict(
        template="ALLGEMEIN",
        lines=[
            ("T", "Guten Morgen zusammen. Ich eröffne unsere Teambesprechung. Heute geht es um den Umzug des Pflegedienstes und um die neue Tourenplanung."),
            ("B", "Morgen. Zum Umzug: Der Mietvertrag für das neue Büro in der Hauptstraße ist unterschrieben. Einzug ist am ersten Februar."),
            ("K", "Das ist gut. Wir brauchen dann aber noch neue Möbel und die Telefonanlage muss umgezogen werden."),
            ("T", "Richtig. Frau Becker, können Sie bis Freitag drei Angebote für die Möbel einholen?"),
            ("B", "Ja, das mache ich. Bis Freitag habe ich die Angebote."),
            ("T", "Sehr gut. Herr Wagner, kümmern Sie sich bitte um die Telefonanlage und klären Sie das mit dem Anbieter bis Ende Januar."),
            ("K", "In Ordnung, ich rufe morgen beim Anbieter an. Zur Tourenplanung habe ich noch einen Punkt. Die Touren im Norden sind zu lang, wir verlieren jeden Tag fast eine Stunde."),
            ("B", "Das sehe ich auch so. Wir könnten die Tour Nord teilen und einen zweiten Wagen einsetzen."),
            ("T", "Dann beschließen wir: Ab März wird die Tour Nord in zwei Touren aufgeteilt. Herr Wagner erstellt dafür bis zur nächsten Sitzung einen Entwurf."),
            ("K", "Mache ich. Offen ist noch, ob wir dafür eine zusätzliche Pflegekraft brauchen."),
            ("T", "Das klären wir in der nächsten Sitzung am fünfzehnten Januar. Vielen Dank, dann schließe ich die Besprechung."),
        ],
        expect=dict(speakers=[3, 3], keywords=["Umzug", "Tour"], task_owners=["Becker", "Wagner"],
                    min_tasks=2, decisions_any=["März", "Tour"]),
    ),
    # ------------------------------------------------------------------ Folgesitzung (Aufgabenabgleich)
    "folgesitzung": dict(
        template="ALLGEMEIN",
        after="drei_personen",
        lines=[
            ("T", "Willkommen zur Folgesitzung. Wir schauen zuerst auf die offenen Aufgaben."),
            ("B", "Die drei Angebote für die Möbel liegen vor, das ist erledigt. Das günstigste kommt von der Firma Holzmann."),
            ("T", "Danke. Dann bestellen wir bei Holzmann. Herr Wagner, wie sieht es mit der Telefonanlage aus?"),
            ("K", "Da warte ich noch auf den Rückruf vom Anbieter. Das ist noch offen."),
            ("T", "Gut, dann bleibt das bei Ihnen. Frau Becker, bitte bestellen Sie die Möbel bis Montag."),
        ],
        expect=dict(speakers=[3, 3], keywords=["Angebote", "Holzmann"], task_owners=["Becker"],
                    min_tasks=1, decisions_any=["Holzmann"], completed_tasks_min=1),
    ),
    # ------------------------------------------------------------------ 5 Personen, Verein, Abstimmung
    "vereinssitzung": dict(
        template="VEREINSSITZUNG",
        agenda=["Begrüßung und Formalien", "Sommerfest", "Mitgliedsbeitrag", "Verschiedenes"],
        lines=[
            ("P", "Ich begrüße alle Anwesenden zur Vorstandssitzung des Fördervereins. Wir sind sieben Mitglieder und damit beschlussfähig. Gibt es Einwände gegen die Tagesordnung? Das ist nicht der Fall."),
            ("R", "Zum Sommerfest: Ich schlage vor, das Fest am vierzehnten Juni im Stadtpark zu feiern. Das Budget wären zweitausend Euro."),
            ("K", "Ich finde zweitausend Euro zu viel. Fünfzehnhundert sollten reichen."),
            ("E", "Mit fünfzehnhundert Euro bekommen wir keine Bühne. Ich bin für zweitausend."),
            ("P", "Dann stimmen wir ab: Sommerfest am vierzehnten Juni mit einem Budget von zweitausend Euro. Wer ist dafür? Fünf Stimmen. Wer ist dagegen? Eine Stimme. Enthaltungen? Eine. Damit ist der Antrag angenommen."),
            ("T", "Ich kümmere mich um die Genehmigung beim Ordnungsamt, bis Ende März."),
            ("P", "Danke, Herr Schröder. Nächster Punkt, der Mitgliedsbeitrag. Der Kassenwart schlägt vor, den Beitrag auf vierzig Euro im Jahr zu erhöhen."),
            ("R", "Ich bin dagegen, das schreckt neue Mitglieder ab."),
            ("P", "Wir stimmen ab. Wer ist für die Erhöhung auf vierzig Euro? Zwei Stimmen. Dagegen? Vier. Enthaltung eine. Der Antrag ist abgelehnt."),
            ("E", "Unter Verschiedenes: Ich schreibe bis zum zehnten Januar einen Artikel für die Vereinszeitung."),
            ("P", "Vielen Dank. Ich schließe die Sitzung um zwanzig Uhr dreißig."),
        ],
        expect=dict(speakers=[3, 5], keywords=["Sommerfest", "Beitrag"], task_owners=["Schröder"],
                    min_tasks=2, decisions_any=["angenommen", "abgelehnt"], vote_format=True,
                    agenda_topics=True),
    ),
    # ------------------------------------------------------------------ Fallbesprechung, sensible Daten
    "fallbesprechung": dict(
        template="FALLBESPRECHUNG",
        lines=[
            ("B", "Wir besprechen heute Herrn Krüger aus der Lindenstraße zwölf. Er ist seit dem Sturz im Oktober unsicher beim Gehen."),
            ("T", "Seine Tochter hat angerufen, ihre Nummer ist null eins sieben null, eins zwei drei vier fünf sechs sieben. Sie wünscht einen Hausbesuch."),
            ("B", "Meine Einschätzung: Er braucht einen Rollator und wahrscheinlich einen höheren Pflegegrad."),
            ("T", "Dann vereinbaren wir: Frau Schmidt beantragt bis zum zwanzigsten Dezember die Höherstufung bei der Pflegekasse."),
            ("B", "Und Herr Yilmaz organisiert den Rollator über das Sanitätshaus, möglichst diese Woche."),
            ("T", "Überprüfung in vier Wochen in der nächsten Fallbesprechung."),
        ],
        expect=dict(speakers=[2, 2], keywords=["Krüger", "Rollator"], task_owners=["Schmidt", "Yilmaz"],
                    min_tasks=2, decisions_any=[], sensitive_any=["Krüger", "Lindenstraße"],
                    topic_prefix="Fall"),
    ),
    # ------------------------------------------------------------------ Übergabe
    "uebergabe": dict(
        template="UEBERGABE",
        lines=[
            ("E", "Übergabe vom Frühdienst an den Spätdienst. Frau Lehmann in Zimmer drei hat heute wenig getrunken, bitte auf Flüssigkeit achten."),
            ("R", "Verstanden. Wie geht es Herrn Becker?"),
            ("E", "Herr Becker hatte heute Morgen leichtes Fieber, achtunddreißig Komma eins. Der Hausarzt kommt um sechzehn Uhr."),
            ("R", "Gut, ich messe um vierzehn Uhr noch einmal Fieber und gebe dem Arzt den Wert."),
            ("E", "Allgemein: Die Medikamentenlieferung kommt morgen früh, bitte annehmen."),
        ],
        expect=dict(speakers=[2, 2], keywords=["Lehmann", "Fieber"], task_owners=[],
                    min_tasks=1, decisions_any=[]),
    ),
}

# Lange Besprechung: Basisszenarien hintereinander (ca. 6 bis 7 Minuten)
LONG_PARTS = ["drei_personen", "vereinssitzung", "fallbesprechung", "drei_personen", "uebergabe"]
