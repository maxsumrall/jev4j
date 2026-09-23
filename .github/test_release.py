"""Offline release checks. Run with Python 3 and PyYAML 6.0.2."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "release_version", ROOT / ".github/release-version.py"
)
VERSION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERSION)
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/release.yml").read_text())
STEPS = WORKFLOW["jobs"]["publish"]["steps"]
SHA = "a" * 40


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in (*VERSION.POMS, ".github/release-version.py", "LICENSE"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        self.bin = self.root / "bin"
        self.bin.mkdir()

    def mock(self, command, body):
        path = self.bin / command
        path.write_text("#!/usr/bin/env python3\nimport os, sys, json\n" + body)
        path.chmod(0o755)

    def run_step(self, prefix, **env):
        step = next(step for step in STEPS if step["name"].startswith(prefix))
        return subprocess.run(
            ["bash", "-c", step["run"]],
            cwd=self.root,
            text=True,
            capture_output=True,
            env={
                **os.environ,
                "PATH": f"{self.bin}:{os.environ['PATH']}",
                "RELEASE_VERSION": "2026.9.1",
                "RELEASE_TAG": "v2026.9.1",
                "RELEASE_COMMIT": SHA,
                "GITHUB_RUN_ATTEMPT": "1",
                "GITHUB_RUN_ID": "123",
                "GITHUB_STEP_SUMMARY": str(self.root / "summary"),
                "GH_REPO": "test/repo",
                "GH_TOKEN": "synthetic",
                **env,
            },
        )

    def test_calver_boundaries(self):
        for version in ("2026.1.1", "2026.12.19", "2027.9.2"):
            VERSION.validate_version(version)
        for version in (
            "0.1.0",
            "2026.0.1",
            "2026.13.1",
            "2026.09.1",
            "2026.9.01",
            "2026.9.0",
            "2026.9.1-SNAPSHOT",
            "2026.9.1\n",
            "$(id)",
            "../x",
        ):
            with self.subTest(version=version), self.assertRaises(ValueError):
                VERSION.validate_version(version)

    def test_versions_and_only_expected_tokens_change(self):
        before = {name: (self.root / name).read_text() for name in VERSION.POMS}
        VERSION.prepare(self.root, "2026.9.3")
        for name, text in before.items():
            after = (self.root / name).read_text()
            self.assertNotIn("0.0.0-SNAPSHOT", after)
            self.assertEqual(
                text,
                after.replace("2026.9.3", "0.0.0-SNAPSHOT").replace(
                    "<tag>v0.0.0-SNAPSHOT</tag>", "<tag>HEAD</tag>"
                ),
            )
        core = (self.root / "jev4j-core/pom.xml").read_text()
        self.assertIn("<version>3.0.2</version>", core)
        self.assertIn("<tag>v2026.9.3</tag>", core)
        with self.assertRaises(ValueError):
            VERSION.prepare(self.root, "2026.9.4")

    def test_stale_last_pom_rejects_without_partial_writes(self):
        path = self.root / VERSION.POMS[-1]
        path.write_text(path.read_text().replace("0.0.0-SNAPSHOT", "0.1.0"))
        before = {name: (self.root / name).read_bytes() for name in VERSION.POMS}
        with self.assertRaises(ValueError):
            VERSION.prepare(self.root, "2026.9.1")
        self.assertEqual(
            before, {name: (self.root / name).read_bytes() for name in VERSION.POMS}
        )

    def test_ci_gate(self):
        self.mock(
            "gh",
            """if os.environ.get('API_FAIL'): sys.exit(1)
if os.environ.get('SECOND_FAIL') and 'openrouter-component.yml' in sys.argv[-1]:
    print('{"workflow_runs": []}')
