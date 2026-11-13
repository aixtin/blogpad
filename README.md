# BlogPad

A minimal Android writing pad that publishes Markdown posts straight to a Hugo site over SSH.

Write on the phone, hit publish, and the app uploads the Markdown file to the site's content
directory, runs the site build command on the server, and returns the post URL. No CMS, no
database, no third-party service — just SSH and a Git-based site.

## Features

- **Post list as the entry screen** — the home screen is a projection of `content/post` on the
  server: title, date, draft status, and tags. Pull to refresh, tap a row to edit, expand search on
  demand, and create a post from the fixed bottom-right button.
- **Edit existing posts** — the remote `.md` is loaded back into the form and saved in place; a
  changed slug renames the remote file and removes the old one; front matter keys the app does not
  own are preserved verbatim.
- **Write, then publish** — the editor holds only the title, the body and the attachments. Tapping
  Done opens a separate publish screen for slug, categories, tags and the cover. Categories and
  tags are offered from what the site already uses and nothing is preselected — a new post is not
  a copy of the last one.
- **Markdown with Hugo front matter** — generated automatically (`title`, `date`, `draft`, `slug`,
  `categories`, `tags`, and `image` when a cover is picked), so the file drops straight into a
  Hugo site.
- **Attachments wait for publish** — a picked file is copied into the app's private inbox and the
  body gets only a placeholder at the cursor; the server is untouched until you publish. Then every
  file goes up in one SFTP session, the placeholders are rewritten to root-relative URLs, the `.md`
  is written, and only after a successful build are the local copies dropped. Names are
  `<slug>-<contenthash>.<ext>`: picking the same image twice uploads it once, and a retry after a
  failed publish hits the file already on the server.
- **Attachment library** — a fallback screen that lists everything under `static/upload`, flags
  files no post references any more, and moves unwanted ones to `.blogpad-trash/` outside the
  site root; restore or purge from there. Nothing is deleted behind your back. Opens from the
  attachments entry at the bottom of the settings screen.
- **Post trash** — deleting a post no longer makes it vanish: the file and the attachments it
  references are moved as a batch into `.blogpad-trash/posts/` outside the site root. Restore
  as draft brings the files back with `draft: true`; restore and build keeps the original
  state. The long-press menu keeps only publish and delete — flipping back to draft touches
  one front matter line while the file and the live page stay put, so it is no longer offered
  as a way to take a post offline.
- **Cover image** — the cover goes to its own subdirectory and is written only into the front
  matter `image` key; it never appears in the body.
- **Cover cropping** — picking a cover opens a crop screen with preset ratios, a fit or free
  mode, a draggable frame that can sit flush against the image edges, and a live ratio label.
  The pending cover survives process death and is restored on the publish screen; tapping the
  thumbnail opens a full-screen preview.
- **Local preview of images** — remote `https://` images and downloaded covers are decoded and
  shown in the preview; site-relative paths are reported as "visible after publish" instead of
  failing.
- **SSH transport** — SFTP upload plus remote command execution over a single session.
  Public key (OpenSSH `ed25519` / `rsa`) and password authentication are both supported.
- **Host key pinning** — the SHA-256 fingerprint must be confirmed once and is then stored;
  subsequent connections are rejected if the fingerprint changes.
- **Automatic rollback** — if the remote build command fails, the uploaded file is removed or
  restored to its previous content, so a broken build never leaves a half-published post behind.
- **Draft mode** — writes `draft: true` and skips link generation.
- **Draft auto-save** — a new post is cached locally as you type and restored on next launch; the
  cache is cleared once the post is published.
- **Encrypted configuration** — host, credentials, and private key are serialized to JSON,
  encrypted with AES-256-GCM using a key held in Android Keystore, and stored in
  `SharedPreferences`. Android backup is disabled for the app.

## Requirements

| Item | Requirement |
|------|-------------|
| Android | 8.0 (API 26) or later |
| Server | Any SSH server with SFTP enabled |
| Site | Hugo project on the server, with the build toolchain installed |
| Build | JDK 17, Android SDK 34, Gradle 8.13 |

## Install

