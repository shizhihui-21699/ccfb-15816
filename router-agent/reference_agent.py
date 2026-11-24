#!/usr/bin/env python3
"""
RoomRadar per-device traffic agent reference implementation.

Runs on a Linux travel router / Raspberry Pi that acts as the trusted AP.
It reads Wi-Fi station counters from `iw ... station dump` and names/IPs from
`ip neigh` and dnsmasq leases. The Android app differences consecutive
cumulative counters and correlates their rates with guided walk windows.

This reference uses HTTP bearer auth for easy testing. In a product, expose it
only on the LAN and put HTTPS with a QR-pinned certificate/public key in front.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = os.environ.get("ROOMRADAR_AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("ROOMRADAR_AGENT_PORT", "8787"))
TOKEN = os.environ.get("ROOMRADAR_TOKEN", "")
DHCP_LEASES = Path(os.environ.get("DHCP_LEASES", "/tmp/dhcp.leases"))


def run_text(args: list[str]) -> str:
    try:
        result = subprocess.run(args, check=False, capture_output=True, text=True, timeout=3)
        return result.stdout if result.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


def wifi_interfaces() -> list[str]:
    ifaces_env = os.environ.get("WIFI_IFACES")
    if ifaces_env:
        return [item.strip() for item in ifaces_env.split(",") if item.strip()]

    output = run_text(["iw", "dev"])
    interfaces = re.findall(r"^\s*Interface\s+(\S+)", output, flags=re.MULTILINE)
    return interfaces or ["wlan0"]


def parse_station_dump(output: str) -> dict[str, dict[str, int]]:
    stations: dict[str, dict[str, int]] = {}
    blocks = re.split(r"(?=^Station\s+)", output, flags=re.MULTILINE)

    for block in blocks:
        match = re.match(r"Station\s+([0-9a-fA-F:]{17})", block)
        if not match:
            continue
        mac = match.group(1).upper()

        def integer_field(name: str) -> int:
            found = re.search(rf"^\s*{re.escape(name)}:\s*(\d+)", block, flags=re.MULTILINE)
            return int(found.group(1)) if found else 0

        stations[mac] = {
            "rxBytes": integer_field("rx bytes"),
            "txBytes": integer_field("tx bytes"),
            "rxPackets": integer_field("rx packets"),
            "txPackets": integer_field("tx packets"),
        }
    return stations


def station_counters() -> dict[str, dict[str, int]]:
    counters: dict[str, dict[str, int]] = {}
    for iface in wifi_interfaces():
        dump = run_text(["iw", "dev", iface, "station", "dump"])
        for mac, values in parse_station_dump(dump).items():
            # A MAC should normally appear on one AP interface. Sum if drivers expose duplicates.
            if mac in counters:
                for key, value in values.items():
                    counters[mac][key] += value
            else:
                counters[mac] = dict(values)
    return counters


def ip_neighbors() -> dict[str, str]:
    output = run_text(["ip", "neigh"])
    mapping: dict[str, str] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 5 and re.fullmatch(r"([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}", parts[4]):
            mapping[parts[4].upper()] = parts[0]
    return mapping


def dhcp_names() -> dict[str, str]:
    names: dict[str, str] = {}
    try:
        lines = DHCP_LEASES.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return names

    for line in lines:
        columns = line.split()
        if len(columns) >= 4:
            mac = columns[1].upper()
            hostname = columns[3]
            if hostname and hostname != "*":
                names[mac] = hostname
    return names


def devices_payload() -> list[dict[str, Any]]:
    counters = station_counters()
    neighbors = ip_neighbors()
    names = dhcp_names()
    macs = set(counters) | set(neighbors) | set(names)

    return [
        {
            "id": mac,
            "mac": mac,
            "ip": neighbors.get(mac),
            "hostname": names.get(mac),
            "transport": "wifi" if mac in counters else "unknown",
        }
        for mac in sorted(macs)
    ]


def counters_payload() -> list[dict[str, Any]]:
    now = int(time.time() * 1000)
    neighbors = ip_neighbors()
    return [
        {
            "deviceId": mac,
            "mac": mac,
            "ip": neighbors.get(mac),
            "timestampMs": now,
            **values,
        }
        for mac, values in station_counters().items()
    ]


class Handler(BaseHTTPRequestHandler):
    server_version = "RoomRadarAgent/0.1"

    def _authorized(self) -> bool:
        if not TOKEN:
            return True
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {TOKEN}"

    def _send_json(self, status: int, payload: Any) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - stdlib API
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            self._send_json(200, {"ok": True})
            return

        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return

        if path == "/api/v1/devices":
            self._send_json(200, devices_payload())
        elif path == "/api/v1/counters":
            self._send_json(200, counters_payload())
        else:
            self._send_json(404, {"error": "not_found"})

    def log_message(self, fmt: str, *args: Any) -> None:
        # Keep the reference agent quiet by default.
        if os.environ.get("ROOMRADAR_AGENT_DEBUG"):
            super().log_message(fmt, *args)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"RoomRadar agent listening on http://{HOST}:{PORT}")
    if not TOKEN:
        print("WARNING: ROOMRADAR_TOKEN is empty; anyone on this LAN can read device metadata.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
