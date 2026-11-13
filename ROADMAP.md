# Roadmap

## Release policy

Published releases: **v1.0.0**, **v1.5.0**, **v1.5.1**, **v1.6.0**, **v1.7.0**, and **v1.8.5**.

v1.1 through v1.4 are internal milestones, not releases. Each milestone must compile and install on
a real device for self-testing, but it gets no tag, no GitHub Release, and no download-point update.
The reasons:

- The app has no built-in update check, so publishing never reaches existing users on its own — the
  distribution value of a release is zero.
- A single maintainer does the work, and cutting a release is a fixed cost (build, rename, checksum,
  notes, syncing the download point). Semi-finished work should not pay that cost.
- Nothing between v1.1 and v1.4 is a live defect that users need urgently.

Releases are counted in closed feature sets, not in commits or in version-number slots. During
development `versionName` carries the target version directly (e.g. `1.6.0-dev`); `versionCode` is
bumped only when a release actually ships.

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
4. **Every milestone is installable and verifiable on its own.** Internal builds are not published,
   but the repository is never left in a state that fails to compile or ships a half-built screen.

## Versions

| Version | Scope | Status | Notes |
|---------|-------|--------|-------|
| v1.0.0 | Single-screen editor: write, publish, draft mode, rollback | Released | The only published version |
| v1.1 | Post list as the entry screen: title, date, draft badge, tags; new post; open existing post; refresh | Done | List → editor navigation |
| v1.2 | Editing an existing post: load remote source, overwrite in place, remote rename when the slug changes, multi-file rollback on build failure | Done | Split/merge rollback into an explicit operation list |
| v1.3 | Category and tag discovery: scan existing front matter, offer selectable chips, allow new values | Done | Taxonomy cached in SharedPreferences, rewritten on every list refresh; chips are shortcuts only |
| v1.4 | Housekeeping: delete post, draft → published, search, offline list cache | Done | Delete and draft toggle both rebuild and roll back on failure; search filters title/slug/tags/categories in memory; last successful listing is cached for offline viewing, and writes stay blocked while offline |
| v1.5 | Preview: structural read-only render, toggle in the top bar, cursor position preserved, "open in browser" escape hatch | Released | Renders headings, lists, quotes, fenced code, tables, images and links; the toggle keeps scroll offset and cursor; the browser button shows only when the post has a live URL |
| v1.6.0 | Media library: attachment upload into `static/upload` with per-type subdirectories, insertion at the cursor, cover written to front matter `image`, remote images loaded in preview | Released | Video and audio need goldmark `unsafe` on the site; attachments are read fully into memory, capped at 64 MB |
| v1.5.1 | Interaction cleanup: icon top bar, on-demand search, new-post FAB, pull to refresh; rows show date + status; collapsible metadata and a single bottom action; card-based settings, segmented auth, custom fingerprint dialog; title opens About with GitHub Releases update check and a Toast notice | Released | Publish results and long-press actions still use the existing dialogs; a bottom sheet and dark mode come later |
| v1.7.0 | Attachments upload at publish time; the editor keeps title, body and attachments while slug, categories, tags and cover move to a separate publish screen; new attachment library | Released | Picked files land in the app's private inbox and the server is untouched before publish; categories and tags are picked from the site's existing vocabulary with nothing preselected; attachments are named by content hash so the same file is never uploaded twice; orphan files are only listed, and removal goes to `.blogpad-trash/` outside the site root |
| v1.8.5 | Cover cropping rebuilt (preset ratios, fit and free modes, safe margins, pending cover restored after process death, full-screen preview); post trash that moves deleted posts outside the site root with restore-as-draft and restore-and-build; an "other files" attachment kind; one shared helper for site links | Released | Deletion is no longer final: the post and the attachments it references move to `.blogpad-trash/` together. The long-press menu keeps only publish and delete, since flipping to draft leaves both the file and the live page in place. A blank site URL falls back to the default site with the `www.` prefix dropped, so a site never gets two addresses |

## Deletion and taking posts offline (v1.8.5)

Deleting a post no longer makes it vanish. The file is moved with the attachments it references, as a
batch, into `.blogpad-trash/posts/<batch>-<slug>/` outside the site root, which leaves the long-press
menu with just publish and delete. Flipping back to draft edits a single front matter line while the
file and the live page stay where they are, so it is not offered as a way to take something offline.

The trash has two exits: restore as draft moves the files back and resets `draft` to `true`, while
restore and build keeps the original state and rebuilds, which suits a published post deleted by
mistake. Purging happens only in the trash, and only after the list has been shown.

## Split rules for editing and publishing (v1.7.0)

The post-list top bar keeps only search and settings, creation lives in the bottom-right FAB, and
refresh happens only through pull-to-refresh.

**The editor is only about writing**: back, title, body, **+ Attach**, and one **Done** in the top
right. No slug, categories, tags, cover or draft toggle. A picked file is copied into the app's
private inbox and the body gets a single placeholder at the cursor; the server is not touched until
publish. Swapping a picture or a video ten times leaves nothing behind on the remote side.

**The publish screen owns the metadata**: tapping Done opens a separate screen. The slug is offered
as a candidate generated from the title, collapsed, editable when expanded; categories and tags are
picked from what the site already uses with **+ New** at the end of each row, and **nothing is
preselected** — a new post is not a copy of the previous one; one cover image goes only into front
matter `image`. **Save as draft** and **Publish** sit at the bottom.

A draft is not validated for categories or tags; all of it may be empty.

**Why upload is deferred to publish**: uploading on pick turns "pick, then change your mind" into
remote leftovers, and a clever naming scheme only cleans up after the damage. Deferring it means the
server only ever sees the final result. The price is that the inbox has to be visible and
clearable, and placeholders have to be recognised when the referenced file is gone (a stale
placeholder must block publish instead of producing a dead link).

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
