"""Deutsche Fristangaben in feste Daten umrechnen (Port von engine/DateResolver.kt)."""
from __future__ import annotations

import calendar
import re
from datetime import date, timedelta

_WD = ["Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So."]

WEEKDAYS = {"montag": 0, "dienstag": 1, "mittwoch": 2, "donnerstag": 3, "freitag": 4,
            "samstag": 5, "sonnabend": 5, "sonntag": 6}
MONTHS = {"januar": 1, "jänner": 1, "jan": 1, "februar": 2, "feb": 2, "märz": 3, "maerz": 3,
          "april": 4, "apr": 4, "mai": 5, "juni": 6, "juli": 7, "august": 8, "aug": 8,
          "september": 9, "sept": 9, "sep": 9, "oktober": 10, "okt": 10, "november": 11,
          "nov": 11, "dezember": 12, "dez": 12}
NUMBERS = {"einem": 1, "einer": 1, "ein": 1, "eine": 1, "zwei": 2, "drei": 3, "vier": 4,
           "fünf": 5, "sechs": 6, "sieben": 7, "acht": 8, "zehn": 10, "vierzehn": 14}
_MONTH_ALT = "|".join(MONTHS.keys())


def fmt(d: date) -> str:
    return f"{_WD[d.weekday()]}, {d.day:02d}.{d.month:02d}.{d.year}"


def resolve(raw: str, meeting: date) -> str:
    d = parse(raw, meeting)
    if d is None:
        return raw.strip() or "offen"
    return fmt(d)


def _num(s: str) -> int | None:
    if s.isdigit():
        return int(s)
    return NUMBERS.get(s)


def _safe(y: int, m: int, d: int) -> date | None:
    try:
        return date(y, m, d)
    except ValueError:
        return None


def _year_for(month: int, day: int, meeting: date) -> int:
    c = _safe(meeting.year, month, day)
    if c is not None and c < meeting - timedelta(days=14):
        return meeting.year + 1
    return meeting.year


def _last_day(d: date) -> date:
    return d.replace(day=calendar.monthrange(d.year, d.month)[1])


def _add_months(d: date, n: int) -> date:
    m = d.month - 1 + n
    y = d.year + m // 12
    m = m % 12 + 1
    return date(y, m, min(d.day, calendar.monthrange(y, m)[1]))


def _next(d: date, wd: int) -> date:          # streng danach
    delta = (wd - d.weekday()) % 7
    return d + timedelta(days=delta or 7)


def _next_or_same(d: date, wd: int) -> date:
    return d + timedelta(days=(wd - d.weekday()) % 7)


def parse(raw: str, d: date) -> date | None:
    t = re.sub(r"\s+", " ", re.sub(r"[,;]", " ", raw.lower())).strip()
    if not t or t == "offen":
        return None
    m = re.search(r"(\d{1,2})\.(\d{1,2})\.(\d{2,4})?", t)
    if m:
        day, mon = int(m.group(1)), int(m.group(2))
        if 1 <= mon <= 12 and 1 <= day <= 31:
            if m.group(3):
                y = int(m.group(3))
                y = 2000 + y if y < 100 else y
            else:
                y = _year_for(mon, day, d)
            return _safe(y, mon, day)
    m = re.search(rf"(\d{{1,2}})\.? ?({_MONTH_ALT})\b(?: (\d{{4}}))?", t)
    if m:
        day, mon = int(m.group(1)), MONTHS[m.group(2)]
        y = int(m.group(3)) if m.group(3) else _year_for(mon, day, d)
        return _safe(y, mon, day)
    m = re.search(rf"(ende|anfang|mitte|spätestens ende) (?:des )?({_MONTH_ALT})\b", t)
    if m:
        mon = MONTHS[m.group(2)]
        first = date(_year_for(mon, 28, d), mon, 1)
        if "ende" in m.group(1):
            return _last_day(first)
        if m.group(1) == "mitte":
            return first.replace(day=15)
        return first
    if "heute" in t:
        return d
    if "übermorgen" in t:
        return d + timedelta(days=2)
    if re.search(r"\bmorgen\b", t):
        return d + timedelta(days=1)
    if re.search(r"ende (des|dieses) monats|monatsende|ende des laufenden monats", t):
        return _last_day(d)
    if re.search(r"ende (des )?nächsten monats", t):
        return _last_day(_add_months(d, 1))
    if re.search(r"ende (der|dieser) woche|wochenende", t):
        return _next_or_same(d, 4)
    if re.search(r"ende (der )?nächste[nr]? woche", t):
        return _next_or_same(d, 4) + timedelta(weeks=1)
    if re.search(r"ende des jahres|jahresende", t):
        return date(d.year, 12, 31)
    m = re.search(r"in (\w+) (tag|tagen|woche|wochen|monat|monaten)\b", t)
    if m:
        n = _num(m.group(1))
        if n is not None:
            if m.group(2).startswith("tag"):
                return d + timedelta(days=n)
            if m.group(2).startswith("woche"):
                return d + timedelta(weeks=n)
            return _add_months(d, n)
    for name, wd in WEEKDAYS.items():
        if re.search(rf"\b{name}\b", t):
            dt = _next(d, wd)
            if re.search(r"nächste woche|übernächste", t) and dt < _next(d, 0):
                dt += timedelta(weeks=1)
            return dt
    if re.search(r"^(bis )?(zur |in der )?nächste[nr]? woche$", t):
        return _next(d, 0)
    return None
