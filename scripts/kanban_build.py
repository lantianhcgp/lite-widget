#!/usr/bin/env python3
"""Read-only, standard-library task aggregation. Never executes keeper commands."""

import argparse
import http.client
import json
import math
import os
import re
import shlex
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

UNKNOWN_PREFIX = "[进度未量化] "
STATUSES = {"running", "blocked", "waiting", "incident"}
PRIORITY = {"incident": 0, "blocked": 1, "running": 2, "waiting": 3}
MAX_TASKS = 8
MAX_ELAPSED = 2147483647
STATUS_ALIASES = {
    "active": "running", "进行": "running", "进行中": "running", "运行中": "running",
    "受阻": "blocked", "阻塞": "blocked", "等待": "waiting", "事故": "incident",
    "done": "waiting", "completed": "waiting", "已完成": "waiting", "完成": "waiting",
}
COMPLETED_STATUSES = {"done", "completed", "已完成", "完成"}
MD_FIELDS = {"任务名": "name", "任务线": "line", "任务状态": "status", "进度百分比": "progress",
             "备注": "note", "当前环节": "stage"}
DEFAULT_STATE = "~/.hermes/scripts/task_keeper_state.json"
DEFAULT_SOURCES = "~/.hermes/scripts/kanban_sources.json"
DEFAULT_OUTPUT = "/storage/emulated/0/Documents/看板.json"
MCP_ENDPOINT = "http://127.0.0.1:8765/mcp"
MCP_PATH = "widgets/task-kanban/看板.json"
MAX_INPUT_BYTES = 2 * 1024 * 1024
MAX_BOARD_BYTES = 256 * 1024
TIMESTAMP_PATTERN = r"^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,6})?(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00))$(?![\s\S])"


class InvalidInput(ValueError):
    """Invalid input; callers must not expose source contents in diagnostics."""


def _limited(value, limit):
    # Match the Android contract's UTF-16 string length, including emoji.
    return value.encode("utf-16-le", errors="replace")[:limit * 2].decode("utf-16-le", errors="ignore")


def _task(raw, source, tag):
    if not isinstance(raw, dict):
        return None
    fallback_name = tag.removeprefix("简报-") or tag
    name = raw.get("name", source.get("name", fallback_name))
    line = raw.get("line", source.get("line", tag))
    note = raw.get("note", "")
    if not all(isinstance(value, str) for value in (name, line, note)):
        return None
    if not name.strip() or not line.strip():
        return None
    status = raw.get("status", "running")
    if not isinstance(status, str):
        return None
    completed = status.lower().strip() in COMPLETED_STATUSES
    status = STATUS_ALIASES.get(status.lower().strip(), status.lower().strip())
    if status not in STATUSES:
        return None
    if "progress" in raw:
        progress = raw["progress"]
        if type(progress) not in (int, float) or not 0 <= progress <= 100:
            return None
        if not math.isfinite(progress):
            return None
    elif completed:
        progress = 100
    else:
        progress = 0
        if not note.startswith(UNKNOWN_PREFIX.rstrip()):
            note = UNKNOWN_PREFIX + note
    if note.startswith(UNKNOWN_PREFIX.rstrip()) and progress != 0:
        return None
    task = {"name": _limited(name.strip(), 64), "line": _limited(line.strip(), 64),
            "status": status, "progress": progress, "note": _limited(note, 240)}
    if "elapsed" in raw:
        elapsed = raw["elapsed"]
        if type(elapsed) not in (int, float) or not 0 <= elapsed <= MAX_ELAPSED:
            return None
        if isinstance(elapsed, float) and (not math.isfinite(elapsed) or not elapsed.is_integer()):
            return None
        task["elapsed"] = int(elapsed)
    return task


