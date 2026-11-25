"""Port von TokenAlignTest.kt und shortenEvenly-Test (Parakeet-Spracherkennung wie in der App)."""
from pkpc.generator import shorten_evenly
from pkpc.transcribe import Turn, Word, speaker_at, tokens_to_words, windows, words_to_segments

TOKENS = [" G", "uten", " Mor", "gen", ".", " Ja", ",", " gern", "e", "."]
TS = [0.1, 0.2, 0.4, 0.5, 0.6, 3.0, 3.1, 3.3, 3.4, 3.5]


def test_tokens_become_words_with_offset():
    w = tokens_to_words(TOKENS, TS, None, 10.0)
    assert [x.text for x in w] == ["Guten", "Morgen.", "Ja,", "gerne."]
    assert abs(w[0].start - 10.1) < 1e-3 and abs(w[2].start - 13.0) < 1e-3


def test_words_grouped_by_speaker_and_renumbered():
    w = tokens_to_words(TOKENS, TS, None, 0.0)
    s = words_to_segments(w, [Turn(0, 1, 5), Turn(2.8, 4, 2)])
    assert [(x.speaker_id, x.text) for x in s] == [(0, "Guten Morgen."), (1, "Ja, gerne.")]


def test_words_between_turns_go_to_nearest_speaker():
    turns = [Turn(0, 1, 0), Turn(5, 6, 1)]
    assert speaker_at(1.5, turns) == 0 and speaker_at(4.2, turns) == 1 and speaker_at(3, []) == 0


def test_single_stray_word_is_merged():
    w = [Word("Wir", 0, 0.3), Word("teilen", 0.3, 0.6), Word("die", 0.6, 0.8), Word("Tour.", 0.8, 1.2)]
    s = words_to_segments(w, [Turn(0, 0.6, 0), Turn(0.6, 0.8, 1), Turn(0.8, 2, 0)])
    assert [x.text for x in s] == ["Wir teilen die Tour."]


def test_windows_cut_at_quiet_points():
    assert windows(60, lambda a, b: a + 2) == [(0, 17), (17, 34), (34, 51), (51, 60)]
    assert windows(10, lambda a, b: a) == [(0, 10)]


def test_shorten_evenly_keeps_every_part():
    text = "\n\n".join(f"Notizen zu Teil {n}:\n" + "\n".join(f"- Punkt {i} aus Teil {n}" for i in range(1, 21))
                       for n in range(1, 4))
    short = shorten_evenly(text, 0.5)
    assert len(short) < len(text) * 0.6
    assert all(f"Notizen zu Teil {n}:" in short for n in range(1, 4))