Download the APK from the [Releases](../../releases) page and install it. The app ships without
any preset server configuration.

## Configuration

Open **Settings** and fill in two groups of fields.

### Connection

| Field | Default | Notes |
|-------|---------|-------|
| Host | — | Hostname or IP address of the server |
| Port | `22` | SSH port |
| User | — | SSH user name |
| Auth | public key | Switch between key and password |
| Private key | — | Paste, or import from a file. OpenSSH format only; `.ppk` is not supported |
| Passphrase | — | Optional key passphrase |
| Password | — | Used when password auth is selected |

Tap **Test connection**. On the first connection the server fingerprint is shown; confirm it to
pin the host. The same fingerprint check applies to every later publish.

### Site

| Field | Default | Notes |
|-------|---------|-------|
| Site root | — | Absolute path of the Hugo project on the server |
| Post directory | `content/post` | Relative to the site root |
| Build command | `hugo --gc --minify` | Executed in the site root after upload |
| Site URL | — | Base URL used to build the post link |
| Link template | — | Optional, e.g. `https://example.com/posts/{slug}/`. `{slug}` is replaced |

Notes:

- Use the **absolute path** of the build tool (`/usr/local/bin/hugo`) if the tool is not on the
  default `PATH` of a non-interactive shell.
- If a link template is set it wins over the site URL; otherwise the fallback is
  `<siteUrl>/archives/<slug>/`.

### Media library

| Field | Default | Notes |
|-------|---------|-------|
| Media root | `static/upload` | Must sit under `static/` and be relative to the site root |
| Group by type | on | When on, kinds are split into the subdirectories below |
| Subdirectories | `images,videos,audio,covers,files` | Positional: image, video, audio, cover, other; first four required, fifth defaults to files |

Notes:

- The public URL is derived from the physical path, so `static/upload/images` is referenced as
  `/upload/images/...` and the domain comes from the site's `baseURL`. No second URL prefix to
  maintain.
- Absolute paths, `..`, and the Hugo `assets/` directory are rejected: `assets/` is a Hugo Pipes
  resource directory and cannot be referenced directly, only `static/` is copied to the site root.
- Video and audio are inserted as HTML tags, which Hugo drops unless
  `markup.goldmark.renderer.unsafe = true` is set in the site configuration. Images and covers use
  plain Markdown and are unaffected.

## Usage

1. The app opens on the post list. Tap **New** to start a post, or tap a row to edit it.
2. The editor holds only the title, the body and **+ Attach**. A picked image, video or audio file
   is copied into the app's private inbox and the body gets a single placeholder at the cursor;
   the server is untouched, so swapping a picture ten times costs nothing.
3. Tap **Done** to open the publish screen: the slug is offered as a candidate generated from the
   title (collapsed, editable when expanded); categories and tags are picked from what the site
   already uses, with **+ New** at the end of each row; one cover image, written only into front
   matter `image`.
4. **Save as draft** writes `draft: true` and skips the link. **Publish** first uploads every
   attachment in one session, rewrites the placeholders to root-relative URLs, then writes the
   `.md` and runs the build on the server.
5. A failed build rolls the post file back; attachments already uploaded are matched by content
   hash on retry, so nothing is uploaded twice.
6. On success the status line reports the remote path, the post URL and the tail of the build
   output, the link can be copied with one tap, and the inbox copies are dropped.

## Build from source