def _markdown_task(text):
    """Use only explicit fields in the current section, never ratios or prose."""
    lines = text.splitlines()
    current_title = r"当前(?:任务|状态|进度|证据|环节|备注|任务线|任务名)?"
    headings = []
    fence = None
    for original in lines:
        line = original.strip()
        if line.startswith(">"):
            continue
        fence_match = re.match(r"^(`{3,}|~{3,})", line)
        if fence_match:
            marker = fence_match.group(1)[0]
            fence = None if fence == marker else marker
            continue
        if fence is None:
            heading = re.match(r"^(#{1,6})\s+(.+)", line)
            if heading:
                headings.append(re.sub(r"\s+#+\s*$", "", heading.group(2)).strip())
    explicit_current = any(re.fullmatch(current_title, title) for title in headings)
    started = not headings
    current_level = None
    fields = {}
    fence = None
    for original in lines:
        line = original.strip()
        fence_match = re.match(r"^(`{3,}|~{3,})", line)
        if fence_match:
            marker = fence_match.group(1)[0]
            fence = None if fence == marker else marker
            continue
        if fence is not None or line.startswith(">"):
            continue
        heading = re.match(r"^(#{1,6})\s+(.+)", line)
        if heading:
            level = len(heading.group(1))
            title = re.sub(r"\s+#+\s*$", "", heading.group(2)).strip()
            is_current = re.fullmatch(current_title, title) is not None
            if current_level is not None:
                # Explicit current sections allow only current children; an
                # implicit project section ends at the very next heading.
                if not explicit_current or level <= current_level or not is_current:
                    break
            elif is_current:
                current_level = level
                started = True
            elif any(word in title for word in ("历史", "旧", "以下")):
                break
            elif not explicit_current:
                current_level = level
                started = True
            continue
        if not started:
            continue
        if re.fullmatch(r"(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,}", line):
            break
        line = line.replace("**", "").replace("__", "").replace("`", "")
        line = re.sub(r"^(?:[-+*]|\d+[.)])\s+", "", line)
        if line.startswith("|"):
            cells = [cell.strip() for cell in line.strip("|").split("|")]
            if len(cells) != 2:
                continue
            key, value = cells
        else:
            pair = re.fullmatch(r"(任务名|任务线|任务状态|进度百分比|备注|当前环节)\s*[:：]\s*(.*)", line)
            if not pair:
                continue
            key, value = pair.groups()
        field = MD_FIELDS.get(key)
        if field is not None:
            # A first explicit current field wins; later repeated fields cannot revert it.
            fields.setdefault(field, value.strip())
    if not fields:
        return None
    if "progress" in fields:
        value = fields["progress"]
        if not re.fullmatch(r"[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)\s*[%％]?", value):
            return None
        fields["progress"] = float(value.rstrip("%％").strip())
    notes = [fields[key] for key in ("note", "stage") if fields.get(key)]
    fields["note"] = "；".join(notes)
    fields.pop("stage", None)
    return fields


def _source_tasks(source, tag):
    try:
        path = Path(source["path"]).expanduser()
        text = _read_text(path)
        if path.suffix.lower() in {".md", ".markdown"}:
            members = [_markdown_task(text)]
        else:
            raw = json.loads(text)
            members = raw.get("tasks", [raw]) if isinstance(raw, dict) else []
        if not isinstance(members, list):
            return []
        tasks = []
        for member in members:
            # Elapsed belongs to keeper evidence, never to editable progress sources.
            raw_task = {key: value for key, value in member.items() if key != "elapsed"} if isinstance(member, dict) else member
            task = _task(raw_task, source, tag)
            if task is not None:
                tasks.append(task)
        return tasks
    except (OSError, ValueError, TypeError, KeyError):
        return []


