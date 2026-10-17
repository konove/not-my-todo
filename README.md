# Not My TODO

A JetBrains IDE plugin that keeps a TODO list attached to your code and lets an
AI agent take items off your hands.

- **Capture:** select code, press Ctrl+Alt+Shift+Z (or right-click, Add TODO), and
  type. `!p1`..`!p3` sets the priority and `#word` adds a tag. With nothing
  selected, or after removing the anchor, it saves a plain note. A plain note can go into this project, Inbox or another project; `@inbox` in the text puts it into Inbox.
- **List:** the Not My TODO tool window at the bottom. Search with words, an id like `T-12`, `#tag`
  and `!p1`. Double-click an item to jump to its code. The selector at the left of the filter bar shows the items of this project, of Inbox, or of all projects; in All projects each row names its project, and another project's item is `key/T-3`, double-clicked to open its checkout and fixed with Claude Code started there.
- **Effort:** an item can say how much work it is: `S`, `M` or `L`, set by the
  agent that files it or by you in the detail pane. Within a priority the
  list puts less effort first and items without one last; search `!s`, `!m`
  or `!l` to see one size.
- **Code comments:** the Code comments entry lists the TODO comments the IDE
  finds in the project's sources. Track as an Item turns one into an item
  attached to that line.
- **Whose move:** an item tagged `#needs-decision` waits for you; everything
  else that is open is an agent's to fix. The Agent can fix and Needs my
  decision entries list each kind, the person icon in the detail pane toggles
  the tag, and Fix All leaves tagged items out. An agent that finds a decision
  is needed is told to say what has to be decided in the item's `toDecide`,
  which adds the tag. A balloon tells you when an agent does that, or comments
  on an item that waits for you or that is in progress, and Show opens the
  item; several in one write share one balloon. Fix on a
  tagged item asks the agent for the options and its recommendation, and it
  does the work only after you have chosen. What it asked, the options and
  your answers are kept in the item's `decisions`, and the detail pane shows
  them above the details.
- **Links:** an item can be blocked by other items, be a duplicate of one, and
  be a part of one. A blocked item shows a padlock and is left out of Agent
  can fix until every item it waits for is closed. Parts stand under their
  parent in the list, which shows how many of them are closed; parts go one
  level deep. A parent with open parts has the highest priority among them,
  whatever was set on it, so a part never stands under a parent of less
  weight; its own priority comes back when the last of them is closed. The detail pane shows the links, an id there goes to that item,
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
  Claude Code in a terminal tab or to the clipboard. A sent item is marked in
  progress; a prompt on the clipboard may reach no agent, so it leaves the
  status alone and asks the agent to mark the item itself.
- **Drag to a session:** drag a row of the list onto a terminal where Claude
  Code is running, in the IDE or outside it, and the item's prompt lands in
  Claude's input for you to send. Copying a row gives the same text. The
  status is not changed; the prompt asks the agent to mark the item in
  progress.
- **Settings:** Settings, Tools, Not My TODO. The default fix target, the command
  that starts Claude, the lines of context, a short prompt that sends the agent
  to `todo_get`, extra instructions for every prompt, the Fix All limit, the
  default priority, the editor marks, the Code comments entry, whether Fix
  skips its dialog, and this project's key in the store, with its remotes and checkouts, and the store's folder.
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

Items are kept outside the repositories, in one store per machine:
`~/.local/share/not-my-todo-store` on Linux (or under `$XDG_DATA_HOME`),
`~/Library/Application Support/not-my-todo-store` on macOS,
`%LOCALAPPDATA%\not-my-todo-store` on Windows, or the folder that
`NOT_MY_TODO_STORE` names. Each repository is a project there, found by its
checkout or by its remote, and each item is a JSON file of its own:
`projects/<key>/items/T-12.json`. IDs are `T-1`, `T-2`, ... within a project
and are never reused; from another project an item is `key/T-12`. Items that
belong to no repository go into the project `inbox`.

The first time the IDE opens a project with 0.3.0, the items of its
`.todos/items.json` are moved into the store with their ids, and a balloon
offers to delete the file. The file is never written again.

## For agents

The `todo_*` tools come from the `notmytodo-store` server of [not-my-todo-store](../not-my-todo-store), which Claude Code starts for every session, in any folder and whether or not the IDE runs. Install it from that repository with `./gradlew installServer`; the plugin says so when Claude Code lacks it. Every tool takes `project`: a key, `inbox`, or a folder; `todo_list` with `project: "*"` lists every project.

| Tool | Purpose |
|---|---|
| `todo_list` | List items, filtered by status, tags to have or not have, priority, effort, file or directory, text (`text` for every word, `anyText` for any of several words or phrases), `blocked` and `parent`; `compact` leaves out details and says of a parent how many of its parts are open, fixed and closed, for example `2 open, 2 fixed, 1 closed` |
| `todo_get` | One item with its current line range and code; an anchor that is lost has the stored text in place of the code |
| `todo_create` | Add an item, optionally attached to `path` and lines, linked with `blockedBy`, `duplicateOf` and `parent`, with the `source` commit or run it came from (`HEAD` and other names are stored as the commits they name), with an `effort` of `s`, `m` or `l`, and with `toDecide` when you have to decide something first |
| `todo_update` | Change fields; set `status` to `fixed` when the work is done, a parent's when none of its parts is open; pass `startLine` to re-attach it to moved code; `blockedBy`, `duplicateOf` and `parent` set its links; `resolution` says what was done, and the commit that names the item id in its message is shown as the one that fixed it, unless `fixedIn` names another; `toDecide` says what you have to decide and tags the item `needs-decision` |
| `todo_comment` | Add a note to an item's comments without touching its other fields |
| `todo_decided` | Record what you were asked about an item and what you answered: each question, the options offered and the answer; the `needs-decision` tag is taken away |
| `todo_batch` | Create or change several items in one call, saved as one write: all of them or, when one is wrong, none; `comment` on an entry adds a comment to that item, `path`, `startLine` and `places` re-attach it, and `ref` on a new item lets the entries after it name it as `$ref` |
| `todo_delete` | Delete an item the agent made, when no other item is its part, is blocked by it or is a duplicate of it |
| `todo_projects` | The projects: key, remotes, checkouts on this machine, and how many items are open, need a decision and are fixed |
| `todo_move` | Move an item, with its parts when asked, to another project; every link to it follows |

Without the server, an item can be read in its file in the store. Line numbers there are as of the last time that item was saved: lines that only moved are not written, so find the code by the anchor's `text`.

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
