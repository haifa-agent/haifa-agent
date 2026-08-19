#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import real_environment


class RealEnvironmentTest(unittest.TestCase):
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
        repository = Path(__file__).resolve().parents[1]

        paths = real_environment.paths()

        self.assertEqual(repository, paths.repository)
        self.assertEqual(
            repository / "haifa-agent-applications/haifa-agent-personal-assistant-server",
            paths.server,
        )
        self.assertEqual(repository / "haifa-agent-applications/haifa-agent-personal-assistant-web", paths.web)

    def test_web_environment_freezes_separate_search_and_fetch_providers(self) -> None:
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

        defaults = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-chat-flash",
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            tavily_key="tavily-secret",
        )

        self.assertEqual("tavily", defaults["HAIFA_PERSONAL_WEB_SEARCH_PROVIDER"])
        self.assertEqual("tavily", defaults["HAIFA_PERSONAL_WEB_FETCH_PROVIDER"])
        self.assertEqual("https://api.tavily.com/search", defaults["HAIFA_PERSONAL_WEB_SEARCH_ENDPOINT"])
        self.assertEqual("https://api.tavily.com/extract", defaults["HAIFA_PERSONAL_WEB_FETCH_ENDPOINT"])

        mixed = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-chat-flash",
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            browserless_token="browserless-secret",
            tavily_key="tavily-secret",
            web_search_provider="tavily",
            web_fetch_provider="browserless",
        )

        self.assertEqual("tavily", mixed["HAIFA_PERSONAL_WEB_SEARCH_PROVIDER"])
        self.assertEqual("https://api.tavily.com/search", mixed["HAIFA_PERSONAL_WEB_SEARCH_ENDPOINT"])
        self.assertEqual("env://TAVILY_API_KEY", mixed["HAIFA_PERSONAL_WEB_SEARCH_CREDENTIAL"])
        self.assertEqual("browserless", mixed["HAIFA_PERSONAL_WEB_FETCH_PROVIDER"])
        self.assertEqual(
            "https://production-sfo.browserless.io/content",
            mixed["HAIFA_PERSONAL_WEB_FETCH_ENDPOINT"],
        )
        self.assertEqual("env://BROWSERLESS_TOKEN", mixed["HAIFA_PERSONAL_WEB_FETCH_CREDENTIAL"])

        tavily = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-chat-flash",
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            tavily_key="tavily-secret",
            web_search_provider="aliyun",
            web_fetch_provider="tavily",
        )
        self.assertEqual("https://api.tavily.com/extract", tavily["HAIFA_PERSONAL_WEB_FETCH_ENDPOINT"])
        self.assertEqual("env://TAVILY_API_KEY", tavily["HAIFA_PERSONAL_WEB_FETCH_CREDENTIAL"])

        with self.assertRaisesRegex(ValueError, "browserless credential is required"):
            real_environment.backend_environment(
                "deepseek-secret",
                "deepseek-chat-flash",
                None,
                "aliyun-secret",
                "continuation-secret",
                paths,
                root / "skills",
                None,
                web_search_provider="aliyun",
                web_fetch_provider="browserless",
            )

    def test_responses_style_configuration_uses_shared_provider_connection_fields(self) -> None:
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

        environment = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-responses-flash",
            ("http://127.0.0.1:30000/v1", "openai-secret", "gpt-test"),
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            tavily_key="tavily-secret",
        )

        self.assertEqual("deepseek-responses-flash", environment["HAIFA_PERSONAL_DEFAULT_MODEL_ID"])
        self.assertEqual(
            "openai-responses",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_1_STYLE"],
        )
        self.assertEqual(
            "deepseek-openai-responses",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_1_DIALECT"],
        )
        self.assertEqual(
            "anthropic-messages",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_STYLE"],
        )
        self.assertEqual(
            "https://api.deepseek.com/anthropic",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_ENDPOINT"],
        )
        self.assertEqual(
            "deepseek-anthropic-messages",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_DIALECT"],
        )
        self.assertEqual(
            "anthropic-messages",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_STYLE"],
        )
        self.assertEqual(
            "openai-responses",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_STYLE"],
        )
        self.assertEqual(
            "anthropic-messages",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_STYLE"],
        )
        self.assertEqual(
            "DeepSeek V4 Pro",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_MODELDISPLAYNAME"],
        )
        self.assertEqual(
            "openai-responses",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_1_APIBINDINGS_0_STYLE"],
        )
        self.assertEqual("gpt-test", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_PROVIDERMODELID"])
        self.assertEqual("TEXT_CHAT", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_CAPABILITIES_0"])
        self.assertEqual("env://OPENAI_API_KEY", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_CREDENTIALREFERENCE"])
        self.assertNotIn("HAIFA_PERSONAL_MODELPROVIDERS_1_DIALECTID", environment)
        self.assertNotIn("HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_IMAGEINPUT", environment)
        self.assertFalse(any(name.startswith("CHATGPT2API_") for name in environment))
        self.assertNotIn("openai-secret", json.dumps(list(environment)))

    def test_deepseek_only_configuration_omits_optional_openai_provider(self) -> None:
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

        environment = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-chat-flash",
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            tavily_key="tavily-secret",
        )

        self.assertEqual("deepseek", environment["HAIFA_PERSONAL_MODELPROVIDERS_0_ID"])
        self.assertEqual("deepseek-chat-flash", environment["HAIFA_PERSONAL_DEFAULT_MODEL_ID"])
        self.assertFalse(any(name.startswith("OPENAI_") for name in environment))
        self.assertFalse(any(name.startswith("HAIFA_PERSONAL_MODELPROVIDERS_1_") for name in environment))

    def test_bailian_key_value_file_adds_qwen_models_without_exposing_secret_in_names(self) -> None:
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
        environment = real_environment.backend_environment(
            "deepseek-secret",
            real_environment.BAILIAN_DEFAULT_MODEL_ID,
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            ("bailian-secret", "workspace-123", "cn-beijing"),
            tavily_key="tavily-secret",
        )

        self.assertEqual("aliyun-bailian", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_ID"])
        self.assertEqual(
            "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_1_ENDPOINT"],
        )
        self.assertEqual(
            real_environment.BAILIAN_DEFAULT_MODEL_ID,
            environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_PROVIDERMODELID"],
        )
        self.assertEqual(
            "ADAPTIVE",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_REASONINGMODE"],
        )
        self.assertEqual(
            "IMAGE_INPUT",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_3_CAPABILITIES_2"],
        )
        self.assertEqual("env://DASHSCOPE_API_KEY", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_CREDENTIALREFERENCE"])
        self.assertNotIn("bailian-secret", json.dumps(list(environment)))

    def test_bailian_configuration_reads_key_value_file_and_defaults_region(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "ss-bailian.txt"
            key_file.write_text("API_KEY:test-secret\nWORKSPACE_ID:Workspace-123\n", encoding="utf-8")

            configured = real_environment.optional_bailian_configuration(
                str(key_file), environment={}
            )

        self.assertEqual(("test-secret", "workspace-123", "cn-beijing"), configured)

    def test_kimi_and_zhipu_keys_add_only_reviewed_api_styles_and_never_enter_configuration_names(self) -> None:
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

        environment = real_environment.backend_environment(
            "deepseek-secret",
            "deepseek-chat-flash",
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
            kimi_key="kimi-secret",
            bigmodel_key="bigmodel-secret",
            tavily_key="tavily-secret",
        )

        self.assertEqual("kimi", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_ID"])
        self.assertEqual("kimi-openai-chat", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_APIBINDINGS_0_DIALECT"])
        self.assertEqual("kimi-k3", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_PROVIDERMODELID"])
        self.assertEqual("zhipu", environment["HAIFA_PERSONAL_MODELPROVIDERS_2_ID"])
        self.assertEqual("zhipu-openai-chat", environment["HAIFA_PERSONAL_MODELPROVIDERS_2_APIBINDINGS_0_DIALECT"])
        self.assertEqual(
            "zhipu-anthropic-messages",
            environment["HAIFA_PERSONAL_MODELPROVIDERS_2_APIBINDINGS_1_DIALECT"],
        )
        self.assertFalse(any("responses" in key.lower() for key in environment if key.startswith("HAIFA_PERSONAL_MODELPROVIDERS_2_")))
        names = json.dumps(list(environment))
        self.assertNotIn("kimi-secret", names)
        self.assertNotIn("bigmodel-secret", names)

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

    def test_default_web_providers_are_tavily(self) -> None:
        with mock.patch.dict(real_environment.os.environ):
            real_environment.os.environ.pop("HAIFA_PERSONAL_WEB_SEARCH_PROVIDER", None)
            real_environment.os.environ.pop("HAIFA_PERSONAL_WEB_FETCH_PROVIDER", None)
            arguments = real_environment.parser().parse_args([])

        self.assertEqual("tavily", arguments.web_search_provider)
        self.assertEqual("tavily", arguments.web_fetch_provider)

    def test_optional_bailian_configuration_does_not_replace_the_verified_default(self) -> None:
        bailian = ("bailian-secret", "workspace-123", "cn-beijing")

        self.assertEqual(
            real_environment.DEFAULT_MODEL_ID,
            real_environment.resolve_default_model_id(None, bailian),
        )
        self.assertEqual(
            real_environment.BAILIAN_DEFAULT_MODEL_ID,
            real_environment.resolve_default_model_id(
                real_environment.BAILIAN_DEFAULT_MODEL_ID,
                bailian,
            ),
        )
        with self.assertRaisesRegex(RuntimeError, "Qwen default model requires"):
            real_environment.resolve_default_model_id(
                real_environment.BAILIAN_DEFAULT_MODEL_ID,
                None,
            )

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
            (str(paths.runtime / "backend"), str(paths.server)),
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
