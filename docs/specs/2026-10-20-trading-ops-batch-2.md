# Trading operations, batch 2: amend orders, order IDs, live feeds, transfers

Status: implemented (2026-10-20) · Date: 2026-10-20

## Implementation notes

Where the build differs from the text below:

- **§6 account feeds.** The `webData2` subscription is gone: Hyperliquid answers it with a parse error. `orderUpdates` and `userEvents` messages don't name their user, so several accounts can't share one connection with them. Live accounts therefore subscribe to `clearinghouseState`, `openOrders`, `spotState` and `userFills`, which all carry the user. Order status (§3, §4) comes from open orders, fills by cloid, and the REST `orderStatus` lookup for orders that leave the book.
- **§6 market feeds.** `activeAssetCtx` was not wired up. Funding changes hourly, and the 15 s meta poll already covers it.
- **§8 vault transfers.** When the player's own API wallet is on their computer, the client signs and posts the vault transfer straight to Hyperliquid, so the game server never handles it. Server-signed accounts (operator or delegated key) send an owner-checked request to the server instead. Vault transfers are refused for sub-accounts.
- **Testing.** The reconnect test is `FeedReconnectTest`, using a fake transport (`HlSocket.Transport`). It covers a dropped connection, backoff after failed connects, and no reconnect after stop, with every subscription re-sent and unsubscribed feeds left out. Feed parsing is covered by recorded-message tests (`FeedTest`).
- **Phase 2 latency (measured 2026-10-20).** The test account was HLP Strategy A (`0x0104…703a`), a busy public address, followed with a watch-only account on the live feed. It had *On fill* Market Sensors on 11 coins. Latency was measured from each fill's exchange timestamp to the sensor's redstone output rising, over 8 sensor pulses: median 625 ms, p90 831 ms, max 831 ms, all under 1 s. Most of that is about 290 ms of feed delivery (median of 135 fills measured directly) plus the sensor's 10-tick check interval.

## Why

HyperCraft already covers the core of a Hyperliquid front end: market, limit (GTC / post-only / IOC), stop and take-profit orders, brackets, TWAP, leverage and margin mode, isolated-margin top-ups, the dead-man's switch, sub-account transfers, builder fees, watch-only accounts and exact PnL.

The goal stays the same: put as much as possible **on the board**, so redstone builders can be creative. This batch closes the gaps that matter most for that:

1. **Amending orders in place**, so a circuit can keep moving one resting order (trailing entries, laddering, repricing) instead of piling up new ones.
2. **Client order IDs**, so a block knows which orders are its own. That makes the first point possible, and it also lets a block report its order's status to redstone and cancel its order after a timeout.
3. **Live WebSocket feeds** in place of 1–3 s polling. Sensors react to fills and price moves sooner, and a busy server stays clear of the REST rate limits.
4. **Moving money**: USDC between spot and perps, and deposits/withdrawals to Hyperliquid vaults.

## References

