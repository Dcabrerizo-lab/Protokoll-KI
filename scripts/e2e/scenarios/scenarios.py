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
# Eigene Begriffe (Einstellungen > Fachbegriffe), wie ein Pflegedienst sie pflegen würde
VOCABULARY = {"zwei_personen": ["Kaiser"], "drei_personen": ["Becker", "Wagner", "Tour Nord"], "folgesitzung": ["Becker", "Wagner", "Holzmann"], "vereinssitzung": ["Schröder", "Ordnungsamt", "Förderverein"], "fallbesprechung": ["Schmidt", "Yilmaz", "Krüger", "Pflegekasse"], "uebergabe": ["Lehmann", "Becker"], "drei_personen_laerm": ["Becker", "Wagner", "Tour Nord"], "drei_personen_leise": ["Becker", "Wagner", "Tour Nord"], "lang": ["Becker", "Förderverein", "Krüger", "Lehmann", "Ordnungsamt", "Pflegekasse", "Schmidt", "Schröder", "Tour Nord", "Wagner", "Yilmaz"]}

LONG_PARTS = ["drei_personen", "vereinssitzung", "fallbesprechung", "drei_personen", "uebergabe"]

# ============================================================================ Stress-Szenarien
# Harte Bedingungen für Messungen (Host-Benchmark scripts/bench/asr_diar_bench.py und Job
# „Stress-Szenarien“ in e2e.yml). Die WAVs werden nicht eingecheckt, sondern mit
# `make_audio.py <voices> <out> --nur-stress` erzeugt (Namen beginnen mit „stress_“).
#
# Zusätzliche Stimmen: G=glados (Piper), außerdem abgeleitete Stimmen aus vorhandenen Piper-Stimmen
# mit anderer Tonhöhe/Sprechgeschwindigkeit („Akzent-Annäherung“, andere Stimmlage):
#   Schlüssel -> (Piper-Stimme, Sprecher-ID, Sprechtempo, Tonhöhenfaktor)
STRESS_VOICES = {
    "G": ("glados-medium", 0, 1.0, 1.0),
    "T2": ("thorsten-medium", 0, 0.85, 0.84),      # tiefer, langsam
    "K2": ("karlsson-low", 0, 1.3, 1.12),          # höher, schnell
    "B2": ("kerstin-low", 0, 0.8, 0.9),            # tiefer, sehr langsam
    "R2": ("ramona-low", 0, 1.35, 1.08),           # schnell, etwas höher
}

