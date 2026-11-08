"""Assemble explicit, credential-free 0.1.0 release assets from a committed tree.

Does not publish, modify git refs or recursively copy a game/browser directory.
Uses only the Python standard library. Run from the project root.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--apple-player', type=Path, required=True)
    parser.add_argument('--dependency-sources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
        raise ValueError('Pass the exact 40-character release commit')
    version = '0.1.0'
    root = 'DangoMusic-GTNH-' + version + '/'
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / ('dangomusicgtnh-' + version + '.jar')
    with zipfile.ZipFile(args.jar) as jar:
        required = ['META-INF/LICENSE-DangoMusic-GPL-3.0.txt', 'META-INF/NOTICE-DangoMusic.txt',
                    'META-INF/licenses/Kotlin-Apache-2.0.txt', 'META-INF/licenses/JLayer-LGPL-2.1.txt',
                    'META-INF/licenses/JOrbis-LGPL-2.1.txt', 'assets/dangomusicgtnh/fonts/Inter-LICENSE.txt']
        assert all(name in jar.namelist() for name in required)
        assert not any('DangoCodecRebuildProbe' in name for name in jar.namelist())
        assert json.loads(jar.read('mcmod.info'))[0]['version'] == version
    shutil.copyfile(args.jar, target)

    dependencies = []
    coordinates = [
        ('org/jcraft/jorbis/0.0.17', 'jorbis-0.0.17-sources.jar'),
        ('javazoom/jlayer/1.0.1', 'jlayer-1.0.1-sources.jar'),
        ('com/google/zxing/core/3.3.3', 'core-3.3.3-sources.jar'),
        ('org/jetbrains/kotlin/kotlin-stdlib/2.2.21', 'kotlin-stdlib-2.2.21-sources.jar'),
        ('org/jetbrains/annotations/13.0', 'annotations-13.0-sources.jar'),
    ]
    source_zip = args.output / ('dangomusicgtnh-' + version + '-source-bundle.zip')
    committed = subprocess.check_output(['git', 'archive', '--format=zip', args.revision])
    with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as bundle:
        with zipfile.ZipFile(io.BytesIO(committed)) as tree:
            for name in tree.namelist():
                if not name.endswith('/'):
                    bundle.writestr(root + name, tree.read(name))
        for coordinate, name in coordinates:
            data = (args.dependency_sources / name).read_bytes()
            with zipfile.ZipFile(io.BytesIO(data)) as dependency:
                assert any(path.endswith(('.java', '.kt')) for path in dependency.namelist())
            dependencies.append({'file': name, 'sha256': sha(data),
                                 'url': 'https://repo.maven.apache.org/maven2/' + coordinate + '/' + name})
            bundle.writestr(root + 'dependency-sources/' + name, data)
        bundle.writestr(root + 'SOURCE_MANIFEST.json', json.dumps(
            {'version': version, 'commit': args.revision, 'dependencies': dependencies}, indent=2) + '\n')
    # Only these files enter the optional component; no bin/obj/profile blanket copy.
    component_files = ['DangoApplePlayer.exe', 'DangoApplePlayer.dll',
                       'DangoApplePlayer.deps.json', 'DangoApplePlayer.runtimeconfig.json',
                       'Microsoft.Web.WebView2.Core.dll', 'Microsoft.Web.WebView2.WinForms.dll',
                       'WebView2Loader.dll', 'runtimes/win-x64/native/WebView2Loader.dll',
                       'licenses/DangoMusic-GPL-3.0.txt', 'licenses/WebView2-SDK-LICENSE.txt',
                       'licenses/WebView2-SDK-NOTICE.txt']
    component = args.output / ('dangomusicgtnh-apple-player-' + version + '-windows-x64.zip')
    manifest = {'version': version, 'commit': args.revision, 'selfContained': False,
                'requirements': ['.NET 8 Desktop Runtime x64', 'WebView2 Evergreen Runtime'], 'files': []}
    config = json.loads((args.apple_player / 'DangoApplePlayer.runtimeconfig.json').read_text())
    assert 'frameworks' in config['runtimeOptions']  # No .NET runtime distribution.
    deps = json.loads((args.apple_player / 'DangoApplePlayer.deps.json').read_text())
    assert 'DangoApplePlayer/0.1.0' in deps['libraries']
    with zipfile.ZipFile(component, 'w', zipfile.ZIP_DEFLATED) as bundle:
        for name in component_files:
            data = (args.apple_player / name).read_bytes()
            manifest['files'].append({'file': name, 'sha256': sha(data), 'size': len(data)})
            bundle.writestr('apple-player/' + name, data)
        bundle.writestr('apple-player/COMPONENT_MANIFEST.json', json.dumps(manifest, indent=2) + '\n')
        bundle.writestr('apple-player/README.txt',
                        'DangoMusic GTNH Edition 0.1.0 - optional Windows x64 Apple player\n'
                        'Install .NET 8 Desktop Runtime x64 and WebView2 Evergreen Runtime.\n'
                        'Place this apple-player folder in .minecraft/config/dangomusicgtnh/.\n'
                        'Keep all accounts/ files. Login and protected playback run in WebView2;\n'
                        'control and browse in game. Experimental; see docs/RELEASE_0_1_0.md.\n'
                        'Source and licenses accompany this release; no account data is included.\n')
    assets = [target, source_zip, component]
    checksums = '\n'.join(sha(path.read_bytes()) + '  ' + path.name for path in assets) + '\n'
    (args.output / 'SHA256SUMS.txt').write_text(checksums, encoding='utf-8')
    print(checksums, end='')


if __name__ == '__main__':
    main()
