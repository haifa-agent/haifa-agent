"""Focused filesystem/ref/PATH checks; never touches the real installation or registry."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'refresh-ca' / 'update_coding_agent.py'
SPEC = importlib.util.spec_from_file_location('update_coding_agent', SCRIPT)
updater = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(updater)


class UpdaterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ca-updater-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.install = self.root / 'installed'
        self.report = self.root / 'report'
        self.report.mkdir()
        self.version = '0.1.4-SNAPSHOT'
        self.folder = 'haifa-coding-agent-v' + self.version
        self.jar_name = 'haifa-agent-cli-' + self.version + '.jar'

    def package(self, content=b'new jar'):
        archive = self.root / 'package.zip'
        with zipfile.ZipFile(archive, 'w') as package:
            for path, data in {
                'bin/haifa.bat': b'launcher', 'jre/bin/java.exe': b'jre',
                'haifa-coding.yaml': b'new default config', 'lib/' + self.jar_name: content
            }.items():
                package.writestr(self.folder + '/' + path, data)
        return archive

    def existing_install(self):
        target = self.install / self.folder
        for name in ('lib', 'bin', 'jre'):
            (target / name).mkdir(parents=True)
        (target / 'lib' / self.jar_name).write_bytes(b'old jar')
        (target / 'bin/haifa.bat').write_bytes(b'custom launcher')
        (target / 'jre/custom-runtime').write_bytes(b'old runtime')
        (target / 'haifa-coding.yaml').write_bytes(b'custom config')
        return target

    def test_new_version_installs_complete_package_and_keeps_old_version(self):
        old = self.install / 'haifa-coding-agent-v0.1.3-SNAPSHOT'
        old.mkdir(parents=True)
        (old / 'keep').write_bytes(b'old version')
        with patch.object(updater, 'smoke') as smoke:
            result = updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertEqual(result['mode'], 'new-install')
        self.assertEqual((self.install / self.folder / 'lib' / self.jar_name).read_bytes(), b'new jar')
        self.assertTrue((self.install / self.folder / 'jre/bin/java.exe').is_file())
        self.assertEqual((old / 'keep').read_bytes(), b'old version')
        self.assertEqual(smoke.call_count, 2)

    def test_failed_new_install_smoke_removes_only_new_directory(self):
        old = self.install / 'haifa-coding-agent-v0.1.3-SNAPSHOT'
        old.mkdir(parents=True)
        (old / 'keep').write_bytes(b'keep')
        with patch.object(updater, 'smoke', side_effect=[None, RuntimeError('installed smoke failed')]):
            with self.assertRaisesRegex(RuntimeError, 'installed smoke failed'):
                updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertFalse((self.install / self.folder).exists())
        self.assertEqual((old / 'keep').read_bytes(), b'keep')

    def test_existing_install_updates_only_jar_with_backup(self):
        target = self.existing_install()
        with patch.object(updater, 'smoke') as smoke:
            result = updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertEqual(result['mode'], 'jar-update')
        self.assertEqual((target / 'lib' / self.jar_name).read_bytes(), b'new jar')
        self.assertEqual(Path(result['jar_backup']).read_bytes(), b'old jar')
        self.assertEqual((target / 'haifa-coding.yaml').read_bytes(), b'custom config')
        self.assertEqual((target / 'bin/haifa.bat').read_bytes(), b'custom launcher')
        self.assertEqual((target / 'jre/custom-runtime').read_bytes(), b'old runtime')
        self.assertEqual(smoke.call_count, 2)

    def test_locked_jar_preserves_original_and_removes_pending_file(self):
        target = self.existing_install()
        with patch.object(updater, 'smoke'), patch.object(updater.os, 'replace', side_effect=PermissionError):
            with self.assertRaisesRegex(RuntimeError, 'locked'):
                updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertEqual((target / 'lib' / self.jar_name).read_bytes(), b'old jar')
        self.assertEqual(list((target / 'lib').glob('*.pending-*')), [])

    def test_old_runtime_incompatibility_prevents_replacement(self):
        target = self.existing_install()
        with patch.object(updater, 'smoke', side_effect=[None, RuntimeError('incompatible')]):
            with self.assertRaisesRegex(RuntimeError, 'incompatible'):
                updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertEqual((target / 'lib' / self.jar_name).read_bytes(), b'old jar')

    def test_archive_traversal_is_rejected_before_extract(self):
        archive = self.package()
        with zipfile.ZipFile(archive, 'a') as package:
            package.writestr(self.folder + '/../outside.txt', b'escape')
        with self.assertRaisesRegex(ValueError, 'outside'):
            updater.deploy(archive, self.install, self.version, self.report)
        self.assertFalse((self.install / 'outside.txt').exists())
        self.assertFalse((self.install / self.folder).exists())

    def test_malformed_install_is_preserved(self):
        target = self.existing_install()
        (target / 'lib/haifa-agent-cli-extra.jar').write_bytes(b'extra')
        with patch.object(updater, 'smoke'), self.assertRaisesRegex(ValueError, 'exactly'):
            updater.deploy(self.package(), self.install, self.version, self.report)
        self.assertEqual((target / 'lib' / self.jar_name).read_bytes(), b'old jar')

    def test_path_replaces_deduplicates_and_preserves_unrelated_entries(self):
        old = self.install / 'haifa-coding-agent-v0.1.3-SNAPSHOT/bin'
        new = self.install / self.folder / 'bin'
        unrelated = str(self.root / 'other/haifa-coding-agent-v0.1.2/bin')
        original = f'%CUSTOM_BIN%;{old};C:\\Tools;{unrelated};{new};'
        expected = f'%CUSTOM_BIN%;{new};C:\\Tools;{unrelated};'
        self.assertEqual(updater.path_with_install(original, self.install, new), expected)
        self.assertEqual(updater.path_with_install(expected, self.install, new), expected)
        self.assertEqual(updater.path_with_install('', self.install, new), str(new))

    def test_pom_versions_follow_revision_and_reject_path_components(self):
        def pom(version):
            return ('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                    '<version>${revision}</version><properties><revision>' + version +
                    '</revision></properties></project>')
        self.assertEqual(updater.pom_version(pom('0.1.5')), '0.1.5')
        with self.assertRaises(ValueError):
            updater.pom_version(pom('../escape'))

    def test_branch_and_worktree_resolution_without_switching(self):
        repo = self.root / 'haifa-agent'
        repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'dev', str(repo)], check=True)
        (repo / 'pom.xml').write_text('initial', encoding='utf-8')
        for args in (['add', '.'], ['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                                   'commit', '-qm', 'Initial'], ['branch', 'feat-unchecked']):
            updater.git(repo, *args)
        checkout = self.root / 'haifa-agent-worktrees/feature'
        updater.git(repo, 'worktree', 'add', '-qb', 'feat-checked', str(checkout))
        self.assertEqual(updater.select_source(repo, 'feat-checked', 'branch'), (checkout, None))
        self.assertEqual(updater.select_source(repo, 'feature', 'worktree'), (checkout, None))
        self.assertEqual(updater.select_source(repo, str(checkout), 'auto'), (checkout, None))
        future, sha = updater.select_source(repo, 'feat-unchecked', 'branch')
        self.assertFalse(future.exists())
        self.assertEqual(sha, updater.git(repo, 'rev-parse', 'feat-unchecked'))
        self.assertEqual(updater.git(repo, 'branch', '--show-current'), 'dev')
        updater.git(repo, 'branch', 'feature')
        with self.assertRaisesRegex(ValueError, 'different'):
            updater.select_source(repo, 'feature', 'auto')
        with self.assertRaises(ValueError):
            updater.select_source(repo, str(self.root / 'unregistered'), 'worktree')


    def test_prunes_every_older_update_artifact_except_current(self) -> None:
        artifacts = self.root / 'local-tmp' / 'artifacts'
        stale = [
            artifacts / 'ca-update-20260101T000000Z-aaaaaaaa',
            artifacts / 'ca-update-20260102T000000Z-bbbbbbbb',
        ]
        current = artifacts / 'ca-update-20260103T000000Z-cccccccc'
        unrelated = artifacts / 'pa-web-20260101'
        for directory in [*stale, current, unrelated]:
            directory.mkdir(parents=True)
            (directory / 'report.json').write_text('{}\n', encoding='utf-8')

        removed = updater.prune_stale_reports(artifacts, current)

        self.assertEqual([path.name for path in stale], removed)
        self.assertTrue(current.is_dir())
        self.assertTrue(unrelated.is_dir())
        self.assertFalse(any(path.exists() for path in stale))

if __name__ == '__main__':
    unittest.main()