```bash
# JDK 17 and Android SDK 34 are required
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

The project can also be opened directly in Android Studio. `local.properties` is not versioned.

## Security notes

This app executes a shell command on the server you configure, so the account it uses should be
scoped accordingly.

- Create a dedicated deployment account instead of reusing a personal or administrative one.
- Restrict that account to the site directory and the few commands the build needs. A restricted
  shell wrapper, or an SSH `command=` / `ForceCommand` rule, is recommended.
- The build command and post directory are free-form strings; they are passed to the shell as
  typed. Keep them under control and avoid interpolating untrusted input.
- Credentials never leave the device except as SSH authentication material. Nothing is uploaded to
  any third-party service, and the app contains no analytics or telemetry.

## Project layout

```
app/src/main/java/io/github/aixtin/blogpad/
├── ConfigStore.kt      # configuration model, Keystore encryption, site URL normalisation
├── PostListActivity.kt # post list: remote listing, row rendering, open to edit, publish or delete
├── PostRepository.kt   # remote read/write, slug rename, rollback, trash moves
├── MainActivity.kt     # editor: new and edit modes, attachment staging, local draft cache
├── PublishMetaActivity.kt # publish screen: slug, taxonomy, cover, cover preview
├── AttachmentInbox.kt  # attachment staging: private storage, index, reference counting
├── PublishFlow.kt      # publish pipeline: batch upload, placeholder swap, write md, cleanup
├── MediaPick.kt        # image / arbitrary file picking, normalised to pending Uris
├── MediaLibrary.kt     # library scan, orphan detection, trash (move / restore / purge)
├── MediaManagerActivity.kt # attachment library: files and trash
├── TrashStore.kt       # post trash: batch folders, restore as draft or as published
├── PostTrashActivity.kt # post trash UI: list, attachment counts, restore and purge
├── CoverCrop.kt        # cover decoding, ratio-aware cropping, output
├── CoverCropActivity.kt # crop screen: preset ratios, fit or free, hand-off
├── CoverCropView.kt    # crop view: safe margins, drag inside frame, gesture exclusion
├── CoverRatioStore.kt  # remembers the last crop ratio
├── CoverPendingStore.kt # pending cover stored on disk until publish
├── SettingsActivity.kt # connection and site settings, fingerprint, library and trash entries
└── SshPublisher.kt     # SFTP upload, remote build, rollback, front matter rendering
```

## Dependencies

| Library | Version | License |
|---------|---------|---------|
| [AndroidX SwipeRefreshLayout](https://developer.android.com/jetpack/androidx/releases/swiperefreshlayout) | 1.1.0 | Apache-2.0 |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | 0.2.26 | BSD-style |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) | 1.82 | MIT-style |

## Version history

| Version | Date | versionCode | Notes |
|---------|------|-------------|-------|
| 1.0.0 | 2026-11-09 | 1 | First release: single-screen editor with write, publish, draft, and rollback |
| 1.5.0 | 2026-11-10 | 3 | Post list, editing existing posts, categories and tags, housekeeping, structural preview |
| 1.5.1 | 2026-11-11 | 4 | Interaction rework: icon top bar, on-demand search, new-post FAB, pull to refresh; rows show date + status; collapsible post metadata and a single bottom action; card-based settings, segmented auth, custom fingerprint dialog; tapping the title opens About, which checks GitHub Releases and announces new versions with a Toast |
| 1.6.0 | 2026-11-13 | 5 | Media library: attachment upload over SFTP into `static/upload` with per-type subdirectories, cursor-relative insertion, cover image written to front matter `image`, and remote image preview |
| 1.7.0 | 2026-11-13 | 6 | Attachments are uploaded at publish time instead of on pick (private inbox plus content-hash names, so the same file is never uploaded twice); the editor keeps only title, body and attachments while slug, categories, tags and cover move to a separate publish screen fed by the site's existing vocabulary; new attachment library with orphan detection and a trash folder outside the site root |
| 1.8.5 | 2026-11-14 | 12 | Cover cropping rebuilt: preset ratios, fit and free modes, safe margins, survival across process death, full-screen cover preview; new post trash that moves deleted posts into batch folders outside the site root with restore-as-draft and restore-and-build, and no more draft toggle in the long-press menu; attachments gained an "other files" kind defaulting to `files`; site links resolve through one helper that falls back to the default site and drops the `www.` prefix |

The release procedure lives in [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md).

## Roadmap

Planned versions, non-goals, and the editing/preview interaction rules live in
[ROADMAP.md](ROADMAP.md) ([中文](ROADMAP.zh-CN.md)).

## License

[MIT](LICENSE)

## Author

Built and maintained by **atin** ([@aixtin](https://github.com/aixtin)), an independent Android developer.
`atin` and `aixtin` are the same person across GitHub, Gitee and [atin.asia](https://atin.asia/about/).
