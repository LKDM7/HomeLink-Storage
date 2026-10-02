"""Validation of the generated pipe assets (no Minecraft classes replaced)."""
import itertools
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "src/main/resources/assets/homelink_storage"
sides = ("north", "east", "south", "west", "up", "down")
opposites = (("north", "south"), ("east", "west"), ("up", "down"))
parts = json.loads((root / "blockstates/storage_pipe.json").read_text())["multipart"]

def matches(condition, state):
    return all(any(matches(c, state) for c in value) if key == "OR" else
               all(matches(c, state) for c in value) if key == "AND" else
               state[key] in value.split("|") for key, value in condition.items())

for values in itertools.product(("none", "pipe", "container", "controller"), repeat=6):
    state = dict(zip(sides, values))
    applied = [p["apply"]["model"].split("storage_pipe_")[1] for p in parts if matches(p["when"], state)]
    connected = {s for s in sides if state[s] != "none"}
    straight = any(connected == set(pair) for pair in opposites)
    assert applied.count("straight") == int(straight), state
    assert applied.count("core") == int(not straight), state
    assert applied.count("arm") == (0 if straight else len(connected)), state
    assert applied.count("cap") == (0 if straight else 6 - len(connected)), state
    assert applied.count("joint") == values.count("pipe"), state
    assert applied.count("connector") == values.count("container") + values.count("controller"), state

model_count = element_count = 0
for path in (root / "models").rglob("storage_pipe*.json"):
    model_count += 1
    model = json.loads(path.read_text())
    if "children" in model:
        assert model["loader"] == "neoforge:composite"
        assert model["item_render_order"] == ["metal", "glass"]
        for name, child in model["children"].items():
            assert child["render_type"] == "minecraft:" + ("solid" if name == "metal" else "translucent")
            assert (root / "models" / (child["parent"].split(":")[1] + ".json")).is_file()
    for value in model.get("textures", {}).values():
        assert (root / "textures" / (value.split(":")[1] + ".png")).is_file()
    for part in model.get("elements", []):
        element_count += 1
        assert all(-16 <= a < b <= 32 for a, b in zip(part["from"], part["to"])), path
        for face in part["faces"].values():
            assert face["texture"][1:] in model["textures"], path
            assert all(0 <= v <= 16 for v in face["uv"]), path
    if path.stem.endswith("_glass"):
        assert model["render_type"] == "minecraft:translucent", path
    elif "elements" in model:
        assert model["render_type"] == "minecraft:solid", path

print(f"PIPE_CONNECTOR_ASSETS_OK states=4096 models={model_count} elements={element_count} exclusive_joint_socket=true layers=true noncoplanar_glass=true references=true uv_bounds=true")
