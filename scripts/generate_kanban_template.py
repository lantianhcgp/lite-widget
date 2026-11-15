#!/usr/bin/env python3
"""Compile explicit Kanban slots into existing static Canvas specs (no runtime loop)."""
import argparse
import json
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/templates/task-kanban"
COLORS = {"running": "#5ABDF7", "blocked": "#E8BB53", "waiting": "#8993A5", "incident": "#EF767E"}
LABELS = {"running": "运行中", "blocked": "受阻", "waiting": "等待", "incident": "事故"}
CHIP_BACKGROUNDS = {"running": "#17364D", "blocked": "#423620", "waiting": "#29303C", "incident": "#45272F"}
PANEL = {"type": "linear", "angle": 95, "colors": ["#202D44", "#151D2C"]}
BOARD = {"type": "linear", "angle": 115, "colors": ["#17233A", "#0C111D"]}


def solid(color):
    return {"type": "solid", "color": color}


def text(identifier, field=None, content=None, size: float = 12, color="#EDF0F5",
         width: str | int = "fill", height=16, **extra):
    item = {"type": "text", "id": identifier, "width": width, "height": height,
            "size": size, "color": color, "maxLines": 1, **extra}
    item.update({"bind": {"field": field}} if field else {"text": content or ""})
    return item


def frame(identifier, children, direction="vertical", width: str | int = "fill",
          height: str | int = "hug", **extra):
    return {"type": "frame", "id": identifier, "direction": direction,
            "width": width, "height": height, "children": children, **extra}


def condition(index, field, value):
    return {"field": f"parcel.{index}.{field}", "op": "eq", "value": value}


def status_pills(index, tight=False):
    width, height, font = (34, 13, 8.5) if tight else (48, 16, 9)
    inset = [1, 1, 1, 1] if tight else [1, 3, 1, 3]
    chips = [frame(f"chip-{index}-{status}", [
        text(f"chip-label-{index}-{status}", content=LABELS[status], size=font,
             width=width - 6, height=height - 2, align="center", weight=600, color=color)],
        width=width, height=height, padding=inset,
        style={"radius": 8, "background": solid(CHIP_BACKGROUNDS[status])},
        visibleIf=condition(index, "status", status)) for status, color in COLORS.items()]
    return frame(f"status-{index}", chips, width=width, height=height)


def elapsed_text(index, tight=False):
    return text(f"elapsed-{index}", field=f"parcel.{index}.elapsedLabel", size=7.5 if tight else 9,
                width=32 if tight else "fill", height=10 if tight else 16,
                align="right", color="#B7C8DF")


def progress_lane(index, tight=False, include_elapsed=False):
    prefix = f"parcel.{index}."
    known = {"type": "progress", "id": f"bar-{index}", "width": "fill", "height": 4,
             "bind": {"field": prefix + "progress"}, "min": 0, "max": 100,
             "thickness": 3, "roundCap": False, "track": solid("#282E3A"), "bar": solid("#AFA3FF"),
             "visibleIf": condition(index, "progressUnknown", False)}
    segments = [frame(f"segment-{index}-{n}", [], width="fill", height=4,
                      style={"radius": 1, "background": solid("#AFA3FF" if n % 2 == 0 else "#536181")})
                for n in range(10)]
    unknown = frame(f"unknown-bar-{index}", segments, "horizontal", height=4, gap=1 if tight else 2,
                    visibleIf=condition(index, "progressUnknown", True))
    children = [frame(f"progress-track-{index}", [known, unknown], height=4),
                text(f"progress-{index}", field=prefix + "progressLabel", size=7.5 if tight else 8.5,
                     width=25 if tight else 31, height=10 if tight else 12,
                     align="right", color="#C8BBFF")]
    if include_elapsed:
        children.append(elapsed_text(index, tight=True))
    return frame(f"progress-row-{index}", children, "horizontal", height=10 if tight else 12,
                 align="center", gap=3 if tight else 4)


def metadata(index, split=True):
    prefix = f"parcel.{index}."
    line = text(f"line-{index}", field=prefix + "line", size=8.5 if split else 8,
                width="fill" if split else 35, height=11 if split else 10, color="#A3B0C6")
    note = text(f"note-{index}", field=prefix + "displayNote", size=8 if split else 7.5,
                height=10, color="#8190AB")
    return [line, note] if split else [frame(f"metadata-{index}", [line, note], "horizontal", height=10, gap=3)]


def card_shell(index, children, height, pad=7):
    rails = [frame(f"rail-{index}-{status}", [], width=4, height=height,
                   style={"background": solid(color)}, visibleIf=condition(index, "status", status))
             for status, color in COLORS.items()]
    return frame(f"task-{index}", [frame(f"rails-{index}", rails, width=4, height=height),
                 frame(f"content-{index}", children, height=height, padding=[1, pad, 1, pad])],
                 "horizontal", height=height, style={"radius": 10, "background": PANEL},
                 visibleIf={"field": f"parcel.{index}.name", "op": "exists"})


def dashboard_card(index):
    status = frame(f"status-row-{index}", [status_pills(index), elapsed_text(index)],
                   "horizontal", height=16, gap=3)
    return card_shell(index, [text(f"name-{index}", field=f"parcel.{index}.name", size=11, height=15, weight=600),
                             status, progress_lane(index), *metadata(index)], 66)


