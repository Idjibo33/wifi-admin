#!/usr/bin/env python3
"""
Local fake ZLT router that reproduces a **session expiry**, deterministically.

It exists to validate the Mission 08 scenario that cannot be reproduced on
the real router without waiting for the firmware to expire a session by
itself, and without ever writing to that router.

The sequence it reproduces is the one `ZltRouterClient` implements:

1. `cmd 232` (challenge) succeeds, `cmd 100` (login) succeeds: the session is
   valid.
2. A read (`cmd 223`, `cmd 224`, `cmd 225`) answers `NO_AUTH`: the session has
   expired.
3. The client renews the session **once** (`cmd 232` + `cmd 100`). The renewal
   is **refused** (`login_fail: "fail"`), so the client raises
   `RouterAuthenticationException`.
4. The app maps that to `RouterUnavailableException.SessionExpired`.

The refusal of the renewal is what distinguishes this scenario from a simple
"session expired then renewed successfully" case, and it is the case the
Android layer must handle.

## Modes

The mode is read from the environment `FAKE_EXPIRY_MODE`, or from the
`X-Fake-Expiry-Mode` request header, which lets a test change the behaviour
without restarting the server.

| Mode | Behaviour |
|---|---|
| `renew-ok` | The read says `NO_AUTH`, the renewal succeeds, the read is repeated. **No expiry.** |
| `renew-refused` | The read says `NO_AUTH`, the renewal is refused. **This is the expiry.** |
| `renew-timeout` | The read says `NO_AUTH`, the renewal request times out. The app must see a network error, **not** an expiry. |
| `renew-cooldown` | The read says `NO_AUTH`, the challenge reply announces the 180 s penalty. The app must see a cooldown, **not** an expiry. |
| `expire-after=N` | The first N reads succeed, then the session expires (renewal refused). |

## Safety

The server is **read-only for the device commands**: it answers 232, 100 and
223/224/225, and refuses every write with a counted attempt, exactly like
`fake_router.py`. It binds to `127.0.0.1` by default.

Usage:
    FAKE_EXPIRY_PORT=18082 python3 tools/fake_router_expiry.py

Then, for the CLI:
    poc-cli --host 127.0.0.1:18082 --format json --devices
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SESSION = "fake-session-id"
CHALLENGE = "fake-challenge-token"

# How many device reads succeed before the session expires. The mode
# "renew-refused" expires on the very first read after the login.
DEFAULT_EXPIRE_AFTER = 1

# A realistic-looking device list. The MACs are the documentation examples,
# not real devices.
DHCP = [
    {
        "mac": "AA:BB:CC:DD:EE:01",
        "ip": "192.168.1.192",
        "hostname": "sample-phone",
        "interface": "wlan",
        "expires": "3600",
        "ipv6": "",
    },
]
WIFI24 = [
    {
        "mac": "AA:BB:CC:DD:EE:01",
        "ip": "192.168.1.192",
        "rssi": "-72",
        "txrate": "72.2",
        "rxrate": "54.0",
        "ssid": "sample-network",
    }
]
WIFI5 = []

WRITE_ATTEMPTS = {}

# Shared state, guarded by a lock: the server is threaded.
STATE_LOCK = threading.Lock()
STATE = {
    "read_count": 0,
    "login_count": 0,
    "challenge_count": 0,
}


def mode_from(header_value, environ_value):
    """The effective mode, header first, environment second."""
    for candidate in (header_value, environ_value):
        if candidate:
            return candidate.strip()
    return "renew-refused"


def expire_after_from(mode):
    """How many reads succeed before the expiry, for this mode."""
    if mode.startswith("expire-after="):
        try:
            return max(0, int(mode.split("=", 1)[1]))
        except ValueError:
            return DEFAULT_EXPIRE_AFTER
    return DEFAULT_EXPIRE_AFTER


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _mode(self):
        return mode_from(
            self.headers.get("X-Fake-Expiry-Mode"),
            os.environ.get("FAKE_EXPIRY_MODE"),
        )

    def _json(self, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length).decode("utf-8"))
        cmd = request.get("cmd")
        method = request.get("method")
        mode = self._mode()

        if cmd == 232:
            with STATE_LOCK:
                STATE["challenge_count"] += 1
            if mode == "renew-cooldown":
                # The router announces the 180 s penalty in the challenge
                # reply, exactly like the firmware does after failed logins.
                self._json({
                    "success": True,
                    "token": CHALLENGE,
                    "buffer": "0",
                    "netx_login_time": 0,
                })
                return
            self._json({"success": True, "token": CHALLENGE})
            return

        if cmd == 100:
            with STATE_LOCK:
                STATE["login_count"] += 1
                login_count = STATE["login_count"]
            if mode == "renew-refused" and login_count >= 2:
                # The renewal is refused: this is what turns a session expiry
                # into a confirmed expiry for the app.
                self._json({
                    "success": False,
                    "login_fail": "fail",
                    "message": "login_fail",
                })
                return
            if mode == "renew-refused" and login_count == 1:
                # The first login succeeds: the session is initially valid.
                self._json({"success": True, "sessionId": SESSION})
                return
            if mode == "expire-after" or mode.startswith("expire-after="):
                self._json({"success": True, "sessionId": SESSION})
                return
            self._json({"success": True, "sessionId": SESSION})
            return

        if cmd in (223, 224, 225):
            with STATE_LOCK:
                STATE["read_count"] += 1
                read_count = STATE["read_count"]

            if mode == "renew-ok":
                # The first read is refused, the renewal succeeds, the read
                # is repeated: the app must NOT see an expiry.
                if read_count == 1:
                    self._json({"success": False, "message": "NO_AUTH"})
                    return
                self._json(self._device_reply(cmd))
                return

            if mode == "renew-refused":
                # Every read says the session is gone. The renewal fails, so
                # the client raises an authentication exception.
                self._json({"success": False, "message": "NO_AUTH"})
                return

            if mode.startswith("expire-after="):
                limit = expire_after_from(mode)
                if read_count <= limit:
                    self._json(self._device_reply(cmd))
                    return
                self._json({"success": False, "message": "NO_AUTH"})
                return

            self._json(self._device_reply(cmd))
            return

        # Every other command is refused and counted: this server never writes.
        WRITE_ATTEMPTS[str(cmd)] = WRITE_ATTEMPTS.get(str(cmd), 0) + 1
        self._json({"success": False, "message": "READ_ONLY_FAKE"})

    def _device_reply(self, cmd):
        if cmd == 223:
            return {"success": True, "cmd": 223, "dhcp_list_info": DHCP}
        if cmd == 224:
            return {"success": True, "cmd": 224, "wlan24g_wifi_info": WIFI24}
        return {"success": True, "cmd": 225, "wlan5g_wifi_info": WIFI5}

    def do_GET(self):
        # Introspection only: it never exposes the device data.
        with STATE_LOCK:
            counters = dict(STATE)
        self._json({
            "write_attempts": WRITE_ATTEMPTS,
            "counters": counters,
            "mode": self._mode(),
        })

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("FAKE_EXPIRY_PORT", "18082"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    print(f"fake router (expiry) on 127.0.0.1:{port}", flush=True)
    server.serve_forever()
