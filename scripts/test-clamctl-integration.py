#!/usr/bin/env python3
"""Run the native Linux CLI against the real Spring app and a synthetic ClamD.

Requires a packaged application JAR, cli/dist/linux_amd64/clamctl and Go on PATH.
All database/settings/files are created in a temporary directory.
"""
from pathlib import Path
import datetime
import os
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class Clamd(socketserver.StreamRequestHandler):
    def handle(self):
        first = self.rfile.read(1)
        if not first:
            return
        end = b"\0" if first == b"z" else b"\n"
        data = bytearray()
        while len(data) < 8192:
            char = self.rfile.read(1)
            if not char or char == end:
                break
            data.extend(char)
        command = data.decode("utf-8")
        name, _, argument = command.partition(" ")
        version = "ClamAV 1.5.4/27920/" + datetime.datetime.now(datetime.timezone.utc).strftime("%a %b %d %H:%M:%S %Y")
        if name == "PING":
            reply = "PONG"
        elif name == "VERSION":
            reply = version
        elif name in {"COMMANDS", "VERSIONCOMMANDS"}:
            reply = version + "| COMMANDS: PING VERSION STATS INSTREAM MULTISCAN SCAN CONTSCAN VERSIONCOMMANDS"
        elif name == "STATS":
            reply = "POOLS: 1\nTHREADS: live 5 idle 3 max 10 idle-timeout 30\nQUEUE: 0 items\nMEMSTATS: heap N/A pools 1 pools_used 1.500M pools_total 2.000M\nEND"
        elif name == "INSTREAM":
            payload = bytearray()
            while True:
                header = self.rfile.read(4)
                if len(header) != 4:
                    return
                size = struct.unpack("!I", header)[0]
                if size == 0:
                    break
                payload.extend(self.rfile.read(size))
            if b"CLAMD_ERROR" in payload:
                reply = "stream: Synthetic scanner failure ERROR"
            else:
                reply = "stream: Win.Test.EICAR_HDB-1 FOUND" if b"EICAR" in payload else "stream: OK"
        elif name in {"SCAN", "MULTISCAN", "CONTSCAN"}:
            target = Path(argument)
            try:
                payload = target.read_bytes()
                reply = argument + (": Win.Test.EICAR_HDB-1 FOUND" if b"EICAR" in payload else ": OK")
            except OSError:
                reply = argument + ": File unavailable ERROR"
        else:
            reply = "UNKNOWN COMMAND"
        self.wfile.write(reply.encode("utf-8") + end)


class ClamdServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    jar = ROOT / "target/clamav-web-client-2.1.0.jar"
    binary = ROOT / "cli/dist/linux_amd64/clamctl"
    if not jar.is_file() or not binary.is_file():
        raise SystemExit("Build the JAR and CLI binaries before running this integration check.")
    with tempfile.TemporaryDirectory(prefix="clamctl-spring-") as name, ClamdServer(("127.0.0.1", 0), Clamd) as clamd:
        folder = Path(name)
        scan = folder / "scan"
        scan.mkdir()
        sample = scan / "clean.txt"
        sample.write_text("clean path validation")
        (folder / "conf").mkdir()
        (folder / "conf/clamav-web-client.properties").write_text("app.allowedScanRoots=" + str(scan) + "\n")
        password = "ClamctlIsolatedIntegrationAdmin456"
        env = os.environ.copy()
        env.update({"CLAMAV_HOST": "127.0.0.1", "CLAMAV_PORT": str(clamd.server_address[1]),
                    "CLAMAV_UPLOAD_PATH_SCAN_ENABLED": "false", "CLAMAV_CONFIG_PATH": "", "FRESHCLAM_CONFIG_PATH": "",
                    "CLAMAV_IDENTITY_LDAP_ENABLED": "false", "CLAMAV_IDENTITY_OIDC_ENABLED": "false",
                    "CLAMAV_BOOTSTRAP_ADMIN_PASSWORD": password})
        threading.Thread(target=clamd.serve_forever, daemon=True).start()
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        with (folder / "application.log").open("w+") as log:
            application = subprocess.Popen(["java", "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=" + str(port),
                "--server.forward-headers-strategy=native", "--server.tomcat.remoteip.internal-proxies=127.0.0.1/32", "--spring.datasource.url=jdbc:h2:mem:cli-e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password="], cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                server = "http://127.0.0.1:" + str(port)
                deadline = time.monotonic() + 55
                while True:
                    if application.poll() is not None or time.monotonic() > deadline:
                        log.seek(0)
                        raise RuntimeError("isolated Spring app did not start:\n" + log.read()[-6000:])
                    try:
                        with urllib.request.urlopen(server + "/login", timeout=1) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        time.sleep(0.2)
                env.update({"CLAMCTL_INTEGRATION_SERVER": server, "CLAMCTL_INTEGRATION_BINARY": str(binary),
                            "CLAMCTL_INTEGRATION_PASSWORD": password, "CLAMCTL_INTEGRATION_SCAN_PATH": str(sample), "GOTOOLCHAIN": "local"})
                subprocess.run([os.environ.get("CLAMCTL_GO", "go"), "test", "-count=1", "-run", "^TestSpringApplication$", "-v", "./internal/cli"], cwd=ROOT / "cli", env=env, check=True, timeout=180)
            finally:
                application.terminate()
                try:
                    application.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    application.kill()
                    application.wait(timeout=5)
                clamd.shutdown()


if __name__ == "__main__":
    main()
