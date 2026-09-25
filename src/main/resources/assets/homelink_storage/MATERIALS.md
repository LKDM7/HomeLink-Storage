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
| Storage Overflow Chest | Purpur cabinet on a blackstone plinth, copper corner posts and waist band, amethyst front panels with a dark intake slot and an orange overflow stripe, amethyst lid indicator | 13 |
| USB Linking Key (item) | Slim graphite casing, copper side rails, cyan indicator, hollow silver USB-A connector with retention marks and inner contacts, lanyard loop | 26 |

The Overflow Chest deliberately uses its own palette (purpur, polished blackstone, cut copper, amethyst and orange concrete) so it is recognised at a glance; its blockstate uses the four horizontal `facing` values. The other blocks' material palette uses gray/black concrete, polished deepslate, copper, cyan concrete and small sea-lantern/lime accents. The Controller has a synchronized lit model and emits light level 7 during the lit half of its one-second connection blink cycle.

All blockstates expect `facing` with six values: `north`, `east`, `south`, `west`, `up`, `down`. Models are authored facing north. East/south/west rotate Y by 90/180/270 degrees; up/down rotate X by 270/90 degrees. The Link's north face is its contact face, attached toward the selected inventory; its indicator is on the opposite side. Controller, Terminal and Repeater use north as their display/front face.

The resource models do not alter collision or selection shapes. Their Java blocks must define matching shapes if narrow geometry should also have narrow collisions.

Storage Repeater resources require registration of block/item `homelink_storage:storage_repeater`; without it its recipe, loot table and required mining tag reference cannot resolve.
