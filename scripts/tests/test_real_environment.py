#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import os
import sys
import tempfile
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import real_environment

SOURCE = Path(real_environment.__file__).read_text(encoding="utf-8")


class RealEnvironmentTest(unittest.TestCase):
    def temporary_paths(self, root: Path) -> real_environment.Paths:
        runtime = root / "runtime"
        return real_environment.Paths(
            repository=root,
            server=root / "server",
            web=root / "web",
            runtime=runtime,
            data=runtime / "data",
            logs=runtime / "logs",
            backend=runtime / "backend",
            maven_wrapper=root / "mvnw",
        )

    def test_root_scripts_location_resolves_repository_paths(self) -> None:
        repository = Path(__file__).resolve().parents[2]

        paths = real_environment.paths()

        self.assertEqual(repository, paths.repository)
        self.assertEqual(
            repository / "haifa-agent-applications/haifa-agent-personal-assistant-server",
            paths.server,
        )
        self.assertEqual(repository / "haifa-agent-applications/haifa-agent-personal-assistant-web", paths.web)
        self.assertEqual(repository / "local-tmp/personal-assistant-real", paths.runtime)
        self.assertEqual(repository / "local-tmp/personal-assistant-real/data", paths.data)
        self.assertEqual(repository / "local-tmp/personal-assistant-real/logs", paths.logs)
        self.assertEqual(repository / "local-tmp/personal-assistant-real/backend", paths.backend)

    def test_parser_exposes_only_the_three_local_lifecycle_options(self) -> None:
        options = {action.dest for action in real_environment.parser()._actions}

        self.assertEqual({"help", "rebuild", "backend_jar", "startup_timeout_seconds"}, options)

        removed_options = (
            "--stop",
            "--force",
            "--dry-run",
            "--default-model-id",
            "--bailian-region",
            "--web-search-provider",
            "--web-fetch-provider",
            "--trusted-script-manifest",
            "--backend-launch-mode",
            "--continuation-key-file",
        )
        for option in removed_options:
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    real_environment.parser().parse_args([option, "value"])

    def test_script_carries_no_provider_credential_or_native_inspection_knowledge(self) -> None:
        forbidden = (
            "DEEPSEEK_API_KEY",
            "TAVILY_API_KEY",
            "KIMI_API_KEY",
            "BIGMODEL_API_KEY",
            "SILICONFLOW_API_KEY",
            "BROWSERLESS_TOKEN",
            "DASHSCOPE_API_KEY",
            "ALIYUN_IQS_API_KEY",
            "HAIFA_ANTIGRAVITY",
            "HAIFA_CODEX_",
            "HAIFA_PERSONAL_DEFAULT_MODEL_ID",
            "HAIFA_PERSONAL_WEB_",
            "HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST",
            "HAIFA_PERSONAL_CONTINUATION_KEY",
            "model-auth://",
            "env://",
            "deepseek-chat-flash",
            "netstat",
            "lsof",
            "winreg",
            "icacls",
            "Stop-Process",
            "Get-CimInstance",
            "powershell",
            "--stop",
            "--force",
            "--dry-run",
            "--backend-launch-mode",
            "last-start.json",
            "last-stop.json",
        )

        for token in forbidden:
            with self.subTest(token=token):
                self.assertNotIn(token, SOURCE)

    def test_script_stays_within_the_reviewed_size_budget(self) -> None:
        self.assertLessEqual(len(SOURCE.splitlines()), 260)

    def test_health_checks_bypass_any_configured_http_proxy(self) -> None:
        # An empty ProxyHandler keeps build_opener from installing the environment's proxies, so loopback
        # health checks reach 127.0.0.1 directly even when http_proxy is set for the rest of the shell.
        self.assertIn("urllib.request.ProxyHandler({})", SOURCE)
        self.assertNotIn("urlopen", SOURCE)

        with mock.patch.dict(os.environ, {"http_proxy": "http://127.0.0.1:2081"}, clear=False):
            proxied = [
                handler
                for handler in urllib.request.build_opener().handlers
                if isinstance(handler, urllib.request.ProxyHandler) and handler.proxies
            ]
            unproxied = [
                handler
                for handler in urllib.request.build_opener(urllib.request.ProxyHandler({})).handlers
                if isinstance(handler, urllib.request.ProxyHandler) and handler.proxies
            ]

        self.assertNotEqual([], proxied)
        self.assertEqual([], unproxied)

    def test_runtime_environment_injects_the_data_directory_and_trusted_host_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self.temporary_paths(Path(directory))

            environment = real_environment.runtime_environment(paths)

        self.assertEqual(
            {
                "HAIFA_PERSONAL_DATA_DIR": str(paths.data),
                "HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED": "true",
            },
            environment,
        )

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
        self.assertNotIn("clean", real_environment.backend_build_arguments(False))

    def test_invalid_arguments_are_rejected(self) -> None:
        for arguments in (["--startup-timeout-seconds", "29"], ["--startup-timeout-seconds", "601"]):
            with self.subTest(arguments=arguments):
                with self.assertRaisesRegex(RuntimeError, "30 to 600"):
                    real_environment.validate_arguments(real_environment.parser().parse_args(arguments))

        with self.assertRaisesRegex(RuntimeError, "cannot be used together"):
            real_environment.validate_arguments(
                real_environment.parser().parse_args(["--rebuild", "--backend-jar", "server.jar"])
            )

    def test_rebuild_port_conflict_message_asks_for_releasing_the_ports(self) -> None:
        message = real_environment.rebuild_port_conflict_message()

        self.assertIn("ports 20000 and 20001", message)
        self.assertIn("--rebuild", message)
        self.assertNotIn("--stop", message)

    def test_resolve_backend_jar_prefers_the_explicit_option_and_rejects_missing_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = self.temporary_paths(root)
            explicit = root / "built/server.jar"
            explicit.parent.mkdir(parents=True)
            explicit.write_bytes(b"explicit")
            arguments = real_environment.parser().parse_args(["--backend-jar", str(explicit)])

            with mock.patch.object(real_environment, "build_backend") as build:
                resolved = real_environment.resolve_backend_jar(arguments, paths)

            self.assertEqual(explicit, resolved)
            build.assert_not_called()

            missing = root / "absent.jar"
            arguments = real_environment.parser().parse_args(["--backend-jar", str(missing)])
            with self.assertRaisesRegex(RuntimeError, "--backend-jar is not a readable file"):
                real_environment.resolve_backend_jar(arguments, paths)

    def test_missing_backend_jar_triggers_a_package_build(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self.temporary_paths(Path(directory))
            target = paths.server / "target/haifa-agent-personal-assistant-server-0.1.0.jar"
            arguments = real_environment.parser().parse_args([])

            def produce(*_args: object, **_kwargs: object) -> None:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"built")

            with mock.patch.object(real_environment, "build_backend", side_effect=produce) as build:
                resolved = real_environment.resolve_backend_jar(arguments, paths)

        self.assertEqual(target, resolved)
        build.assert_called_once_with(paths, False)

    def test_staging_uses_a_fixed_app_jar_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self.temporary_paths(Path(directory))
            source = paths.server / "target/haifa-agent-personal-assistant-server-0.1.0.jar"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"first build")

            staged = real_environment.stage_backend_jar(source, paths)

            self.assertEqual(paths.backend / "app.jar", staged)
            self.assertEqual(b"first build", staged.read_bytes())

            source.write_bytes(b"second build")
            restaged = real_environment.stage_backend_jar(source, paths)

            self.assertEqual(staged, restaged)
            self.assertEqual(b"second build", restaged.read_bytes())

    def test_frontend_probes_require_serve_and_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self.temporary_paths(Path(directory))
            self.assertFalse(real_environment.frontend_dependencies_installed(paths))
            self.assertFalse(real_environment.frontend_dist_ready(paths))

            serve_marker = paths.web / "node_modules/serve/build/main.js"
            serve_marker.parent.mkdir(parents=True, exist_ok=True)
            serve_marker.write_text("console.log('serve');\n", encoding="utf-8")
            index = paths.web / "dist/index.html"
            index.parent.mkdir(parents=True, exist_ok=True)
            index.write_text("<html></html>\n", encoding="utf-8")

            self.assertTrue(real_environment.frontend_dependencies_installed(paths))
            self.assertTrue(real_environment.frontend_dist_ready(paths))

    def test_healthy_services_are_reused_without_starting_processes(self) -> None:
        arguments = real_environment.parser().parse_args([])
        with mock.patch.object(real_environment, "required_command", return_value="tool"), mock.patch.object(
            real_environment, "port_open", return_value=False
        ), mock.patch.object(real_environment, "http_healthy", return_value=True), mock.patch.object(
            real_environment, "ensure_frontend"
        ) as ensure_frontend, mock.patch.object(
            real_environment, "start_process"
        ) as start_process, mock.patch.object(
            real_environment, "supervise"
        ) as supervise, tempfile.TemporaryDirectory() as directory:
            real_environment.start_environment(arguments, self.temporary_paths(Path(directory)))

        ensure_frontend.assert_called_once()
        start_process.assert_not_called()
        supervise.assert_called_once_with([])

    def test_occupied_but_unhealthy_port_fails_closed_without_stopping_processes(self) -> None:
        arguments = real_environment.parser().parse_args([])
        with mock.patch.object(real_environment, "required_command", return_value="tool"), mock.patch.object(
            real_environment, "port_open", return_value=True
        ), mock.patch.object(real_environment, "http_healthy", return_value=False), mock.patch.object(
            real_environment, "start_process"
        ) as start_process, tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "No process was stopped"):
                real_environment.start_environment(arguments, self.temporary_paths(Path(directory)))

        start_process.assert_not_called()

    def test_rebuild_is_refused_while_the_ports_are_in_use(self) -> None:
        arguments = real_environment.parser().parse_args(["--rebuild"])
        with mock.patch.object(real_environment, "port_open", return_value=True), mock.patch.object(
            real_environment, "required_command", return_value="tool"
        ), tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "ports 20000 and 20001"):
                real_environment.start_environment(arguments, self.temporary_paths(Path(directory)))

    def test_foreground_launcher_terminates_children_when_interrupted(self) -> None:
        arguments = real_environment.parser().parse_args([])
        child = mock.Mock()
        child.poll.return_value = None
        with mock.patch.object(real_environment, "required_command", return_value="tool"), mock.patch.object(
            real_environment, "port_open", return_value=False
        ), mock.patch.object(real_environment, "http_healthy", return_value=False), mock.patch.object(
            real_environment, "ensure_frontend"
        ), mock.patch.object(
            real_environment, "resolve_backend_jar", return_value=Path("server.jar")
        ), mock.patch.object(
            real_environment, "stage_backend_jar", return_value=Path("app.jar")
        ), mock.patch.object(
            real_environment, "start_process", return_value=child
        ), mock.patch.object(
            real_environment, "wait_for_http"
        ), mock.patch.object(
            real_environment, "supervise", side_effect=KeyboardInterrupt
        ), mock.patch.object(
            real_environment, "terminate_process"
        ) as terminate, tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(KeyboardInterrupt):
                real_environment.start_environment(arguments, self.temporary_paths(Path(directory)))

        self.assertEqual(2, terminate.call_count)

    def test_supervise_returns_immediately_when_it_owns_no_child(self) -> None:
        real_environment.supervise([])


if __name__ == "__main__":
    unittest.main()
