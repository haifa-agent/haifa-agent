#!/usr/bin/env python3

from __future__ import annotations

import base64
import contextlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import real_environment

CONTINUATION_KEY_NAME = "HAIFA_PERSONAL_CONTINUATION_KEY"


class RealEnvironmentTest(unittest.TestCase):
    def test_backend_environment_injects_only_runtime_inputs_not_model_metadata(self) -> None:
        root = Path("repository")
        paths = real_environment.Paths(
            root, root / "server", root / "web", root / "runtime", root / "runtime/data",
            root / "runtime/logs", root / "runtime/last-start.json", root / "runtime/last-stop.json", root / "mvnw")
        environment = real_environment.backend_environment(
            "deepseek-secret", "deepseek-chat-flash", None, "aliyun-secret", "continuation-secret",
            paths, None, kimi_key="kimi-secret", bigmodel_key="bigmodel-secret",
            siliconflow_key="siliconflow-secret", tavily_key="tavily-secret")
        self.assertEqual("deepseek-chat-flash", environment["HAIFA_PERSONAL_DEFAULT_MODEL_ID"])
        self.assertEqual("deepseek-secret", environment["DEEPSEEK_API_KEY"])
        self.assertEqual("kimi-secret", environment["KIMI_API_KEY"])
        self.assertEqual("true", environment["HAIFA_PERSONAL_WEB_SEARCH_ENABLED"])
        self.assertEqual("tavily", environment["HAIFA_PERSONAL_WEB_SEARCH_PROVIDER_ID"])
        self.assertEqual("true", environment["HAIFA_PERSONAL_WEB_FETCH_ENABLED"])
        self.assertEqual("tavily", environment["HAIFA_PERSONAL_WEB_FETCH_PROVIDER_ID"])
        self.assertFalse(any(name.startswith("HAIFA_PERSONAL_MODELPROVIDERS_") for name in environment))
        self.assertFalse(any("_MODELS_" in name for name in environment))
        self.assertFalse(any(name.startswith("HAIFA_PERSONAL_MCP_") for name in environment))
        self.assertNotIn("HAIFA_PERSONAL_SKILL_ROOT", environment)

    def test_backend_environment_injects_bailian_runtime_configuration(self) -> None:
        root = Path("repository")
        paths = real_environment.Paths(
            root, root / "server", root / "web", root / "runtime", root / "runtime/data",
            root / "runtime/logs", root / "runtime/last-start.json", root / "runtime/last-stop.json", root / "mvnw")
        environment = real_environment.backend_environment(
            "deepseek-secret", "deepseek-chat-flash", None, "aliyun-secret", "continuation-secret",
            paths, None, bailian=("dashscope-secret", "ws-123", "cn-beijing"))
        self.assertEqual("dashscope-secret", environment["DASHSCOPE_API_KEY"])
        self.assertEqual("ws-123", environment["ALIYUN_BAILIAN_WORKSPACE_ID"])
        self.assertEqual("cn-beijing", environment["ALIYUN_BAILIAN_REGION"])
        self.assertEqual(
            "https://ws-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            environment["HAIFA_PERSONAL_BAILIAN_ENDPOINT"],
        )

    def test_backend_environment_injects_antigravity_runtime_configuration(self) -> None:
        root = Path("repository")
        paths = real_environment.Paths(
            root, root / "server", root / "web", root / "runtime", root / "runtime/data",
            root / "runtime/logs", root / "runtime/last-start.json", root / "runtime/last-stop.json", root / "mvnw")
        environment = real_environment.backend_environment(
            "deepseek-secret", "deepseek-chat-flash", None, "aliyun-secret", "continuation-secret",
            paths, None, antigravity=real_environment.AntigravityConfiguration(
                "https://cloudcode.test/v1", "gemini-test", "http://127.0.0.1:9999"))
        self.assertEqual("https://cloudcode.test/v1", environment["HAIFA_ANTIGRAVITY_MODEL_ENDPOINT"])
        self.assertEqual("http://127.0.0.1:9999", environment["HAIFA_ANTIGRAVITY_PROXY_URL"])
        self.assertEqual("gemini-test", environment["HAIFA_ANTIGRAVITY_MODEL"])

    @staticmethod
    def write_server_jar(path: Path, payload: bytes = b"application") -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        manifest = (
            "Manifest-Version: 1.0\r\n"
            "Main-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
            f"Start-Class: {real_environment.EXPECTED_SERVER_START_CLASS}\r\n"
            "\r\n"
        )
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
            archive.writestr("BOOT-INF/classes/application.bin", payload)
            archive.writestr("BOOT-INF/lib/dependency.jar", b"dependency")

    def test_root_scripts_location_resolves_repository_paths(self) -> None:
        repository = Path(__file__).resolve().parents[2]

        paths = real_environment.paths()

        self.assertEqual(repository, paths.repository)
        self.assertEqual(
            repository / "haifa-agent-applications/haifa-agent-personal-assistant-server",
            paths.server,
        )
        self.assertEqual(repository / "haifa-agent-applications/haifa-agent-personal-assistant-web", paths.web)

    def test_parser_exposes_no_credential_file_or_external_service_options(self) -> None:
        removed_options = (
            "--deepseek-key-file",
            "--bailian-key-file",
            "--kimi-key-file",
            "--bigmodel-key-file",
            "--siliconflow-key-file",
            "--aliyun-iqs-key-file",
            "--browserless-key-file",
            "--tavily-key-file",
            "--utility-mcp-directory",
            "--utility-mcp-proxy-url",
            "--utility-mcp-proxy-providers",
            "--personal-skill-root",
        )

        for option in removed_options:
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    real_environment.parser().parse_args([option, "value"])

    def test_continuation_key_file_option_defaults_to_the_runtime_directory(self) -> None:
        with mock.patch.dict(real_environment.os.environ):
            real_environment.os.environ.pop("HAIFA_PERSONAL_CONTINUATION_KEY_FILE", None)
            arguments = real_environment.parser().parse_args([])

        self.assertEqual("", arguments.continuation_key_file)
        self.assertEqual(
            real_environment.paths().runtime / "continuation-key.env",
            real_environment.continuation_key_path(real_environment.paths()),
        )

    def test_provider_credentials_are_read_from_the_process_environment(self) -> None:
        with mock.patch.dict(real_environment.os.environ, {"DEEPSEEK_API_KEY": "deepseek-secret"}):
            self.assertEqual(
                "deepseek-secret", real_environment.required_environment_value("DEEPSEEK_API_KEY")
            )

        with mock.patch.dict(real_environment.os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "HAIFA_TEST_ABSENT_ENVIRONMENT_VARIABLE"):
                real_environment.required_environment_value("HAIFA_TEST_ABSENT_ENVIRONMENT_VARIABLE")
            self.assertIsNone(
                real_environment.optional_environment_value("HAIFA_TEST_ABSENT_ENVIRONMENT_VARIABLE")
            )

    def test_key_file_reads_only_the_expected_env_variable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "continuation-key.env"
            key_file.write_text(
                f"# persisted continuation key\n{CONTINUATION_KEY_NAME}=test-secret\n",
                encoding="utf-8",
            )

            secret = real_environment.read_key_file(key_file, CONTINUATION_KEY_NAME, "Continuation")

        self.assertEqual("test-secret", secret)

    def test_key_file_rejects_legacy_raw_secret_format(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "continuation-key.env"
            key_file.write_text("test-secret\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "KEY=VALUE"):
                real_environment.read_key_file(key_file, CONTINUATION_KEY_NAME, "Continuation")

    def test_key_file_rejects_unknown_duplicate_and_empty_variables(self) -> None:
        cases = (
            ("OTHER_API_KEY=test-secret\n", "unexpected variable"),
            (
                f"{CONTINUATION_KEY_NAME}=first\n{CONTINUATION_KEY_NAME}=second\n",
                f"duplicate {CONTINUATION_KEY_NAME}",
            ),
            (f"{CONTINUATION_KEY_NAME}=\n", f"{CONTINUATION_KEY_NAME} is empty"),
        )
        for content, expected_message in cases:
            with self.subTest(
                expected_message=expected_message
            ), tempfile.TemporaryDirectory() as directory:
                key_file = Path(directory) / "continuation-key.env"
                key_file.write_text(content, encoding="utf-8")

                with self.assertRaisesRegex(RuntimeError, expected_message):
                    real_environment.read_key_file(key_file, CONTINUATION_KEY_NAME, "Continuation")

    def test_continuation_key_is_created_as_an_env_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "runtime/continuation-key.env"
            with mock.patch.object(real_environment, "restrict_secret_file"), mock.patch.object(
                real_environment.secrets, "token_bytes", return_value=b"a" * 32
            ):
                configured = real_environment.continuation_key("", key_file)

            lines = key_file.read_text(encoding="ascii").splitlines()

        self.assertEqual("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=", configured)
        self.assertEqual([f"{CONTINUATION_KEY_NAME}={configured}"], lines)

    def test_continuation_key_prefers_the_injected_environment_variable(self) -> None:
        configured = base64.b64encode(b"b" * 32).decode("ascii")
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "continuation-key.env"
            with mock.patch.dict(real_environment.os.environ, {CONTINUATION_KEY_NAME: configured}):
                self.assertEqual(configured, real_environment.continuation_key("", key_file))

            self.assertFalse(key_file.exists())

    def test_continuation_key_rejects_material_that_is_not_base64_aes_256(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "32 bytes"):
            real_environment.validate_continuation_key(
                base64.b64encode(b"short").decode("ascii"), "Continuation"
            )
        with self.assertRaisesRegex(RuntimeError, "Base64"):
            real_environment.validate_continuation_key("not-base64!", "Continuation")

    def test_environment_manages_only_the_web_and_backend_services(self) -> None:
        paths = real_environment.paths()

        self.assertEqual(
            ["personal-web", "personal-backend"],
            [definition.role for definition in real_environment.definitions(paths)],
        )
        self.assertEqual(
            [real_environment.FRONTEND_PORT, real_environment.BACKEND_PORT],
            [definition.port for definition in real_environment.definitions(paths)],
        )

    def test_bailian_configuration_uses_environment_and_defaults_region(self) -> None:
        self.assertIsNone(real_environment.optional_bailian_configuration("cn-beijing", environment={}))
        self.assertIsNone(
            real_environment.optional_bailian_configuration(
                "cn-beijing", environment={"DASHSCOPE_API_KEY": "test-secret"}
            )
        )
        self.assertEqual(
            ("test-secret", "workspace-123", "cn-beijing"),
            real_environment.optional_bailian_configuration(
                "cn-beijing",
                environment={
                    "DASHSCOPE_API_KEY": "test-secret",
                    "ALIYUN_BAILIAN_WORKSPACE_ID": "Workspace-123",
                },
            ),
        )
        self.assertEqual(
            ("test-secret", "workspace-123", "cn-hangzhou"),
            real_environment.optional_bailian_configuration(
                "cn-beijing",
                environment={
                    "DASHSCOPE_API_KEY": "test-secret",
                    "ALIYUN_BAILIAN_WORKSPACE_ID": "Workspace-123",
                    "ALIYUN_BAILIAN_REGION": "CN-Hangzhou",
                },
            ),
        )

    def test_optional_openai_provider_requires_complete_environment_group(self) -> None:
        self.assertIsNone(real_environment.optional_openai_environment({}))
        self.assertIsNone(
            real_environment.optional_openai_environment({"OPENAI_API_KEY": "openai-secret"})
        )
        self.assertEqual(
            ("http://127.0.0.1:30000/v1", "openai-secret", "gpt-test"),
            real_environment.optional_openai_environment(
                {
                    "OPENAI_BASE_URL": "http://127.0.0.1:30000/v1",
                    "OPENAI_API_KEY": "openai-secret",
                    "OPENAI_MODEL_ID": "gpt-test",
                }
            ),
        )

    def test_invalid_mode_combinations_are_rejected(self) -> None:
        arguments = real_environment.parser().parse_args(["--stop", "--rebuild"])
        with self.assertRaisesRegex(RuntimeError, "cannot be used together"):
            real_environment.validate_arguments(arguments)

        arguments = real_environment.parser().parse_args(["--force"])
        with self.assertRaisesRegex(RuntimeError, "only be used with --stop"):
            real_environment.validate_arguments(arguments)

    def test_default_model_can_select_a_configured_deepseek_api_style(self) -> None:
        with mock.patch.dict(real_environment.os.environ):
            real_environment.os.environ.pop("HAIFA_PERSONAL_DEFAULT_MODEL_ID", None)
            default_arguments = real_environment.parser().parse_args([])
        chat_arguments = real_environment.parser().parse_args(
            ["--default-model-id", "deepseek-chat-flash"]
        )

        self.assertIsNone(default_arguments.default_model_id)
        self.assertEqual("deepseek-chat-flash", chat_arguments.default_model_id)
        self.assertEqual(
            "deepseek-chat-flash", real_environment.resolve_default_model_id(None, None)
        )

    def test_default_web_providers_are_tavily(self) -> None:
        with mock.patch.dict(real_environment.os.environ):
            real_environment.os.environ.pop("HAIFA_PERSONAL_WEB_SEARCH_PROVIDER", None)
            real_environment.os.environ.pop("HAIFA_PERSONAL_WEB_FETCH_PROVIDER", None)
            arguments = real_environment.parser().parse_args([])

        self.assertEqual("tavily", arguments.web_search_provider)
        self.assertEqual("tavily", arguments.web_fetch_provider)

    def test_backend_launch_mode_defaults_to_jar(self) -> None:
        with mock.patch.dict(real_environment.os.environ):
            real_environment.os.environ.pop("HAIFA_PERSONAL_BACKEND_LAUNCH_MODE", None)

            arguments = real_environment.parser().parse_args([])

        self.assertEqual("jar", arguments.backend_launch_mode)

    def test_classpath_backend_launch_uses_current_compiled_classes_without_a_jar(self) -> None:
        launch = real_environment.backend_launch(
            "java",
            "classpath",
            None,
            {"HAIFA_PERSONAL_DEV_CLASSPATH": "classes;dependencies"},
        )

        self.assertEqual("java", launch.command)
        self.assertEqual((real_environment.EXPECTED_SERVER_START_CLASS,), launch.arguments)
        self.assertEqual({"CLASSPATH": "classes;dependencies"}, launch.environment)

    def test_classpath_backend_launch_fails_closed_without_an_ide_classpath(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "HAIFA_PERSONAL_DEV_CLASSPATH"):
            real_environment.backend_launch("java", "classpath", None, {})

    def test_classpath_backend_launch_rejects_rebuild(self) -> None:
        arguments = real_environment.parser().parse_args(
            ["--backend-launch-mode", "classpath", "--rebuild"]
        )

        with self.assertRaisesRegex(RuntimeError, "does not support --rebuild"):
            real_environment.validate_arguments(arguments)

    def test_rebuild_port_conflict_message_explains_stop_then_rebuild(self) -> None:
        arguments = real_environment.parser().parse_args(["--rebuild"])
        with mock.patch.object(real_environment, "port_open", return_value=True):
            with self.assertRaises(RuntimeError) as failure:
                real_environment.start_environment(arguments, real_environment.paths())

        message = str(failure.exception)

        self.assertIn("Stop the running environment first, then rebuild", message)
        self.assertIn(r".\scripts\start-real-environment.ps1 --stop", message)
        self.assertIn(r".\scripts\start-real-environment.ps1 --rebuild", message)

    def test_backend_build_uses_the_repository_unit_test_skip_property(self) -> None:
        self.assertEqual(
            (
                "-pl",
                ":haifa-agent-personal-assistant-server",
                "-am",
                "-DskipUnitTests=true",
                "clean",
                "package",
            ),
            real_environment.backend_build_arguments(True),
        )
        self.assertNotIn("-DskipTests", real_environment.backend_build_arguments(False))

    def test_backend_runtime_jar_is_content_addressed_and_outside_maven_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = real_environment.Paths(
                repository=root,
                server=root / "server",
                web=root / "web",
                runtime=root / "runtime",
                data=root / "runtime/data",
                logs=root / "runtime/logs",
                state=root / "runtime/last-start.json",
                stop_state=root / "runtime/last-stop.json",
                maven_wrapper=root / "mvnw",
            )
            source = paths.server / "target/haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar"
            self.write_server_jar(source, b"first build")

            first = real_environment.stage_server_jar(source, paths)
            reused = real_environment.stage_server_jar(source, paths)
            self.write_server_jar(source, b"second build")
            second = real_environment.stage_server_jar(source, paths)

            self.assertEqual(first, reused)
            self.assertNotEqual(first, second)
            self.assertEqual(paths.runtime / "backend", second.parent)
            with zipfile.ZipFile(second) as archive:
                self.assertEqual(b"second build", archive.read("BOOT-INF/classes/application.bin"))
            self.assertFalse(first.exists())

    def test_non_executable_server_jar_is_rejected_before_staging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = real_environment.Paths(
                repository=root,
                server=root / "server",
                web=root / "web",
                runtime=root / "runtime",
                data=root / "runtime/data",
                logs=root / "runtime/logs",
                state=root / "runtime/last-start.json",
                stop_state=root / "runtime/last-stop.json",
                maven_wrapper=root / "mvnw",
            )
            source = paths.server / "target/haifa-agent-personal-assistant-server-test.jar"
            source.parent.mkdir(parents=True)
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")

            self.assertIn("Main-Class", real_environment.server_jar_validation_error(source) or "")
            with self.assertRaisesRegex(RuntimeError, "Refusing to stage a non-executable"):
                real_environment.stage_server_jar(source, paths)

    def test_invalid_existing_server_jar_triggers_package_and_post_build_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = real_environment.Paths(
                repository=root,
                server=root / "server",
                web=root / "web",
                runtime=root / "runtime",
                data=root / "runtime/data",
                logs=root / "runtime/logs",
                state=root / "runtime/last-start.json",
                stop_state=root / "runtime/last-stop.json",
                maven_wrapper=root / "mvnw",
            )
            source = paths.server / "target/haifa-agent-personal-assistant-server-test.jar"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"incomplete Maven output")

            def complete_repackage(*_args: object, **_kwargs: object) -> None:
                self.write_server_jar(source)

            with mock.patch.object(
                real_environment,
                "run_checked",
                side_effect=complete_repackage,
            ) as build:
                resolved = real_environment.ensure_executable_server_jar(paths, False)

            self.assertEqual(source, resolved)
            self.assertIsNone(real_environment.server_jar_validation_error(resolved))
            build.assert_called_once_with(
                paths.maven_wrapper,
                *real_environment.backend_build_arguments(False),
                cwd=paths.repository,
            )

    def test_backend_stop_validation_accepts_runtime_and_legacy_target_locations(self) -> None:
        root = Path("repository")
        paths = real_environment.Paths(
            repository=root,
            server=root / "server",
            web=root / "web",
            runtime=root / "runtime",
            data=root / "runtime/data",
            logs=root / "runtime/logs",
            state=root / "runtime/last-start.json",
            stop_state=root / "runtime/last-stop.json",
            maven_wrapper=root / "mvnw",
        )

        backend = next(
            definition
            for definition in real_environment.definitions(paths)
            if definition.role == "personal-backend"
        )

        self.assertEqual(
            (
                str(paths.runtime / "backend"),
                str(paths.server),
                real_environment.EXPECTED_SERVER_START_CLASS,
            ),
            backend.command_tokens,
        )
        with mock.patch.object(
            real_environment,
            "process_information",
            return_value=(backend.process_name, f"java -jar {paths.runtime / 'backend' / 'server.jar'}"),
        ):
            real_environment.validate_process(backend, 42)
        with mock.patch.object(
            real_environment,
            "process_information",
            return_value=(backend.process_name, f"java -jar {paths.server / 'target' / 'server.jar'}"),
        ):
            real_environment.validate_process(backend, 43)

    def test_state_is_written_atomically_as_utf8_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "state.json"

            real_environment.atomic_json(target, [{"Role": "personal-backend", "Pid": 42}])

            self.assertEqual(
                [{"Role": "personal-backend", "Pid": 42}],
                json.loads(target.read_text(encoding="utf-8")),
            )
            self.assertFalse(any(target.parent.glob("*.tmp-*")))


if __name__ == "__main__":
    unittest.main()
