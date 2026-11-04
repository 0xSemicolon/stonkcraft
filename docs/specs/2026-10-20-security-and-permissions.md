# Security model, wallet custody and shared trading

Status: implemented with this change · Date: 2026-10-20

Safety comes first. Real money can sit behind these blocks, other people's machines run the server, and Minecraft items can be dropped, stolen or duplicated by bugs. This doc sets out what we protect, who we protect it from, and how each wallet mode and the new shared-trading permissions are enforced.

## Who we defend against

| Actor | Can | Must not be able to |
|---|---|---|
| Another player | Pick up dropped items, open blocks they can reach, send any packet | Trade, withdraw from, or change an account they weren't granted |
| A grantee (someone you let trade) | Trade within the limits you set | Exceed those limits, withdraw, move money, re-share, or change permissions |
| Server operator (op) | Run the server, read world files and config | Trade or withdraw a player's real-money account without a grant. A server holding a key can always misuse it; see custody modes. |
| A malicious server | Send your game any signing request | Get your game to sign withdrawals, transfers or approvals, or anything outside your own wallet limits |
| Someone who copies the world folder | Read world files | Read delegated keys |
| Malware or someone else reading your disk | Read your config folder | Read your keys, if you set a wallet passphrase |

## Audit findings (before this change)

| # | Finding | Fix |
|---|---|---|
| 1 | An account card was a full key. Holding one gave terminal trading **and coin withdrawal**. | Cards are now for routing, not authority (see Cards). Withdraw, close, print and permission changes are owner-only. |
| 2 | Ops could trade and withdraw any account, including live ones. With self-custody this made the owner's game sign for them. | The op override now covers paper accounts only. Live accounts need the owner or an explicit grant. |
| 3 | A stolen card in the thief's own executor traded the victim's account. | Blocks trade only if **the block's owner** has trade permission on the account. |
| 4 | Clients auto-signed any server request under one per-order cap: no rate limit, daily budget, leverage cap, price sanity check, or knowledge of who asked. | Per-wallet signing policy (below), and every request names who asked for it. |
| 5 | No input validation (NaN / ∞ / huge sizes, any leverage) and no packet rate limit. | Requests are sanitised. Each player is limited to 5 trading actions/s. |
| 6 | Delegated keys were plaintext in the world folder, and the operator key plaintext in config. | Delegated keys are encrypted at rest with a server secret kept outside the world. The operator key can come from an environment variable. |
| 7 | Client wallet keys were plaintext on disk. | Optional passphrase lock (AES-GCM, PBKDF2). |

## Wallet custody modes

Every live account shows its mode in the vault and terminal.

| Mode | Who holds the key | Trades when | Risk you accept | How to get it |
|---|---|---|---|---|
| **Watch** (bring your own, read-only) | Nobody | Never | None. Hyperliquid account data is public. | Paste any address into a vault |
| **Self-custody** | Your computer (`config/hypercraft-wallets.json`, optionally passphrase-locked) | While you're online | A malicious server can ask your game to sign trading actions. It can't get withdrawals or transfers signed, and it's held to your wallet limits. | Add an API wallet you made on Hyperliquid, **or let the mod create one** ("in-app managed", below) |
| **In-app managed** | Same as self-custody. The mod generated the key. | While you're online | Same as self-custody | Wallets → *Create API wallet*: the key is generated locally, then approved with one main-wallet signature (`approveAgent`) |
| **Server-held key** (delegated) | The server, encrypted at rest | 24/7 | The server operator can trade with it. API wallets can't withdraw. | Vault → *Let server sign* (opt-in, shift-click) |
| **Server wallet** (operator) | The server operator's own wallet | 24/7 | It's the operator's money | Operator config |

API wallets ("agents") **can't withdraw or transfer funds off Hyperliquid**. That is the backstop for every mode above. Actions that do move money (spot ⇄ perps, builder-fee approval, approving an agent) need the **main** wallet. The mod asks for that key per action, uses it on your computer only, and clears it.

## Shared trading (permissions)

The owner of an account can grant other players access:

