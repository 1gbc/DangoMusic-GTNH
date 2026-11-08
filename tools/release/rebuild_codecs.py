"""Rebuild bundled LGPL codecs from the exact source archives (Python 3.9+, JDK 8).

Sources are extracted once; later runs keep your edits. --probe adds a harmless
extra class to each library to verify the final shaded JAR uses the replacement.
No codec source is relicensed or downloaded by this script.
"""
import argparse
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--probe', action='store_true')
    args = parser.parse_args()
    suffix = '.exe' if (args.jdk / 'bin/javac.exe').exists() else ''
    javac, jar = (args.jdk / ('bin/' + name + suffix) for name in ['javac', 'jar'])
    for group, artifact, version, package in [
        ('org.jcraft', 'jorbis', '0.0.17', 'com.jcraft.jorbis'),
        ('javazoom', 'jlayer', '1.0.1', 'javazoom.jl.decoder'),
    ]:
        source = args.sources / (artifact + '-' + version + '-sources.jar')
        tree = args.output / 'editable-sources' / artifact
        if not tree.exists():
            with zipfile.ZipFile(source) as archive:
                for name in archive.namelist():
                    path = PurePosixPath(name)
                    if path.is_absolute() or '..' in path.parts or '\\' in name:
                        raise ValueError('Unsafe archive path')
                archive.extractall(tree)
        if args.probe:
            probe = tree / package.replace('.', '/') / 'DangoCodecRebuildProbe.java'
            probe.write_text('package ' + package + '; public final class DangoCodecRebuildProbe {'
                             ' public static String value() { return "rebuilt-from-source"; }}\n',
                             encoding='utf-8')
        # Always recompile; never remove user-editable sources.
        classes = args.output / 'classes' / artifact
        classes.mkdir(parents=True, exist_ok=True)
        sources = sorted(tree.rglob('*.java'))
        arguments = args.output / (artifact + '-sources.txt')
        arguments.write_text('\n'.join('"' + p.resolve().as_posix() + '"' for p in sources), encoding='utf-8')
        subprocess.run([str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-d', str(classes), '@' + str(arguments)], check=True)
        for resource in tree.rglob('*'):
            relative = resource.relative_to(tree)
            if resource.is_file() and resource.suffix != '.java' and relative.parts[0] != 'META-INF':
                destination = classes / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(resource, destination)
        repository = args.output / 'maven' / group.replace('.', '/') / artifact / version
        repository.mkdir(parents=True, exist_ok=True)
        target = repository / (artifact + '-' + version + '.jar')
        subprocess.run([str(jar), 'cf', str(target.resolve()), '-C', str(classes), '.'], check=True)
        target.with_suffix('.pom').write_text(
            '<project><modelVersion>4.0.0</modelVersion><groupId>' + group + '</groupId><artifactId>' +
            artifact + '</artifactId><version>' + version + '</version></project>\n', encoding='utf-8')
        print('Built', target)
    print('Replacement repository:', (args.output / 'maven').resolve())


if __name__ == '__main__':
    main()
