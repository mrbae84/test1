# 💣 LuckyBomb VN — DEMO

> **DEMO — VIRTUAL CURRENCY ONLY — NO REAL MONEY**

A fictional, single-file casino-style **UI prototype** built with plain HTML, CSS and
vanilla JavaScript. Open `index.html` in any modern browser — there is no build step,
no dependencies, no backend and no network requests of any kind.

## What this is not

This prototype deliberately contains **none** of the following:

- no real payment gateway, and no external payment API
- no real QR payment (the "QR code" is a decorative canvas drawing that encodes nothing)
- no bank details — none are shown, requested, or collected
- no real-money deposits and no withdrawals
- no real gambling

Every balance, bet and payout is **virtual VND** that exists only in this browser's
`localStorage`. The "SIMULATE PAYMENT" button simply increments a number; it never
claims that a payment was made.

## Features

| Screen | What it does |
| --- | --- |
| **Login / Register** | Fictional accounts kept in `localStorage`; new accounts start with 100,000 virtual VND. |
| **Bomb / Mines** | 5×5 board, 3/5/10/15 bombs, multiplier = fair odds × 97%, cash out any time. |
| **Vòng Quay May Mắn** | 12-segment CSS wheel (0x, 0.5x, 1x, 1.5x, 2x, 3x, 5x) with an animated spin. |
| **Dice Roll** | Three dice — `THREE WHITE` (triple, 10x), `ODD` (1.8x), `EVEN` (1.8x). |
| **Add Virtual Money** | Preset and custom amounts, fake QR graphic, simulated top-up, demo transaction log. |
| **History / Account** | Per-game filters, virtual win/loss stats, account management. |

## Command chat

Open the ⌨️ button (bottom right) and type:

```
/always win for <username>      make that demo account always win
/always loose for <username>    make that demo account always lose
add <username> <amount>         credit virtual VND
/normal for <username>          back to random outcomes
/balance <username>             show a virtual balance
/users        /help     /clear
```

The rigging applies to all three games: mines quietly relocates its bombs, the wheel
picks its best or worst segment, and the dice are re-rolled until they match.

## Notes

- Passwords are run through a toy scrambler so plaintext is not left in `localStorage`.
  This is **not** authentication — do not reuse a real password.
- Fully responsive down to 320px; `prefers-reduced-motion` is respected.

Gambling carries real risk. This repository exists only to demonstrate UI and
animation work — please do not treat it as a real product.
