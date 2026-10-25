import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "dist-support/scripts/package_distributions.py"
SPEC = importlib.util.spec_from_file_location("release_distributions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


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


if __name__ == "__main__":
    unittest.main()
