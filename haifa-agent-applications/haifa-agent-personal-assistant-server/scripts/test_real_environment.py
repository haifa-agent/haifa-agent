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
    def test_openai_image_capability_is_part_of_shared_backend_configuration(self) -> None:
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
            "openai-secret",
            "aliyun-secret",
            "continuation-secret",
            paths,
            root / "skills",
            None,
        )

        self.assertEqual("openai-chat-completions", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_DIALECTID"])
        self.assertEqual("gpt-5.6-luna", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_PROVIDERMODELID"])
        self.assertEqual("true", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_IMAGEINPUT"])
        self.assertEqual("env://OPENAI_API_KEY", environment["HAIFA_PERSONAL_MODELPROVIDERS_1_CREDENTIALREFERENCE"])
        self.assertNotIn("openai-secret", json.dumps(list(environment)))

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