| Role | Can |
|---|---|
| **View** | See the account in their terminal, and use its cards in sensors and displays |
| **Trade** | View, plus place, amend and cancel orders, and set leverage, from the terminal or from blocks they own |

**Limits on each Trade grant:**
- **Max order size (USD).** 0 means only the account's own caps apply.
- **Max leverage.**
- **Allowed markets.** Empty means all.
- **Reduce-only:** they can only shrink positions.
- **Expiry:** hours, or never.

**Grantees can never** withdraw, move money, print cards, close or rename the account, delegate keys, change permissions, or re-share.

**Freeze.** One switch in the owner's Access screen stops all trading by anyone but the owner, including every block-driven order. Cancels still work, because cancelling only reduces risk.

**Activity log.** Every action on the account is recorded: who did it, through what (terminal, which executor at which position, command, kill switch), and the outcome. The owner sees the last 100 entries. When someone else trades, an online owner also gets a chat line.

**Self-custody and sharing.** A grantee's trade on a self-custody account still has to be signed by the owner's game, so it only happens while the owner is online. It also happens only if the owner turns on **"Sign for other players"** in that wallet's settings (off by default). The sign request names who asked and through what. The owner's game applies the wallet's own limits on top of the grant's.

## Cards

A card is now a **route, not a key.**
- Each card is printed as **Read** (sensors, displays) or **Trade** (executors, kill switches, leverage dials). It records a card id in the account.
- The owner can **revoke** any card. A revoked card stops working everywhere at once.
- A Trade card only works in a block whose **owner** has Trade permission on the account (the owner, or a grantee). Its actions are held to that grantee's limits.
- Holding a valid card lets you see the account. It never lets you trade from the terminal, withdraw, or change anything.
- Cards from before this change count as Trade cards, so existing builds keep working. The block-owner rule still protects them.

## Signing policy (self-custody wallets)

Your game checks every server request against the wallet's own settings and refuses anything outside them. Refusals and signatures are kept in the wallet's sign log.

| Setting | Default | Checks |
|---|---|---|
| Allowed actions | trading only | `order`, `cancel`, `cancelByCloid`, `batchModify`, `updateLeverage`, `twapOrder`, `twapCancel`, `scheduleCancel`, `updateIsolatedMargin`, `subAccountTransfer`. Never withdrawals, sends, vault transfers or approvals. |
| Max order (USD) | 250 | Notional of each opening order, amended order or TWAP |
| Max leverage | 10 | `updateLeverage` |
| Price band | 10% | Limit and trigger prices must sit within this distance of **this computer's own** mid price |
| Rate | 30 per minute | Signatures per rolling minute |
| Daily budget (USD) | 2,500 | Opening notional per rolling 24 h |
| Sign for other players | off | Requests started by anyone but you |
| Expiry | required, ≤ 60 s | Every request must carry a short `expiresAfter` |
| Passphrase lock | off | While locked, nothing is signed |

## Server hardening

- **Delegated keys** are encrypted with AES-256-GCM in `hypercraft_delegated_keys.json`. The secret comes from the `HYPERCRAFT_KEY_SECRET` environment variable, or is generated once into `config/hypercraft-secret.key`, outside the world folder. Old plaintext files are migrated on load.
- **Operator key:** the `HYPERCRAFT_PRIVATE_KEY` environment variable overrides `privateKey` in config. A plaintext key in config logs a warning.
- **Requests** are sanitised: finite numbers, sizes up to $1B, price offsets within ±50%, TP/SL up to 1000%, leverage 1–100, margin moves up to $10M. Each player gets 5 trading actions per second.
- **Watch accounts** need nothing: no keys and no permission. The data is public.

## Tests

- `AccessPolicyTest`: roles, expiry, freeze, limits (size, leverage, markets, reduce-only), card revocation and level, the block-owner rule, and owner-only actions.
- `SignPolicyTest`: price band, leverage cap, rate and daily budget, sign-for-others.
- `KeyVaultTest`: encrypt/decrypt round trip, tamper detection, wrong secret.
- `WalletLockTest`: passphrase round trip, wrong passphrase.
- `HlActionsTest`: SDK vector for `approveAgent`.
