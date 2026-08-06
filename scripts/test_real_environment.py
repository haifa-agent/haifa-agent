#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import real_environment


class RealEnvironmentTest(unittest.TestCase):
    def test_root_scripts_location_resolves_repository_paths(self) -> None:
        repository = Path(__file__).resolve().parents[1]

        paths = real_environment.paths()

        self.assertEqual(repository, paths.repository)
        self.assertEqual(
            repository / "haifa-agent-applications/haifa-agent-personal-assistant-server",
            paths.server,
        )
        self.assertEqual(repository / "haifa-agent-applications/haifa-agent-personal-assistant-web", paths.web)

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
            ("http://127.0.0.1:30000/v1", "openai-secret", "gpt-test"),
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
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
            None,
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
        )

        self.assertEqual("deepseek", environment["HAIFA_PERSONAL_MODELPROVIDERS_0_ID"])
        self.assertFalse(any(name.startswith("OPENAI_") for name in environment))
        self.assertFalse(any(name.startswith("HAIFA_PERSONAL_MODELPROVIDERS_1_") for name in environment))

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
