"""Verify final release checksums, nested archives, metadata and component list.

The secret rules are a bounded check, not a complete security audit. Only paths
and rule names are reported; matching content is never printed.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

RULES = {
    'private-key': rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'github-token': rb'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}',
    'jwt': rb'eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}',
    'cookie': rb'MUSIC_U\s*[=:]\s*[\"\x27][A-Za-z0-9%+/_-]{30,}',
}


def inspect(data, name, depth=0):
    for rule, pattern in RULES.items():
        if re.search(pattern, data):
            raise ValueError('Sensitive-data candidate: ' + name + ' (' + rule + ')')
    if depth < 4 and name.lower().endswith(('.jar', '.zip')):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for path in archive.namelist():
                if path.endswith('/'):
                    continue
                if path.lower().endswith(('.p8', '.pem', '.mp3', '.flac', '.ogg', '.m4a', '.wav', '.pyc')):
                    raise ValueError('Unexpected release resource: ' + name + '/' + path)
                if 'apple-webview/' in path or path.startswith(('accounts/', 'logs/', 'crash-reports/')):
                    raise ValueError('Unexpected private directory: ' + path)
                inspect(archive.read(path), name + '/' + path, depth + 1)


def main():
    directory = Path(sys.argv[1])
    for row in (directory / 'SHA256SUMS.txt').read_text().splitlines():
        checksum, name = row.split('  ', 1)
        if Path(name).name != name:
            raise ValueError('Unsafe checksum path')
        data = (directory / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == checksum, name
        inspect(data, name)
        print('Verified checksum and archive scan:', name)
    with zipfile.ZipFile(directory / 'dangomusicgtnh-0.1.0-source-bundle.zip') as archive:
        prefix = 'DangoMusic-GTNH-0.1.0/'
        manifest = json.loads(archive.read(prefix + 'SOURCE_MANIFEST.json'))
        for source in manifest['dependencies']:
            data = archive.read(prefix + 'dependency-sources/' + source['file'])
            assert hashlib.sha256(data).hexdigest() == source['sha256']
        assert 'version = "0.1.0"' in archive.read(prefix + 'build.gradle.kts').decode()
    with zipfile.ZipFile(directory / 'dangomusicgtnh-apple-player-0.1.0-windows-x64.zip') as archive:
        manifest = json.loads(archive.read('apple-player/COMPONENT_MANIFEST.json'))
        expected = {'apple-player/' + row['file'] for row in manifest['files']}
        expected.update(['apple-player/COMPONENT_MANIFEST.json', 'apple-player/README.txt'])
        assert set(archive.namelist()) == expected
        for row in manifest['files']:
            assert hashlib.sha256(archive.read('apple-player/' + row['file'])).hexdigest() == row['sha256']
        assert manifest['selfContained'] is False
    print('Release source, dependency and component manifests verified')


if __name__ == '__main__':
    main()
