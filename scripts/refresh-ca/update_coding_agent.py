#!/usr/bin/env python3
"""Build a selected Haifa checkout, package CA, and update the local installation."""
import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
import zipfile

# The Coding Agent commands share one contracts module; keep the import usable when
# this file is loaded by path (tests) instead of being run as a script.
sys.path.insert(
    0, str(Path(__file__).resolve().parents[2] / 'dist-support' / 'scripts')
)
from distribution_contracts import (  # noqa: E402 - repository scripts run unpackaged
    cli_build_command,
    pom_version,
    sha256_file,
)

DEFAULT_REPO = Path(__file__).resolve().parents[2]
DEFAULT_INSTALL = Path(os.environ.get('HAIFA_CA_INSTALL_ROOT', r'D:\dev\haifa-agent'))

# The "latest" channel keeps its own installation next to the versioned one. It differs from the
# versioned package in exactly two files: the launcher name and the version key inside the CLI JAR.
LATEST_FOLDER = 'haifa-coding-agent-latest'
LATEST_LAUNCHER = 'haifa-latest.bat'
VERSION_ENTRY = 'META-INF/haifa/coding-agent-version.properties'
VERSION_LABEL = re.compile(r'^[A-Za-z0-9._+-]{1,64}$')


def git(repo, *args):
    return subprocess.check_output(
        ['git', '-C', str(repo), *args], text=True, encoding='utf-8', errors='strict'
    ).strip()


def worktrees(repo):
    result = []
    for field in git(repo, 'worktree', 'list', '--porcelain', '-z').split('\0'):
        if field.startswith('worktree '):
            result.append({'path': Path(field[9:]).resolve()})
        elif field.startswith('branch '):
            result[-1]['branch'] = field[7:]
    return result


def select_source(repo, target, kind):
    """Return (checkout, ref-to-materialize); never switch an existing checkout."""
    entries = worktrees(repo)
    if kind != 'branch':
        candidate = Path(target).expanduser()
        matches = [entry['path'] for entry in entries if (
            entry['path'] == candidate.resolve() if candidate.is_absolute()
            else entry['path'].name == target
        )]
        if len(matches) > 1:
            raise ValueError('Ambiguous worktree name; provide its absolute path')
        if matches:
            if kind == 'auto' and not candidate.is_absolute():
                branch_exists = subprocess.run(
                    ['git', '-C', str(repo), 'show-ref', '--verify', '--quiet', f'refs/heads/{target}']
                ).returncode == 0
                same_branch = any(e['path'] == matches[0] and e.get('branch') == f'refs/heads/{target}'
                                  for e in entries)
                if branch_exists and not same_branch:
                    raise ValueError('Name matches different worktree and branch; use --branch or --worktree')
            return matches[0], None
        if kind == 'worktree' or candidate.is_absolute():
            raise ValueError('Worktree must be a registered checkout of this repository')
    branch = target.removeprefix('refs/heads/')
    # Fully qualified refs prevent accidental tag/SHA/path interpretation.
    if subprocess.run(['git', 'check-ref-format', '--branch', branch],
                      capture_output=True).returncode:
        raise ValueError('Invalid branch name')
    ref = f'refs/heads/{branch}'
    git(repo, 'show-ref', '--verify', ref)
    matches = [e['path'] for e in entries if e.get('branch') == ref]
    if len(matches) > 1:
        raise ValueError('Branch has multiple checkouts; select a worktree explicitly')
    if matches:
        return matches[0], None
    sha = git(repo, 'rev-parse', '--verify', ref + '^{commit}')
    slug = re.sub(r'[^A-Za-z0-9_-]+', '-', branch)[:48]
    checkout = repo.parent / 'haifa-agent-worktrees' / f'ca-build-{slug}-{sha[:12]}'
    registered = [e for e in entries if e['path'] == checkout]
    if registered:
        if git(checkout, 'rev-parse', 'HEAD') != sha or git(checkout, 'status', '--porcelain'):
            raise ValueError('Cached build worktree has changed; select another checkout explicitly')
        return checkout, None
    if checkout.exists():
        raise ValueError(f'Unregistered build directory already exists: {checkout}')
    return checkout, sha


