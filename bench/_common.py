"""Shared helpers for bench scripts. Standard library only, so the scripts run anywhere Python 3.10+ runs."""

import http.client
import json
import os
import platform
import statistics
import subprocess
import time
import urllib.parse
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sh(*cmd, check=True):
    return subprocess.run(cmd, cwd=REPO, check=check, capture_output=True, text=True).stdout.strip()


def machine_info():
    """Hardware and software context recorded next to every benchmark result (spec section 8)."""
    info = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "cpu_count": os.cpu_count(),
    }
    try:
        with open("/proc/cpuinfo") as f:
            info["cpu_model"] = next(l.split(":", 1)[1].strip() for l in f if l.startswith("model name"))
        with open("/proc/meminfo") as f:
            info["mem_total"] = next(l.split(":", 1)[1].strip() for l in f if l.startswith("MemTotal"))
    except (OSError, StopIteration):
        pass
    try:
        info["docker"] = sh("docker", "info", "--format",
                            "server={{.ServerVersion}} ncpu={{.NCPU}} mem_bytes={{.MemTotal}}")
    except (OSError, subprocess.CalledProcessError):
        info["docker"] = "unavailable"
    try:
        info["git_commit"] = sh("git", "rev-parse", "--short", "HEAD")
        info["git_dirty"] = bool(sh("git", "status", "--porcelain", "--untracked-files=no"))
    except (OSError, subprocess.CalledProcessError):
        pass
    return info


def psql(database, sql):
    """Query Postgres directly (not through the service under test) via the compose container."""
    out = sh("docker", "compose", "exec", "-T", "postgres",
             "psql", "-U", "postgres", "-d", database, "-tA", "-F", "|", "-c", sql)
    return [row.split("|") for row in out.splitlines() if row]


class Client:
    """One keep-alive HTTP/1.1 connection. Not thread-safe: use one per thread."""

    def __init__(self, base_url, timeout=30):
        u = urllib.parse.urlparse(base_url)
        self.conn = http.client.HTTPConnection(u.hostname, u.port, timeout=timeout)

    def request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if data else {}
        t0 = time.perf_counter()
        try:
            self.conn.request(method, path, body=data, headers=headers)
            resp = self.conn.getresponse()
            payload = resp.read()
            status = resp.status
        except (OSError, http.client.HTTPException):
            self.conn.close()
            status, payload = -1, b""
        return status, (time.perf_counter() - t0) * 1000.0, payload

    def close(self):
        self.conn.close()


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def median(values):
    return statistics.median(values) if values else None
