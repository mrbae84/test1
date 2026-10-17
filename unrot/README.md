# 🧠 unrot (web version)

A single-file web version of the **unrot** screen-time habit app: earn Brain Coins by
doing real-life things ("unrotting") and spend them on scroll time. Open
`unrot/index.html` in any modern browser. It needs no build step and no backend, and the only
network request is for the Google font, which falls back to the system font if it can't load.

All data (coins, sessions, journal entries, settings) lives in this browser's
`localStorage` under the key `unrot.v1`. **Settings → Delete my account** erases it.

## Screens

| Screen | What it does |
| --- | --- |
| **Home** | Brain Coins wallet (max 100), Unrot / Scroll tiles, "Get rich quick" shortcuts, First steps checklist (+30 bonus), daily streak (10 coins/day), live bedtime countdown, Essential Unrots, Today's Brainlog bar and timeline. |
| **Unrot sessions** | Timed activities (Workout 15/hr, Practice a skill 20/hr, Cook a meal 10/hr, …) with duration picker, allowed apps, "Tell people you're offline" (share sheet or clipboard), a countdown that survives reloads, and pro-rata coins if you stop early. |
| **Breathe** | Five guided breathing patterns with an animated orb, 2 coins per full minute. |
| **Journal** | Gratitude, Emotional Check-In (mood picker, 3 h cooldown) and Reflection prompts. |
| **Exercise** | Pushup counter (tap with your nose), 1 coin per pushup. |
| **Side Quests** | Photo quests ("Send me a photo of water!"). The photo is only previewed, never uploaded. Each quest can be done once per day. |
| **Scroll** | Buy 1–30 minutes of scroll time (1/2/3 coins per minute by difficulty) with the Unrot Bank card-tap and receipt animation, then an unlocked-time countdown. |
| **Stats** | Reclaimed time, Unrot Bank card, lifetime earnings, Power Ups / Unrots breakdown, "Your patterns" cards, week/month charts for coins, brainrot and unrot time with % vs last period, Brainlog Calendar. |
| **Challenges** | 28 Day Unrot: earn 10 coins a day for 28 days. |
| **Settings** | Distracting apps, difficulty, language, purchase animation toggle, bedtime, notifications, name, weekly recap, feedback, legal, delete account, blocking help. |

The first launch is seeded to match the reference screenshots: 99/100 coins, one
Energy Breathing session (+6) and one minute of scrolling (−1) this morning. Dates use the Vietnamese
locale like the screenshots (`11 - 17 thg 10 2026`, `CN … T7`, `SA`/`CH`).

A browser can't block other apps, so **Scroll** works on the honour system. Pair it with
iOS Screen Time or Android Digital Wellbeing if you want hard limits.