def is_reparse(path):
    """Catch Windows junctions too, including on Python 3.11."""
    return path.exists() and bool(path.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def run(command, cwd):
    print('[RUN] ' + subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def powershell_entry(script):
    shell = shutil.which('pwsh') or shutil.which('powershell')
    if not shell:
        raise RuntimeError('PowerShell is required')
    return [shell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(script)]


def is_ca_bin(entry, install_root):
    path = Path(os.path.expandvars(entry.strip().strip('"')))
    return (path.name.lower() == 'bin'
            and path.parent.name.lower().startswith('haifa-coding-agent-v')
            and path.parent.parent == install_root)


def path_with_install(old_path, install_root, new_bin):
    """Replace only CA bin entries immediately under this installation root."""
    result, inserted = [], False
    for entry in old_path.split(';'):
        if is_ca_bin(entry, install_root):
            if not inserted:
                result.append(str(new_bin))
                inserted = True
        else:
            result.append(entry)
    if not inserted:
        if result == ['']:
            result = []
        result.append(str(new_bin))
    return ';'.join(result)


def path_with_addition(old_path, new_bin):
    """Add one bin directory once, keeping every other entry (both channels stay runnable)."""
    entries = [entry for entry in old_path.split(';') if entry]
    wanted = os.path.normcase(os.path.normpath(str(new_bin)))
    present = {
        os.path.normcase(os.path.normpath(os.path.expandvars(entry.strip().strip('"'))))
        for entry in entries
    }
    if wanted in present:
        return old_path
    return ';'.join([*entries, str(new_bin)])


def read_user_path():
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as key:
        try:
            return winreg.QueryValueEx(key, 'Path')
        except FileNotFoundError:
            return '', winreg.REG_EXPAND_SZ


def write_user_path(value, value_type):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment', 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, 'Path', 0, value_type, value)
    if read_user_path()[0] != value:
        raise RuntimeError('User PATH readback mismatch')
    # Notify Explorer/new terminals; already running shells keep their own environment.
    result = ctypes.c_size_t()
    ctypes.windll.user32.SendMessageTimeoutW(
        ctypes.c_void_p(0xffff), 0x001a, 0, ctypes.c_wchar_p('Environment'),
        2, 1000, ctypes.byref(result)
    )


def check_machine_path(install_root, new_bin):
    import winreg
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                       r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment') as key:
        machine_path = winreg.QueryValueEx(key, 'Path')[0]
    # Machine PATH precedes User PATH in new shells. Never claim a successful switch if
    # an older CA in Machine PATH would keep winning; do not silently require admin.
    for entry in machine_path.split(';'):
        if is_ca_bin(entry, install_root):
            if Path(os.path.expandvars(entry.strip().strip('"'))) != new_bin:
                raise RuntimeError('An older CA bin is in Machine PATH; remove it there before retrying')


def extract_checked(archive, parent, folder):
    # Strip the archive's single product directory into the private staging area.
    with zipfile.ZipFile(archive) as package:
        files = []
        for entry in package.infolist():
            try:
                relative = Path(entry.filename).relative_to(folder)
            except ValueError:
                raise ValueError('Archive contains a path outside the expected CA directory') from None
            destination = (parent / relative).resolve()
            if not destination.is_relative_to(parent):
                raise ValueError('Archive contains a path outside the expected CA directory')
            files.append((entry, destination))
        for entry, destination in files:
            if entry.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with package.open(entry) as source, destination.open('wb') as output:
                    shutil.copyfileobj(source, output)
    for relative in ('bin/haifa.bat', 'jre/bin/java.exe', 'haifa-coding.yaml'):
        if not (parent / relative).is_file():
            raise ValueError(f'Incomplete package: {relative}')
    return parent


def smoke(checkout, directory, launcher='haifa.bat'):
    with tempfile.TemporaryDirectory(prefix='smoke-', dir=directory) as isolated:
        env = os.environ.copy()
        env['HAIFA_DATA_ROOT'] = isolated
        for key in ('HAIFA_SQLITE_DATABASE_PATH', 'HAIFA_TRANSCRIPT_ROOT', 'HAIFA_LOG_DIR'):
            env.pop(key, None)
        result = subprocess.run(
            [str(checkout / 'bin' / launcher), '--help'], cwd=isolated, env=env,
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60,
            creationflags=subprocess.CREATE_NO_WINDOW
        )
        if result.returncode or '--workspace' not in result.stdout:
            raise RuntimeError(f'CA launcher smoke failed (exit {result.returncode}); deployment cancelled')


def verify_packaged_jar(archive, folder, built_jar):
    """The archive must carry exactly the JAR produced by the selected checkout."""
    with zipfile.ZipFile(archive) as package:
        packaged = package.read(f'{folder}/lib/{built_jar.name}')
    if hashlib.sha256(packaged).hexdigest() != sha256_file(built_jar):
        raise RuntimeError('Package JAR differs from selected checkout build')


def latest_label(source, head):
    """Identify a local build by its checkout directory name plus the first six commit characters."""
    label = f'{Path(source).name}-{head[:6]}'
    if not VERSION_LABEL.match(label):
        raise ValueError(f'Checkout name cannot form a version label: {Path(source).name}')
    return label


def patch_jar_version(source_bytes, label):
    """Rewrite only the packaged version key; every other JAR entry keeps its bytes."""
    patched = io.BytesIO()
    found = False
    with zipfile.ZipFile(io.BytesIO(source_bytes)) as original:
        with zipfile.ZipFile(patched, 'w', zipfile.ZIP_DEFLATED) as rebuilt:
            for entry in original.infolist():
                data = original.read(entry.filename)
                if entry.filename == VERSION_ENTRY:
                    found = True
                    data = f'version={label}\n'.encode('utf-8')
                rebuilt.writestr(entry, data)
    if not found:
        raise ValueError(f'CLI JAR lacks {VERSION_ENTRY}; rebuild the selected checkout')
    return patched.getvalue()


def assemble_latest(archive, install_root, version, label, report_dir):
    """Publish the latest-channel installation; PATH and the versioned installation stay untouched."""
    folder = f'haifa-coding-agent-v{version}'
    jar_name = f'haifa-agent-cli-{version}.jar'
    target = install_root / LATEST_FOLDER
    if target.is_symlink() or is_reparse(target):
        raise ValueError('Latest installation must not be a symlink/junction')
    if target.resolve().parent != install_root:
        raise ValueError('Latest installation target escaped the install root')
    install_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ca-latest-', dir=install_root) as temporary:
        staging = Path(temporary).resolve()
        packaged = extract_checked(archive, staging / 'package', folder)
        packaged_jar = packaged / 'lib' / jar_name
        packaged_launcher = packaged / 'bin/haifa.bat'
        if not packaged_jar.is_file() or not packaged_launcher.is_file():
            raise ValueError('Expected CLI JAR or launcher missing from package')
        latest = staging / LATEST_FOLDER
        shutil.copytree(packaged, latest)
        launcher_bytes = packaged_launcher.read_bytes()
        (latest / 'bin/haifa.bat').unlink()
        (latest / 'bin' / LATEST_LAUNCHER).write_bytes(launcher_bytes)
        (latest / 'lib' / jar_name).write_bytes(patch_jar_version(packaged_jar.read_bytes(), label))
        smoke(latest, staging, LATEST_LAUNCHER)
        previous = None
        if target.exists():
            for name in ('lib', 'bin', 'jre'):
                child = target / name
                if not child.is_dir() or is_reparse(child):
                    raise ValueError(f'Invalid existing installation: {child}')
            jars = list((target / 'lib').glob('haifa-agent-cli*.jar'))
            if [jar.name for jar in jars] != [jar_name]:
                raise ValueError('Existing latest lib must contain exactly the expected CLI JAR')
            previous = install_root / f'.{LATEST_FOLDER}.previous-{uuid.uuid4().hex}'
            try:
                os.replace(target, previous)
            except PermissionError as exc:
                raise RuntimeError(
                    'Latest package is locked or not writable. Exit any running haifa-latest, then retry.'
                ) from exc
        try:
            os.replace(latest, target)
        except Exception:
            if previous is not None and not target.exists():
                os.replace(previous, target)
            raise
        if previous is not None:
            shutil.rmtree(previous, ignore_errors=True)
        return {'channel': 'latest', 'mode': 'latest-replace' if previous else 'latest-install',
                'version_label': label, 'launcher': str(target / 'bin' / LATEST_LAUNCHER),
                'jar_sha256': sha256_file(target / 'lib' / jar_name)}


def deploy(archive, install_root, version, report_dir):
    folder = f'haifa-coding-agent-v{version}'
    target = install_root / folder
    if target.is_symlink() or is_reparse(target):
        raise ValueError('Installation directory must not be a symlink/junction')
    if target.resolve().parent != install_root:
        raise ValueError('Installation target escaped the install root')
    jar_name = f'haifa-agent-cli-{version}.jar'
    install_root.mkdir(parents=True, exist_ok=True)
    # Stage on the destination volume so replacing an existing JAR is atomic.
    with tempfile.TemporaryDirectory(prefix='.ca-update-', dir=install_root) as temporary:
        staging = Path(temporary).resolve()
        packaged = extract_checked(archive, staging, folder)
        packaged_jar = packaged / 'lib' / jar_name
        if not packaged_jar.is_file():
            raise ValueError('Expected CLI JAR missing from package')
        expected_hash = sha256_file(packaged_jar)
        smoke(packaged, staging)
        backup = None
        if target.exists():
            for name in ('lib', 'bin', 'jre'):
                child = target / name
                if not child.is_dir() or is_reparse(child):
                    raise ValueError(f'Invalid existing installation: {child}')
            jars = list((target / 'lib').glob('haifa-agent-cli*.jar'))
            if jars != [target / 'lib' / jar_name]:
                raise ValueError('Existing lib must contain exactly the expected CLI JAR')
            installed_jar = jars[0]
            if installed_jar.is_symlink():
                raise ValueError('Installed JAR must not be a symlink')
            backup = report_dir / jar_name
            shutil.copy2(installed_jar, backup)
            # Check the installed launcher/JRE/config with the new JAR before replacing anything.
            compatibility = staging / 'compatibility'
            compatibility.mkdir()
            shutil.copytree(target / 'bin', compatibility / 'bin')
            shutil.copytree(target / 'jre', compatibility / 'jre')
            shutil.copy2(target / 'haifa-coding.yaml', compatibility / 'haifa-coding.yaml')
            (compatibility / 'lib').mkdir()
            shutil.copy2(packaged_jar, compatibility / 'lib' / jar_name)
            smoke(compatibility, staging)
            pending = installed_jar.with_suffix('.pending-' + uuid.uuid4().hex)
            try:
                shutil.copy2(packaged_jar, pending)
                os.replace(pending, installed_jar)
            except PermissionError as exc:
                raise RuntimeError('JAR is locked or not writable. Exit the running CA, then retry.') from exc
            finally:
                pending.unlink(missing_ok=True)
            mode = 'jar-update'
        else:
            # Windows can reject renaming a whole JRE directory after running its
            # java.exe. Copy a new installation and smoke it before publishing PATH.
            # mkdir must succeed exclusively: never clean up someone else's directory.
            target.mkdir()
            try:
                shutil.copytree(packaged, target, dirs_exist_ok=True)
                smoke(target, staging)
            except Exception:
                if target.resolve().parent == install_root and not is_reparse(target):
                    shutil.rmtree(target)
                raise
            installed_jar = target / 'lib' / jar_name
            mode = 'new-install'
        if sha256_file(installed_jar) != expected_hash:
            raise RuntimeError('Installed JAR checksum mismatch')
        return {'mode': mode, 'jar_sha256': sha256_file(installed_jar),
                'jar_backup': str(backup) if backup else None}


def prune_stale_reports(artifacts_dir, keep):
    """Delete every older ca-update-* artifact directory, keeping the current run."""
    removed = []
    for candidate in sorted(Path(artifacts_dir).glob('ca-update-*')):
        if candidate.is_dir() and candidate.resolve() != Path(keep).resolve():
            shutil.rmtree(candidate)
            removed.append(candidate.name)
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['update', 'latest'])
    parser.add_argument('target', nargs='?', help='Branch, worktree name, or absolute checkout path')
    selectors = parser.add_mutually_exclusive_group()
    selectors.add_argument('--branch', help='Explicit local branch name')
    selectors.add_argument('--worktree', help='Explicit worktree name or absolute path')
    parser.add_argument('--repo-root', type=Path, default=DEFAULT_REPO)
    parser.add_argument('--install-root', type=Path, default=DEFAULT_INSTALL)
    parser.add_argument('--dry-run', action='store_true', help='Show plan without building or writing')
    args = parser.parse_args()
    if bool(args.target) + bool(args.branch) + bool(args.worktree) != 1:
        parser.error('Supply exactly one target, --branch, or --worktree')
    if os.name != 'nt':
        parser.error('Windows is required for CA packaging and User PATH updates')
    repo = args.repo_root.resolve(strict=True)
    install_root = args.install_root.resolve()
    source, materialize = select_source(
        repo, args.target or args.branch or args.worktree,
        'branch' if args.branch else 'worktree' if args.worktree else 'auto'
    )
    content = git(repo, 'show', materialize + ':pom.xml') if materialize else (source / 'pom.xml').read_text(encoding='utf-8')
    version = pom_version(content)
    head = materialize or git(source, 'rev-parse', 'HEAD')
    latest = args.action == 'latest'
    if latest:
        label = latest_label(source, head)
        target = install_root / LATEST_FOLDER
        operation = 'latest-replace' if target.exists() else 'latest-install'
    else:
        label = None
        target = install_root / f'haifa-coding-agent-v{version}'
        operation = 'jar-update' if target.exists() else 'new-install'
        check_machine_path(install_root, target / 'bin')
    plan = {'source': str(source), 'create_detached_worktree_at': materialize,
            'channel': 'latest' if latest else 'versioned',
            'version': version, 'version_label': label, 'destination': str(target),
            'operation': operation,
            'build': 'invoke-haifa-maven.ps1 --layer L2 -- -pl :haifa-agent-cli -am -DskipUnitTests=true clean package',
            'package': 'dist-support/scripts/build-distributions.ps1 ca',
            'path_scope': 'User (append)' if latest else 'User', 'dry_run': args.dry_run}
    print(json.dumps(plan, indent=2), flush=True)
    if args.dry_run:
        return
    if materialize:
        source.parent.mkdir(parents=True, exist_ok=True)
        git(repo, 'worktree', 'add', '--detach', str(source), materialize)
    for relative in ('build-support/scripts/invoke-haifa-maven.ps1', 'dist-support/scripts/build-distributions.ps1'):
        if not (source / relative).is_file():
            raise ValueError(f'Selected source lacks {relative}')
    dirty = bool(git(source, 'status', '--porcelain'))
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    report_dir = repo / 'local-tmp' / 'artifacts' / f'ca-update-{stamp}'
    report_dir.mkdir(parents=True)
    old_path, value_type = read_user_path()
    if latest:
        new_path = path_with_addition(old_path, target / 'bin')
    else:
        new_path = path_with_install(old_path, install_root, target / 'bin')
    report = {**plan, 'commit': head, 'dirty_source': dirty, 'user_path_before': old_path,
              'path_registry_type': value_type, 'status': 'started'}
    report_file = report_dir / 'report.json'
    report_file.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    try:
        run(cli_build_command(source), source)
        run(powershell_entry(source / 'dist-support/scripts/build-distributions.ps1')
            + ['ca', '--output-dir', str(report_dir)], source)
        if git(source, 'rev-parse', 'HEAD') != head or pom_version((source / 'pom.xml').read_text(encoding='utf-8')) != version:
            raise RuntimeError('Source HEAD/version changed during build; deployment cancelled')
        archive = report_dir / f'haifa-coding-agent-windows-x64-v{version}.zip'
        built_jar = source / 'haifa-agent-applications/haifa-agent-cli/target' / f'haifa-agent-cli-{version}.jar'
        folder = f'haifa-coding-agent-v{version}'
        verify_packaged_jar(archive, folder, built_jar)
        report.update({'archive': str(archive), 'archive_sha256': sha256_file(archive)})
        if latest:
            report.update(assemble_latest(archive, install_root, version, label, report_dir))
            if read_user_path() != (old_path, value_type):
                raise RuntimeError('User PATH changed concurrently; latest package installed but PATH not changed')
            if new_path != old_path:
                write_user_path(new_path, value_type)
            report.update({'status': 'success', 'user_path_after': new_path})
        else:
            report.update(deploy(archive, install_root, version, report_dir))
            # Refuse to overwrite a simultaneous edit by another installer/settings window.
            if read_user_path() != (old_path, value_type):
                raise RuntimeError('User PATH changed concurrently; JAR installed but PATH not changed')
            if new_path != old_path:
                write_user_path(new_path, value_type)
            report.update({'status': 'success', 'user_path_after': new_path})
        report['pruned_artifacts'] = prune_stale_reports(report_dir.parent, report_dir)
        if latest:
            print(f'[OK] {report["mode"]}: {target}\n[OK] version {label}\n'
                  f'[OK] launcher: {target / "bin" / LATEST_LAUNCHER}\n'
                  f'[OK] User PATH: {target / "bin"}\n'
                  '[INFO] The versioned installation is unchanged; reopen your terminal, then run haifa-latest.')
        else:
            print(f'[OK] {report["mode"]}: {target}\n[OK] User PATH: {target / "bin"}\nReopen your terminal to use the updated PATH.')
    except Exception as exc:
        report.update({'status': 'failed', 'error': str(exc)})
        raise
    finally:
        report_file.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(f'[REPORT] {report_file}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'[ERROR] {error}', file=sys.stderr)
        sys.exit(1)
