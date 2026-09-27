# HomeLink Storage: industrial graphite and copper

Each block has original multipart geometry. Models use Minecraft textures as material swatches; no vanilla texture image is copied into the mod.

| Block | Geometry | Elements |
| --- | --- | --- |
| Storage Controller | Shallow wall panel, copper frame, graphite screen, vents and blinking connection indicator | 12 |
| Storage Terminal | Weighted base, twin copper supports, tilted display with separate illuminated lines and keyboard | 15 |
| Storage Terminal (wall/ceiling) | Thin mounting plate, copper bezel, recessed cyan screen, status lines and controls | 13 |
| Storage Link | Compact wall-mounted connector, copper contacts and visible outward status indicator | 12 |
| Storage Repeater | Low instrument base with twin antenna masts, copper collars and illuminated tips | 16 |
| Storage Deposit | Graphite input cabinet, recessed top tray, copper rim, cyan front display and side vents | 22 |
| Storage Overflow Chest | Closed graphite chest on a deepslate plinth, reinforced corners, raised lid with twin copper straps and rear hinges, cyan inset latch and recessed side handles | 28 |
| USB Linking Key (item) | Slim graphite casing, copper side rails, cyan indicator, hollow silver USB-A connector with retention marks and inner contacts, lanyard loop | 26 |

The Overflow Chest shares the network's gray/black concrete, polished deepslate, copper block and cyan concrete palette. Its closed strapped lid and central latch distinguish it from the Deposit's open input tray; its blockstate uses the four horizontal `facing` values. Its geometry fits the existing selection bounds, from [0.5, 0, 0.5] to [15.5, 15, 15.5]. Other network blocks add small sea-lantern/lime accents. The Controller has a synchronized lit model and emits light level 7 during the lit half of its one-second connection blink cycle.

All blockstates expect `facing` with six values: `north`, `east`, `south`, `west`, `up`, `down`. Models are authored facing north. East/south/west rotate Y by 90/180/270 degrees; up/down rotate X by 270/90 degrees. The Link's north face is its contact face, attached toward the selected inventory; its indicator is on the opposite side. Controller, Terminal and Repeater use north as their display/front face.

The resource models do not alter collision or selection shapes. Their Java blocks must define matching shapes if narrow geometry should also have narrow collisions.

Storage Repeater resources require registration of block/item `homelink_storage:storage_repeater`; without it its recipe, loot table and required mining tag reference cannot resolve.