STRESS = {
    # -------------------------------------------------- Durcheinanderreden (überlappende Sätze)
    # overlap = Sekunden, um die der Satz vor dem Ende des vorigen beginnt
    "stress_durcheinander": dict(
        template="ALLGEMEIN",
        lines=[
            ("T", "Also, wir müssen heute dringend über die Urlaubsplanung für den Sommer sprechen.", 0),
            ("B", "Ja, aber vorher noch kurz: Der Dienstwagen mit dem Kennzeichen für Tour Süd ist schon wieder kaputt.", 0.8),
            ("K", "Schon wieder? Das ist jetzt das dritte Mal in diesem Monat.", 1.2),
            ("E", "Ich habe die Werkstatt gestern angerufen, die können erst am Donnerstag.", 0.6),
            ("T", "Moment, Moment, eins nach dem anderen. Erst der Wagen, dann der Urlaub.", 1.0),
            ("K", "Dann nehmen wir bis Donnerstag den Ersatzwagen vom Nachbardienst.", 0.4),
            ("B", "Genau, Herr Wagner, können Sie das heute noch mit dem Nachbardienst klären?", 0.9),
            ("K", "Ja, mache ich heute Nachmittag.", 0.3),
            ("E", "Und zur Urlaubsplanung: Ich brauche die ersten zwei Augustwochen frei, das ist schon lange angemeldet.", 1.1),
            ("T", "Das geht nicht, da sind schon drei Kolleginnen im Urlaub.", 1.4),
            ("B", "Vielleicht können wir tauschen, ich nehme stattdessen den Juli.", 0.7),
            ("T", "Gut, dann beschließen wir: Frau Becker hat Urlaub im Juli, Frau Kaiser im August. Frau Kaiser trägt das bis Freitag in die Urlaubsliste ein.", 0.5),
            ("E", "Mache ich, danke euch.", 0.2),
        ],
        expect=dict(speakers=[3, 5], keywords=["Urlaub", "Werkstatt", "Ersatzwagen"],
                    task_owners=["Wagner", "Kaiser"], min_tasks=2, decisions_any=["Juli", "August", "Urlaub"]),
    ),
    # -------------------------------------------------- großes Team, 8 Sprecher
    "stress_grossteam": dict(
        template="ALLGEMEIN",
        lines=[
            ("P", "Guten Tag allerseits, ich begrüße Sie zum Qualitätszirkel. Heute sind alle acht Teamleitungen dabei."),
            ("R", "Ich fange mit der Tagespflege an: Die Auslastung liegt bei neunzig Prozent, wir haben eine Warteliste."),
            ("T", "Im ambulanten Dienst fehlen uns zwei Pflegefachkräfte. Die Stellenanzeige läuft seit drei Wochen."),
            ("G", "In der Verwaltung haben wir die Abrechnung mit den Pflegekassen für Oktober abgeschlossen."),
            ("K", "Beim Fuhrpark stehen zwei Fahrzeuge zur Hauptuntersuchung an, das mache ich nächste Woche."),
            ("E", "Die Hauswirtschaft braucht neue Wäschewagen, die alten sind kaputt."),
            ("B", "Für die Fortbildung habe ich den Erste-Hilfe-Kurs am zwölften Februar organisiert."),
            ("K2", "Und im betreuten Wohnen ziehen im Januar drei neue Mieter ein."),
            ("P", "Danke. Frau Lange, holen Sie bitte bis Monatsende zwei Angebote für die Wäschewagen ein."),
            ("E", "Ja, das übernehme ich."),
            ("P", "Herr Brandt, bitte schalten Sie die Stellenanzeige zusätzlich bei der Arbeitsagentur."),
            ("T", "Mache ich bis Mittwoch."),
            ("G", "Noch ein Hinweis: Die Datenschutzschulung ist für alle Pflicht, bitte bis Ende Januar absolvieren."),
            ("R", "Können wir die Schulung auch online machen?"),
            ("G", "Ja, es gibt eine Online-Variante."),
            ("P", "Dann beschließen wir: Die Datenschutzschulung findet online statt, Frist ist Ende Januar. Vielen Dank, bis zum nächsten Qualitätszirkel."),
        ],
        expect=dict(speakers=[5, 9], keywords=["Qualitätszirkel", "Stellenanzeige", "Datenschutzschulung"],
                    task_owners=["Lange", "Brandt"], min_tasks=2, decisions_any=["online", "Datenschutz"]),
    ),
    # -------------------------------------------------- viel Fachvokabular (Pflege/Medizin/Verwaltung)
    "stress_fachvokabular": dict(
        template="FALLBESPRECHUNG",
        lines=[
            ("B", "Fallbesprechung Frau Hoffmann, vierundachtzig Jahre, Pflegegrad drei, Diagnosen Diabetes mellitus Typ zwei, Herzinsuffizienz und beginnende Demenz."),
            ("T", "Am Sakrum hat sich ein Dekubitus Kategorie zwei entwickelt. Die Wundversorgung erfolgt mit Hydrokolloidverband, Verbandswechsel alle drei Tage."),
            ("B", "Laut Braden-Skala besteht ein hohes Dekubitusrisiko. Wir brauchen eine Wechseldruckmatratze und einen Lagerungsplan."),
            ("R", "Die Insulintherapie ist umgestellt: Basalinsulin abends sechzehn Einheiten, dazu Korrekturschema nach Blutzuckermessung."),
            ("T", "Der Medikationsplan muss aktualisiert werden, Metformin wurde wegen der Niereninsuffizienz abgesetzt. Neu ist ein Diuretikum, Torasemid morgens zehn Milligramm."),
            ("R", "Achtung Sturzrisiko durch das Diuretikum, nächtliche Toilettengänge. Wir sollten ein Sturzprotokoll führen."),
            ("B", "Für die Verwaltung: Der Antrag auf Höherstufung beim Medizinischen Dienst ist gestellt, die Begutachtung ist am dritten März. Die Verordnung häuslicher Krankenpflege läuft Ende Februar aus."),
            ("T", "Dann beschließen wir: Frau Wolf fordert beim Hausarzt eine Folgeverordnung an und bestellt die Wechseldruckmatratze über das Sanitätshaus."),
            ("R", "In Ordnung. Ich dokumentiere außerdem die Vitalzeichen und die Flüssigkeitsbilanz täglich im Pflegebericht."),
            ("B", "Evaluation der Pflegeplanung in zwei Wochen, Herr Neumann übernimmt das Assessment."),
        ],
        expect=dict(speakers=[3, 4], keywords=["Dekubitus", "Insulin", "Medikationsplan", "Wechseldruckmatratze", "Verordnung"],
                    task_owners=["Wolf"], min_tasks=2, decisions_any=["Verordnung", "Wechseldruck", "Matratze"],
                    sensitive_any=["Hoffmann", "Diabetes", "Demenz"], topic_prefix="Fall"),
    ),
    # -------------------------------------------------- Akzent-Annäherung: andere Stimmlage/Tempo
    "stress_akzente": dict(
        template="DIENSTBESPRECHUNG",
        lines=[
            ("T2", "Guten Morgen. Wir sprechen heute über die Einarbeitung der neuen Kollegen aus dem Anerkennungsverfahren."),
            ("R2", "Ich betreue Frau Petrović, sie kommt gut zurecht, braucht aber noch Unterstützung bei der Dokumentation."),
            ("B2", "Herr Okafor fängt nächsten Montag an. Wer übernimmt die Praxisanleitung?"),
            ("K2", "Das kann ich machen, ich habe die Weiterbildung zum Praxisanleiter."),
            ("T2", "Sehr gut. Herr Wagner ist also Praxisanleiter für Herrn Okafor. Bitte erstellen Sie bis Freitag einen Einarbeitungsplan."),
            ("K2", "Ja, bis Freitag liegt der Plan vor."),
            ("R2", "Außerdem brauchen beide einen Sprachkurs für Fachsprache Pflege, die Anmeldung läuft bis zum fünfzehnten."),
            ("B2", "Ich melde beide bis zum fünfzehnten beim Sprachkurs an."),
            ("T2", "Danke, dann ist das beschlossen. Wir treffen uns wieder in vier Wochen."),
        ],
        expect=dict(speakers=[3, 5], keywords=["Einarbeitung", "Praxisanleit", "Sprachkurs"],
                    task_owners=["Wagner"], min_tasks=2, decisions_any=[]),
    ),
}