def _command_identity(command, tag, depth=0):
    """Parse quoted argv or shell -c payloads, without opening query files."""
    if not isinstance(command, str) or depth > 4:
        return ("tag", tag)
    try:
        tokens = shlex.split(command)
    except ValueError:
        return ("tag", tag)
    for index, token in enumerate(tokens):
        if token == "chat":
            for position in range(index + 1, len(tokens)):
                option = tokens[position]
                for flag in ("--query-file", "--resume"):
                    if option.startswith(flag + "="):
                        value = option[len(flag) + 1:]
                    elif option == flag and position + 1 < len(tokens):
                        value = tokens[position + 1]
                    else:
                        continue
                    if value and not value.startswith("-"):
                        return (flag, value)
    for index, token in enumerate(tokens[:-1]):
        if token in {"-c", "-lc", "-cl"}:
            nested = _command_identity(tokens[index + 1], tag, depth + 1)
            if nested[0] != "tag":
                return nested
    return ("tag", tag)


def _keeper_entries(state):
    if not isinstance(state, dict) or not isinstance(state.get("seen"), dict):
        raise InvalidInput("keeper unavailable")
    entries = []
    for entry in state["seen"].values():
        if not isinstance(entry, dict):
            continue
        tag = entry.get("tag")
        gone = entry.get("gone", 0)
        if not isinstance(tag, str) or not tag.strip() or type(gone) not in (int, float):
            continue
        if gone < 0 or (isinstance(gone, float) and not math.isfinite(gone)):
            continue
        entries.append(entry)
    if state["seen"] and not entries:
        raise InvalidInput("keeper unavailable")
    return entries


def _keeper_elapsed(entries):
    """Complete minutes across valid recorded pairs, never the wall clock."""
    pairs = []
    for entry in entries:
        start, last_seen = entry.get("start"), entry.get("last_seen")
        if any(type(value) not in (int, float) or (isinstance(value, float) and not math.isfinite(value))
               for value in (start, last_seen)):
            continue
        if start >= 0 and last_seen >= start:
            pairs.append((start, last_seen))
    if not pairs:
        return None
    start = min(pair[0] for pair in pairs)
    last_seen = max(pair[1] for pair in pairs)
    try:
        minutes = (last_seen - start) // 60
    except OverflowError:
        return None
    if not 0 <= minutes <= MAX_ELAPSED:
        return None
    return int(minutes)


def build_tasks(state, sources):
    """Return only explicit task fields; a bad progress line does not poison peers.

    Source tags match exactly. Inactive keeper lines require include_inactive=true.
    All wrappers of one query/resume identity share the first mapped source.
    """
    entries = _keeper_entries(state)
    if not isinstance(sources, list):
        raise InvalidInput("sources unavailable")
    mappings = {}
    for source in sources:
        if isinstance(source, dict) and isinstance(source.get("tag"), str):
            mappings.setdefault(source["tag"], source)
    groups = {}
    for entry in entries:
        if entry.get("gone", 0) == 0:
            identity = _command_identity(entry.get("cmd"), entry["tag"])
            groups.setdefault(identity, []).append(entry)
    tasks = []
    used_tags = set()
    for group in groups.values():
        mapped = next((entry for entry in group if entry["tag"] in mappings), group[0])
        tag = mapped["tag"]
        used_tags.update(entry["tag"] for entry in group)
        if tag in mappings:
            group_tasks = _source_tasks(mappings[tag], tag)
        else:
            task = _task({}, {}, tag)
            group_tasks = [task] if task is not None else []
        elapsed = _keeper_elapsed(group)
        if elapsed is not None:
            for task in group_tasks:
                task["elapsed"] = elapsed
        tasks.extend(group_tasks)
    for tag, source in mappings.items():
        if tag not in used_tags and source.get("include_inactive") is True:
            inactive_tasks = _source_tasks(source, tag)
            elapsed = _keeper_elapsed(entry for entry in entries if entry["tag"] == tag and entry.get("gone", 0) > 0)
            if elapsed is not None:
                for task in inactive_tasks:
                    task["elapsed"] = elapsed
            tasks.extend(inactive_tasks)
    return sorted(tasks, key=lambda task: PRIORITY[task["status"]])[:MAX_TASKS]


