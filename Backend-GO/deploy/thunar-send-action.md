# Thunar "Send to phone via AndroControl" custom action

Adds a right-click entry in Thunar that sends the selected file(s)/folder(s) to the
paired phone via the running AndroControl agent (`androcontrol-clip`). Folders and
multi-selections are zipped automatically. Requires an active phone connection.

## Option A — Thunar GUI
Edit → Configure custom actions… → ➕, then:

- **Name:** `Send to phone (AndroControl)`
- **Command:** `androcontrol-clip send %F`
- **Appearance Conditions → File Pattern:** `*`
- Tick **Directories** and all file types.

## Option B — drop-in config
Append this `<action>` inside the `<actions>` element of
`~/.config/Thunar/uca.xml` (create the file from the GUI first so the wrapper exists),
then restart Thunar (`thunar -q`):

```xml
<action>
	<icon>phone</icon>
	<name>Send to phone (AndroControl)</name>
	<unique-id>androcontrol-send-1</unique-id>
	<command>androcontrol-clip send %F</command>
	<description>Send to the paired phone via AndroControl</description>
	<patterns>*</patterns>
	<directories/>
	<audio-files/>
	<image-files/>
	<other-files/>
	<text-files/>
	<video-files/>
</action>
```

`%F` passes the absolute paths of all selected items. The agent (started with
`ANDROCONTROL_FILE_TRANSFER=1`) must be running; if the phone isn't connected the command
prints a clear error.
