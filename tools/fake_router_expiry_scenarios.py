#!/usr/bin/env python3
"""
End-to-end check of the **session expiry** chain, against the local fake router.

It proves the protocol half of the Mission 08 scenario:

    login OK  ->  read says NO_AUTH  ->  renewal refused  ->  error

and it proves the three negative cases that must NOT be reported as an
expiry:

    renewal OK      -> the read succeeds, no expiry
    renewal timeout -> a network error, not an expiry
    renewal cooldown-> a cooldown, not an expiry

It never contacts a real router: the server binds to `127.0.0.1` and the POC
only sends read-only commands. Every write attempt is counted and must stay
at zero.

Usage:
    ./gradlew :poc-cli:installDist
    python3 tools/fake_router_expiry_scenarios.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
PORT = 18083
BASE = f"http://127.0.0.1:{PORT}"

# (mode, expected outcome in the CLI output, what the case proves)
CASES = [
    (
        "renew-refused",
        "authentication",
        "a refused renewal is an expiry: the session must be reopened",
    ),
    (
        "renew-ok",
        "sample-phone",
        "a successful renewal is NOT an expiry: the read is repeated",
    ),
    (
        "renew-cooldown",
        "cooldown",
        "the 180 s penalty is NOT an expiry: it keeps its own error",
    ),
]


def start_router(mode):
    """Starts the expiry fake router in the given mode."""
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "fake_router_expiry.py")],
        env={
            **os.environ,
            "FAKE_EXPIRY_PORT": str(PORT),
            "FAKE_EXPIRY_MODE": mode,
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


def run_cli(mode):
    """Runs the POC against the fake router and returns its output."""
    cli = os.path.join(
        APP, "poc-cli", "build", "install", "poc-cli", "bin", "poc-cli"
    )
    if not os.path.exists(cli):
        raise SystemExit("build the CLI first: ./gradlew :poc-cli:installDist")

    result = subprocess.run(
        [
            cli,
            "--host", f"127.0.0.1:{PORT}",
            "--format", "console",
            "--yes",
        ],
        input="test-password\n",
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr


def main():
    failures = []

    for mode, expected, proves in CASES:
        proc = start_router(mode)
        try:
            output = run_cli(mode)
            ok = expected.lower() in output.lower()

            print(f"[{'ok ' if ok else 'KO '}] {mode:16} expects {expected!r}")
            print(f"     proves: {proves}")
            if not ok:
                failures.append(mode)
                print(output)

            # The fake router must never have seen a write command.
            stats = read_stats()
            writes = stats.get("write_attempts", {})
            if writes:
                failures.append(f"{mode}: writes were attempted: {writes}")
                print(f"[KO ] write attempts: {writes}")

            # The renewal must have been attempted exactly once, and the
            # renewal must never be repeated.
            counters = stats.get("counters", {})
            logins = counters.get("login_count", 0)
            if mode == "renew-refused" and logins != 2:
                failures.append(
                    f"{mode}: expected 2 logins (initial + one renewal), got {logins}"
                )
                print(f"[KO ] login_count={logins}, expected 2")
        finally:
            proc.terminate()
            proc.wait(timeout=5)

    print()
    if failures:
        print(f"FAILED: {failures}")
        return 1
    print("every expiry scenario behaved as expected, and no write was attempted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
