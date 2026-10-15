# HoloPrint

A Fabric mod for Minecraft **1.21.11** that shows schematics as glowing holographic blueprints, and has a built-in online schematic browser with one-click downloads.

Client-side only. It works in singleplayer and on any server, because nothing is sent to the server: it only draws on your screen and reads your own files. It uses just one tiny optional mixin (mouse wheel during grab mode), so it stays out of other mods' way (it is built to sit alongside Sodium, Sodium Extra and Iris, but hasn't been tested with them yet).

## Building the .jar (GitHub does it for you)

1. Make a new GitHub repo (for example `holoprint`) and upload everything in this folder, including the hidden `.github` folder and the `gradle` folder.
2. Open the repo's **Actions** tab. A build starts by itself on every push (or press **Run workflow**).
3. When it's green, open the run, scroll to **Artifacts**, download **HoloPrint-jar**, unzip it, and put `holoprint-0.2.0.jar` in your `mods` folder next to Fabric API.

If a build fails, open the failed step, copy the red error lines, and paste them back to Claude to fix.

## Controls (all rebindable in Options → Controls → HoloPrint)

| Key | Action |
| --- | --- |
| H | Open the HoloPrint menu |
| J | Show / hide holograms |
| G (hold) | Grab the build: it follows your aim; scroll the mouse wheel to push it closer/further |
| V | Put the block you're aiming at (in the hologram) into your hand, if it's in your hotbar |
| K | Finished-build view: shows the whole build as real blocks (hold, or toggle in Settings) |
| Arrow keys | Move the build forward / back / left / right (relative to where you face) |
| Page Up / Page Down | Move the build up / down |
| R | Rotate 90° |
| Numpad + / − / * | Layer up / layer down / cycle layer mode |
| Numpad 7 / Numpad 9 | Mark corner 1 / corner 2 of an area to save (Create page) |
| (unbound) | Mirror |

In the menu: **Tab** cycles pages, **Shift + click** on the X/Y/Z buttons moves 10 blocks, drag the 3D preview to turn it.

## Pages

- **Library** — your schematics (`.litematic`, `.schem`, `.nbt`) with a spinning **3D preview** (or top-down map), ★ favorites, and your own **collections** (e.g. "Bases", "Farms").
- **Browse** — search online with **thumbnails**, quick categories, sorting, a **Minecraft version filter**, and one-click **Download** straight into `schematics/HoloPrint Downloads`.
- **Builds** — move, rotate, mirror, layers, progress stats, and **share codes**: "Copy share code" gives you a text code; your friend clicks "Paste share code" and gets the same hologram in the same spot (small schematics travel inside the code; online ones download automatically).
- **Create** — mark two corners and **save your own build** as a `.litematic` (also opens in Litematica). Saved to `schematics/HoloPrint Saves`.
- **Materials** — what's left, what you carry, what's in your chests, and a **Grab next** card telling you which chest to visit first. HoloPrint remembers chest contents when you open them; the next chest also gets a glowing outline in the world.
- **Settings** — themes, colours, menu/HUD/hologram options, and building helpers (auto-pick, grab snapping, finished-view hold/toggle, chest outline).
- **About** — credits and your current key bindings.

## Notes

- Thumbnails work for PNG/JPEG/GIF images; sites that use WebP show a placeholder.
- Chest memory only knows what you've seen by opening a chest yourself. It's per world/server.
- Saving a build copies the blocks your game has loaded; chest contents and mobs aren't included.
- Online results come from the MineMev API (`minemev.com`), which also indexes several other schematic sites. Downloads are checked to be real schematic files before saving.