else: print(os.environ['RUNS'])
""",
        )
        good = {
            "head_sha": SHA,
            "head_branch": "main",
            "event": "push",
            "status": "completed",
            "conclusion": "success",
        }
        cases = [(good, True), ({}, False)]
        for key, values in {
            "head_sha": ["b" * 40],
            "head_branch": ["prep"],
            "event": ["workflow_dispatch"],
            "status": ["queued", "in_progress"],
            "conclusion": ["failure", "cancelled", "skipped", "neutral", None],
        }.items():
            cases.extend(({**good, key: value}, False) for value in values)
        for run, succeeds in cases:
            with self.subTest(run=run):
                result = self.run_step(
                    "Require successful CI", RUNS=json.dumps({"workflow_runs": [run]})
                )
                self.assertEqual(result.returncode == 0, succeeds, result.stderr)
        for runs in ('{"workflow_runs": []}', "invalid", "null"):
            self.assertNotEqual(
                self.run_step("Require successful CI", RUNS=runs).returncode, 0
            )
        self.assertNotEqual(
            self.run_step(
                "Require successful CI",
                RUNS=json.dumps({"workflow_runs": [good]}),
                API_FAIL="1",
            ).returncode,
            0,
        )
        self.assertNotEqual(
            self.run_step(
                "Require successful CI",
                RUNS=json.dumps({"workflow_runs": [good]}),
                SECOND_FAIL="1",
            ).returncode,
            0,
        )

    def test_preflight_stops_on_existing_version_errors_or_rerun(self):
        self.mock(
            "git",
            """if sys.argv[1] == 'rev-parse': print(os.environ['RELEASE_COMMIT'])
elif sys.argv[1] == 'show-ref': sys.exit(0 if os.environ.get('TAG_EXISTS') else 1)
""",
        )
        self.mock("curl", "print(os.environ.get('HTTP_STATUS', '404'))\n")
        for status in ("200", "403", "429", "500", "000"):
            # Each invocation needs unmodified snapshot POMs.
            for name in VERSION.POMS:
                shutil.copyfile(ROOT / name, self.root / name)
            with self.subTest(status=status):
                self.assertNotEqual(
                    self.run_step("Prepare release", HTTP_STATUS=status).returncode, 0
                )
        for name in VERSION.POMS:
            shutil.copyfile(ROOT / name, self.root / name)
        self.assertNotEqual(
            self.run_step("Prepare release", TAG_EXISTS="1").returncode, 0
        )
        self.assertNotEqual(
            self.run_step("Prepare release", GITHUB_RUN_ATTEMPT="2").returncode, 0
        )
        for name in VERSION.POMS:
            shutil.copyfile(ROOT / name, self.root / name)
        result = self.run_step("Prepare release")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tag_reservation_and_identity(self):
        self.mock(
            "gh",
            """payload = json.load(sys.stdin)
with open('requests', 'a') as log: log.write(json.dumps(payload) + '\\n')
if os.environ.get('TAG_DENIED'): sys.exit(1)
if os.environ.get('REF_DENIED') and 'ref' in payload: sys.exit(1)
print('b' * 40)
""",
        )
        result = self.run_step("Reserve annotated")
        self.assertEqual(result.returncode, 0, result.stderr)
        tag, ref = map(json.loads, (self.root / "requests").read_text().splitlines())
        self.assertEqual(tag["object"], SHA)
        self.assertEqual(tag["tagger"]["email"], "jmsumrall@gmail.com")
        self.assertEqual(tag["tagger"]["name"], "Max Sumrall")
        self.assertEqual(ref, {"ref": "refs/tags/v2026.9.1", "sha": "b" * 40})
        self.assertNotEqual(
            self.run_step("Reserve annotated", TAG_DENIED="1").returncode, 0
        )
        self.assertNotEqual(
            self.run_step("Reserve annotated", REF_DENIED="1").returncode, 0
        )
        self.assertNotEqual(
            self.run_step("Reserve annotated", GH_TOKEN="").returncode, 0
        )

    def test_workflow_contract_and_shell_syntax(self):
        job = WORKFLOW["jobs"]["publish"]
        self.assertEqual(job["environment"], "maven-central")
        self.assertEqual(job["if"], "github.ref == 'refs/heads/main'")
        self.assertEqual(job["env"]["RELEASE_COMMIT"], "${{ github.sha }}")
        self.assertEqual(STEPS[0]["with"]["ref"], "${{ github.sha }}")
        names = [step["name"] for step in STEPS]
        self.assertLess(
            names.index("Reserve annotated source tag before any Central upload"),
            names.index("Sign and publish libraries"),
        )
        for step in STEPS:
            if "run" in step:
                result = subprocess.run(
                    ["bash", "-n"], input=step["run"], text=True, capture_output=True
                )
                self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