# Akustische Varianten bestehender Dialoge (gleicher Text und Sprecher, harte Akustik)
#   Name -> (Basisszenario, Effekt, Erwartungen-Überschreibung)
STRESS_ACOUSTIC = {
    "stress_hall": ("drei_personen", "hall", dict(speakers=[2, 4])),           # Raum mit ~0,9 s Nachhall
    "stress_fernmikrofon": ("vereinssitzung", "fern", dict(speakers=[3, 6])),  # Handy weit weg: leise, dumpf, Rauschen
    "stress_cafe": ("fallbesprechung", "cafe", dict(speakers=[2, 3])),         # Stimmengewirr + Geschirr, ~8 dB SNR
    "stress_strasse": ("uebergabe", "strasse", dict(speakers=[2, 3])),         # Verkehr, vorbeifahrende Autos, ~6 dB SNR
    "stress_fern_hall_laerm": ("drei_personen", "alles", dict(speakers=[2, 4])),  # alles zusammen
}

VOCABULARY.update({
    "stress_durcheinander": ["Becker", "Wagner", "Kaiser", "Tour Süd"],
    "stress_grossteam": ["Qualitätszirkel", "Lange", "Brandt", "Arbeitsagentur"],
    "stress_fachvokabular": ["Hoffmann", "Wolf", "Neumann", "Dekubitus", "Sakrum", "Hydrokolloidverband", "Braden-Skala",
                             "Wechseldruckmatratze", "Basalinsulin", "Korrekturschema", "Medikationsplan", "Metformin",
                             "Niereninsuffizienz", "Torasemid", "Diuretikum", "Sturzprotokoll", "Medizinischer Dienst",
                             "Verordnung häuslicher Krankenpflege", "Flüssigkeitsbilanz", "Assessment"],
    "stress_akzente": ["Petrović", "Okafor", "Wagner", "Praxisanleiter", "Anerkennungsverfahren"],
    "stress_hall": VOCABULARY["drei_personen"], "stress_fernmikrofon": VOCABULARY["vereinssitzung"],
    "stress_cafe": VOCABULARY["fallbesprechung"], "stress_strasse": VOCABULARY["uebergabe"],
    "stress_fern_hall_laerm": VOCABULARY["drei_personen"],
})

# Sehr lange Aufnahme (nur Host-Benchmark, wird nie eingecheckt): alle Szenarien so oft
# hintereinander, bis MARATHON_MINUTES erreicht sind. Erzeugen mit `make_audio.py ... --marathon`.
MARATHON_MINUTES = 60