def _read_text(path, limit=MAX_INPUT_BYTES):
    path = Path(path).expanduser()
    if not path.is_file():
        raise InvalidInput("input unavailable")
    with path.open("rb") as stream:
        content = stream.read(limit + 1)
    if len(content) > limit:
        raise InvalidInput("input unavailable")
    return content.decode("utf-8-sig")


def _valid_board(board):
    if not isinstance(board, dict) or set(board) != {"updated_at", "tasks"}:
        return False
    if not isinstance(board["updated_at"], str) or re.fullmatch(TIMESTAMP_PATTERN, board["updated_at"]) is None:
        return False
    try:
        updated = datetime.fromisoformat(board["updated_at"].replace("Z", "+00:00"))
    except (ValueError, TypeError, AttributeError):
        return False
    if updated.utcoffset() is None or not isinstance(board["tasks"], list) or len(board["tasks"]) > MAX_TASKS:
        return False
    for task in board["tasks"]:
        required = {"name", "line", "status", "progress", "note"}
        if not isinstance(task, dict) or not required <= set(task) <= required | {"elapsed"}:
            return False
        if _task(task, {}, "unused") != task:
            return False
    return True


def _load_previous(path):
    try:
        board = json.loads(_read_text(path, MAX_BOARD_BYTES))
        return board if _valid_board(board) else None
    except (OSError, ValueError):
        return None


def _board_content(board):
    return json.dumps(board, ensure_ascii=False, allow_nan=False, indent=2) + "\n"


def _write_board(path, tasks):
    previous = _load_previous(path)
    if previous is not None and previous["tasks"] == tasks:
        return previous, False
    board = {"updated_at": datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"), "tasks": tasks}
    if not _valid_board(board):
        raise InvalidInput("board unavailable")
    content = _board_content(board)
    if len(content.encode("utf-8")) > MAX_BOARD_BYTES:
        raise InvalidInput("board unavailable")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, prefix=".kanban-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return board, True


def _rpc_result(reply, request_id):
    if not isinstance(reply, dict) or reply.get("jsonrpc") != "2.0" or reply.get("id") != request_id or "error" in reply:
        raise InvalidInput("push failed")
    result = reply.get("result")
    if not isinstance(result, (dict, str)):
        raise InvalidInput("push failed")
    if isinstance(result, dict) and ("error" in result or ("isError" in result and result["isError"] is not False)):
        raise InvalidInput("push failed")
    return result


def _readback_board(result):
    if isinstance(result, dict) and {"updated_at", "tasks"} <= set(result):
        return result
    if isinstance(result, dict):
        if "structuredContent" in result:
            return result["structuredContent"]
        content = result.get("content")
        if isinstance(content, str):
            result = content
        elif isinstance(content, dict):
            return content
        elif isinstance(content, list) and len(content) == 1 and isinstance(content[0], dict) and content[0].get("type") == "text":
            result = content[0].get("text")
        else:
            raise InvalidInput("push failed")
    if isinstance(result, str):
        return json.loads(result)
    raise InvalidInput("push failed")


def push_board(board, token, *, endpoint=MCP_ENDPOINT):
    """One manual write plus one exact read; endpoint override is loopback-test only.

    HTTPConnection bypasses environment proxies and does not follow redirects.
    Never calls refresh/reload, retries, or any task/keeper command.
    """
    try:
        url = urlsplit(endpoint)
        port = url.port
    except ValueError:
        raise InvalidInput("push failed") from None
    if (url.scheme != "http" or url.hostname != "127.0.0.1" or port is None
            or not 1 <= port <= 65535 or url.username is not None or url.password is not None
            or url.path != "/mcp" or url.query or url.fragment):
        raise InvalidInput("push failed")
    if not isinstance(token, str) or not token.strip() or any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise InvalidInput("push failed")
    if not _valid_board(board):
        raise InvalidInput("push failed")
    header_name = "Author" + "ization"
    header_value = "Bear" + "er " + token

    def call(name, arguments, request_id):
        payload = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                              "params": {"name": name, "arguments": arguments}}, ensure_ascii=False, allow_nan=False).encode("utf-8")
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            connection.request("POST", "/mcp", body=payload, headers={"Content-Type": "application/json", header_name: header_value})
            response = connection.getresponse()
            if response.status != 200:
                raise InvalidInput("push failed")
            content = response.read(MAX_BOARD_BYTES + 1)
            if len(content) > MAX_BOARD_BYTES:
                raise InvalidInput("push failed")
            return _rpc_result(json.loads(content.decode("utf-8")), request_id)
        except (OSError, ValueError, http.client.HTTPException):
            raise InvalidInput("push failed") from None
        finally:
            connection.close()

    call("fs_write", {"path": MCP_PATH, "content": _board_content(board)}, 1)
    returned = _readback_board(call("fs_read", {"path": MCP_PATH}, 2))
    if not _valid_board(returned) or returned != board:
        raise InvalidInput("push failed")


