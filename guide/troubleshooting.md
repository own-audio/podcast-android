---
title: Troubleshooting
nav_order: 15
description: "What to do when a server signs you out, an episode won't play, or downloads wait; and how to share the app's log with a bug report."
---

# Troubleshooting
{: .no_toc }

1. TOC
{:toc}

## The log

**Settings → Logs** shows what the app wrote since it started: failed requests, playback
trouble, sync. The share button at the top sends it as a text file through Android's share
sheet, for example to attach to [an issue](https://github.com/own-audio/podcast-android/issues).
Nothing is sent unless you share it. Addresses are logged without sign-in tokens, but the log
can hold your server's addresses, so look it over before you post it publicly.

## A server stopped working

- **"The server no longer accepts this sign-in."** Open the server in Settings and tap
  **Sign In Again**. Your downloads and positions on the phone stay as they are.
- **"The server can't be reached."** Open the server's page and tap **Check Now**. If no
  address answers, check that the phone is on a network that reaches the server. For a home
  server you also use away from home, add its VPN or public address under **Addresses** (see
  [Servers and sync](servers.md#connection-and-addresses)).
  While the app is on screen it also asks such a server again by itself: after 15 seconds,
  then less often, at most every 5 minutes, and at once when you come back to the app or open
  the server's page. In the background, or while the phone is locked, it doesn't.
- **"This own.audio server is too old for this app."** The app needs own.audio server 1.0.4 or
  later; update the server.
- **"This account has no podcast library on the server."** The Audiobookshelf account has no
  podcast library it can see. Add one on the server, or give the account access.
- **podfetch refuses the account for syncing.** The admin account can't sync there: add a
  separate user on podfetch and sign in with it.

## An episode won't play

- **"This episode is not on this device. Download it, or turn Offline Mode off."** Offline Mode
  is on, or there is no network. Turn Offline Mode off with the Wi-Fi button on New Episodes.
- **"On mobile data only downloaded episodes play."** **On Mobile Data** is set to
  **Downloaded Only**; change it in [Settings](settings.md#streaming), or download the episode.
- **"No signal — waiting"** in the player: the saved part has run out and there is no signal.
  It plays on by itself when the signal returns.

## Downloads wait

With **Download on Wi-Fi Only** on (the default), downloads wait for Wi-Fi. Downloads started by
a show's rules also wait until the battery isn't low. A failed download shows
**Try the Download Again** in its menu, and the Downloads tab has **Retry … Failed** for all of
them.

## New episodes don't show up

Pull New Episodes down to check every feed and server at once. Check that the show's
**Show in New Episodes** is on in its [Show Settings](shows.md#show-settings). If background
checks seem rare, Android's battery saving for the app may be holding them back.

## Still stuck

[Open an issue](https://github.com/own-audio/podcast-android/issues) with the log attached, or
write to hello@own.audio.
