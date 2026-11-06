"""Exercise the loopback protocol using an inert token and a simulated browser."""
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent

with TemporaryDirectory() as classes:
    subprocess.run(["javac", "-Xlint:-options", "--release", "8", "-d", classes, str(ROOT / "AppleMusicProbe.java")], check=True)
    process = subprocess.Popen(
        ["java", "-cp", classes, "AppleMusicProbe", str(ROOT), "--no-browser"],
        env={**os.environ, "DANGO_AM_DEVELOPER_TOKEN": "fixture.token.only"},
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
    )
    try:
        url, pairing = process.stdout.readline().strip().split("#", 1)
        origin = url.rstrip("/")

        def post(path, key=None, browser_origin=origin, body=""):
            headers = {"Origin": browser_origin}
            if key is not None:
                headers["Authorization"] = "Bearer " + key
            try:
                with urlopen(Request(url + path, body.encode(), headers), timeout=3) as response:
                    return response.status, response.read().decode()
            except HTTPError as error:
                return error.code, error.read().decode()

        def command(line):
            process.stdin.write(line + "\n")
            process.stdin.flush()

        process.stdout.readline()
        assert post("pair", pairing, "http://elsewhere.invalid")[0] == 403
        assert post("pair", "invalid")[0] == 403
        status, session = post("pair", pairing)
        assert status == 200 and session and session != pairing
        assert post("pair", pairing)[0] == 403
        assert post("token", pairing)[0] == 403
        assert post("token", session) == (200, "fixture.token.only")

        report = "0 1 7 12500 190000"
        assert post("sync", session, body=report) == (200, "0 idle")
        command("status")
        assert "authorized=1 libraryCount=7 positionMs=12500 durationMs=190000" in process.stdout.readline()

        command("load 12345")
        for _ in range(20):
            status, payload = post("sync", session, body=report)
            if payload == "1 load 12345":
                break
            time.sleep(0.05)
        assert (status, payload) == (200, "1 load 12345")

        command("stop")
        for _ in range(20):
            status, payload = post("sync", session, body=report)
            if payload == "2 stop":
                break
            time.sleep(0.05)
        assert (status, payload) == (200, "2 stop")
        command("status")
        assert "positionMs=0 durationMs=0" in process.stdout.readline()
        assert post("sync", session, body="2 1 7 0 0") == (200, "2 stop")

        command("quit")
        assert process.wait(timeout=5) == 0
        print("Loopback pairing, origin validation, status, and stop generation passed.")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
