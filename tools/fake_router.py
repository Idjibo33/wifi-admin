#!/usr/bin/env python3
"""
Local fake ZLT CGI used to exercise the POC end-to-end without touching a router.

It speaks only the five read-only commands of phase 1 (232, 100, 223, 224, 225)
and answers anything else with an explicit refusal.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SESSION = "fake-session-id"
CHALLENGE = "fake-challenge-token"

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

        if cmd == 232:
            response = {"success": True, "token": CHALLENGE}
        elif cmd == 100:
            response = {"success": True, "sessionId": SESSION}
        elif cmd == 233:
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
        elif cmd == 278:
            subcmd = str(request.get("subcmd", "1"))
            if request.get("method") == "POST":
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

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    # ThreadingHTTPServer: the POC opens more than one connection (the device
    # client and the Wi-Fi filter client), and a single-threaded server would
    # make the second one wait until the first one closes its keep-alive
    # connection.
    server = ThreadingHTTPServer(("127.0.0.1", 18080), Handler)
    server.daemon_threads = True
    server.serve_forever()
