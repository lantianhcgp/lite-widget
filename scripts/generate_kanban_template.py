#!/usr/bin/env python3
"""Compile explicit Kanban slots into the existing JSON widget spec (no runtime loop)."""
import argparse
import json
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/templates/task-kanban"
COLORS = {"running": "#52C99B", "blocked": "#E8BB53", "waiting": "#8993A5", "incident": "#EF767E"}


def solid(color):
    return {"type": "solid", "color": color}


def text(identifier, field=None, content=None, size=12, color="#EDF0F5", width: str | int = "fill", height=16, **extra):
    item = {"type": "text", "id": identifier, "width": width, "height": height,
            "size": size, "color": color, "maxLines": 1, **extra}
    item.update({"bind": {"field": field}} if field else {"text": content or ""})
    return item


def frame(identifier, children, direction="vertical", width: str | int = "fill", height: str | int = "hug", **extra):
    return {"type": "frame", "id": identifier, "direction": direction,
            "width": width, "height": height, "children": children, **extra}


def row(index, compact=False):
    prefix = f"parcel.{index}."
    dots = [frame(f"dot-{index}-{status}", [], "absolute", 6, 6,
                  style={"radius": 3, "background": solid(color)},
                  visibleIf={"field": prefix + "status", "op": "eq", "value": status})
            for status, color in COLORS.items()]
    dotbox = frame(f"status-{index}", dots, "vertical", 6, 15, justify="center")
    title = frame(f"title-{index}", [dotbox,
        text(f"name-{index}", field=prefix + "name", size=11 if compact else 12, height=15, weight=600),
        text(f"percent-{index}", field=prefix + "percent", size=10 if compact else 11,
             width=31, height=15, align="right", color="#BFC7D7")],
        "horizontal", height=15, gap=5, align="center")
    bar = {"type": "progress", "id": f"bar-{index}", "width": "fill", "height": 3,
           "bind": {"field": prefix + "progress"}, "min": 0, "max": 100,
           "thickness": 2, "roundCap": False,
           "track": solid("#282E3A"), "bar": solid("#AFA3FF")}
    subtitle = text(f"line-{index}", field=prefix + ("line" if compact else "detail"),
                    size=9 if compact else 9, height=10, color="#99A4B7")
    return frame(f"task-{index}", [title, bar, subtitle], height=30, gap=1,
                 visibleIf={"field": prefix + "name", "op": "exists"})


def root(size):
    compact = size.startswith("2")
    limit = {"2x2": 2, "4x2": 4, "2x4": 8, "4x4": 8}[size]
    pad = 10 if compact else (12 if size == "4x2" else 16)
    title = text("board-title", content="任务看板" if compact else "任务进度", size=14 if compact else 16,
                 height=21, weight=600)
    header = frame("header", [title] if compact else [title, text("summary", field="parcel.summary", width=60,
                   size=11, height=21, align="right", color="#99A4B7")],
                   "horizontal", height=21, align="center", gap=4)
    if size == "4x2":
        pairs = [frame(f"pair-{i}", [row(i), row(i + 1)], "horizontal", height=30, gap=12) for i in (0, 2)]
        slots = frame("slots", pairs, gap=6)
    else:
        slots = frame("slots", [row(i, compact) for i in range(limit)], gap=2)
    slots["visibleIf"] = {"field": "parcel.count", "op": "gt", "value": 0}
    empty = frame("empty", [text("empty-title", content="暂无任务", size=13, height=20),
                     text("empty-hint", content="先生成并推送看板数据", size=10, height=16, color="#99A4B7")],
                  height=64, justify="center", gap=3,
                  visibleIf={"field": "parcel.count", "op": "missing"})
    empty_zero = json.loads(json.dumps(empty, ensure_ascii=False))
    empty_zero["id"] = "empty-zero"
    empty_zero["visibleIf"] = {"field": "parcel.count", "op": "eq", "value": 0}
    for child in empty_zero["children"]:
        child["id"] += "-zero"
    footer = text("updated", field="parcel.updated_ms", size=9, height=13, color="#99A4B7")
    footer["bind"]["format"] = {"date": "HH:mm", "prefix": "数据更新 "}
    more = text("more", field="parcel.more2" if limit == 2 else "parcel.more4", size=9, height=13, color="#AFA3FF",
                visibleIf={"field": "parcel.count", "op": "gt", "value": limit})
    body = [header, slots, empty, empty_zero, {"type": "spacer", "size": "fill"}, more, footer]
    return frame("board", body, height="fill", gap=2, padding=[pad, pad, pad, pad],
                 style={"radius": 16 if compact else 32, "background": solid("#12161F")})


def generate():
    sizes = ("2x2", "4x2", "2x4", "4x4")
    spec = {"version": 1, "canvas": {"width": 360, "height": 180, "fit": "auto"},
            "root": root("4x2"), "variants": {s: {"root": root(s)} for s in sizes},
            "data": {"source": "kanban-local", "vars": {}}}
    manifest = {"id": "task-kanban", "name": "任务进度看板", "version": "1.0.0", "size": "4x4",
                "minAppVersion": "0.3.9", "schemaVersion": 1,
                "description": "8 槽本地任务看板；4x4/2x4 展示 8 项，4x2 展示 4 项，2x2 展示 2 项。无新增定时刷新。"}
    return {"manifest.json": manifest, "widget.json": spec}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if committed spec differs from generator")
    args = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    for name, data in generate().items():
        content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        path = BASE / name
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                raise SystemExit(f"template drift: {name}")
        else:
            path.write_text(content, encoding="utf-8")
    print("kanban template: 4 safe sizes, 8 explicit slots, assets match generator")


if __name__ == "__main__":
    main()
