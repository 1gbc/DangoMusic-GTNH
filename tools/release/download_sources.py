"""Download the exact release dependency sources and verify their SHA-256."""
import hashlib
from pathlib import Path
import sys
import urllib.request

SOURCES = [
    ('org/jcraft/jorbis/0.0.17/jorbis-0.0.17-sources.jar', '1643dd368b9c160276caf8d1f6a8c0aae43ca5bf49b53348a2a01623641708e5'),
    ('javazoom/jlayer/1.0.1/jlayer-1.0.1-sources.jar', 'ecde410fc8940ab5d8d5a1d5c585870a3a194f4001701e66a27b8dd8cb7b75ce'),
    ('com/google/zxing/core/3.3.3/core-3.3.3-sources.jar', 'e646f122c7fe517f4ea2325f181985e20bff215bde566dd886035cb1b33679ac'),
    ('org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21-sources.jar', '664f515359444a92267a13266101431a630f99d6b8d04407a92b1a0e558d33ee'),
    ('org/jetbrains/annotations/13.0/annotations-13.0-sources.jar', '42a5e144b8e81d50d6913d1007b695e62e614705268d8cf9f13dbdc478c2c68e'),
]


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    for path, checksum in SOURCES:
        target = output / path.split('/')[-1]
        if not target.exists():
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + path, timeout=60) as response:
                data = response.read()
        else:
            data = target.read_bytes()
        if hashlib.sha256(data).hexdigest() != checksum:
            raise ValueError('Source checksum mismatch: ' + target.name)
        target.write_bytes(data)
        print('Verified', target.name)


if __name__ == '__main__':
    main()
