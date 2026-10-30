#!/usr/bin/env python3
"""
Local fake ZLT CGI used to exercise the POC end-to-end without touching a router.

It speaks the read-only commands used by the POC (232, 100, 223, 224, 225,
278, and the firewall reads 23 and 30). Any write to the firewall (POST 23,
POST 30, cmd 20) is refused and changes nothing.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SESSION = "fake-session-id"
CHALLENGE = "fake-challenge-token"

FIREWALL_RULES = [
    {
        "enableRule": True,
        "enableLink": False,
        "ippro": "IPV4",
        "mac": "AA:BB:CC:DD:EE:01",
        "remark": "test device",
    },
    {
        "enableRule": False,
        "enableLink": True,
        "ippro": "IPV4V6",
        "mac": "AA:BB:CC:DD:EE:02",
        "remark": "",
    },
]

FIREWALL_DEFAULTS = [
    {"enableRule": True, "acceptAll": "true", "ippro": "IPV4"},
    {"enableRule": True, "acceptAll": "true", "ippro": "IPV6"},
]

# Counts every write attempt, so a test can prove the POC never writes.
WRITE_ATTEMPTS = {}


def firewall_rules_reply(scenario="default"):
    """The reply of `GET 23` for the requested scenario.

    `fw-no-datas` reproduces the reply the real firmware sent on
    30/10/2026: `success` is true, `message` is present and **empty**, and
    `datas` is absent.
    """
    if scenario == "fw-no-datas":
        return {"success": True, "cmd": 23, "message": ""}
    if scenario == "fw-msg-empty-datas":
        # The same reply, but with a confirmed empty collection.
        return {"success": True, "cmd": 23, "message": "", "datas": []}
    if scenario == "fw-null-datas":
        return {"success": True, "cmd": 23, "datas": None}
    if scenario == "fw-empty-datas":
        return {"success": True, "cmd": 23, "datas": []}
    if scenario == "fw-datas-object":
        return {"success": True, "cmd": 23, "datas": {"mac": "AA:BB:CC:DD:EE:01"}}
    if scenario == "fw-limited-access":
        return {"success": False, "cmd": 23, "message": "LIMITED_ACCESS"}
    if scenario == "fw-limited-access-success-true":
        return {"success": True, "cmd": 23, "message": "LIMITED_ACCESS"}
    if scenario == "fw-unknown-message":
        return {"success": False, "cmd": 23, "message": "ERR_FW_9999 secret"}
    if scenario == "fw-no-success-flag":
        return {"cmd": 23, "message": "success", "datas": []}
    return {"success": True, "cmd": 23, "datas": FIREWALL_RULES}


def firewall_defaults_reply(scenario="default"):
    """The reply of `GET 30` for the requested scenario."""
    if scenario in ("fw-no-datas", "fw-limited-access",
                    "fw-limited-access-success-true", "fw-unknown-message",
                    "fw-no-success-flag"):
        return firewall_rules_reply(scenario)
    if scenario == "fw-msg-empty-datas":
        return {"success": True, "cmd": 30, "message": "", "datas": []}
    if scenario == "fw-null-datas":
        return {"success": True, "cmd": 30, "datas": None}
    if scenario == "fw-empty-datas":
        return {"success": True, "cmd": 30, "datas": []}
    if scenario == "fw-datas-object":
        return {"success": True, "cmd": 30, "datas": {"acceptAll": "true"}}
    return {"success": True, "cmd": 30, "datas": FIREWALL_DEFAULTS}

DHCP = [
    {
        "mac": "AA:BB:CC:DD:EE:01",
        "ip": "192.168.1.192",
        "hostname": "pixel-6",
        "interface": "wlan",
        "expires": "3600",
        "ipv6": "",
    },
    {
        "mac": "aa:bb:cc:dd:ee:02",
        "ip": "192.168.1.50",
        "hostname": "desktop",
        "interface": "lan",
        "expires": "",
        "ipv6": "",
    },
    {
        "mac": "aa:bb:cc:dd:ee:03",
        "ip": "192.168.1.77",
        "hostname": "NULL",
        "interface": "wlan",
        "expires": "",
        "ipv6": "",
    },
]

WIFI24 = [
    {
        "mac": "aa:bb:cc:dd:ee:01",
        "ip": "192.168.1.192",
        "rssi": "-72",
        "txrate": "72.2",
        "rxrate": "54.0",
        "ssid": "Airtel_5G_2.4GHz",
    }
]

WIFI5 = [
    {
        "mac": "aa:bb:cc:dd:ee:01",
        "ip": "192.168.1.192",
        "rssi": "-55",
        "txrate": "866.0",
        "rxrate": "780.0",
        "ssid": "Airtel_5G_5GHz",
    }
]

# State of the Wi-Fi MAC filter (cmd 278), per band.
# subcmd '1' = 2.4 GHz, subcmd '0' = 5 GHz.
MAC_FILTER = {
    "1": {
        "macfilter": "deny",
        "maclist": [
            {"mac": "AA:BB:CC:DD:EE:EE", "remarks": "test device"},
        ],
    },
    "0": {
        "macfilter": "close",
        "maclist": [],
    },
}


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 so the connection framing matches what OkHttp expects.
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length).decode("utf-8"))
        cmd = request.get("cmd")
        method = request.get("method")
        scenario = self.headers.get("X-Fake-Scenario") or os.environ.get(
            "FAKE_SCENARIO", "default"
        )

        if cmd == 232:
            response = {"success": True, "token": CHALLENGE}
        elif cmd == 100:
            response = {"success": True, "sessionId": SESSION}
        elif cmd == 233:
            # A write token is counted, never silently handed out.
            WRITE_ATTEMPTS["233"] = WRITE_ATTEMPTS.get("233", 0) + 1
            response = {"success": True, "token": "fake-write-token"}
        elif cmd == 132:
            # WPS: subcmd '0' = 2.4 GHz, '1' = 5 GHz.
            subcmd = str(request.get("subcmd", "0"))
            response = {
                "success": True,
                "wifiOpen": "1",
                "wlan2g_wps_switch": "0",
                "wlan5g_wps_switch": "0",
                "subcmd": subcmd,
            }
        elif cmd == 417:
            response = {"success": True, "wifiStatus": "1"}
        elif cmd == 223:
            response = {"success": True, "cmd": 223, "dhcp_list_info": DHCP}
        elif cmd == 224:
            response = {"success": True, "cmd": 224, "wlan24g_wifi_info": WIFI24}
        elif cmd == 225:
            response = {"success": True, "cmd": 225, "wlan5g_wifi_info": WIFI5}
        elif cmd == 23:
            if method == "POST":
                # Never mutate: the firewall write path is forbidden.
                WRITE_ATTEMPTS["23"] = WRITE_ATTEMPTS.get("23", 0) + 1
                response = {"success": False, "message": "READ_ONLY_FAKE"}
            else:
                response = firewall_rules_reply(scenario)
        elif cmd == 28:
            # The older, single-entry variant of the default policy.
            if method == "POST":
                WRITE_ATTEMPTS["28"] = WRITE_ATTEMPTS.get("28", 0) + 1
                response = {"success": False, "message": "READ_ONLY_FAKE"}
            else:
                response = {
                    "success": True,
                    "cmd": 28,
                    "datas": [
                        {"enableRule": True, "acceptAll": "true", "ippro": "IPV4"}
                    ],
                }
        elif cmd == 30:
            if method == "POST":
                WRITE_ATTEMPTS["30"] = WRITE_ATTEMPTS.get("30", 0) + 1
                response = {"success": False, "message": "READ_ONLY_FAKE"}
            else:
                response = firewall_defaults_reply(scenario)
        elif cmd == 20:
            # Applying pending firewall rules is a write.
            WRITE_ATTEMPTS["20"] = WRITE_ATTEMPTS.get("20", 0) + 1
            response = {"success": False, "message": "READ_ONLY_FAKE"}
        elif cmd == 278:
            subcmd = str(request.get("subcmd", "1"))
            if method == "POST":
                # The fake router applies the write, like the real one.
                MAC_FILTER[subcmd] = request.get("datas", {})
                response = {"success": True}
            else:
                response = {
                    "success": True,
                    "cmd": 278,
                    "datas": MAC_FILTER.get(
                        subcmd, {"macfilter": "close", "maclist": []}
                    ),
                }
        else:
            response = {"success": False, "message": "UNSUPPORTED_CMD"}

        body = json.dumps(response).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # A read-only introspection endpoint: it exposes the write counters
        # so a test can prove the POC never wrote anything.
        body = json.dumps({"write_attempts": WRITE_ATTEMPTS}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    # ThreadingHTTPServer: the POC opens more than one connection (the device
    # client and the Wi-Fi filter client), and a single-threaded server would
    # make the second one wait until the first one closes its keep-alive
    # connection.
    server = ThreadingHTTPServer(
        ("127.0.0.1", int(os.environ.get("FAKE_ROUTER_PORT", "18080"))), Handler
    )
    server.daemon_threads = True
    server.serve_forever()
