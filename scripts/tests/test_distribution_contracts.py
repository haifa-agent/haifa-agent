"""Contract checks for the shared Coding Agent distribution helpers.

The three commands (release packager, local packager, updater) must not carry their
own copy of these rules, so the identity checks below fail as soon as one consumer
starts shadowing a shared implementation again.
"""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]


def load(relative_path, name):
    spec = importlib.util.spec_from_file_location(name, REPOSITORY / relative_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


CONTRACTS = load("dist-support/scripts/distribution_contracts.py", "distribution_contracts")
RELEASE = load("dist-support/scripts/package_distributions.py", "release_distributions")
LOCAL = load("scripts/package-local-coding-agent.py", "package_local_coding_agent")
UPDATER = load("scripts/refresh-ca/update_coding_agent.py", "update_coding_agent")


def pom(version, revision=None):
    properties = "" if revision is None else f"<properties><revision>{revision}</revision></properties>"
    return (
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        f"<version>{version}</version>{properties}</project>"
    )


class PomVersionTest(unittest.TestCase):
    def test_resolves_revision_placeholder_and_keeps_literal_versions(self):
        self.assertEqual(CONTRACTS.pom_version(pom("${revision}", "0.1.5-SNAPSHOT")), "0.1.5-SNAPSHOT")
        self.assertEqual(CONTRACTS.pom_version(pom("0.1.2")), "0.1.2")

    def test_rejects_unresolved_or_path_like_versions(self):
        for version, revision in (("${revision}", None), ("../escape", None), ("", None)):
            with self.subTest(version=version), self.assertRaises(ValueError):
                CONTRACTS.pom_version(pom(version, revision))

    def test_reads_the_version_from_a_pom_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            pom_path = Path(temporary) / "pom.xml"
            pom_path.write_text(pom("${revision}", "0.1.5-SNAPSHOT"), encoding="utf-8")
            self.assertEqual(CONTRACTS.read_pom_version(pom_path), "0.1.5-SNAPSHOT")


class ModelConfigurationTest(unittest.TestCase):
    def test_accepts_the_current_model_api_structure(self):
        CONTRACTS.validate_model_configuration(
            "allowedBindings: [deepseek-responses-flash, deepseek-chat-pro]\n"
            "credentialRef: model-auth://deepseek/default\n"
        )

    def test_rejects_retired_model_configuration(self):
        with self.assertRaisesRegex(ValueError, "retired model configuration"):
            CONTRACTS.validate_model_configuration(
                "allowedBindings:\n"
                "  - deepseek-responses-flash\n"
                "  - deepseek-chat-pro\n"
                "credentialRef: model-auth://deepseek/default\n"
                "dialectVersion: '1.0'\n"
            )

    def test_rejects_missing_model_configuration(self):
        with self.assertRaisesRegex(ValueError, "missing the current model API structure"):
            CONTRACTS.validate_model_configuration("models:\n  default: deepseek-responses-flash\n")


class ShadedJarTest(unittest.TestCase):
    def jar(self, path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nMain-Class: io.haifa.agent.cli.HaifaCliMain\n",
            )
            for entry in entries:
                archive.writestr(entry, b"class")

    def test_accepts_a_complete_shaded_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar_file = Path(temporary) / "haifa-agent.jar"
            self.jar(jar_file, CONTRACTS.REQUIRED_SHADED_JAR_ENTRIES)
            CONTRACTS.validate_shaded_jar(jar_file)

    def test_requires_the_tui4j_cleanup_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar_file = Path(temporary) / "haifa-agent.jar"
            self.jar(
                jar_file,
                tuple(
                    entry
                    for entry in CONTRACTS.REQUIRED_SHADED_JAR_ENTRIES
                    if not entry.endswith("ProgramCleanup.class")
                ),
            )
            with self.assertRaisesRegex(RuntimeError, "ProgramCleanup.class"):
                CONTRACTS.validate_shaded_jar(jar_file)

    def test_rejects_a_thin_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar_file = Path(temporary) / "haifa-agent.jar"
            jar_file.write_bytes(b"thin jar")
            with self.assertRaisesRegex(RuntimeError, "invalid"):
                CONTRACTS.validate_shaded_jar(jar_file)


class Sha256Test(unittest.TestCase):
    def test_digest_matches_the_standard_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            payload = Path(temporary) / "payload.bin"
            payload.write_bytes(b"haifa")
            self.assertEqual(
                CONTRACTS.sha256_file(payload), hashlib.sha256(b"haifa").hexdigest()
            )


class CliBuildCommandTest(unittest.TestCase):
    def test_build_goes_through_the_layered_repository_entry(self):
        command = CONTRACTS.cli_build_command(REPOSITORY)
        self.assertTrue(any("invoke-haifa-maven" in part for part in command))
        self.assertEqual(command[command.index("--layer") + 1], "L2")
        self.assertEqual(
            command[command.index("--") + 1:],
            ["-pl", ":haifa-agent-cli", "-am", "-DskipUnitTests=true", "clean", "package"],
        )
        self.assertNotIn("-DskipTests", command)


class SingleImplementationTest(unittest.TestCase):
    def assert_shared(self, function):
        """Fail when a command stops using the shared module and carries its own copy."""
        self.assertEqual(function.__module__, "distribution_contracts")
        self.assertEqual(
            Path(function.__code__.co_filename).resolve(),
            Path(CONTRACTS.__file__).resolve(),
        )

    def test_every_command_imports_the_shared_contracts(self):
        for function in (
            RELEASE.sha256_file,
            RELEASE.validate_model_configuration,
            RELEASE.validate_shaded_jar,
            LOCAL.validate_model_configuration,
            LOCAL.validate_shaded_jar,
            LOCAL.cli_build_command,
            UPDATER.pom_version,
            UPDATER.sha256_file,
            UPDATER.cli_build_command,
        ):
            with self.subTest(function=function.__name__):
                self.assert_shared(function)


if __name__ == "__main__":
    unittest.main()
