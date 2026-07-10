<p align="center">
  <img src="docs/branding/twizzle-icon.png" width="150" alt="Twizzle icon" />
</p>

<p align="center">
  <img src="docs/branding/twizzle-wordmark.png" width="520" alt="Twizzle" />
</p>

# Twizzle

Twizzle is a Twitch-focused Android and Android TV streaming app built for live channels, full live-DVR rewind, clips, favorites, low-latency playback, and timestamp-synchronized chat.

## Highlights

- Live Twitch streams with Android phone and TV support
- Full live-DVR rewind when a current broadcast archive is available
- Live IRC chat at the recommended live position
- Historical replay chat synchronized to the current VOD timestamp
- Native Twitch, BTTV, FFZ, and 7TV emotes
- Twitch clips and past broadcasts
- Favorites and a Twitch-first home experience
- Public-client Twitch device sign-in without an embedded client secret

## Builds

Development APKs are produced by GitHub Actions from the `built-in-twitch-provider` branch.

- Releases: https://github.com/hagginaterr/cloudstream-live/releases
- Build workflows: https://github.com/hagginaterr/cloudstream-live/actions
- Issues and support: https://github.com/hagginaterr/cloudstream-live/issues

## Compatibility

Phase 1 of the rebrand intentionally keeps the existing Android application ID, namespace, signing identity, stored preferences, and legacy deep-link schemes so existing installations can update without losing data.

Twizzle deep-link aliases are available alongside the legacy schemes:

- `twizzleplayer://`
- `twizzleapp://`
- `twizzlerepo://`
- `twizzleshare://`
- `twizzlesearch://`
- `twizzlecontinuewatching://`

## Open-source attribution

Twizzle is an independent fork derived from the open-source CloudStream project. CloudStream and its contributors are not affiliated with or responsible for Twizzle. Twizzle is not affiliated with or endorsed by Twitch.

The project remains licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
