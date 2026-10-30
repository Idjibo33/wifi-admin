#!/usr/bin/env python3
"""Offline end-to-end check of the firewall diagnostic, against the fake router.

It starts `tools/fake_router.py`, runs the POC's firewall read for several
scenarios and checks the rendered report. It never contacts a real router.

Usage: python3 tools/fake_router_scenarios.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
PORT = 18081
BASE = f"http://127.0.0.1:{PORT}"

# The POC has no way to set a custom header, so the scenario is selected
# through the query string of the CGI path, which the fake router reads.
SCENARIOS = [
    # The reply the real firmware sent on 30/10/2026: success true, message
    # present and empty, datas absent.
    ("fw-no-datas", "RÉPONSE INVALIDE", "default"),
    # The same, but with a confirmed empty collection.
    ("fw-msg-empty-datas", "VIDE (confirmée)", "default"),
    ("fw-null-datas", "RÉPONSE INVALIDE", "default"),
    ("fw-empty-datas", "DONNÉES REÇUES", "default"),
    ("fw-datas-object", "RÉPONSE INVALIDE", "default"),
    ("fw-limited-access", "INDISPONIBLE", "default"),
    ("fw-limited-access-success-true", "INDISPONIBLE", "default"),
    # An unknown message is not a refusal: it is an unusable answer.
    ("fw-unknown-message", "INDÉTERMINÉ", "default"),
    ("fw-no-success-flag", "INDÉTERMINÉ", "default"),
    # The variant that reads the default policy with command 28.
    ("default", "DONNÉES REÇUES", "cmd28"),
]


def start_router(scenario="default"):
    """Starts the fake router with the given scenario.

    The scenario is read from the environment by the router itself, so the
    router has to be restarted for each scenario.
    """
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "fake_router.py")],
        env={
            **os.environ,
            "FAKE_ROUTER_PORT": str(PORT),
            "FAKE_SCENARIO": scenario,
        },
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(50):
        try:
            urllib.request.urlopen(f"{BASE}/__stats", timeout=1).read()
            return proc
        except Exception:
            time.sleep(0.1)
    proc.terminate()
    raise SystemExit("the fake router did not start")


def read_stats():
    with urllib.request.urlopen(f"{BASE}/__stats", timeout=2) as r:
        return json.loads(r.read().decode())


def main():
    cli = os.path.join(
        APP, "poc-cli", "build", "install", "poc-cli", "bin", "poc-cli"
    )
    if not os.path.exists(cli):
        raise SystemExit("build the CLI first: ./gradlew :poc-cli:installDist")

    failures = []
    for scenario, expected, variant in SCENARIOS:
        proc = start_router(scenario)
        try:
            args = [
                cli, "--host", f"127.0.0.1:{PORT}", "--format", "diagnostic",
                "--firewall-filter", "--yes",
            ]
            if variant != "default":
                args += ["--firewall-variant", variant]
            result = subprocess.run(
                args,
                input="test\n", capture_output=True, text=True,
            )
            out = result.stdout
            ok = expected in out
            print(
                f"[{'ok ' if ok else 'KO '}] {scenario:32} "
                f"({variant:7}) expects {expected!r}"
            )
            if not ok:
                failures.append(scenario)
                print(out)

            if "AA:BB:CC:DD:EE" in out:
                failures.append(scenario + " (mac leaked)")
                print("  LEAK: a MAC address was printed")

            stats = read_stats()
            writes = stats.get("write_attempts", {})
            if writes:
                failures.append(f"{scenario}: writes were attempted: {writes}")
                print(f"[KO ] write attempts: {writes}")
        finally:
            proc.terminate()
            proc.wait(timeout=5)

    print()
    if failures:
        print(f"FAILED: {failures}")
        return 1
    print("all scenarios behaved as expected, and no write was attempted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
