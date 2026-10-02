"""Generate the native multipart pipe models; run with Python 3 from any directory.

Glass panels form an open octagonal bore. Metal is built as individual rim bars,
never as a solid end face. Straight runs use one continuous tube per block;
junctions use short arms and a small inspection chamber.
"""

import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/homelink_storage"
MODELS = ROOT / "models/block"
SIDES = ("north", "east", "south", "west", "up", "down")
CONNECTED = "pipe|controller|container"
TEXTURES = {
    "particle": "homelink_storage:block/pipe_graphite",
    "glass": "homelink_storage:block/pipe_glass",
    "frame": "homelink_storage:block/pipe_graphite",
    "copper": "homelink_storage:block/pipe_copper",
    "recess": "homelink_storage:block/pipe_graphite",
}


def texture(name, pixel):
    """Small deterministic RGBA material swatches, with no external image library."""
    size = 32
    scanlines = b"".join(b"\0" + bytes(channel for x in range(size) for channel in pixel(x, y))
                         for y in range(size))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(scanlines, 9)) + chunk(b"IEND", b"")
    directory = ROOT / "textures/block"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / (name + ".png")).write_bytes(data)


def glass_pixel(x, y):
    if x in (0, 31):
        return (207, 229, 232, 72)
    # Quiet blue-grey glass and one soft diagonal reflection; most of the bore stays clear.
    if 11 <= y - x // 3 <= 13:
        return (225, 240, 241, 34)
    return (159, 192, 202, 38)


texture("pipe_glass", glass_pixel)
texture("pipe_graphite", lambda x, y: tuple(c + ((x * 7 + y * 11) % 5 - 2) for c in (48, 54, 60)) + (255,))
texture("pipe_copper", lambda x, y: tuple(c + ((x * 3 + y * 5) % 5 - 2) for c in (166, 108, 72)) + (255,))


def element(start, end, material, faces=None, rotation=None):
    def uv(face):
        if material == "glass":
            return [0, 0, 16, 16]
        # Keep the material texel density consistent on bars, plates and bolts.
        u, v = {"north": (0, 1), "south": (0, 1), "east": (2, 1),
                "west": (2, 1), "up": (0, 2), "down": (0, 2)}[face]
        width, height = end[u] - start[u], end[v] - start[v]
        return [round(8 - width / 2, 5), round(8 - height / 2, 5),
                round(8 + width / 2, 5), round(8 + height / 2, 5)]
    result = {
        "from": [round(v, 5) for v in start],
        "to": [round(v, 5) for v in end],
        "faces": {face: {"uv": uv(face), "texture": "#" + material} for face in (faces or SIDES)},
    }
    if rotation:
        origin, angle = rotation
        result["rotation"] = {"origin": origin, "axis": "z", "angle": angle, "rescale": False}
    return result


def glass(length):
    half_wall = 0.0125
    panels = [
        element([6.5, 11.5 - half_wall, 0], [9.5, 11.5 + half_wall, length], "glass", ["up", "down"]),
        element([6.5, 4.5 - half_wall, 0], [9.5, 4.5 + half_wall, length], "glass", ["up", "down"]),
        element([4.5 - half_wall, 6.5, 0], [4.5 + half_wall, 9.5, length], "glass", ["east", "west"]),
        element([11.5 - half_wall, 6.5, 0], [11.5 + half_wall, 9.5, length], "glass", ["east", "west"]),
    ]
    half = math.sqrt(2)
    for x, y, angle in [(10.5, 10.5, -45), (5.5, 10.5, 45), (10.5, 5.5, 45), (5.5, 5.5, -45)]:
        panels.append(element([x - half, y - half_wall, 0], [x + half, y + half_wall, length], "glass",
                              ["up", "down"], ([x, y, 8], angle)))
    return panels


def rim(start, end, radius=3.5, accent=True, thickness=0.35, material=None):
    """Eight thin bars around the bore, with copper only on two opposed facets."""
    a = radius - 2
    half = math.sqrt(2) + thickness / 2
    bars = [
        element([8 - a - thickness / 2, 8 + radius - thickness / 2, start],
                [8 + a + thickness / 2, 8 + radius + thickness / 2, end], "frame"),
        element([8 - a - thickness / 2, 8 - radius - thickness / 2, start],
                [8 + a + thickness / 2, 8 - radius + thickness / 2, end], "frame"),
        element([8 - radius - thickness / 2, 8 - a - thickness / 2, start],
                [8 - radius + thickness / 2, 8 + a + thickness / 2, end], "frame"),
        element([8 + radius - thickness / 2, 8 - a - thickness / 2, start],
                [8 + radius + thickness / 2, 8 + a + thickness / 2, end], "frame"),
    ]
    offset = radius - 1
    for index, (x, y, angle) in enumerate([
        (8 + offset, 8 + offset, -45), (8 - offset, 8 + offset, 45),
        (8 + offset, 8 - offset, 45), (8 - offset, 8 - offset, -45),
    ]):
        selected = "copper" if accent and index in (0, 3) else "frame"
        bars.append(element([x - half, y - thickness / 2, start],
                            [x + half, y + thickness / 2, end], selected, rotation=([x, y, 8], angle)))
    if material:
        for bar in bars:
            for face in bar["faces"].values():
                face["texture"] = "#" + material
    return bars


def rails(length):
    # Two fine longitudinal spines keep the collars visually attached without a cage.
    return [element([x - 0.11, y - 0.11, 0], [x + 0.11, y + 0.11, length], "frame",
                    rotation=([x, y, 8], -45)) for x, y in [(5.5, 5.5), (10.5, 10.5)]]


