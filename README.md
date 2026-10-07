# Not My TODO

A JetBrains IDE plugin that keeps a TODO list attached to your code and lets an
AI agent take items off your hands.

- **Capture:** select code, press Ctrl+Alt+Shift+Z (or right-click, Add TODO), and
  type. `!p1`..`!p3` sets the priority and `#word` adds a tag. With nothing
  selected, or after removing the anchor, it saves a plain note.
- **List:** the Not My TODO tool window at the bottom. Search with words, an id like `T-12`, `#tag`
  and `!p1`. Double-click an item to jump to its code.
- **Code comments:** the Code comments entry lists the TODO comments the IDE
  finds in the project's sources. Track as an Item turns one into an item
  attached to that line.
- **Whose move:** an item tagged `#needs-decision` waits for you; everything
  else that is open is an agent's to fix. The Agent can fix and Needs my
  decision entries list each kind, the person icon in the detail pane toggles
  the tag, and Fix All leaves tagged items out. An agent that finds a decision
  is needed is told to add the tag and say what has to be decided in a
  comment. A balloon tells you when an agent comments on an item that waits
  for you or that is in progress, and Show opens the item; several in one
  write share one balloon. Fix on a
  tagged item asks the agent for the options and its recommendation, and it
  does the work only after you have chosen.
- **Links:** an item can be blocked by other items, be a duplicate of one, and
  be a part of one. A blocked item shows a padlock and is left out of Agent
  can fix until every item it waits for is closed. Parts stand under their
  parent in the list, which shows how many of them are closed; parts go one
  level deep. The detail pane shows the links, an id there goes to that item,
  and Edit changes them.
- **Where from, and how fixed:** an item can say which commit or run it came
  from, and once fixed, in which commit and what was done. Agents are asked
  for both when they report an item fixed. The detail pane shows them above
  the details, and Edit changes them.
- **Anchors:** items follow their code as you edit, switch branches, or let an
  agent change files. If the code can no longer be found the item shows
  "Anchor lost"; select the code and press Re-attach to selection. An item can
  have several anchors: Add an Anchor in the detail pane attaches the selection,
  or the whole file when nothing is selected, and the arrows beside the place step through them.
  An anchor on a whole file follows the file when it is renamed or moved.
- **Fix with Claude:** sends an item, with its code and 20 lines either side, to
  Claude Code in a terminal tab or to the clipboard.
- **Settings:** Settings, Tools, Not My TODO. The default fix target, the command
  that starts Claude, the lines of context, a short prompt that sends the agent
  to `todo_get`, extra instructions for every prompt, the Fix All limit, the
  default priority, the editor marks, the Code comments entry, whether Fix
  skips its dialog, and where this project keeps its items file.
- **Running session (research preview):** with "Send to a running Claude Code
  session" switched on in the settings, Fix can put its prompt straight into a
  Claude Code session that is already running in the project, in any terminal.
  Press Register on the settings page once, then start Claude with
  `claude --dangerously-load-development-channels server:notmytodo`. Claude Code
  asks for confirmation each time it starts with that flag, and it does not
  confirm delivery: if a prompt did not arrive, the balloon offers to open it
  in a new tab. A session started without the flag is shown on the settings
  page as running without the channel flag and is not sent to.

## Data

Items are stored in `.todos/items.json` in the project root, unless the settings
name another file for the project. Commit it or add `.todos/` to `.gitignore`;
the plugin does not decide for you. IDs are `T-1`, `T-2`, ... and are never
reused.

## For agents

With the IDE's MCP server enabled, these tools are available:

| Tool | Purpose |
|---|---|
| `todo_list` | List items, filtered by status, tags to have or not have, priority, file or directory, text, `blocked` and `parent`; `compact` leaves out details |
| `todo_get` | One item with its current line range and code |
| `todo_create` | Add an item, optionally attached to `path` and lines, linked with `blockedBy`, `duplicateOf` and `parent`, and with the `source` commit or run it came from |
| `todo_update` | Change fields; set `status` to `fixed` when the work is done; pass `startLine` to re-attach it to moved code; `blockedBy`, `duplicateOf` and `parent` set its links; `resolution` and `fixedIn` say what was done and in which commit |
| `todo_comment` | Add a note to an item's comments without touching its other fields |
| `todo_batch` | Create or change several items in one call, saved as one write: all of them or, when one is wrong, none; `comment` on an entry adds a comment to that item |

Without MCP, read `.todos/items.json` directly. Line numbers there are as of
the plugin's last write.

## Build

```
./gradlew test          # run the tests
./gradlew runIde        # start a sandbox CLion with the plugin
./gradlew buildPlugin   # build/distributions/not-my-todo-<version>.zip
```

Gradle downloads the CLion version named in `build.gradle.kts` and, if the
machine has none, a JDK 25. Gradle itself needs a JDK 17 or newer to start.
Install the zip with Settings, Plugins, the gear menu,
Install Plugin from Disk.

`./gradlew signPlugin` writes a signed zip for the Marketplace next to it. It
reads the certificate chain, the private key and the key's password from the
environment variables `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
`PRIVATE_KEY_PASSWORD`; none of them belong in the repo.

## License

Copyright 2026 konove. Licensed under the [Apache License, Version 2.0](LICENSE).
