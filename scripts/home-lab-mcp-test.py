#!/usr/bin/env python3
"""Exercise the actual clamctl MCP stdio protocol. Requires Python 3.9+, no packages.

Uses normal clamctl saved configuration or CLAMAV_* environment credentials.
Only writes a temporary clean file inside the explicitly chosen upload root.
"""
import argparse
import json
import queue
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class MCP:
    def __init__(self, binary, root):
        self.events = queue.Queue(maxsize=100)
        self.serial = 0
        self.process = subprocess.Popen(
            [binary, "mcp", "serve", "--upload-root", str(root), "--interval=500ms", "--timeout=2m"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            while True:
                line = self.process.stdout.readline(8 * 1024 * 1024 + 1)
                if not line or len(line) > 8 * 1024 * 1024:
                    break
                self.events.put(json.loads(line), timeout=1)
        except (ValueError, OSError, queue.Full):
            pass
        finally:
            try:
                self.events.put(None, timeout=1)
            except queue.Full:
                pass

    def send(self, method, params, identifier=None):
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if identifier is not None:
            message["id"] = identifier
        self.process.stdin.write(json.dumps(message).encode() + b"\n")
        self.process.stdin.flush()

    def call(self, method, params):
        self.serial += 1
        self.send(method, params, self.serial)
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            try:
                event = self.events.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty:
                break
            require(event is not None, "MCP process closed its output; check clamctl configuration, credentials and TLS.")
            if event.get("id") == self.serial:
                require("error" not in event, "MCP protocol request failed: " + method)
                return event["result"]
        raise RuntimeError("MCP response timed out: " + method)

    def tool(self, name, arguments, error=False):
        result = self.call("tools/call", {"name": name, "arguments": arguments})
        require(bool(result.get("isError")) == error, "Unexpected tool outcome: " + name + "; check scopes, paths and API availability.")
        print("PASS", name, "(expected denial)" if error else "", flush=True)
        return result.get("structuredContent") or result

    def close(self):
        try:
            self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clamctl", required=True, help="Absolute path to the matching clamctl executable")
    parser.add_argument("--upload-root", required=True, type=Path, help="Existing narrow lab directory")
    parser.add_argument("--eicar-file", type=Path, help="Optional official EICAR fixture beneath the upload root")
    parser.add_argument("--server-path", help="Optional lab server path authorized by the API token")
    args = parser.parse_args()
    root = args.upload_root.resolve()
    require(root.is_dir() and root != Path(root.anchor), "Choose an existing narrow lab directory, not a filesystem root.")
    require(Path(args.clamctl).is_absolute() and Path(args.clamctl).is_file(), "Provide an absolute clamctl executable path.")
    client = MCP(args.clamctl, root)
    try:
        result = client.call("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "clamav-home-lab-test", "version": "1"}})
        require(result.get("serverInfo", {}).get("name") == "clamav-webui", "Unexpected MCP server identity.")
        client.send("notifications/initialized", {})
        tools = client.call("tools/list", {})
        require({t["name"] for t in tools["tools"]} == {"clamav_scan_file", "clamav_scan_path", "clamav_get_job", "clamav_check_health", "clamav_list_endpoints"}, "Tool discovery mismatch.")
        resources = client.call("resources/list", {})
        require({r["uri"] for r in resources["resources"]} == {"clamav://stats", "clamav://jobs/recent", "clamav://audit/recent"}, "Resource discovery mismatch.")
        print("PASS initialization and discovery", flush=True)
        health = client.tool("clamav_check_health", {})
        require(health.get("health", {}).get("connectivity") == "UP", "ClamD is not UP; inspect Endpoint Health.")
        print("Definition freshness:", health["health"].get("signatureDatabaseFreshness", "UNKNOWN"), flush=True)
        client.tool("clamav_list_endpoints", {})
        with tempfile.NamedTemporaryFile(prefix="mcp-clean-", suffix=".txt", dir=root, delete=False) as file:
            clean = Path(file.name)
            file.write(b"Harmless MCP home-lab fixture\n")
        try:
            job = client.tool("clamav_scan_file", {"file_path": str(clean)})
            require(job.get("verdict") == "OK", "Clean fixture did not finish with OK.")
            detail = client.tool("clamav_get_job", {"job_id": job["id"]})
            require(detail.get("verdict") == "OK", "Persisted job verdict changed.")
        finally:
            clean.unlink(missing_ok=True)
        if args.eicar_file:
            infected = args.eicar_file.resolve()
            require(root in infected.parents and infected.is_file(), "EICAR fixture must exist beneath the chosen upload root.")
            job = client.tool("clamav_scan_file", {"file_path": str(infected)})
            require(job.get("verdict") == "VIRUS_FOUND" and job.get("signatures"), "EICAR was not detected; inspect the actual job and daemon signatures.")
        if args.server_path:
            job = client.tool("clamav_scan_path", {"path": args.server_path})
            require(job.get("status") == "ACCEPTED" and job.get("jobId"), "Server path submission was not accepted.")
        else:
            print("SKIP server-path execution: provide --server-path to explicitly authorize that test.", flush=True)
        for resource in resources["resources"]:
            result = client.call("resources/read", {"uri": resource["uri"]})
            require(result.get("contents"), "Resource has no content: " + resource["uri"])
            json.loads(result["contents"][0]["text"])
            print("PASS", resource["uri"], flush=True)
        with tempfile.TemporaryDirectory(prefix="mcp-outside-") as directory:
            outside = Path(directory) / "outside.txt"
            outside.write_text("Harmless outside-root fixture")
            require(root not in outside.resolve().parents, "Choose a narrower upload root for the confinement test.")
            client.tool("clamav_scan_file", {"file_path": str(outside)}, error=True)
        print("PASS home-lab MCP acceptance checks", flush=True)
    finally:
        client.close()


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print("FAIL:", error, file=sys.stderr)
        sys.exit(1)
