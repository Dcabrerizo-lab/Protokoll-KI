package de.protokollki.data

import de.protokollki.engine.Holder

object AppSettings { class S(val vocabulary: List<String> = emptyList()); val state = Holder(S()) }
