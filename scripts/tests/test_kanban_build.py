import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "kanban_build.py"


def load_module():
    spec = importlib.util.spec_from_file_location("kanban_build", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FileTestCase(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    @staticmethod
    def entry(tag="简报-widget", gone=0, cmd=None):
        return {"tag": tag, "gone": gone, "cmd": cmd or f'hermes chat --query-file "/test/{tag}.md"', "last_seen": 1234}

    @staticmethod
    def state(*entries):
        return {"seen": {str(index): entry for index, entry in enumerate(entries)}}

    def source(self, data, tag="简报-widget", filename="progress.json", **options):
        path = self.root / filename
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return {"tag": tag, "path": str(path), **options}


class KanbanBuildTests(FileTestCase):
    def test_board_dates_use_rfc3339_calendar_subset(self):
        invalid = ("2026-11-15 08:00:00Z", "2026-W46-7T08:00:00Z", "2026-11-15T08:00:00+01:00:30",
                   "0000-11-15T08:00:00Z", "2026-02-30T08:00:00Z", "2026-11-15T08:00:00+18:01",
                   "2026-11-15T08:00:00.1234567Z", "2026-11-15T24:00:00Z", "2026-11-15T08:00:60Z")
        valid = ("2026-11-15T08:00:00Z", "2026-11-15T08:00:00.123456Z", "2026-11-15T08:00:00+08:00",
                 "2024-02-29T08:00:00.1-18:00", "2026-11-15T08:00:00+18:00")
        for value in invalid:
            with self.subTest(invalid=value):
                self.assertFalse(self.module._valid_board({"updated_at": value, "tasks": []}))
        for value in valid:
            with self.subTest(valid=value):
                self.assertTrue(self.module._valid_board({"updated_at": value, "tasks": []}))

    def test_unknown_nonzero_progress_is_skipped_and_invalid_board(self):
        for note in ("[进度未量化]", "[进度未量化] 预检"):
            with self.subTest(note=note):
                bad = {"name": "任务", "line": "widget", "status": "running", "progress": 37, "note": note}
                self.assertIsNone(self.module._task(bad, {}, "unused"))
                self.assertFalse(self.module._valid_board({"updated_at": "2026-11-15T08:00:00Z", "tasks": [bad]}))
                good = {**bad, "progress": 0}
                self.assertEqual(good, self.module._task(good, {}, "unused"))
                self.assertTrue(self.module._valid_board({"updated_at": "2026-11-15T08:00:00Z", "tasks": [good]}))

    def test_keeper_wrappers_collapse_to_one_real_task(self):
        source = self.source({"name": "渲染验证", "line": "widget", "status": "running", "progress": 37, "note": "预检通过"})
        entry = self.entry(cmd='hermes chat --query-file "/test/brief.md"')
        result = self.module.build_tasks(self.state(entry, entry, entry), [source])
        self.assertEqual([{"name": "渲染验证", "line": "widget", "status": "running", "progress": 37, "note": "预检通过"}], result)

    def test_missing_percentage_is_explicitly_unknown(self):
        source = self.source({"status": "active", "note": "测试 8/8，版本 0.3.9；非进度"}, name="视觉复核", line="widget")
        result = self.module.build_tasks(self.state(self.entry()), [source])
        self.assertEqual([{"name": "视觉复核", "line": "widget", "status": "running", "progress": 0, "note": "[进度未量化] 测试 8/8，版本 0.3.9；非进度"}], result)

    def test_progress_requires_a_finite_number_in_range_without_clamping(self):
        for invalid in (True, False, None, "40%", float("nan"), float("inf"), -1, 101, -0.001, 100.001):
            with self.subTest(progress=invalid):
                source = self.source({"name": "坏线", "status": "running", "progress": invalid})
                self.assertEqual([], self.module.build_tasks(self.state(self.entry()), [source]))
        for valid in (0, 100, 12.5):
            with self.subTest(progress=valid):
                source = self.source({"name": "真进度", "progress": valid})
                tasks = self.module.build_tasks(self.state(self.entry()), [source])
                self.assertEqual(valid, tasks[0]["progress"])
                self.assertEqual("", tasks[0]["note"])

    def test_json_task_array_skips_bad_members_and_limits_strings(self):
        source = self.source({"tasks": [None, {"progress": "8/8"}, {"name": "名" * 80, "line": "😀" * 80, "status": "running", "progress": 19.5, "note": "注" * 280, "cmd": "must-not-appear"}]})
        tasks = self.module.build_tasks(self.state(self.entry()), [source])
        self.assertEqual(1, len(tasks))
        self.assertEqual({"name", "line", "status", "progress", "note"}, set(tasks[0]))
        self.assertLessEqual(len(tasks[0]["name"]), 64)
        self.assertLessEqual(len(tasks[0]["line"].encode("utf-16-le")) // 2, 64)
        self.assertLessEqual(len(tasks[0]["note"]), 240)
        self.assertNotIn("must-not-appear", json.dumps(tasks))

    def test_bad_source_does_not_hide_other_active_lines(self):
        bad = self.source({"progress": -5}, tag="bad", filename="bad.json")
        good = self.source({"name": "好线", "line": "good", "progress": 72, "status": "blocked", "note": "等确认"}, tag="good", filename="good.json")
        self.assertEqual([{"name": "好线", "line": "good", "status": "blocked", "progress": 72, "note": "等确认"}], self.module.build_tasks(self.state(self.entry("bad"), self.entry("good")), [bad, good]))

    def test_unmapped_active_task_is_unknown_but_missing_mapped_file_is_skipped(self):
        missing = {"tag": "简报-missing", "path": str(self.root / "missing.json")}
        result = self.module.build_tasks(self.state(self.entry("简报-未映射"), self.entry("简报-missing")), [missing])
        self.assertEqual([{"name": "未映射", "line": "简报-未映射", "status": "running", "progress": 0, "note": "[进度未量化] "}], result)

    def test_quoted_query_and_nested_shell_wrappers_share_one_identity(self):
        source = self.source({"name": "唯一任务", "progress": 23}, tag="mapped")
        entries = [
            self.entry("outer", cmd='bash -lc "hermes chat --query-file \'/test/a brief.md\'"'),
            self.entry("mapped", cmd="python /test/hermes/cli.py chat --query-file '/test/a brief.md'"),
            self.entry("third", cmd='hermes chat --query-file="/test/a brief.md"'),
        ]
        tasks = self.module.build_tasks(self.state(*entries), [source])
        self.assertEqual(1, len(tasks))
        self.assertEqual("唯一任务", tasks[0]["name"])
        self.assertEqual(23, tasks[0]["progress"])

    def test_resume_identity_deduplicates_but_distinct_queries_stay_separate(self):
        entries = [self.entry("one", cmd="hermes chat --resume session-A"),
                   self.entry("two", cmd='bash -c "hermes chat --resume=session-A"'),
                   self.entry("three", cmd="hermes chat --resume session-B"),
                   self.entry("four", cmd="hermes chat --query-file /test/another.md")]
        tasks = self.module.build_tasks(self.state(*entries), [])
        self.assertEqual(["one", "three", "four"], [task["line"] for task in tasks])

    def test_gone_wrappers_are_not_completion_and_inactive_requires_explicit_opt_in(self):
        sources = [self.source({"name": "已走线", "status": "done"}, tag="gone", filename="gone.json"),
                   self.source({"name": "等待线", "status": "waiting", "progress": 22}, tag="wait", filename="wait.json", include_inactive=True)]
        state = self.state(self.entry("gone", gone=1), self.entry("wait", gone=3))
        self.assertEqual([{"name": "等待线", "line": "wait", "status": "waiting", "progress": 22, "note": ""}], self.module.build_tasks(state, sources))
        sources[0]["include_inactive"] = True
        tasks = self.module.build_tasks(state, sources)
        self.assertEqual(100, tasks[0]["progress"])
        self.assertEqual("waiting", tasks[0]["status"])
        sources[0]["include_inactive"] = "true"
        self.assertEqual(1, len(self.module.build_tasks(state, sources)))

    def test_source_tags_are_exact_not_implicit_prefixes(self):
        source = self.source({"name": "不能套用", "progress": 99}, tag="简报-widget")
        tasks = self.module.build_tasks(self.state(self.entry("简报-widget-other")), [source])
        self.assertEqual("widget-other", tasks[0]["name"])
        self.assertEqual(0, tasks[0]["progress"])

    def test_max_eight_with_stable_status_priority(self):
        data = {"tasks": [
            {"name": "wait-a", "status": "waiting", "progress": 0},
            {"name": "run-a", "status": "running", "progress": 1},
            {"name": "block-a", "status": "blocked", "progress": 2},
            {"name": "incident-a", "status": "incident", "progress": 3},
            {"name": "run-b", "status": "running", "progress": 4},
            {"name": "incident-b", "status": "incident", "progress": 5},
            {"name": "block-b", "status": "blocked", "progress": 6},
            {"name": "run-c", "status": "running", "progress": 7},
            {"name": "wait-b", "status": "waiting", "progress": 8},
            {"name": "run-d", "status": "running", "progress": 9},
        ]}
        tasks = self.module.build_tasks(self.state(self.entry()), [self.source(data)])
        self.assertEqual(["incident-a", "incident-b", "block-a", "block-b", "run-a", "run-b", "run-c", "run-d"], [task["name"] for task in tasks])

    def test_malformed_keeper_is_distinct_from_valid_empty_state(self):
        for bad in (None, [], {}, {"seen": []}, {"seen": {"p": None}}, {"seen": {"p": {"tag": "bad", "gone": "0"}}}):
            with self.subTest(state=bad):
                with self.assertRaises(ValueError):
                    self.module.build_tasks(bad, [])
        self.assertEqual([], self.module.build_tasks({"seen": {}}, []))
        tasks = self.module.build_tasks({"seen": {"bad": None, "good": self.entry()}}, [])
        self.assertEqual(1, len(tasks))

    def markdown_source(self, text, **options):
        path = self.root / "progress.md"
        path.write_text(text, encoding="utf-8")
        return {"tag": "简报-widget", "path": str(path), **options}

    def test_markdown_reads_only_explicit_current_fields(self):
        source = self.markdown_source("# 当前任务\n任务名：组件复核\n任务线：widget\n任务状态：active\n进度百分比：37.5%\n备注：预检通过\n当前环节：四稿硬检\n## 当前证据\n测试通过率：8/8\n版本：0.3.9\n", name="映射名")
        self.assertEqual([{"name": "组件复核", "line": "widget", "status": "running", "progress": 37.5, "note": "预检通过；四稿硬检"}], self.module.build_tasks(self.state(self.entry()), [source]))

    def test_markdown_live_active_note_does_not_invent_percentage_or_incident(self):
        source = self.markdown_source("# 当前\n任务状态：active\n当前环节：四稿硬检和组件视觉复核\n备注：历史 blocked 事故已排除\n测试通过率：100%\n版本：8/8\n")
        task = self.module.build_tasks(self.state(self.entry()), [source])[0]
        self.assertEqual("running", task["status"])
        self.assertEqual(0, task["progress"])
        self.assertEqual("[进度未量化] 历史 blocked 事故已排除；四稿硬检和组件视觉复核", task["note"])

    def test_markdown_history_old_below_and_rules_never_override_current(self):
        boundaries = ("## 历史记录", "## 旧状态", "## 以下为历史", "---", "***", "___")
        for boundary in boundaries:
            with self.subTest(boundary=boundary):
                source = self.markdown_source("# 当前\n任务状态：active\n当前环节：复核\n" + boundary + "\n任务状态：incident\n进度百分比：100%\n任务名：旧名字\n")
                tasks = self.module.build_tasks(self.state(self.entry()), [source])
                self.assertEqual("running", tasks[0]["status"])
                self.assertEqual(0, tasks[0]["progress"])
                self.assertNotIn("旧名字", json.dumps(tasks, ensure_ascii=False))
        source = self.markdown_source("任务状态：incident\n进度百分比：100%\n# 当前\n任务状态：active\n当前环节：复核\n")
        self.assertEqual(0, self.module.build_tasks(self.state(self.entry()), [source])[0]["progress"])

    def test_markdown_past_record_does_not_supply_current_progress(self):
        source = self.markdown_source("# 当前任务\n任务状态：running\n## 过往记录\n进度百分比：99%")
        tasks = self.module.build_tasks(self.state(self.entry()), [source])
        self.assertEqual([{"name": "widget", "line": "简报-widget", "status": "running", "progress": 0,
                           "note": "[进度未量化] "}], tasks)

    def test_markdown_current_section_ends_at_noncurrent_or_peer_heading(self):
        boundaries = ("## 过往记录", "## 往期", "## 记录", "## 回顾", "## 归档", "## 已完成任务",
                      "## 下一任务", "## 补充材料", "# 当前任务", "# 当前证据", "# 另一任务")
        for boundary in boundaries:
            with self.subTest(boundary=boundary):
                source = self.markdown_source("# 当前任务\n任务状态：running\n" + boundary +
                                              "\n进度百分比：99%\n备注：不能回放\n## 当前证据\n任务名：不能恢复\n")
                tasks = self.module.build_tasks(self.state(self.entry()), [source])
                self.assertEqual(0, tasks[0]["progress"])
                self.assertEqual("[进度未量化] ", tasks[0]["note"])
                self.assertEqual("widget", tasks[0]["name"])

    def test_markdown_project_title_keeps_current_fields_until_any_next_heading(self):
        current = "# lite-widget 任务进度看板 · 2026-11-15\n任务状态：active\n当前环节：R2复查\n"
        ignored = "\n> ## 当前任务\n```\n## 当前进度\n```\n~~~\n## 当前证据\n~~~\n"
        for boundary in ("", "## 待验收", "### 其他材料", "# 另一项目", "## 当前任务设计"):
            with self.subTest(boundary=boundary):
                text = current + ignored
                if boundary:
                    text += boundary + "\n进度百分比：99%\n备注：不能回放\n任务状态：incident\n"
                self.assertEqual({"status": "active", "note": "R2复查"}, self.module._markdown_task(text))

    def test_markdown_explicit_current_subsection_stays_in_scope(self):
        source = self.markdown_source("# 简报\n进度百分比：99%\n## 当前任务\n任务状态：running\n"
                                      "### 当前证据\n进度百分比：21%\n备注：当前证据\n")
        tasks = self.module.build_tasks(self.state(self.entry()), [source])
        self.assertEqual(21, tasks[0]["progress"])
        self.assertEqual("当前证据", tasks[0]["note"])

    def test_markdown_bold_bullets_and_current_table(self):
        source = self.markdown_source("# 当前\n- **任务名**：校验\n- **任务状态：** 受阻\n| 进度百分比 | 20% |\n| 当前环节 | 等用户确认 |\n")
        task = self.module.build_tasks(self.state(self.entry()), [source])[0]
        self.assertEqual("校验", task["name"])
        self.assertEqual("blocked", task["status"])
        self.assertEqual(20, task["progress"])
        self.assertEqual("等用户确认", task["note"])

    def test_markdown_non_progress_numbers_and_bad_explicit_percentage(self):
        for value in ("8/8", "v0.3.9", "NaN", "Infinity", "-1%", "101%", "20% 测试8/8"):
            with self.subTest(value=value):
                source = self.markdown_source("# 当前\n任务状态：active\n进度百分比：" + value + "\n")
                self.assertEqual([], self.module.build_tasks(self.state(self.entry()), [source]))
        source = self.markdown_source("# 证据\n测试通过率：8/8\n版本：0.3.9\n")
        self.assertEqual([], self.module.build_tasks(self.state(self.entry()), [source]))

    def test_markdown_quotes_code_and_duplicate_stale_fields_are_ignored(self):
        source = self.markdown_source("# 当前\n任务状态：active\n进度百分比：31%\n> 任务状态：incident\n```\n进度百分比：100%\n```\n## 历史\n进度百分比：99%\n")
        task = self.module.build_tasks(self.state(self.entry()), [source])[0]
        self.assertEqual("running", task["status"])
        self.assertEqual(31, task["progress"])


class CliTests(FileTestCase):
    def run_cli(self, *options):
        return subprocess.run([sys.executable, "-B", str(SCRIPT), *map(str, options)], capture_output=True, text=True, timeout=8)

    def cli_files(self):
        state = self.root / "keeper.json"
        sources = self.root / "sources.json"
        output = self.root / "board.json"
        source = self.source({"name": "当前测试", "line": "widget", "status": "active", "progress": 36, "note": "验证"})
        state.write_text(json.dumps(self.state(self.entry())), encoding="utf-8")
        sources.write_text(json.dumps([source]), encoding="utf-8")
        return state, sources, output

    def test_cli_print_and_idempotent_bytes_mtime_and_timestamp(self):
        state, sources, output = self.cli_files()
        args = ("--state", state, "--sources", sources, "--output", output)
        first = self.run_cli(*args)
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(f"tasks=1 changed=yes path={output}\n", first.stdout)
        before = output.read_bytes()
        stat = output.stat()
        board = json.loads(before)
        self.assertEqual(36, board["tasks"][0]["progress"])
        offset = datetime.fromisoformat(board["updated_at"].replace("Z", "+00:00")).utcoffset()
        self.assertIsNotNone(offset)
        assert offset is not None
        self.assertEqual(0, offset.total_seconds())
        second = self.run_cli(*args)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(f"tasks=1 changed=no path={output}\n", second.stdout)
        self.assertEqual(before, output.read_bytes())
        self.assertEqual(stat.st_mtime_ns, output.stat().st_mtime_ns)
        printed = self.run_cli(*args, "--print")
        self.assertEqual(board, json.loads(printed.stdout))
        self.assertEqual("", printed.stderr)
        progress = self.root / "progress.json"
        progress.write_text(json.dumps({"name": "当前测试", "line": "widget", "progress": 37}), encoding="utf-8")
        third = self.run_cli(*args, "--print")
        self.assertEqual(0, third.returncode, third.stderr)
        self.assertEqual(37, json.loads(third.stdout)["tasks"][0]["progress"])
        self.assertNotEqual(board["updated_at"], json.loads(third.stdout)["updated_at"])

    def test_cli_unreadable_keeper_preserves_valid_board_without_exception_details(self):
        state, sources, output = self.cli_files()
        args = ("--state", state, "--sources", sources, "--output", output)
        self.assertEqual(0, self.run_cli(*args).returncode)
        before, stamp = output.read_bytes(), output.stat().st_mtime_ns
        for bad in ("PRIVATE_SENTINEL{", json.dumps({"seen": []})):
            state.write_text(bad, encoding="utf-8")
            result = self.run_cli(*args)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertEqual("看板：输入不可用\n", result.stderr)
            self.assertEqual(before, output.read_bytes())
            self.assertEqual(stamp, output.stat().st_mtime_ns)
        state.unlink()
        self.assertNotEqual(0, self.run_cli(*args).returncode)
        self.assertEqual(before, output.read_bytes())

    def test_explicit_missing_sources_preserves_previous_board(self):
        state, sources, output = self.cli_files()
        args = ("--state", state, "--sources", sources, "--output", output)
        self.assertEqual(0, self.run_cli(*args).returncode)
        before = output.read_bytes()
        sources.unlink()
        result = self.run_cli(*args)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("看板：输入不可用\n", result.stderr)
        self.assertEqual(before, output.read_bytes())

    def test_default_missing_sources_still_lists_unmapped_active_task(self):
        state, _, output = self.cli_files()
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(self.module, "DEFAULT_SOURCES", str(self.root / "absent.json")), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = self.module.main(["--state", str(state), "--output", str(output), "--print"])
        self.assertEqual(0, code)
        task = json.loads(stdout.getvalue())["tasks"][0]
        self.assertEqual(0, task["progress"])
        self.assertTrue(task["note"].startswith("[进度未量化] "))
        self.assertEqual("", stderr.getvalue())

    def test_cli_help_has_only_manual_safe_options(self):
        result = self.run_cli("--help")
        self.assertEqual(0, result.returncode)
        for option in ("--state", "--sources", "--output", "--print", "--push", "--token-file"):
            self.assertIn(option, result.stdout)
        result = self.run_cli("--token", "SECRET_MUST_NOT_ECHO")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("SECRET_MUST_NOT_ECHO", result.stdout + result.stderr)

    def test_output_cannot_overwrite_keeper_mapping_or_progress_inputs(self):
        state, sources, _ = self.cli_files()
        for target in (state, sources, self.root / "progress.json"):
            with self.subTest(target=target):
                before = target.read_bytes()
                result = self.run_cli("--state", state, "--sources", sources, "--output", target)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("看板：输入不可用\n", result.stderr)
                self.assertEqual(before, target.read_bytes())

    def test_failed_atomic_replace_keeps_previous_and_removes_temporary(self):
        state, sources, output = self.cli_files()
        args = ["--state", str(state), "--sources", str(sources), "--output", str(output)]
        self.assertEqual(0, self.run_cli(*args).returncode)
        before = output.read_bytes()
        (self.root / "progress.json").write_text(json.dumps({"progress": 38}), encoding="utf-8")
        with patch.object(self.module.os, "replace", side_effect=OSError("PRIVATE_SENTINEL")), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertNotEqual(0, self.module.main(args))
        self.assertEqual(before, output.read_bytes())
        self.assertEqual("看板：写入失败\n", errors.getvalue())
        self.assertEqual([], list(self.root.glob(".kanban-*.tmp")))


@contextlib.contextmanager
def mock_mcp(mode="text"):
    calls, stored = [], {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append({"path": self.path, "auth": self.headers.get("Authorization"), **request})
            params = request["params"]
            name, arguments = params["name"], params["arguments"]
            if mode == "error":
                reply = {"jsonrpc": "2.0", "id": request["id"], "error": {"message": "PRIVATE_SENTINEL"}}
            elif mode == "isError":
                reply = {"jsonrpc": "2.0", "id": request["id"], "result": {"isError": True, "content": [{"type": "text", "text": "PRIVATE_SENTINEL"}]}}
            elif name == "fs_write":
                stored["board"] = json.loads(arguments["content"])
                reply = {"jsonrpc": "2.0", "id": request["id"], "result": {"isError": False, "content": [{"type": "text", "text": "ok"}]}}
            else:
                board = stored["board"] if mode != "mismatch" else {"updated_at": "2026-01-01T00:00:00Z", "tasks": []}
                result = board if mode == "dict" else {"isError": False, "content": [{"type": "text", "text": json.dumps(board)}]}
                reply = {"jsonrpc": "2.0", "id": request["id"], "result": result}
            body = json.dumps(reply).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.02), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/mcp", calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        if thread.is_alive():
            raise AssertionError("mock MCP did not stop")


class McpTests(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.board = {"updated_at": "2026-11-15T00:00:00Z", "tasks": [{"name": "本地测试", "line": "test", "status": "running", "progress": 34, "note": ""}]}

    def test_real_local_http_writes_then_exactly_reads_json_value(self):
        for mode in ("text", "dict"):
            with self.subTest(mode=mode), mock_mcp(mode) as (endpoint, calls):
                self.module.push_board(self.board, "test-only-token", endpoint=endpoint)
                self.assertEqual(["fs_write", "fs_read"], [call["params"]["name"] for call in calls])
                self.assertEqual(["widgets/task-kanban/看板.json"] * 2, [call["params"]["arguments"]["path"] for call in calls])
                self.assertEqual(["Bearer test-only-token"] * 2, [call["auth"] for call in calls])
                self.assertEqual(["/mcp", "/mcp"], [call["path"] for call in calls])
                self.assertEqual(self.board, json.loads(calls[0]["params"]["arguments"]["content"]))

    def test_rpc_iserror_and_readback_mismatch_fail_without_retry(self):
        for mode, count in (("error", 1), ("isError", 1), ("mismatch", 2)):
            with self.subTest(mode=mode), mock_mcp(mode) as (endpoint, calls):
                with self.assertRaises(ValueError):
                    self.module.push_board(self.board, "test-only-token", endpoint=endpoint)
                self.assertEqual(count, len(calls))

    def test_remote_endpoints_are_rejected_before_network(self):
        for endpoint in ("http://example.com/mcp", "http://127.0.0.1:8765/mcp?token=leak", "http://u:p@127.0.0.1:8765/mcp", "https://127.0.0.1/mcp"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                self.module.push_board(self.board, "test-only-token", endpoint=endpoint)

    def test_cli_reads_token_only_during_manual_push_and_reports_safe_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            state, sources, output, token = (root / name for name in ("state.json", "sources.json", "board.json", "token.txt"))
            state.write_text('{"seen": {}}', encoding="utf-8")
            sources.write_text("[]", encoding="utf-8")
            token.write_text("file-test-token\n", encoding="utf-8")
            args = ["--state", str(state), "--sources", str(sources), "--output", str(output)]
            with patch.object(self.module, "push_board") as push, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.module.main(args + ["--token-file", str(root / "missing-token")]))
                push.assert_not_called()
                self.assertEqual(0, self.module.main(args + ["--push", "--token-file", str(token)]))
                self.assertEqual("file-test-token", push.call_args.args[1])
                self.assertNotIn("endpoint", push.call_args.kwargs)
            with patch.dict(os.environ, {"LITE_WIDGET_MCP_TOKEN": "env-test-token"}), patch.object(self.module, "push_board", side_effect=ValueError("PRIVATE_SENTINEL env-test-token")), contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertNotEqual(0, self.module.main(args + ["--push"]))
                self.assertEqual("看板：推送失败\n", stderr.getvalue())
                self.assertEqual("", stdout.getvalue())
                self.assertNotIn("env-test-token", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
