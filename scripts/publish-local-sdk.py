#!/usr/bin/env python3
"""Publish a Coding product preview closure to an isolated, owned Maven-local repository."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'dist-support' / 'scripts'))
from distribution_contracts import read_pom_version, sha256_file
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ENTRY_POINTS = ('haifa-coding-api', 'haifa-coding-host', 'haifa-coding-client')


def git(*args: str) -> str:
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def modules() -> dict[str, tuple[Path, ET.Element]]:
    result = {}
    def visit(pom: Path) -> None:
        document = ET.parse(pom).getroot()
        artifact = document.findtext('m:artifactId', namespaces=NS)
        if artifact in result:
            raise ValueError('Duplicate reactor artifact')
        result[artifact] = (pom.parent, document)
        for child in document.findall('m:modules/m:module', NS):
            visit(pom.parent / child.text.strip() / 'pom.xml')
    visit(ROOT / 'pom.xml')
    return result


def closure(reactor: dict[str, tuple[Path, ET.Element]]) -> set[str]:
    included: set[str] = set()
    def include(artifact: str) -> None:
        if artifact in included:
            return
        if artifact not in reactor:
            raise ValueError('Internal dependency is outside the reactor: ' + artifact)
        included.add(artifact)
        _, document = reactor[artifact]
        parent = document.find('m:parent', NS)
        if parent is not None and parent.findtext('m:groupId', namespaces=NS) == 'io.haifa':
            include(parent.findtext('m:artifactId', namespaces=NS))
        for dependency in document.findall('m:dependencies/m:dependency', NS):
            if dependency.findtext('m:scope', default='compile', namespaces=NS) == 'test':
                continue
            if dependency.findtext('m:groupId', namespaces=NS) == 'io.haifa':
                include(dependency.findtext('m:artifactId', namespaces=NS))
    for artifact in ENTRY_POINTS:
        include(artifact)
    if {'haifa-agent-cli', 'haifa-agent-coding-terminal'} & included:
        raise ValueError('Product preview closure contains CLI or TUI')
    return included


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.publishing')
    temporary.write_bytes(data)
    temporary.replace(path)


def publish(repository: Path) -> Path:
    if git('status', '--porcelain'):
        raise ValueError('Product preview publishing requires a clean worktree')
    branch = git('branch', '--show-current')
    identifier = ROOT.name
    if not branch.startswith('feat-') or not re.fullmatch(r'[A-Za-z0-9.-]+', identifier):
        raise ValueError('Product preview requires a feat-* worktree')
    if Path(git('rev-parse', '--absolute-git-dir')).resolve() == Path(git('rev-parse', '--git-common-dir')).resolve():
        raise ValueError('Product previews cannot publish from the main checkout')
    source = git('rev-parse', 'HEAD')
    base = read_pom_version(ROOT / 'pom.xml').removesuffix('-SNAPSHOT')
    version = f'{base}-{identifier}-SNAPSHOT'
    repository = repository.expanduser().resolve()
    manifest = repository / 'io' / 'haifa' / 'haifa-coding-host' / version / 'haifa-coding-product-preview-manifest.json'
    baseline_manifest = repository / 'io' / 'haifa' / 'haifa-agent-bom' / version / 'haifa-sdk-publish-manifest.json'
    if baseline_manifest.exists():
        raise ValueError('Use a separate repository for product previews and the base SDK')
    if manifest.exists():
        previous = json.loads(manifest.read_text(encoding='utf-8'))
        if Path(previous.get('worktreePath', '')).resolve() != ROOT:
            raise ValueError('Feature coordinates are owned by another worktree')
    reactor = modules()
    included = closure(reactor)
    if not manifest.exists() and any((repository / 'io' / 'haifa' / artifact / version).exists() for artifact in included):
        raise ValueError('Existing preview coordinates have no ownership manifest')
    maven = ['-B', '-ntp', '-pl', ':haifa-coding-host,:haifa-coding-client', '-am', f'-Drevision={version}', '-DskipUnitTests=true', '-DskipITs=true', 'clean', 'package']
    wrapper = ROOT / 'build-support' / 'scripts' / ('invoke-haifa-maven.ps1' if os.name == 'nt' else 'invoke-haifa-maven.sh')
    if os.name == 'nt':
        command = [shutil.which('pwsh') or 'powershell', '-NoProfile', '-File', str(wrapper), '--layer', 'L2', '--keep-log', '--', *maven]
    else:
        command = ['sh', str(wrapper), '--layer', 'L2', '--keep-log', '--', *maven]
    subprocess.run(command, cwd=ROOT, check=True)
    if git('rev-parse', 'HEAD') != source or git('status', '--porcelain'):
        raise ValueError('Source changed during product preview build')
    timestamp = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
    files: list[tuple[Path, Path]] = []
    artifacts = []
    for artifact in sorted(included):
        directory, document = reactor[artifact]
        flattened = directory / '.flattened-pom.xml'
        if not flattened.is_file():
            raise ValueError('Flattened product POM missing: ' + artifact)
        content = flattened.read_text(encoding='utf-8')
        if '${revision}' in content or '${sha1}' in content or '${changelist}' in content:
            raise ValueError('Unexpanded product version: ' + artifact)
        target = repository / 'io' / 'haifa' / artifact / version
        outputs = [(flattened, target / f'{artifact}-{version}.pom')]
        if document.findtext('m:packaging', default='jar', namespaces=NS) != 'pom':
            jar = directory / 'target' / f'{artifact}-{version}.jar'
            if not jar.is_file():
                raise ValueError('Product JAR missing: ' + artifact)
            outputs.append((jar, target / jar.name))
        for source_file, destination in outputs:
            files.append((source_file, destination))
            artifacts.append({'groupId': 'io.haifa', 'artifactId': artifact, 'version': version, 'extension': destination.suffix[1:], 'relativePath': str(destination.relative_to(repository)).replace(os.sep, '/'), 'sha256': sha256_file(source_file), 'size': source_file.stat().st_size})
    receipt = {'mode': 'coding-product-preview', 'state': 'publishing', 'version': version, 'identifier': identifier, 'sourceSha': source, 'branch': branch, 'worktreePath': str(ROOT), 'repository': str(repository), 'entryPoints': list(ENTRY_POINTS), 'publishedAtEpochMillis': int(datetime.now(timezone.utc).timestamp() * 1000), 'artifacts': artifacts}
    atomic_write(manifest, (json.dumps(receipt, indent=2) + '\n').encode('utf-8'))
    for source_file, destination in files:
        atomic_write(destination, source_file.read_bytes())
    for artifact in sorted(included):
        target = repository / 'io' / 'haifa' / artifact / version
        names = [destination.name for _, destination in files if destination.parent == target]
        atomic_write(target / '_remote.repositories', ('# Locally published Coding product preview\n' + ''.join(name + '>=\n' for name in names)).encode('utf-8'))
        metadata = f'<metadata><groupId>io.haifa</groupId><artifactId>{artifact}</artifactId><version>{version}</version><versioning><snapshot><localCopy>true</localCopy></snapshot><lastUpdated>{timestamp}</lastUpdated></versioning></metadata>\n'
        atomic_write(target / 'maven-metadata-local.xml', metadata.encode('utf-8'))
    for artifact in artifacts:
        target = repository / artifact['relativePath']
        if sha256_file(target) != artifact['sha256'] or target.stat().st_size != artifact['size']:
            raise ValueError('Product artifact verification failed')
    receipt['state'] = 'complete'
    atomic_write(manifest, (json.dumps(receipt, indent=2) + '\n').encode('utf-8'))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['coding-product-preview'])
    default = Path(os.environ.get('MAVEN_USER_HOME', str(Path.home() / '.m2'))) / 'coding-product-preview'
    parser.add_argument('--local-repo', type=Path, default=default)
    args = parser.parse_args()
    try:
        manifest = publish(args.local_repo)
    except (ValueError, OSError, subprocess.CalledProcessError) as failure:
        print('PRODUCT_PREVIEW=FAIL ' + str(failure), file=sys.stderr)
        return 1
    print('PRODUCT_PREVIEW=PASS manifest=' + str(manifest))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
