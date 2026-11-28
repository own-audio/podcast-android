---
title: The player
nav_order: 7
description: "The mini player, the full player, speed, the sleep timer, chapters, smart rewind and the output switcher in own.audio Podcasts for Android."
---

# The player
{: .no_toc }

1. TOC
{:toc}

## The mini player

While an episode is loaded, a bar above the tabs shows its picture, title and time left, with
play/pause and skip forward. A thin line along its bottom shows how far you are. Tap the bar to
open the full player.

When you open the app again after it was closed, the last episode is back in the mini player,
paused where you left it.

## The full player

![The player](images/player.png){: width="300"}

From the top:

- The down arrow closes the player; the episode keeps playing. Android's back gesture does the
  same.
- The **×** button (**Stop**) stops the episode and clears it from the mini player, the
  notification and the widget.
- The picture, the episode's title and the show. While a sleep timer runs, the time left is on
  the picture; it counts down while the episode is paused too.
- The current chapter's name, if the episode has chapters. Tap it for the chapter list.
- The position bar: drag to move in the episode. Under it, the time played and the time left at
  the current speed, or **Played** at the very end.
- Skip back, play/pause, skip forward. The skip buttons show their seconds; you set them in
  [Settings](settings.md#playback) (15 s back and 30 s forward by default).
- The tools: **Speed**, **Sleep**, **Chapters** (only with chapters), **Up Next** and
  **Output**. A tool is colored while it is in use (a speed other than 1×, a sleep timer
  running, episodes in Up Next).

On a tablet held sideways (a window at least 840 dp wide and wider than tall), the picture, title
and chapter are on the left and the position bar, the buttons and the tools on the right, so
nothing needs scrolling.

While streaming, a line under the position bar may say how many minutes are already saved
ahead on the phone, for example **12 min saved**, so you know how long it plays on without a
signal. If the signal is gone and the saved part runs out, it says **No signal — waiting** and
plays on by itself when the signal returns.

## Speed

Tap **Speed**. Use **Slower** and **Faster** to change it in steps of 0.05×, or tap one of
0.75×, 1×, 1.25×, 1.5×, 1.75×, 2×, 2.5× and 3×. The voice keeps its pitch at any speed.

A show can have a speed of its own in its [Show Settings](shows.md#show-settings); its
episodes start at that speed. Other episodes start at the speed in Settings.

## Sleep timer

Tap **Sleep** and pick 5, 15, 30, 45 or 60 minutes, **End of Chapter** (with chapters) or
**End of Episode**. While it runs, open **Sleep** again to see **Stops in**, **Add 10 Minutes**
or **Turn Off**.

## Chapters

If the episode has chapters, **Chapters** lists them with their start times, the current one
marked. Tap one to play from there.

![Up Next and Chapters](images/sheets_up_next_chapters.png){: width="300"}

## Output

**Output** opens Android's own output switcher, where you pick the phone's speaker, wired or
Bluetooth headphones, or another speaker the system offers. It needs Android 11 or later; on
older versions the button isn't there, and you choose the output in Android's own settings.

## Skip and smart rewind

- **Smart Rewind** (on by default) goes back a little when you resume after a pause: 3 seconds
  after a short pause, up to 30 seconds after an hour or more. Turn it off in Settings.
- **Skip the Intro** is per show, in its [Show Settings](shows.md#show-settings).

## What plays next

When an episode ends, the next one in [Up Next](up-next.md) plays. If Up Next is empty and
**Play New Episodes After Up Next** is on in Settings (the default), the newest episode in New
Episodes that can play now follows.

## Your position

Your position is saved on the phone as you listen, and at once when you pause, skip or stop.
For an episode from a server, it is saved on the phone first and sent to the server when it
can be; with no network it waits on the phone until the server has it.

## Keyboard

With a keyboard attached (on a tablet, for example), **Space** plays and pauses,
**Ctrl+←** and **Ctrl+→** skip back and forward, and **Esc** closes the player.
