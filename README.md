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
- **Write and publish from the phone** — title, slug, categories, tags, body, and a draft toggle;
  slug, categories and tags stay collapsed behind a one-line summary until tapped.
- **Markdown with Hugo front matter** — generated automatically (`title`, `date`, `draft`, `slug`,
  `categories`, `tags`, and `image` when a cover is picked), so the file drops straight into a
  Hugo site.
- **Attachments** — pick an image, video or audio file and it is uploaded by SFTP into a media
  directory under the site's `static/` tree, then referenced at the cursor as a root-relative
  Markdown image or an HTML media tag. Names are `<slug>-<timestamp>.<ext>`, so nothing collides.
- **Cover image** — the cover goes to its own subdirectory and is written only into the front
  matter `image` key; it never appears in the body.
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
- **Draft auto-save** — whatever is typed is cached locally and restored on next launch.
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
| Subdirectories | `images,videos,audio,covers` | Positional: image, video, audio, cover |

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
2. Fill in title and body; slug, categories, and tags are optional.
3. Leave the slug empty to generate one from a timestamp. Changing the slug of an existing post
   renames the remote file on save and removes the old one.
4. Check **Save as draft** to write `draft: true` and skip the link.
5. Tap **Publish** (**Save** when editing). The status line reports the remote path, the post URL,
   and the tail of the build output.
6. On success the link can be copied to the clipboard with one tap.

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
├── ConfigStore.kt      # configuration model, Keystore encryption, persistence
├── PostListActivity.kt # post list: remote directory listing, row rendering, open to edit
├── PostRepository.kt   # remote read/write, slug rename, rollback on build failure
├── MainActivity.kt     # editor: new and edit modes, publish flow, local draft cache
├── SettingsActivity.kt # connection and site settings, fingerprint confirmation
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
| 1.6.0 | 2026-11-13 | 5 | Media library: attachment upload over SFTP into `static/upload` with per-type subdirectories, cursor-relative insertion, cover image written to front matter `image`, and remote image preview |
| 1.5.1 | 2026-11-11 | 4 | Interaction rework: icon top bar, on-demand search, new-post FAB, pull to refresh; rows show date + status; collapsible post metadata and a single bottom action; card-based settings, segmented auth, custom fingerprint dialog; tapping the title opens About, which checks GitHub Releases and announces new versions with a Toast |

The release procedure lives in [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md).

## Roadmap

Planned versions, non-goals, and the editing/preview interaction rules live in
[ROADMAP.md](ROADMAP.md) ([中文](ROADMAP.zh-CN.md)).

## License

[MIT](LICENSE)

## Author

Built and maintained by **atin** ([@aixtin](https://github.com/aixtin)), an independent Android developer.
`atin` and `aixtin` are the same person across GitHub, Gitee and [atin.asia](https://atin.asia/about/).