def save(name, elements, layer="solid"):
    model = {"parent": "minecraft:block/block", "render_type": "minecraft:" + layer,
             "ambientocclusion": False, "textures": TEXTURES, "elements": elements}
    head = json.dumps({key: value for key, value in model.items() if key != "elements"}, indent=2)
    text = head[:-2] + ',\n  "elements": [\n'
    text += ",\n".join("    " + json.dumps(part) for part in elements)
    (MODELS / (name + ".json")).write_text(text + "\n  ]\n}\n", encoding="utf-8")


def save_pair(name, metal, panels, item=False):
    # NeoForge's built-in composite model retains both chunk and item render layers.
    prefix = name + ("_item" if item else "")
    save(prefix + "_metal", metal)
    save(prefix + "_glass", panels, "translucent")
    model = {"parent": "minecraft:block/block", "loader": "neoforge:composite",
             "ambientocclusion": False, "textures": {"particle": TEXTURES["particle"]},
             "children": {"metal": {"parent": "homelink_storage:block/" + prefix + "_metal", "render_type": "minecraft:solid"},
                          "glass": {"parent": "homelink_storage:block/" + prefix + "_glass", "render_type": "minecraft:translucent"}},
             "item_render_order": ["metal", "glass"]}
    if item:
        model["display"] = {
            "gui": {"rotation": [25, 135, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
            "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
        }
    path = ROOT / "models/item/storage_pipe.json" if item else MODELS / (name + ".json")
    path.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")


# Junction edge brackets form a continuous housing, instead of loose corner cubes.
edges = []
for axis in range(3):
    transverse = [i for i in range(3) if i != axis]
    for a in (4.5, 11.5):
        for b in (4.5, 11.5):
            start, end = [0, 0, 0], [0, 0, 0]
            start[axis], end[axis] = 4.5, 11.5
            for index, value in zip(transverse, (a, b)):
                start[index], end[index] = value - 0.14, value + 0.14
            edges.append(element(start, end, "frame"))
save("storage_pipe_core", edges)
save_pair("storage_pipe_arm", rails(4.5), glass(4.5))
save("storage_pipe_joint", rim(0, 0.55))
pads = [
    element([7.1, 11.32, 0.5], [8.9, 11.5, 1.5], "recess"),
    element([7.1, 4.5, 0.5], [8.9, 4.68, 1.5], "recess"),
    element([11.32, 7.1, 0.5], [11.5, 8.9, 1.5], "recess"),
    element([4.5, 7.1, 0.5], [4.68, 8.9, 1.5], "recess"),
]
save_pair("storage_pipe_straight", rails(16) + pads, glass(16))
save_pair("storage_pipe_cap", [
    element([7.1, 7.1, 4.5], [8.9, 8.9, 4.7], "recess"),
], [element([4.5, 4.5, 4.4875], [11.5, 11.5, 4.5125], "glass", ["north", "south"])])
# A true socket at a container: mounting plate, open bore, copper locking sleeve
# and four raised bolt heads. Its rear reaches recessed vanilla chest walls.
connector = [
    element([2.75, 2.75, -1.025], [13.25, 4.25, 0.85], "frame"),
    element([2.75, 11.75, -1.025], [13.25, 13.25, 0.85], "frame"),
    element([2.75, 4.25, -1.025], [4.25, 11.75, 0.85], "frame"),
    element([11.75, 4.25, -1.025], [13.25, 11.75, 0.85], "frame"),
]
for x in (3.45, 12.55):
    for y in (3.45, 12.55):
        connector.append(element([x - 0.45, y - 0.45, 0.85], [x + 0.45, y + 0.45, 1.35], "copper"))
connector += rim(0.85, 2.2, radius=3.9, accent=False, thickness=0.9)
connector += rim(2.2, 2.85, radius=3.9, thickness=0.9, material="copper")
connector += rim(2.85, 3.25, radius=3.9, accent=False, thickness=0.9)
save("storage_pipe_connector", connector)
save_pair("storage_pipe", rails(16) + rim(0, 1) + rim(15, 16), glass(16), item=True)


def applied(name, side="north"):
    result = {"model": "homelink_storage:block/storage_pipe_" + name}
    turns = {"east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270},
             "up": {"x": 270}, "down": {"x": 90}}
    return result | turns.get(side, {})


opposites = [("north", "south"), ("east", "west"), ("up", "down")]
# Everything except the three straight-through cases: isolated, an unmatched end,
# or connections on more than one axis. Conditions also accept container/Controller ends.
junction = {"OR": [dict.fromkeys(SIDES, "none")]}
for first, second in opposites:
    junction["OR"].extend([{first: CONNECTED, second: "none"}, {first: "none", second: CONNECTED}])
for i, pair in enumerate(opposites):
    for other in opposites[i + 1:]:
        junction["OR"].append({"AND": [{"OR": [{side: CONNECTED} for side in pair]},
                                         {"OR": [{side: CONNECTED} for side in other]}]})

parts = []
for first, second in opposites:
    condition = dict.fromkeys(SIDES, "none") | {first: CONNECTED, second: CONNECTED}
    parts.append({"when": condition, "apply": applied("straight", first)})
parts.append({"when": junction, "apply": applied("core")})
for side in SIDES:
    parts.append({"when": {"AND": [junction, {side: "none"}]}, "apply": applied("cap", side)})
    parts.append({"when": {"AND": [junction, {side: CONNECTED}]}, "apply": applied("arm", side)})
    # Pipe joints and container sockets never overlap on the same side.
    parts.append({"when": {side: "pipe"}, "apply": applied("joint", side)})
    parts.append({"when": {side: "controller|container"}, "apply": applied("connector", side)})
(ROOT / "blockstates/storage_pipe.json").write_text(
    '{\n  "multipart": [\n' + ",\n".join("    " + json.dumps(part) for part in parts) + "\n  ]\n}\n",
    encoding="utf-8")
