# Cratez

Cyberpunk music crate tool for DJs (Android, Kotlin + Jetpack Compose).

- **Scan & tag** – pick songs or folders; Cratez detects BPM, key (Camelot), energy and genre (with a dedicated EDM sub-genre model), fetches album art / label / year from iTunes, Deezer and MusicBrainz, and writes tags back into MP3/FLAC files.
- **Share** – Beam (phone-to-phone over Nearby Connections), friend codes over the internet (Cloudflare relay), or email / text / any app.
- **Dupes** – finds duplicate songs across the phone and deletes extras.
- **Player + visualizer** – background playback and MilkDrop-style GPU visualizers with live controls.

## Layout
| Folder | What |
|---|---|
| `app/` | Android app |
| `core/` | Pure Kotlin engine: DSP (BPM/key/energy/quality), genre classifier + fusion, ID3/FLAC tag writer, dupe finder |
| `relay/` | Cloudflare Worker + KV relay for friend-code sharing (free plan) |

## Relay
`relay/` is a Cloudflare Worker. It needs one KV namespace bound as `KV` (id in `relay/wrangler.toml`).
Files are chunked (8 MB) and expire after 7 days or once downloaded. `cd relay && npm test` runs the API tests.