- **Source of truth:** the official [hyperliquid-python-sdk](https://github.com/hyperliquid-dex/hyperliquid-python-sdk). Every new action gets a signing test vector generated with it, the same way `HlActionsTest` and `BuilderFeeTest` work today.
- **Checklist:** `@nktkas/hyperliquid`, which has typed coverage of every action and info request.
- **Not a reference:** `chrisling-dev/hyperliquid-cli`. It is a thin CLI over `@nktkas/hyperliquid` and covers fewer operations than we already have. Its WebSocket-backed watch views are the one idea we take.

## Scope

| # | Feature | Exchange action / feed | Who signs | Shows up on the board as |
|---|---|---|---|---|
| 1 | Client order IDs | `c` field on order wires, `cancelByCloid` | API wallet | Executors own their orders |
| 2 | Amend orders | `batchModify` | API wallet | Executor "on repeat pulse: amend" |
| 3 | Order status to redstone | (uses 1 and the feeds) | none | Executor comparator mode |
| 4 | Cancel after N seconds | `cancelByCloid`, scheduled by the mod | API wallet | Executor setting |
| 5 | Signature expiry | `expiresAfter` in the action hash | API wallet | Invisible safety for client signing |
| 6 | Live feeds | WebSocket `allMids`, `l2Book`, `orderUpdates`, `userFills`, `webData2` | none | Faster sensors and displays |
| 7 | Spot ⇄ perps USDC | `usdClassTransfer` | **Main wallet** | Fund screen only |
| 8 | Vault deposit/withdraw | `vaultTransfer` | API wallet | Vault screen; never auto-signed |

### Out of scope

| Feature | Why it's left out |
|---|---|
| Sending USDC or tokens to other addresses, withdrawing to Arbitrum (`usdSend`, `spotSend`, `withdraw3`) | Moves money out of the account. A game has no business doing that. |
| Staking and delegation | |
| HIP-3 builder-deployed perp markets | Separate project: they change asset numbering and market data. |
| Referral codes (`setReferrer`) | Trivial; can ride along later. |

## 1. Client order IDs (cloid)

**Format.** A cloid is 16 bytes, written as `0x` plus 32 lowercase hex characters. On an order wire it goes in key `c`, after `t`:

```
{a, b, p, s, r, t, c}
```

The key order matters, because the action is hashed as msgpack.

**Our scheme.** `0x` + 8 bytes from the block + 8 bytes of sequence/time.
- The block part is `hash(dimension, blockPos)`, truncated to 8 bytes.
- The sequence part is a per-block counter mixed with the time.

This lets the mod tell which block owns an order without keeping a table. It also survives restarts, because the block part is stable. Orders placed from the terminal get a cloid from a per-player prefix.

**New actions:**
- `HlActions.wire(..., @Nullable String cloid)`
- `HlActions.cancelByCloid(List<(asset, cloid)>)`, which builds `{type: "cancelByCloid", cancels: [{asset, cloid}]}`

**`TradingAccount` additions:**
- `List<OpenOrder> ordersByCloidPrefix(String prefix)`
- `cancelByCloid(coin, cloid)`

**Paper engine.** It stores the cloid on each order and supports the same lookups.

## 2. Amending orders (`batchModify`)

**Action format:**

```
{type: "batchModify", modifies: [{oid: <long oid | cloid string>, order: <order wire>}]}
```

- `oid` can be the exchange order id or the cloid.
- The new wire can carry the same cloid or a new one.
- Price and size follow the same rounding rules as new orders.

**Executor: a new setting "On repeat pulse".**

| Option | Behaviour |
|---|---|
| *Place new* (default) | Today's behaviour. |
| *Amend my order* | If this executor has a resting order on its coin, move it to the current price, size, TP and SL (the Inputs-tab values) with `batchModify`. If not, place a new one. |
| *Replace my order* | Cancel this executor's resting order, then place a new one. Use this where amending isn't allowed, e.g. switching the order type. |

Combined with Inputs-tab price binding (§ existing executor inputs), *Amend* turns any analog circuit into a **trailing or repricing order**. For example, a comparator reading a Market Sensor's analog price drives `PRICE` from −1% to +1%, and a clock pulses the executor every few seconds.

**Paper engine.** `modify(oidOrCloid, newOrder)` changes the order in place and keeps its queue position on the order list. If the new price would cross, it fills immediately, exactly like a new order. Brackets attached to a modified entry move with it, keeping the same percentage offsets.

## 3. Order status on the comparator

**Executor setting "Comparator reads":**

| Option | Output |
|---|---|
| *Last result* (default) | Today's behaviour: 15 = success, 1 = rejected. |
| *My order* | The state of this executor's most recent order (below). |

*My order* values:

| Value | Order state |
|---|---|
| 0 | None |
| 4 | Resting |
| 8 | Partially filled |
| 15 | Filled (stays at 15 until the next pulse) |
| 2 | Cancelled or expired |
| 1 | Rejected |

This makes "when my limit fills, do X" a two-block circuit without a separate sensor. The Market Sensor's existing *On fill* mode stays as it is.

**Where the state comes from.** Order and fill updates from the feeds (§6), matched by cloid. While polling is the fallback, they come from the next REST refresh.

## 4. Cancel after N seconds

Hyperliquid has no good-till-time order type, so the mod provides one.

**Executor setting "Cancel after".**
- 0 means never.
- Otherwise, 5–86400 seconds after placement, the server cancels this executor's order with `cancelByCloid` if it is still resting. Partially filled orders are cancelled for the remainder.

**How it's tracked.**
- Each executor's block entity keeps `(cloid, deadline)` and saves it with the block. A tick checks it once a second.
- After a server restart, deadlines that passed while the server was down are cancelled straight away.
- On client-signed accounts this needs the owner to be online, like every other action. The screen says so.

**Paper engine.** Same behaviour, through its own cancel.

Note: this is different from §5. `expiresAfter` stops a *signed request* from landing late. It does not expire resting orders.

## 5. Signature expiry (`expiresAfter`)

**Hash change.** If `expiresAfter` is present, the action hash gets `0x00` followed by `expiresAfter` as 8 big-endian bytes, after the vault part:

```
msgpack(action) ‖ nonce(8) ‖ vaultFlag[‖ vault] ‖ 0x00 ‖ expiresAfter(8)
```

The posted body carries `"expiresAfter": <ms>`.

**Code changes.** `HlSigner.actionHash` and `recoverL1Signer` take a nullable `expiresAfter`. `HyperliquidApi.exchange` passes it through.

**Policy.** Every **client-signed** request (`SignRequests`) gets `expiresAfter = now + 15 s`. A signature that sits in a slow client, or is replayed by a misbehaving server, can't land minutes later at a different price. The client checks the field before it signs:
- `expiresAfter` must be present.
- It must be no more than 60 s ahead of the client's own clock.

Server-signed and paper accounts don't need it.

User-signed actions (approve builder fee, `usdClassTransfer`) must **not** carry `expiresAfter`. The SDK says these actions don't support it.

## 6. Live feeds (WebSocket)

**Connection.** One `java.net.http.WebSocket` per server, to `wss://api.hyperliquid.xyz/ws` (or the testnet host).
- Subscribe with `{"method": "subscribe", "subscription": {...}}`.
- Send `{"method":"ping"}` every 50 s.
- Reconnect with backoff (1, 2, 4 … up to 30 s) and resubscribe everything.

**Market feeds (`MarketData`):**

| Feed | Used for | REST fallback |
|---|---|---|
| `allMids` | Every ticker | Poll stays, at 15 s instead of 1 s |
| `l2Book` | Only coins something is looking at: an open terminal book view, or a Spread / Book-tilt sensor. Subscribe when the first watcher appears, unsubscribe 30 s after the last one leaves. | Replaces the 1.5 s cached REST book |
| `activeAssetCtx` | Funding, mark price and open interest for coins with a funding sensor or display | |

**Account feeds (`LiveAccount`: live and watch accounts):**

| Feed | Used for |
|---|---|
| `orderUpdates` | Resting, filled and cancelled status by cloid (§3, §4) |
| `userFills` | `lastFillTime` and the fills list, so the *On fill* sensor fires within about a tick |
| `webData2` | Clearinghouse state (positions, margin, open orders) pushed on change |

The REST refresh drops from every 3 s to every 30 s as a consistency check. `portfolio` stays on REST every 30 s; it has no feed.

**Limits.** Hyperliquid caps WebSocket usage per IP:
- connections,
- total subscriptions,
- a small number of **distinct users** across user-specific subscriptions. At the time of writing this is believed to be **10**; check against the current API docs before building.

The mod gives account feeds to accounts in this order, and anything past the cap stays on polling:
1. accounts with an executor or sensor loaded,
2. selected in an open terminal,
3. the rest.

`/hl status` shows which accounts are live-fed and which are polled.

**Threading.** Feed messages arrive on the WebSocket thread. They update the same volatile snapshots the REST poll writes today, so readers on the server thread don't change. Sensors keep their 10-tick evaluation. Fill events also wake the affected executors and sensors on the server thread, so the redstone signal changes on the next tick.

**Paper accounts** are unaffected. They already match against `allMids`, which now arrives sooner, so paper fills feel snappier too.

## 7. USDC between spot and perps (`usdClassTransfer`)

**Action format.** This is a user-signed action, so the API wallet can't sign it.

```
{type: "usdClassTransfer", hyperliquidChain, signatureChainId: "0x66eee", amount: "<decimal>", toPerp: bool, nonce}
```

EIP-712 type: `HyperliquidTransaction:UsdClassTransfer(string hyperliquidChain, string amount, bool toPerp, uint64 nonce)`.

For a sub-account, the amount string gets the suffix ` subaccount:<address>`.

**Signer work.** `HlSigner.userActionDigest` needs a `bool` field type, encoded as `uint256` 0 or 1.

**UI.** On the Fund screen of a live account: **Move USDC → Perps / → Spot**, with an amount and the same one-time main-wallet key prompt as `BuilderFeeScreen`. The key is used on this computer for one signature, then cleared; it is never stored or sent.
- To keep that code in one place, factor the prompt out into a reusable `MainWalletPrompt` (used by builder fees and transfers).
- Not available to redstone. A key can't sit on the board.

Accounts in Hyperliquid's unified-account mode don't need this. If the exchange rejects the transfer for that reason, show its message.

**Paper.** One USDC pool already backs spot and perps, so the buttons are hidden for paper.

## 8. Vault deposit and withdraw (`vaultTransfer`)

**Action format.** Signed by the API wallet (L1 action), with vault address null in the hash:

```
{type: "vaultTransfer", vaultAddress, isDeposit, usd: <int micro-USD>}
```

**UI.** On a live account's vault screen, a **Hyperliquid vaults** section:
- Paste a vault address, or pick HLP.
- Enter an amount.
- Deposit or withdraw.
- It shows the account's current equity in that vault (info `userVaultEquities`).

Typical use: watch the vault with a watch-only account, and wire its PnL to a sensor.

**Security.** A hostile server could otherwise ask a player's game to deposit into a vault the server's owner runs, so the client must never auto-sign `vaultTransfer` from a server request.
- It is not added to `SignPolicy.ALLOWED`.
- The client signs it only from its own vault-screen button, after showing the destination and amount in a confirm step.
- The request still goes through the server's submit path. The client builds and signs the action itself, and the server verifies the signer.

**Paper.** Not supported. Paper has no vaults to deposit into.

## Signing policy changes (client side)

| Action | Policy |
|---|---|
| `batchModify` | Allowed. Each modified wire counts toward the per-order notional cap like a new order (reduce-only wires are exempt). |
| `cancelByCloid` | Allowed. |
| All client-signed requests | Must carry `expiresAfter` no more than 60 s ahead of the client's own clock (§5). |
| `vaultTransfer`, `usdClassTransfer` | Never signed on a server's request. Only from client-initiated UI. |

## Testing

- **SDK test vectors** (hash and signature, mainnet and testnet):
  - an order with cloid,
  - `cancelByCloid`,
  - `batchModify` by oid and by cloid,
  - an order with `expiresAfter`,
  - `usdClassTransfer`, plus its sub-account variant,
  - `vaultTransfer`.
- **Paper engine:**
  - a modify that keeps its place in the queue,
  - a modify that crosses and fills,
  - modifying an entry moves its brackets,
  - cancel-after expiry, including expiry that happened while the server was down,
  - lookup by cloid prefix.
- **Feeds:** recorded JSON fixtures for each channel → parser tests. A reconnect test with a fake WebSocket that drops connections and checks resubscription.
- **Policy:** `SignPolicyTest` cases for every row above.
- **Testnet round trip** (the `-Plive` demo, throwaway key): place with cloid, amend, cancel by cloid. The expected response is the "User or API Wallet does not exist" error with our recovered address. Same as the existing order check.
- **Demo world:**
  - an executor on *Amend* with a clock and an analog price input, showing one order moving on the chart,
  - its comparator on *My order* lighting a lamp on fill,
  - a cancel-after executor timing out.

## Phases

| Phase | Contents | Ships when |
|---|---|---|
| 1 | §1 cloid, §2 amend, §3 comparator status, §4 cancel-after, §5 `expiresAfter`, policy changes | Paper and live both pass the tests above; demo shows the trailing order |
| 2 | §6 WebSocket feeds with REST fallback and the user cap | Fill-to-redstone latency under 1 s on a live account; `/hl status` shows feed state |
| 3 | §7 spot ⇄ perps, §8 vault transfers, shared `MainWalletPrompt` | SDK vectors pass; confirm steps in place |

Phase 3 is independent of phases 1–2 and can be built in parallel.

## Decisions made in this spec (author's calls, open to change)

| Decision | Reason |
|---|---|
| Cancel-after is done by the mod, not the exchange | Hyperliquid has no good-till-time orders. `expiresAfter` only bounds signature freshness. |
| Spot ⇄ perps and vault transfers are UI-only | They need the main wallet, or move funds somewhere a server could choose. |
| Cloids are derived from block position | A block can find its own orders after a restart without a lookup table. |
| WebSocket user subscriptions are prioritised by "has redstone attached" | Under the per-IP user cap, the accounts driving circuits get the fast path. |
| Executor amend keeps the order type | Switching type isn't an amend. *Replace* covers it. |
