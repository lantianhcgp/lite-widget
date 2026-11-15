"""Keeper-backed elapsed minutes: optional contract and read-only aggregation."""

import json
import subprocess
import sys
import unittest
from typing import Any

from test_kanban_build import FileTestCase, SCRIPT


class ElapsedContractTests(FileTestCase):
    def test_legacy_board_remains_readable_and_elapsed_does_not_relax_required_keys(self):
        task = {"name": "旧格式", "line": "widget", "status": "running", "progress": 37, "note": ""}
        board = {"updated_at": "2026-11-15T08:00:00Z", "tasks": [task]}
        output = self.root / "legacy.json"
        output.write_text(json.dumps(board), encoding="utf-8")
        self.assertEqual(board, self.module._load_previous(output))
        before, stamp = output.read_bytes(), output.stat().st_mtime_ns
        self.assertEqual((board, False), self.module._write_board(output, [task]))
        self.assertEqual(before, output.read_bytes())
        self.assertEqual(stamp, output.stat().st_mtime_ns)
        for key in task:
            with self.subTest(missing=key):
                incomplete = {**task, "elapsed": 83}
                incomplete.pop(key)
                self.assertFalse(self.module._valid_board({**board, "tasks": [incomplete]}))
        self.assertFalse(self.module._valid_board({**board, "tasks": [{**task, "elapsed": 83, "extra": 1}]}))

    def test_optional_elapsed_contract_accepts_only_bounded_integral_numbers(self):
        base = {"name": "任务", "line": "widget", "status": "running", "progress": 0, "note": ""}
        for value in (0, 83, 83.0, 2147483647, 2147483647.0):
            with self.subTest(valid=value):
                task = {**base, "elapsed": value}
                self.assertEqual(task, self.module._task(task, {}, "unused"))
                self.assertTrue(self.module._valid_board({"updated_at": "2026-11-15T08:00:00Z", "tasks": [task]}))
        invalid = (True, False, "83", None, -1, -0.1, 0.5, 83.25,
                   float("nan"), float("inf"), -float("inf"), 2147483648,
                   2147483647.5, 10 ** 400)
        for value in invalid:
            with self.subTest(invalid=value):
                task = {**base, "elapsed": value}
                self.assertIsNone(self.module._task(task, {}, "unused"))
                self.assertFalse(self.module._valid_board({"updated_at": "2026-11-15T08:00:00Z", "tasks": [task]}))


class ElapsedBuildTests(FileTestCase):
    def test_elapsed_is_complete_keeper_minutes_only_when_the_time_pair_is_valid(self):
        source = self.source({"name": "复核", "progress": 37})
        valid = ((1700000000.25, 1700005039.75, 83),
                 (1700000000.25, 1700000000.25, 0),
                 (1700000000.25, 1700000059.75, 0),
                 (0, 60, 1), (0, 128849018820, 2147483647))
        for start, last_seen, expected in valid:
            with self.subTest(start=start, last_seen=last_seen):
                entry = {**self.entry(), "start": start, "last_seen": last_seen}
                task = self.module.build_tasks(self.state(entry), [source])[0]
                self.assertEqual(expected, task.get("elapsed"))
                self.assertIs(type(task["elapsed"]), int)
                self.assertEqual(37, task["progress"])
        invalid: list[dict[str, Any]] = [{}, {"start": 0}, {"last_seen": 60},
                   {"start": -1, "last_seen": 60}, {"start": 61, "last_seen": 60},
                   {"start": 0, "last_seen": 128849018880},
                   {"start": 0, "last_seen": 10 ** 400}]
        for value in (None, True, False, "0", float("nan"), float("inf"), -float("inf")):
            invalid.extend(({"start": value, "last_seen": 60}, {"start": 0, "last_seen": value}))
        for timing in invalid:
            with self.subTest(invalid=timing):
                entry = self.entry()
                entry.pop("last_seen")
                entry.update(timing)
                task = self.module.build_tasks(self.state(entry), [source])[0]
                self.assertNotIn("elapsed", task)
                self.assertEqual(37, task["progress"])

    def test_folded_query_or_resume_uses_earliest_valid_start_and_latest_valid_seen(self):
        source = self.source({"name": "唯一任务", "progress": 23}, tag="mapped")
        identities = (("hermes chat --query-file '/test/a brief.md'",
                       'bash -lc "hermes chat --query-file=\'/test/a brief.md\'"'),
                      ("hermes chat --resume session-A", 'bash -c "hermes chat --resume=session-A"'))
        for direct, wrapper in identities:
            with self.subTest(identity=direct):
                entries = [
                    {**self.entry("outer", cmd=wrapper), "start": 1000.25, "last_seen": 1120.25},
                    {**self.entry("mapped", cmd=direct), "start": 2000.25, "last_seen": 2300.25},
                    {**self.entry("latest", cmd=wrapper), "start": 4000.25, "last_seen": 6039.75},
                    {**self.entry("invalid", cmd=direct), "start": -1000, "last_seen": 999999},
                    {**self.entry("gone", gone=3, cmd=direct), "start": 0, "last_seen": 999999},
                    {"tag": "start-only", "cmd": wrapper, "start": 0},
                    {"tag": "end-only", "cmd": direct, "last_seen": 999999},
                ]
                for ordered in (entries, list(reversed(entries))):
                    tasks = self.module.build_tasks(self.state(*ordered), [source])
                    self.assertEqual(1, len(tasks))
                    self.assertEqual("唯一任务", tasks[0]["name"])
                    self.assertEqual(83, tasks[0].get("elapsed"))
                    self.assertEqual(23, tasks[0]["progress"])

    def test_unmapped_wrappers_get_elapsed_without_inventing_progress(self):
        command = "hermes chat --resume session-A"
        entries = [{**self.entry("one", cmd=command), "start": 1000, "last_seen": 1120},
                   {**self.entry("two", cmd=command), "start": 4000, "last_seen": 5980},
                   {**self.entry("other"), "start": 0, "last_seen": 60}]
        tasks = self.module.build_tasks(self.state(*entries), [])
        self.assertEqual([83, 1], [task.get("elapsed") for task in tasks])
        self.assertEqual([0, 0], [task["progress"] for task in tasks])
        self.assertTrue(all(task["note"].startswith("[进度未量化] ") for task in tasks))

    def test_source_elapsed_is_never_substituted_for_keeper_evidence(self):
        for value in (999, 83.0, None, True, "83", -1, 0.5, float("nan"), float("inf")):
            with self.subTest(source_elapsed=value):
                source = self.source({"tasks": [{"name": "显式进度", "progress": 37, "elapsed": value},
                                                {"name": "未知进度", "elapsed": value}]})
                for entry, expected in ((self.entry(), None),
                                        ({**self.entry(), "start": 1000.25, "last_seen": 6039.75}, 83)):
                    tasks = self.module.build_tasks(self.state(entry), [source])
                    self.assertEqual(2, len(tasks))
                    self.assertEqual([expected, expected], [task.get("elapsed") for task in tasks])
                    if expected is None:
                        self.assertTrue(all("elapsed" not in task for task in tasks))
                    self.assertEqual([37, 0], [task["progress"] for task in tasks])
                    self.assertTrue(tasks[1]["note"].startswith("[进度未量化] "))

    def test_inactive_uses_only_matching_gone_history_frozen_times(self):
        sources = [self.source({"name": "冻结线", "status": "waiting", "progress": 22},
                               tag="gone", include_inactive=True, filename="gone.json"),
                   self.source({"name": "无匹配", "elapsed": 999}, tag="missing",
                               include_inactive=True, filename="missing.json")]
        history = {**self.entry("gone", gone=3), "start": 1000.25, "last_seen": 6039.75}
        unrelated = {**self.entry("gone-other", gone=9), "start": 0, "last_seen": 999999}
        tasks = self.module.build_tasks(self.state(history, unrelated), sources)
        self.assertEqual([None, 83], [task.get("elapsed") for task in tasks])
        self.assertEqual([0, 22], [task["progress"] for task in tasks])
        self.assertEqual(["running", "waiting"], [task["status"] for task in tasks])
        history["gone"] = 100
        self.assertEqual(tasks, self.module.build_tasks(self.state(history, unrelated), sources))
        history.pop("start")
        self.assertTrue(all("elapsed" not in task for task in self.module.build_tasks(self.state(history), sources)))
        sources[0]["include_inactive"] = "true"
        self.assertEqual(["无匹配"], [task["name"] for task in self.module.build_tasks(self.state(history), sources)])


