# Roadmap

## Guiding principles

1. **The remote directory is the only source of truth.** The post list is a projection of
   `content/post` on the server. There is no local database of posts and no separate "sync"
   step — a published post appears in the list because it exists on the server.
2. **Markdown is the only content format.** Editing always operates on the `.md` source. The app
   never turns a rendered page into the editable object, so nothing is lost in a HTML → Markdown
   round trip.
3. **A preview checks structure, not styling.** Themes belong to the site, not to the app. Preview
   answers "is the content well-formed" (headings, lists, code fences, links, images) and does not
   chase pixel parity with a third-party theme.
4. **Every version is installable and verifiable on its own.** No step leaves a half-built screen
   in the release.

## Versions

| Version | Scope | Notes |
|---------|-------|-------|
| v1.0.0 | Single-screen editor: write, publish, draft mode, rollback | Released |
| v1.1 | Post list as the entry screen: title, date, draft badge, tags; new post; open existing post; refresh | List → editor navigation |
| v1.2 | Editing an existing post: load remote source, overwrite in place, remote rename when the slug changes, multi-file rollback on build failure | Split/merge rollback into an explicit operation list |
| v1.3 | Category and tag discovery: scan existing front matter, offer selectable chips, allow new values | Cached locally, refreshed on pull |
| v1.4 | Housekeeping: delete post, draft → published, search, offline list cache | —— |
| v1.5 | Preview: structural read-only render, toggle in the top bar, cursor position preserved, "open in browser" escape hatch | See principles 3 |

## Interaction rules for editing and preview (v1.5)

Default mode depends on the state of the post, not on a global preference:

| Opening | Default mode |
|---------|--------------|
| New post | Edit |
| Published post | Preview |
| Draft (`draft: true`) | Edit |

The top bar carries a single toggle whose label flips with the current mode: `Preview` while
editing, `Edit` while previewing. Switching to preview renders the unsaved buffer, not the remote
file, and keeps the scroll and cursor position across the switch. Leaving the editor with unsaved
changes asks before discarding. The default may be flipped back to "edit first" while the renderer
is still maturing; the toggle stays available either way.

## Not planned

- Rich-text (WYSIWYG) editing of the rendered page — breaks the Markdown-as-truth rule.
- Built-in theme replication for arbitrary sites — unbounded work, breaks whenever a theme changes.
- Account systems, cloud sync, or a hosted backend — the app talks to the user's own server only.
