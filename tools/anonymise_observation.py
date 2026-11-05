#!/usr/bin/env python3
"""Anonymise a Chrome DevTools observation of the router's web interface.

The observation is a **projection**, not a capture: the script keeps only
what is needed to compare the official interface's requests with ours, and
throws away everything else **structurally**.

It never keeps a value: no cookie, no session id, no token, no password
hash, no MAC, no IP, no hostname, no remark.

Usage:
    python3 tools/anonymise_observation.py observation.json > report.json

The input file is a small JSON object of your own making:

    {
      "requests":  [{"cmd": 23, "method": "GET", "keys": ["cmd", "method", "sessionId"]}],
      "responses": [{"cmd": 23, "keys": ["cmd", "message", "success"], "datasType": "absent"}]
    }

Only `cmd` (a number), `method` (a short token), `keys` (a list of names)
and `datasType` (a closed vocabulary) survive. Anything else is dropped.
"""
import json
import re
import sys

# The only key names that may be published. Anything else is replaced.
#
# The presence of a field named `sessionId` or `passwd` is itself
# information — it proves the official interface sends it. Only its *value*
# is secret, and values are never kept.
DISPLAYABLE_KEYS = {
    "cmd", "method", "datas", "success", "message", "error", "code", "status",
    "enableRule", "enableLink", "acceptAll", "ippro", "remark", "mac",
    "macfilter", "maclist", "subcmd", "getfun", "language", "token",
    "sessionId", "username", "passwd", "datas.macfilter", "datas.maclist",
    "datas.maclist[].mac", "datas.maclist[].remarks",
}

# The prefixes a name may start with; the name is cut to the prefix.
PREFIXES = [
    "enable", "accept", "ippro", "remark", "ipv4", "ipv6", "ip", "mac",
    "port", "proto", "dns", "url", "host", "time", "date", "expire",
    "count", "num", "list", "filter", "mode", "type", "state", "switch",
    "get", "datas.",
]

# The only types a collection may be reported as.
DATAS_TYPES = {
    "absent", "null", "array", "object", "string", "number", "boolean",
}

# Nothing that looks like a secret **value** may ever appear in the report.
#
# The key *names* are allowed: they describe the shape of a request, which
# is exactly the information the procedure is after. What must never appear
# is a value — a session id, a token, a password hash, a cookie.
SECRET_VALUE_PATTERN = re.compile(
    r"(passwd|password|session_?id|token|cookie|username|authorization)"
    r"\s*[:=]\s*[\"']?[^\"'\s,}]+",
    re.IGNORECASE,
)


def sanitize_key(name):
    """Keeps only a known key name, cut to a known prefix."""
    cleaned = "".join(
        ch for ch in str(name)
        if ch.isalnum() or ch in "_.[ ]"
    ).strip()
    if not cleaned:
        return ""
    if cleaned in DISPLAYABLE_KEYS:
        return cleaned
    if "." in cleaned:
        return ".".join(sanitize_key(part) for part in cleaned.split("."))
    prefix = next((p for p in PREFIXES if cleaned.startswith(p)), None)
    return prefix + "…" if prefix else "…"


def sanitize_keys(keys):
    if not isinstance(keys, list):
        return []
    return [sanitize_key(k) for k in keys if isinstance(k, (str, int, float))]


def sanitize_method(method):
    """Only a short enumeration token may be published."""
    value = str(method or "").strip()
    return value if value in ("GET", "POST") else "?"


def sanitize_cmd(cmd):
    """Only a small integer may be published."""
    try:
        value = int(cmd)
    except (TypeError, ValueError):
        return None
    return value if 0 <= value <= 1000 else None


def sanitize_datas_type(value):
    value = str(value or "absent").strip().lower()
    return value if value in DATAS_TYPES else "absent"


def project_request(entry):
    if not isinstance(entry, dict):
        return None
    cmd = sanitize_cmd(entry.get("cmd"))
    if cmd is None:
        return None
    return {
        "cmd": cmd,
        "method": sanitize_method(entry.get("method")),
        "keys": sanitize_keys(entry.get("keys")),
    }


def project_response(entry):
    if not isinstance(entry, dict):
        return None
    cmd = sanitize_cmd(entry.get("cmd"))
    if cmd is None:
        return None
    return {
        "cmd": cmd,
        "keys": sanitize_keys(entry.get("keys")),
        "datasType": sanitize_datas_type(entry.get("datasType")),
    }


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    with open(sys.argv[1], "r", encoding="utf-8") as handle:
        observation = json.load(handle)

    report = {
        "requests": [
            r for r in (project_request(e) for e in observation.get("requests", []))
            if r is not None
        ],
        "responses": [
            r for r in (project_response(e) for e in observation.get("responses", []))
            if r is not None
        ],
    }

    text = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True)

    # The final safety net: no secret **value** may survive.
    leaked = SECRET_VALUE_PATTERN.findall(text)
    if leaked:
        print(
            "REFUS: the report still contains something that looks like a secret value: "
            + ", ".join(sorted(set(leaked))),
            file=sys.stderr,
        )
        return 1

    # A long hexadecimal blob is never a legitimate structural datum.
    if re.search(r"[0-9a-f]{32,}", text, re.IGNORECASE):
        print(
            "REFUS: the report contains a long hexadecimal string, "
            "which is what a session id or a hash looks like.",
            file=sys.stderr,
        )
        return 1

    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