def compact_card(index, tall_list=False):
    name = text(f"name-{index}", field=f"parcel.{index}.name", size=10, height=13, weight=600)
    if tall_list:
        title = frame(f"title-{index}", [name, status_pills(index, tight=True)], "horizontal", height=13, gap=3)
        return card_shell(index, [title, progress_lane(index, tight=True, include_elapsed=True),
                                 *metadata(index, split=False)], 36, pad=6)
    status = frame(f"status-row-{index}", [status_pills(index), elapsed_text(index)],
                   "horizontal", height=16, gap=3)
    return card_shell(index, [name, status, progress_lane(index, tight=True),
                             *metadata(index, split=False)], 51, pad=6)


def header(size):
    narrow = size.startswith("2")
    large = size == "4x4"
    title_height, detail_height = (21, 15) if large else (14, 12)
    updated = text("updated", field="parcel.updated_ms", size=8.5 if large else 7.5,
                   height=detail_height, color="#98A8C0", align="right")
    updated["bind"]["format"] = {"date": "MM-DD HH:mm"}
    title = text("board-title", content="任务看板", size=15 if large else 11,
                 height=title_height, weight=600, width=48 if narrow else "fill")
    summary = text("summary", field="parcel.summary", size=9 if large else 8, width=43,
                   height=title_height, align="right", color="#AFA3FF")
    if size == "4x2":
        title["width"] = 48
        more = text("more", field="parcel.more4", size=7.5, height=title_height, align="right", color="#AFA3FF",
                    visibleIf={"field": "parcel.count", "op": "gt", "value": 4})
        title_children = [title, more, summary]
    else:
        title_children = [title, updated] if narrow else [title, summary]
    counts = [text(f"count-{status}", field=f"parcel.{status}Count", size=7.5 if narrow else 8.5,
                   width="hug", height=detail_height, color=color) for status, color in COLORS.items()]
    for node, label in zip(counts, ("运行 ", "受阻 ", "等待 ", "事故 ")):
        node["bind"]["format"] = {"decimals": 0, "prefix": label}
    return frame("header", [frame("header-title", title_children, "horizontal", height=title_height, gap=3),
                 frame("header-detail", counts if narrow else [*counts, updated],
                       "horizontal", height=detail_height, gap=3)], height=title_height + detail_height)


def empty_panels():
    empty = frame("empty", [text("empty-title", content="暂无任务", size=13, height=22),
                            text("empty-hint", content="手动推送后显示最新状态", size=9, height=16, color="#99A4B7")],
                  height=60, justify="center", visibleIf={"field": "parcel.count", "op": "missing"})
    zero = json.loads(json.dumps(empty, ensure_ascii=False))
    zero["id"] = "empty-zero"
    zero["visibleIf"] = {"field": "parcel.count", "op": "eq", "value": 0}
    for child in zero["children"]:
        child["id"] += "-zero"
    return empty, zero


def root(size):
    if size in ("4x4", "4x2"):
        count, height, gap = (8, 66, 5) if size == "4x4" else (4, 51, 4)
        make_card = dashboard_card if size == "4x4" else compact_card
        pairs = [frame(f"pair-{i}", [make_card(i), make_card(i + 1)], "horizontal", height=height, gap=6,
                       visibleIf={"field": "parcel.count", "op": "gt", "value": i})
                 for i in range(0, count, 2)]
        slots = frame("slots", pairs, height="hug", gap=gap)
    else:
        count = 8 if size == "2x4" else 2
        slots = frame("slots", [compact_card(i, tall_list=size == "2x4") for i in range(count)],
                      height="hug", gap=3 if size == "2x4" else 4)
    slots["visibleIf"] = {"field": "parcel.count", "op": "gt", "value": 0}
    children = [header(size), frame("header-separator", [], height=1, style={"background": solid("#34415A")}),
                slots, *empty_panels()]
    if size == "2x2":
        children.append(text("more", field="parcel.more2", size=7.5, height=10, color="#AFA3FF",
                             visibleIf={"field": "parcel.count", "op": "gt", "value": 2}))
    pad = 12 if size == "4x4" else 8
    return frame("board", children, height="fill", gap=4 if size == "4x4" else 3,
                 padding=[pad, pad, pad, pad], style={"radius": 16 if size.startswith("2") else 32,
                 "background": BOARD})


def generate():
    sizes = ("2x2", "4x2", "2x4", "4x4")
    spec = {"version": 1, "canvas": {"width": 360, "height": 180, "fit": "auto"},
            "root": root("4x2"), "variants": {size: {"root": root(size)} for size in sizes},
            "data": {"source": "kanban-local", "vars": {}}}
    manifest = {"id": "task-kanban", "name": "任务进度看板", "version": "1.1.0", "size": "4x4",
                "minAppVersion": "0.3.10", "schemaVersion": 1,
                "description": "中文状态胶囊、静态进度与可选时长；4x4/2x4 八项、4x2 四项、2x2 两项。仅手动推送。"}
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
    print("kanban template: 4 safe sizes, textual states, static progress, assets match generator")


if __name__ == "__main__":
    main()
