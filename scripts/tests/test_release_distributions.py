import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "dist-support/scripts/package_distributions.py"
SPEC = importlib.util.spec_from_file_location("release_distributions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

# The packager puts its own directory on sys.path, so the shared contract module used
# to build realistic fixtures is importable right after the packager is loaded.
import distribution_contracts as CONTRACTS  # noqa: E402


class ReleaseDistributionsTest(unittest.TestCase):
    def fixture(self, root):
        def pom(artifact, packaging="jar", modules="", version="0.1.2"):
            return (
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f'<artifactId>{artifact}</artifactId><version>{version}</version>'
                f'<packaging>{packaging}</packaging>{modules}</project>'
            )

        for path, artifact, packaging, modules in (
            (root, "parent", "pom", '<modules><module>common</module><module>sdk</module>'
             '<module>haifa-agent-applications</module><module>haifa-agent-testing</module></modules>'),
            (root / "common", "common", "jar", ""),
            (root / "sdk", "sdk", "jar", ""),
        ):
            path.mkdir(parents=True, exist_ok=True)
            text = pom(artifact, packaging, modules)
            (path / "pom.xml").write_text(text, encoding="utf-8")
            (path / ".flattened-pom.xml").write_text(text, encoding="utf-8")
            if packaging == "jar":
                sources = path / "src/main/java"
                sources.mkdir(parents=True)
                (sources / "Example.java").write_text("class Example {}", encoding="utf-8")
                (path / "target").mkdir()
                for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                    (path / "target" / f"{artifact}-0.1.2{suffix}").write_bytes(b"artifact")
        template = root / "dist-support/templates/sdk"
        template.mkdir(parents=True)
        (template / "README.txt").write_text("SDK", encoding="utf-8")
        (root / "work").mkdir()
        (root / "output").mkdir()

    def test_bundle_contains_dependency_parent_and_classifier_artifacts_without_products(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            archive = MODULE.package_sdk(root, root / "work", root / "output", "0.1.2")
            with zipfile.ZipFile(archive) as bundle:
                files = bundle.namelist()
                prefix = "haifa-agent-sdk-v0.1.2/repository/io/haifa/"
                self.assertIn(prefix + "parent/0.1.2/parent-0.1.2.pom", files)
                self.assertIn(prefix + "common/0.1.2/common-0.1.2.jar", files)
                self.assertIn(prefix + "sdk/0.1.2/sdk-0.1.2-sources.jar", files)
                self.assertIn(prefix + "sdk/0.1.2/sdk-0.1.2-javadoc.jar", files)
                self.assertEqual(len(files), 10)

    def test_dependency_only_starter_needs_no_nonexistent_source_or_javadoc(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            (root / "common/src/main/java/Example.java").unlink()
            for suffix in ("-sources.jar", "-javadoc.jar"):
                (root / "common/target" / f"common-0.1.2{suffix}").unlink()
            archive = MODULE.package_sdk(root, root / "work", root / "output", "0.1.2")
            with zipfile.ZipFile(archive) as bundle:
                self.assertEqual(len(bundle.namelist()), 8)

    def test_missing_dependency_or_unresolved_pom_refuses_bundle(self):
        for problem in ("missing-jar", "unresolved-pom", "wrong-version"):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.fixture(root)
                if problem == "missing-jar":
                    (root / "common/target/common-0.1.2.jar").unlink()
                    error = FileNotFoundError
                else:
                    p = root / "common/.flattened-pom.xml"
                    p.write_text(p.read_text().replace("0.1.2", "${revision}" if problem == "unresolved-pom" else "0.1.1"), encoding="utf-8")
                    error = ValueError
                with self.assertRaises(error):
                    MODULE.package_sdk(root, root / "work", root / "output", "0.1.2")
                self.assertEqual(list((root / "output").glob("*.zip")), [])


class WebDistGuardTest(unittest.TestCase):
    def dist(self, root, api_token):
        dist = root / "dist"
        (dist / "assets").mkdir(parents=True)
        (dist / "index.html").write_text("<html></html>", encoding="utf-8")
        (dist / "assets" / "App-test.js").write_text(
            f"var base={api_token};var admin={MODULE.WEB_RELATIVE_ADMIN_API_BASE}",
            encoding="utf-8",
        )
        return dist

    def test_server_package_refuses_loopback_dist(self):
        with tempfile.TemporaryDirectory() as temporary:
            dist = self.dist(Path(temporary), MODULE.WEB_ABSOLUTE_LOOPBACK_TOKEN)
            with self.assertRaisesRegex(ValueError, "still targets the loopback API"):
                MODULE.verify_web_dist(dist, relative_api_base=True)

    def test_portable_package_refuses_relative_dist(self):
        with tempfile.TemporaryDirectory() as temporary:
            dist = self.dist(Path(temporary), MODULE.WEB_RELATIVE_API_BASE)
            with self.assertRaisesRegex(ValueError, "does not target the loopback API"):
                MODULE.verify_web_dist(dist, relative_api_base=False)

    def test_each_delivery_mode_accepts_its_own_dist(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            MODULE.verify_web_dist(
                self.dist(root / "relative", MODULE.WEB_RELATIVE_API_BASE), relative_api_base=True)
            MODULE.verify_web_dist(
                self.dist(root / "absolute", MODULE.WEB_ABSOLUTE_LOOPBACK_TOKEN), relative_api_base=False)


class PersonalAssistantPackageShapeTest(unittest.TestCase):
    def test_both_packages_share_directory_name_and_artifact_shape(self):
        package = MODULE.pa_package_name("0.1.4")
        self.assertEqual(package, "haifa-personal-assistant-v0.1.4")

        windows = MODULE.pa_artifact_name("windows-x64", "0.1.4")
        linux = MODULE.pa_artifact_name("linux-x64", "0.1.4")
        self.assertEqual(windows, "haifa-personal-assistant-windows-x64-v0.1.4.zip")
        self.assertEqual(linux, "haifa-personal-assistant-linux-x64-v0.1.4.zip")

        # The extracted top-level directory must be derivable from the artifact name alone,
        # which is what the release smoke test relies on.
        for artifact in (windows, linux):
            stem = artifact[: -len(".zip")]
            self.assertEqual(
                stem.replace("-windows-x64", "").replace("-linux-x64", ""),
                package,
            )


class CodingAgentLinuxPackageTest(unittest.TestCase):
    def cli_jar(self, root):
        return root / "haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.4.jar"

    def write_cli_jar(self, root):
        jar = self.cli_jar(root)
        jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(jar, "w") as shaded:
            shaded.writestr(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nMain-Class: io.haifa.agent.cli.HaifaCliMain\n",
            )
            for entry in CONTRACTS.REQUIRED_SHADED_JAR_ENTRIES:
                shaded.writestr(entry, b"class")
        return jar

    def fixture(self, root, target):
        repository = SCRIPT.parents[2]
        shutil.copytree(repository / "dist-support/templates", root / "dist-support/templates")
        config = root.joinpath(*MODULE.CA_CONFIG_SOURCE)
        config.parent.mkdir(parents=True)
        shutil.copy2(repository.joinpath(*MODULE.CA_CONFIG_SOURCE), config)
        self.write_cli_jar(root)
        jre = root / "work/jre" / target
        (jre / "bin").mkdir(parents=True)
        executable = "java.exe" if target == "windows-x64" else "java"
        header = bytearray(70)
        if target == "windows-x64":
            header[:2] = b"MZ"
            header[60:64] = (64).to_bytes(4, "little")
            header[64:70] = b"PE\x00\x00\x64\x86"
        else:
            header[:6] = b"\x7fELF\x02\x01"
            header[18:20] = (62).to_bytes(2, "little")
        (jre / "bin" / executable).write_bytes(header)
        (jre / "release").write_text('JAVA_VERSION="21.0.12"\n', encoding="utf-8")
        (root / "output").mkdir()
        return jre

    def test_linux_tar_preserves_payload_configuration_and_executable_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jre = self.fixture(root, "linux-x64")
            archive = MODULE.package_ca(root, root / "work", root / "output", "0.1.4", jre, "linux-x64")
            self.assertEqual(archive.name, "haifa-coding-agent-linux-x64-v0.1.4.tar.gz")
            with tarfile.open(archive) as bundle:
                prefix = "haifa-coding-agent-v0.1.4/"
                for executable in ("bin/haifa", "jre/bin/java"):
                    self.assertEqual(bundle.getmember(prefix + executable).mode, 0o755)
                self.assertEqual(
                    bundle.extractfile(prefix + "lib/haifa-agent-cli-0.1.4.jar").read(),
                    self.cli_jar(root).read_bytes(),
                )
                self.assertIn(b"SQLITE_WITH_JSONL", bundle.extractfile(prefix + "haifa-coding.yaml").read())
                self.assertNotIn(prefix + "bin/haifa.bat", bundle.getnames())

    def test_refuses_a_thin_cli_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jre = self.fixture(root, "windows-x64")
            self.cli_jar(root).write_bytes(b"thin jar")
            with self.assertRaisesRegex(RuntimeError, "invalid"):
                MODULE.package_ca(root, root / "work", root / "output", "0.1.4", jre)

    def test_windows_ca_keeps_zip_layout_and_platform_staging_is_independent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jre = self.fixture(root, "windows-x64")
            linux_marker = root / "work/linux-x64/haifa-coding-agent-v0.1.4/marker"
            linux_marker.parent.mkdir(parents=True)
            linux_marker.write_bytes(b"linux staging")
            archive = MODULE.package_ca(root, root / "work", root / "output", "0.1.4", jre)
            self.assertEqual(archive.name, "haifa-coding-agent-windows-x64-v0.1.4.zip")
            with zipfile.ZipFile(archive) as bundle:
                self.assertIn("haifa-coding-agent-v0.1.4/bin/haifa.bat", bundle.namelist())
            self.assertEqual(linux_marker.read_bytes(), b"linux staging")

    def test_native_runtime_and_action_reject_wrong_os_or_architecture(self):
        for system, machine, expected in (("Linux", "x86_64", "linux-x64"), ("Windows", "AMD64", "windows-x64")):
            with patch.object(MODULE.platform, "system", return_value=system), patch.object(
                    MODULE.platform, "machine", return_value=machine):
                self.assertEqual(MODULE.native_runtime_platform(), expected)
                wrong_action = "ca" if system == "Linux" else "ca-linux"
                with patch.object(MODULE.sys, "argv", ["packager", wrong_action]), self.assertRaises(SystemExit) as error:
                    MODULE.main()
                self.assertEqual(error.exception.code, 2)
        with patch.object(MODULE.platform, "machine", return_value="aarch64"), self.assertRaises(ValueError):
            MODULE.native_runtime_platform()
        with patch.object(MODULE.platform, "system", return_value="Windows"), patch.object(
                MODULE.platform, "machine", return_value=""):
            self.assertEqual(MODULE.native_runtime_platform(), "windows-x64")

    def test_cached_jre_is_platform_specific_and_rejects_wrong_binary_or_java_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jre = self.fixture(root, "linux-x64")
            with patch.object(MODULE, "native_runtime_platform", return_value="linux-x64"):
                self.assertEqual(MODULE.ensure_jre(root, root / "work"), jre)
                valid_binary = (jre / "bin/java").read_bytes()
                (jre / "bin/java").write_bytes(b"MZ")
                with self.assertRaisesRegex(ValueError, "does not match"):
                    MODULE.ensure_jre(root, root / "work")
                (jre / "bin/java").write_bytes(valid_binary)
                (jre / "release").write_text('JAVA_VERSION="25"\n', encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Java 21"):
                    MODULE.ensure_jre(root, root / "work")
                (jre / "bin/java").write_bytes(valid_binary[:18] + (183).to_bytes(2, "little") + valid_binary[20:])
                with self.assertRaisesRegex(ValueError, "x64 architecture"):
                    MODULE.ensure_jre(root, root / "work")

    def test_checksums_include_both_archive_formats(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("windows.zip", "linux.tar.gz"):
                (output / name).write_bytes(name.encode())
            MODULE.write_checksums(output)
            lines = (output / "sha256sums.txt").read_text().splitlines()
            self.assertEqual(lines, [f"{MODULE.sha256_file(output / name)}  {name}"
                                     for name in ("linux.tar.gz", "windows.zip")])

    @unittest.skipIf(os.name == "nt", "POSIX launcher process contract")
    def test_linux_launcher_preserves_arguments_data_overrides_and_exit_code(self):
        # A child process is necessary to test shell quoting, exported paths and exec exit status.
        with tempfile.TemporaryDirectory(prefix="ca launcher ") as temporary:
            root = Path(temporary)
            jre = self.fixture(root, "linux-x64")
            archive = MODULE.package_ca(root, root / "work", root / "output", "0.1.4", jre, "linux-x64")
            with tarfile.open(archive) as bundle:
                bundle.extractall(root / "extracted", filter="data")
            package = root / "extracted/haifa-coding-agent-v0.1.4"
            java = package / "jre/bin/java"
            java.write_text(
                '#!/usr/bin/env python3\nimport json, os, sys\nfrom pathlib import Path\n'
                'Path(os.environ["CAPTURE_PATH"]).write_text(json.dumps({'
                '"args": sys.argv[1:], "root": os.environ["HAIFA_DATA_ROOT"], '
                '"db": os.environ["HAIFA_SQLITE_DATABASE_PATH"]}))\nsys.exit(17)\n', encoding="utf-8")
            capture = root / "captured.json"
            environment = dict(os.environ, CAPTURE_PATH=str(capture), HAIFA_DATA_ROOT=str(root / "private data"),
                               HAIFA_SQLITE_DATABASE_PATH=str(root / "custom db.sqlite"))
            options = ["--workspace", "a path with spaces", "--message", "literal $HOME;$(ignored)"]
            result = subprocess.run([str(package / "bin/haifa"), *options], env=environment, timeout=10)
            actual = json.loads(capture.read_text())
            self.assertEqual(result.returncode, 17)
            self.assertEqual(actual["args"][-len(options):], options)
            self.assertEqual(actual["root"], environment["HAIFA_DATA_ROOT"])
            self.assertEqual(actual["db"], environment["HAIFA_SQLITE_DATABASE_PATH"])


if __name__ == "__main__":
    unittest.main()