class ElapsedCliTests(FileTestCase):
    def test_repeated_and_same_minute_inputs_preserve_bytes_mtime_timestamp_but_new_minute_updates(self):
        source = self.source({"name": "验证", "note": "测试 8/8；耗时不是进度"})
        entry = {**self.entry(), "start": 1700000000.25, "last_seen": 1700005039.75}
        state, sources, output = (self.root / name for name in ("keeper.json", "sources.json", "board.json"))
        state.write_text(json.dumps(self.state(entry)), encoding="utf-8")
        sources.write_text(json.dumps([source]), encoding="utf-8")
        args = [sys.executable, "-B", str(SCRIPT), "--state", str(state), "--sources", str(sources), "--output", str(output)]
        protected = [state, sources, self.root / "progress.json"]
        snapshots = [(path.read_bytes(), path.stat().st_mtime_ns) for path in protected]
        first = subprocess.run(args, capture_output=True, text=True, timeout=8)
        self.assertEqual(0, first.returncode, first.stderr)
        before, stamp = output.read_bytes(), output.stat().st_mtime_ns
        board = json.loads(before)
        self.assertEqual(83, board["tasks"][0].get("elapsed"))
        self.assertEqual(0, board["tasks"][0]["progress"])
        second = subprocess.run(args + ["--print"], capture_output=True, text=True, timeout=8)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(board, json.loads(second.stdout))
        self.assertEqual(before, output.read_bytes())
        self.assertEqual(stamp, output.stat().st_mtime_ns)
        self.assertEqual(snapshots, [(path.read_bytes(), path.stat().st_mtime_ns) for path in protected])
        entry["last_seen"] = 1700005039.875
        state.write_text(json.dumps(self.state(entry)), encoding="utf-8")
        same_minute = subprocess.run(args, capture_output=True, text=True, timeout=8)
        self.assertEqual(0, same_minute.returncode, same_minute.stderr)
        self.assertIn("changed=no", same_minute.stdout)
        self.assertEqual(before, output.read_bytes())
        self.assertEqual(stamp, output.stat().st_mtime_ns)
        entry["last_seen"] = 1700005040.25
        state.write_text(json.dumps(self.state(entry)), encoding="utf-8")
        changed = subprocess.run(args + ["--print"], capture_output=True, text=True, timeout=8)
        self.assertEqual(0, changed.returncode, changed.stderr)
        new_board = json.loads(changed.stdout)
        self.assertEqual({**board["tasks"][0], "elapsed": 84}, new_board["tasks"][0])
        self.assertNotEqual(board["updated_at"], new_board["updated_at"])
        self.assertNotEqual(before, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