def _token(token_file):
    value = _read_text(token_file, 4096).strip() if token_file is not None else os.environ.get("LITE_WIDGET_MCP_TOKEN", "").strip()
    if not value:
        raise InvalidInput("push failed")
    return value


class _SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default message echoes arbitrary argv, which may contain secrets.
        raise InvalidInput("arguments unavailable")


def main(argv=None):
    parser = _SafeParser(description="零 LLM 看板：只读 keeper/显式进度源；手动推送且精确回读。", allow_abbrev=False)
    parser.add_argument("--state", default=DEFAULT_STATE, help="keeper JSON（默认 ~/.hermes/scripts/task_keeper_state.json）")
    parser.add_argument("--sources", default=None, help="私密映射 JSON；默认 ~/.hermes/scripts/kanban_sources.json，默认文件缺失按 []")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="本地契约 JSON（默认 Documents/看板.json）")
    parser.add_argument("--print", action="store_true", dest="print_board", help="仅输出完整契约 JSON")
    parser.add_argument("--push", action="store_true", help="一次 fs_write + fs_read；固定 127.0.0.1:8765/mcp，无刷新/重试")
    parser.add_argument("--token-file", help="推送令牌文件；否则读取 LITE_WIDGET_MCP_TOKEN，禁止 argv 明文令牌")
    try:
        args = parser.parse_args(argv)
        state_path = Path(args.state).expanduser()
        sources_path = Path(args.sources if args.sources is not None else DEFAULT_SOURCES).expanduser()
        output = Path(args.output).expanduser()
        state = json.loads(_read_text(state_path))
        if args.sources is None and not sources_path.exists():
            sources = []
        else:
            sources = json.loads(_read_text(sources_path))
        tasks = build_tasks(state, sources)
        protected_paths = [state_path, sources_path]
        protected_paths.extend(Path(source["path"]).expanduser() for source in sources
                               if isinstance(source, dict) and isinstance(source.get("path"), str))
        if args.token_file is not None:
            protected_paths.append(Path(args.token_file).expanduser())
        if output.resolve() in {path.resolve() for path in protected_paths}:
            raise InvalidInput("output overlaps input")
    except (OSError, ValueError, TypeError):
        print("看板：输入不可用", file=sys.stderr)
        return 1
    try:
        board, changed = _write_board(output, tasks)
    except (OSError, ValueError, TypeError):
        print("看板：写入失败", file=sys.stderr)
        return 1
    if args.push:
        try:
            push_board(board, _token(args.token_file))
        except (OSError, ValueError, TypeError, http.client.HTTPException):
            print("看板：推送失败", file=sys.stderr)
            return 1
    if args.print_board:
        print(_board_content(board), end="")
    else:
        display_path = str(output).replace("\r", " ").replace("\n", " ")
        print(f"tasks={len(tasks)} changed={'yes' if changed else 'no'} path={display_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
