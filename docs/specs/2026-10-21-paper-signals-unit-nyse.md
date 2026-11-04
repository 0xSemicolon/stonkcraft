# Paper only, composable signals and copy trading, UNIT.XYZ, the NYSE

Status: implemented (2026-10-21) · Date: 2026-10-21

## Why

HyperCraft started as a Hyperliquid front end with optional real-money trading. The direction changes:

1. **Paper trading only.** No mainnet or testnet order signing. Real execution comes back later through one
   seam: a player brings a URL + key for a server that speaks a published protocol (§9). The mod never holds
   exchange keys again.
2. **Copy trading built from composable signals.** Signals are values (a number or a target portfolio) that flow
   between blocks, are combined and transformed, and finally drive a paper account.
3. **Trading tied to the world.** A UNIT.XYZ bridge turns mined metals into account equity and back again. That
   fixes the cold start (a new player mines gold and has money to trade). It also gives trading profits a use in
   survival: withdraw gold, barter with piglins for ender pearls, beat the dragon. Trading becomes a speedrun route.
4. **Mainnet data, always.** Prices and watched accounts come from Hyperliquid mainnet. The testnet switch is gone
   from the config and the UI; one hidden developer override remains.
5. **The New York Stock Exchange replaces Hyperliquid Island.** A faithful 1:1 NYSE on Broad & Wall, as a small
   world of its own and as a home base on a real island in a survival world.
6. **TradFi markets.** trade.xyz's HIP-3 dex (`xyz:`) lists stocks, indices and commodities: AAPL, NVDA, TSLA,
   SP500, GOLD, SILVER, COPPER, oil and more. Paper accounts can trade them, which an NYSE needs.

## Scope

| # | Change | Where it shows up |
|---|---|---|
| 1 | Remove live trading, keys and signing; keep watch-only accounts (read-only mainnet mirrors) | Vault, terminal, Fund screen, config |
| 2 | Mainnet only; hidden dev override | config, `/hl status` |
| 3 | `xyz:` HIP-3 perps (stocks, indices, commodities) in market data and the paper engine | Terminal **Stocks** tab, every block's coin field |
| 4 | Unit tokens: synthetic spot assets backed by in-game metal, priced off `xyz:` perps | Terminal Spot tab and balances |
| 5 | **UNIT.XYZ** block: deposit and withdraw metals as spot tokens or as a 1x long | New block, hoppers |
| 6 | Signal model, **Signal Mixer** block, **Signal Card** item | New block and item |
| 7 | **Copy Desk** block: drives a paper account to a signal's target portfolio | New block |
| 8 | Signals everywhere: Market Sensor, Trade Executor, Account Display read signal cards | Existing blocks |
| 9 | NYSE builder: `-Pnyse` district world, `-Psurvival` island home, `/hl nyse` command | Worlds, command |
| 10 | Remove Hyperliquid Island | |
| 11 | Game hooks: market-hours sensor and the opening bell, advancements, speedrun timer, liquidation effects | Sensor modes, advancements, HUD |

### Out of scope

| Item | Why |
|---|---|
| Any real execution | Comes later through §9, not now |
| Leaderboard scraping (Hyperliquid's stats endpoint is a ~20 MB file) | Copy targets are entered by address or card |
| Item shops for arbitrary vanilla items (diamonds, pearls…) | Only metals with real markets get bridged by default. Server owners can add their own pegs in the Unit config (§5.6). Pearls come from piglins, which take bridged gold |
| HIP-3 dexes other than `xyz` | One is enough to prove it; the loader is dex-generic |

## 1. Paper only

### Removed

- `LiveAccount` write paths, `HlSigner`, `ActionSigner`, `SignRequests`, `LinkFlow`, `KeyVault`, `SigningLimits`,
  `SignPolicy`, `WalletCrypto`, `MsgPack`, `HlActions`. Also builder fees, delegated keys, the operator wallet,
  coin float, sub-account and vault transfers.
- Client: `ClientSigning`, `ClientWallets`, `WalletScreen`, `WalletLimitsScreen`, `CreateWalletScreen`,
  `MainWalletPrompt`, `PassphraseScreen`, `BuilderFeeScreen`, `SpotPerpScreen`, `HlVaultScreen`, the link flow in
  `VaultSetupScreen`, the live sections of `FundScreen` / `VaultScreen` / `TerminalScreen`.
- Signing tests and vectors (`HlSignerTest`, `HlActionsTest`, `BuilderFeeTest`, `Batch2ActionsTest`,
  `WalletSecurityTest`, `SignPolicyTest`, `KeyVaultTest`, `TestnetLiveTest`). The code stays in git history for §9.

### Kept

- **Paper accounts**, unchanged: every order type, brackets, TWAP, funding, the dead-man's switch.
- **Watch accounts**: `LiveAccount` becomes `WatchAccount`, a read-only mirror of any mainnet address (positions,
  orders, fills, equity, Hyperliquid's own PnL). It's a signal source for copy trading (§6).
- Cards, grants, freeze, activity log. A grant's trade limits apply to paper accounts as before.
- Coins. All coins are paper money; the backing component is ignored.

### Migration

- `LIVE` accounts in old saves become `WATCH` accounts on the same address, and the activity log gets the line
  "Live trading was removed; this account now follows the address read-only". Their cards keep working for
  sensors and displays and stop working for trading, as watch cards always did.
- Testnet watch accounts are moved to mainnet (same address).
- `hypercraft_delegated_keys.json` is deleted on load, and the log says so. Encrypted keys have no use left.
- Old config keys (`network`, `liveTrading`, `allowDelegatedKeys`, `privateKey`, `accountAddress`,
  `liveSubAccounts`, `liveTraders`, `maxLiveOrderUsd`, `builderAddress`, `builderFeeTenthsBps`, `mode`) are
  dropped when the config is rewritten.
- Live coins (backing `mainnet` / `testnet`) deposit as paper, like every other coin.

## 2. Mainnet only

- `HyperConfig.apiBase()` returns `https://api.hyperliquid.xyz`.
- Hidden override for development only: the `HYPERCRAFT_API` environment variable or `-Dhypercraft.api=`. It is not
  in the config file or any screen. `/hl status` names the host only when it isn't mainnet.
- `Account.tag()` loses the TESTNET suffix. The testnet banner in the terminal is removed.

## 3. TradFi markets (`xyz:` HIP-3 perps)

- `MarketData` loads `metaAndAssetCtxs` and `allMids` with `"dex": "xyz"`, alongside the main dex. Delisted
  markets are skipped, as now.
- Market names stay the exchange's (`xyz:GOLD`), so candles (`candleSnapshot` with `coin: "xyz:GOLD"`), books and
  funding work unchanged. Lookups also accept the bare ticker when it doesn't clash with a main-dex coin
  (`gold` → `xyz:GOLD`, `aapl` → `xyz:AAPL`). `BTC` still means the main-dex perp.
- Paper margin treats `xyz:` markets like any other perp. Hyperliquid's isolated-only flag on some markets is
  ignored in paper, where every position is margined from the shared balance anyway.
- Terminal: a **Stocks** tab next to Perps and Spot lists `xyz:` markets with a `TradFi` badge, grouped
  Indices / Stocks / Commodities / FX, using a static list of known tickers plus "Other".
- `Asset` gets a `dex` field ("" or "xyz") so the UI can group markets without parsing names.

## 4. Unit tokens

A Unit token is a spot asset that exists only inside HyperCraft and is backed by metal deposited at a UNIT.XYZ
block. It's quoted in USDC and priced at the mid of a reference perp.

| Token | Market name | Priced off | Unit |
|---|---|---|---|
| UGOLD | `UGOLD/USDC` | `xyz:GOLD` | troy oz |
| UCOPPER | `UCOPPER/USDC` | `xyz:COPPER` | lb |

- `MarketData` registers each as a spot `Asset` (`spot = true`, `dex = "unit"`, `apiCoin` = the reference perp).
  Its `Ticker` reuses the reference perp's mid and history. The paper engine therefore buys, sells, values and
  charts Unit tokens with no special cases.
- Unit tokens can be bought and sold for USDC in the terminal's Spot tab like any spot pair, at the usual paper
  slippage and fees. Buying UGOLD with USDC and withdrawing it at a bridge is how trading profits turn into gold.

## 5. UNIT.XYZ block

### 5.1 What it is

A bridge between Minecraft items and an account. Crafted from a gold block, a copper block, an Account Vault and
a Ticker Display; also in the Build tab. Its front shows the live gold price, its sides the copper price.

### 5.2 Which account

1. If a card is inserted (right-click with an account card; sneak + empty hand ejects it), that account.
2. Otherwise the player's selected account (the terminal's ◈ menu).

Deposits only need a card or view access. Withdrawals need `canManage`: the owner, or an op on paper accounts.
Grantees and card holders can't withdraw. This matches the vault.

### 5.3 Deposit

- Right-click holding a bridgeable item deposits the held stack. Sneak + right-click deposits every bridgeable item
  in the inventory.
- **Hoppers** (and any item transfer) can insert into the block. Hopper deposits go to the inserted card's account
  and are refused without a card. A piglin-bartering or gold farm can feed an account this way.
- Each item has a quantity in the token's unit (§5.6). The account is credited with `qty × (1 − fee)` at the
  reference mid. Fee: `unitFeeBps`, default 25 (0.25%), charged on deposit and on withdrawal.
- Refused, with the items left in the player's hand, while the reference market has no price.

### 5.4 Two modes (toggle on the block's screen, remembered per block)

**Spot** (default). Credits the Unit token to the account's spot balances. `spot[UGOLD/USDC] += q`,
`spotCost += q × px`, `netDeposits += q × px`. Equity rises by the value deposited, and the account holds gold
exposure.

**1x long.** Credits the deposit as a fully collateralised long on the reference perp:
`balance += q × px`, `netDeposits += q × px`, then a fee-free fill of `+q` on `xyz:GOLD` at `px`. Equity rises by
the same amount and exposure is the same. The difference is that the position can be traded, levered up or hedged
in the terminal. Depositing into an existing short closes it (physical settlement), and PnL is realised as normal.

### 5.5 Withdraw

The block's screen shows, for each token, the spot balance, the long position, and how much of each can be
withdrawn. Buttons: 1 / 9 / 64 ingots' worth, and **All**. Items come out greedily as blocks, then ingots, then
nuggets (for gold). Anything below one nugget stays in the account. Items the inventory can't hold drop at the
block.

**Spot.** `q ≤ spot balance`. Removes `q`, removes the same share of `spotCost`, and lowers `netDeposits` by `q × px`.

**From a long, prorated by leverage.** A long of size `S` with entry `E` at mark `P` and leverage `L` holds
position equity

    e = S·E/L + S·(P − E)

That is its margin at entry plus its unrealised PnL. At 1x, `e = S·P`: the whole position is the player's gold.
At Nx, only `e / P` ounces are theirs. So:

- At most `kmax = e / P` can be withdrawn.
- Withdrawing `k` closes the fraction `f = k / kmax` of the position: a fee-free fill of `−f·S` at `P` (which
  realises `f·S·(P − E)`), then debits `k·P` from the balance. `netDeposits` falls by `k·P`.
- The remaining position keeps its leverage, and other positions are untouched. The withdrawal is refused if the
  account would end up below maintenance (margin used > equity − spot value). Shorts and other markets can't be
  withdrawn as metal.

Worked check at 1x: `kmax = S` and `f = k/S`. Closing `k` realises `k·(P − E)`, and the balance changes by
`k·(P − E) − k·P = −k·E`, which is exactly the margin released. Nothing is created or destroyed.

### 5.6 Config: `config/hypercraft-unit.json`

Written with defaults on first load; server owners can add pegs.

```json
{
  "feeBps": 25,
  "tokens": [
    {"token": "UGOLD", "priceMarket": "xyz:GOLD", "unit": "oz",
     "items": {"minecraft:gold_nugget": 0.1111111, "minecraft:gold_ingot": 1, "minecraft:gold_block": 9,
               "minecraft:raw_gold": 0.9, "minecraft:raw_gold_block": 8.1, "minecraft:gold_ore": 0.9,
               "minecraft:deepslate_gold_ore": 0.9, "minecraft:nether_gold_ore": 0.4},
     "withdraw": ["minecraft:gold_block", "minecraft:gold_ingot", "minecraft:gold_nugget"]},
    {"token": "UCOPPER", "priceMarket": "xyz:COPPER", "unit": "lb",
     "items": {"minecraft:copper_ingot": 1, "minecraft:copper_block": 9, "minecraft:raw_copper": 0.9,
               "minecraft:raw_copper_block": 8.1, "minecraft:copper_ore": 0.9, "minecraft:deepslate_copper_ore": 0.9},
     "withdraw": ["minecraft:copper_block", "minecraft:copper_ingot"]}
  ]
}
```

Raw metal and ore count for 90% of the refined amount: refining isn't free. A peg can price any item off any market
(e.g. `"minecraft:diamond": 0.0001` of a `BTC`-priced token). The defaults stick to metals that have real markets.

### 5.7 Redstone

- The comparator outputs 15 for one second after a deposit and 8 after a withdrawal.
- The block is also a **signal source** (§6): `ACCOUNT` signal of its card, so a hopper-fed bridge and a Copy Desk
  can share a card.

## 6. Signals

### 6.1 Values

A signal evaluates, once per second, to one of:

- **Scalar**: a number (a price, an APR, a 0–15 redstone level, a 0/1 condition).
- **Portfolio**: target weights, coin → signed fraction of equity (`+0.5` = long half of equity), plus the
  `equity` of whatever it was derived from (USD), so a follower can mirror absolute sizes.

An unavailable input (revoked card, unknown market, no price) evaluates to **none**. Most ops pass none through,
and sinks hold still on none: the Copy Desk doesn't trade, and sensors output 0.

### 6.2 Sources

| Source | Gives | Notes |
|---|---|---|
| Account card (any level, paper or watch) | Portfolio | The account's perp positions (main and `xyz:`) as `notional / equity`. Spot is ignored |
| Signal Card | whatever that signal gives | Composition |
| Op `ADDRESS` | Portfolio | Any mainnet Hyperliquid address, followed read-only. Shares feeds with watch accounts |
| Op `BASKET` | Portfolio | Fixed weights: `BTC:0.5, ETH:0.3, xyz:GOLD:0.2` |
| Op `PRICE` / `CHANGE` / `FUNDING` | Scalar | A market's mid, its % change over N s, or funding APR % |
| Op `METRIC` | Scalar | Any Account Display figure of the input account |
| Op `REDSTONE` | Scalar 0–15 | Strongest signal into the mixer block. Holds its last value while unloaded |
| Op `CONSTANT` | Scalar | |
| Op `MARKET_HOURS` | Scalar 0/1 | 1 while the NYSE is open (§11.1) |

### 6.3 Ops

| Op | Inputs | Result |
|---|---|---|
| `BLEND` | 1–4, each with a weight | Weighted sum of portfolios (weights add; `equity` = weighted mean) or of scalars |
| `SCALE` | portfolio or scalar, optional scalar | × the second input, or × the constant k |
| `INVERT` | portfolio or scalar | × −1 (fade a trader) |
| `FILTER` | portfolio | Keep listed coins, or drop them; longs only / shorts only |
| `TOP` | portfolio | Keep the N largest weights |
| `CAP` | portfolio | Scale down to max gross leverage G, and clip each weight to ±W |
| `DELAY` | any | The value N seconds ago (max 3600) |
| `SMOOTH` | any | Exponential moving average with an N-second half-life |
| `GATE` | any, scalar | The first input while the second is > threshold, else zero (empty portfolio / 0) |
| `COMPARE` | scalar | 1 if ≥ k (or ≤ k), else 0 |
| `MAP` | scalar | Linear lo..hi → 0..1, clamped |
| `PICK` | portfolio | Scalar: one coin's weight, gross leverage, or net leverage |

A portfolio meeting a scalar in `SCALE` / `GATE` is fine. Other type mismatches show "needs a portfolio" on the
block and evaluate to none.

### 6.4 Signal Mixer block

- One block = one signal. The block holds an op, its parameters, and up to 4 **inputs**: right-click with an account
  card or Signal Card adds it to the next free input (shown on the screen with its weight and an eject ✕).
  Only the owner configures it.
- Its screen: op selector, parameter fields, inputs list, live value preview (scalar, or the top 6 weights), and
  **Print card** (costs a blank card, free with the build menu). The printed Signal Card names the signal.
- The block itself also outputs redstone: a scalar maps through `MAP` lo..hi onto 0–15 (default 0..15 directly).
  A portfolio outputs its gross leverage × 5, capped at 15.
- The signal's definition lives in a server registry (`hypercraft_signals.json` in the world), so it keeps evaluating
  when the chunk is unloaded. Breaking the block deletes the signal, and its cards then read "signal gone".
- Cycles are refused when an input is added (a signal can't depend on itself), and evaluation depth is capped at 16.

### 6.5 Access

- Adding an account card as an input requires the card to be valid for that account right now (`checkCard`), and
  that is re-checked on every evaluation. Revoke the card and every signal built on it goes dead.
- A Signal Card is a bearer capability to *read the derived values*, never to trade or to see the underlying
  account. Like a READ card, whoever holds one can use it.
- `ADDRESS` needs `allowWatchAccounts` (default on).

### 6.6 Signals in existing blocks

- **Market Sensor**: new modes `SIGNAL_ABOVE`, `SIGNAL_BELOW`, `SIGNAL_ANALOG` read a Signal Card. They take a
  Signal Card in the card slot instead of an account card. A portfolio reads as gross leverage, or as one coin's
  weight when the coin field is set.
- **Trade Executor**: a new input source `SIGNAL` maps a Signal Card's scalar onto an input's lo..hi range (in
  addition to faces). The card goes in the executor's second slot; right-click with a Signal Card puts it there.
- **Account Display**: given a Signal Card, it shows the signal (scalar, or a weights table).

## 7. Copy Desk block

Drives a paper account toward a signal's target portfolio.

- **Follower**: the TRADE card in the block's card slot. Orders go through `GuardedAccount` as the block's owner
  (grant limits, freeze and the activity log apply, via "copy desk @ x,y,z").
- **Leader**: one of a Signal Card, an account card (paper or watch, copied directly), or an address typed on the
  screen (an implicit `ADDRESS` signal). Right-click with a card: an account card fills the follower slot first if
  it's empty and TRADE-level, otherwise the leader slot. The screen has ✕ to eject either.
- **Settings**

| Setting | Default | Meaning |
|---|---|---|
| Sizing | Proportional | *Proportional*: target notional = weight × follower equity × ratio. *Mirror*: = weight × leader equity × ratio (absolute size) |
| Ratio | 1.0 | |
| Max leverage | 5x | Target scaled down so gross ≤ this |
| Band | 2% of the market's target, min $10 | Ignore differences smaller than this (relative to each market, so a book of many small positions is still copied) |
| Max step | $5,000 | Largest single rebalance order |
| Interval | 10 s | Rebalance period (min 3 s) |
| Drawdown stop | 0 (off) | Pause if equity falls this % from its peak since copying started |
| On lost signal | Hold | *Hold* positions, or *Flatten* copied markets |
| Redstone | Ignore | *Pause while powered* or *Run only while powered* |

- **Rebalance step**: for each coin in target ∪ current perps, `diff = target − current`. If `|diff| ≥ band`, send a
  market order for `sign(diff) × min(|diff|, step)` (reduce-only when it only shrinks a position). Before the first
  opening order on a coin, set that coin's leverage to `clamp(ceil(gross target × 1.25), 1, market max)`. Each step
  places at most 8 orders; the rest wait for the next interval.
- Runs in a server-side registry (like signals), so copying continues while the chunk is unloaded. Breaking the block
  stops it. The registry keeps the last redstone state.
- **Shows**: a floating label ("copying 0x0104… · 4 mkts · tracking 1.2%"), and the comparator outputs tracking
  error (0 = on target, 15 = ≥ 15% of equity off).

## 8. Account and signal rendering

- The **Terminal** header shows a ⧉ badge when a Copy Desk drives the selected account.
- `/hl signals` lists your signals and Copy Desks with their current values.

## 9. Future: remote execution (not built now)

The seam is `TradingAccount`. A later `Account.Kind.REMOTE` will hold `{url, keyId}` and a client that speaks the
**HyperCraft Execution Protocol**: JSON over HTTPS, Bearer key, with `POST /v1/orders`, `POST /v1/orders/{cloid}/cancel`,
`POST /v1/orders/{cloid}/amend`, `POST /v1/cancel-all`, `POST /v1/leverage`, `GET /v1/state` (positions, orders, fills,
equity), and `GET /v1/stream` (SSE) for state changes. The request shapes are `OrderRequest` and the snapshot shape
`TradingAccount.snapshot()`. The player's own server (local or hosted) holds the exchange keys. The only change
needed in the mod today is to keep every write behind `TradingAccount` and every UI behind `snapshot()`, which this
work does.

## 10. The New York Stock Exchange

### 10.1 What gets built (`NyseBuilder`)

Real layout at 1 block = 1 m. North is +Z-negative (Minecraft north), with Broad Street running north–south and
Wall Street east–west:

- **NYSE, 18 Broad Street** (west side of Broad, south of Wall): a granite podium of two storeys with three arched
  entrances; six fluted Corinthian columns about 16 m tall standing in front of a 30 m-wide window wall; the
  entablature lettered **NEW YORK STOCK EXCHANGE**; and a pediment with the 11-figure group around *Integrity*,
  arms outstretched. A giant US flag hangs between the middle columns. Behind it stands **11 Wall Street** (the
  1922 annex), a plain stone block rising higher on the Wall St corner.
- **The trading floor** (the Main Room, about 33 × 43 m, 22 m to a gilded coffered ceiling with a skylight).
  It holds about a dozen **trading posts**, octagonal booths with a ring of Ticker Displays on top showing `xyz:`
  stocks, a terminal at every post, and brokers' desks round the walls. The **Big Board** is a long wall of tickers
  and account displays. The **bell podium** is a balcony over the floor with the opening bell, wired to a
  market-hours sensor (§11.1), and the NYSE banner.
- **Federal Hall** (26 Wall St, north side of Wall facing down Broad): a Doric temple front with 8 columns over a
  broad flight of steps. **George Washington's statue** stands on its pedestal on the steps.
- **23 Wall Street**, the House of Morgan, on the south-east corner: a low marble block with a chamfered corner
  entrance.
- **Fearless Girl**, on Broad St facing the NYSE facade. **Charging Bull** at the south end of Broad St (it
  actually stands at Bowling Green; the district is compressed, and the field guide says so).
- **Trinity Church** spire at the west end of Wall St.
- Streets: Broad St pedestrian plaza with security bollards and planters, Wall St with its granite curbs and lamp
  posts, sidewalks, and closed facades of neighbouring buildings to frame the canyon.

HyperCraft fixtures in the building:

- **Members' vault** in the basement: account vaults and a **UNIT.XYZ** labelled *U.S. Assay Office* (the real
  Assay Office stood next door on Wall St).
- **Signals desk**: a Signal Mixer and a Copy Desk preset to copy a public address (HLP), waiting for a card.
- A **bot lab** of working circuits from the old tower: the opening-bell buyer, a gold breakout, a stop-loss, a
  market-hours gate.
- **Survival essentials** in the members' lounge: beds, chests, crafting table, furnaces, a smithing table and an
  ender chest, so it works as a survival home.

### 10.2 Worlds

- **`./gradlew runClient -Pnyse`**: a small flat world (template `worlds/nyse/level.dat`, superflat at G = 63,
  creative, peaceful, noon, world border 384 m) with the full district. Built on first launch; `-Prebuild` starts
  over; `-Pshots` tours it for screenshots.
- **`./gradlew runClient -Psurvival`**: a normal survival world (template `worlds/nyse-survival/level.dat`, fixed
  seed, normal difficulty). On first launch the mod looks for a **real island** near spawn: it samples the
  generator's surface height on a 16 m grid out to 1,500 m and picks the nearest land mass of 2,500–40,000 m² that is
  ringed by ocean and has room for the NYSE footprint. It builds the NYSE there (levelling a plot and keeping the
  rest of the island natural), then sets world spawn on Broad St and puts the player there. The district on the
  island is the NYSE plus Fearless Girl and the Bull, with no neighbouring buildings.
- **`/hl nyse build`** (op): builds the NYSE at your feet, facing the way you look, in any world.
  **`/hl nyse island`** (op): runs the island search and build in the current world.

Hyperliquid Island is removed: `IslandBuilder`, `IslandDetails`, `TowerFloors`, `IslandDemo`, `worlds/hyperisland`,
`-Pisland`.

## 11. More ways trading touches the game

### 11.1 Market hours and the opening bell

`MarketClock` knows NYSE regular hours (09:30–16:00 America/New_York, Mon–Fri, with the full-day holidays for
2026–2027). New Market Sensor modes: `MARKET_OPEN` (on while open) and `MARKET_BELL` (a 1 s pulse at the open and at
the close). The NYSE's bell podium wires `MARKET_BELL` into a bell, so the bell rings at the real opening and closing
times. A player can also ring the bell by hand at the podium (advancement).

### 11.2 Advancements (a "HyperCraft" tab)

| Advancement | When |
|---|---|
| Open for Business | Open a paper account |
| First Fill | Any order fills |
| Gold Rush | Deposit gold at a UNIT.XYZ |
| Cash Out | Withdraw metal at a UNIT.XYZ |
| Copycat | A Copy Desk places its first order |
| Signal Processing | Build a signal from another signal |
| Rekt | Get liquidated |
| Millionaire | Reach $1,000,000 equity |
| Ring the Bell | Ring the NYSE bell |
| Wall Street Speedrun | Finish a speedrun (below) |

They are granted from code through `minecraft:impossible` criteria.

### 11.3 Speedrun timer

`/hl speedrun start`: records the start time and the player's total equity across owned accounts, and turns off the
paper mint and the starter grant for that player until the run ends. The HUD shows the timer and net worth.
Killing the Ender Dragon finishes the run: chat summary (time, gold bridged in and out, trading PnL) and an entry
in `/hl speedrun top`. `/hl speedrun stop` abandons it. The intended route: mine gold → bridge → trade → withdraw
gold → barter with piglins for pearls → the End.

### 11.4 Liquidation

When a paper account is liquidated, its owner (if online) sees a harmless lightning bolt (visual only), hears
thunder and gets a "MARGIN CALL" title. Rekt is granted.

## 12. Testing

- Unit tests:
  - Unit deposit/withdraw maths in both modes, including leverage proration and the 1x conservation check.
  - Signal ops: every op, none-propagation, cycle refusal, depth cap.
  - Copy Desk rebalance planning: band, step, reduce-only, the leverage cap and proportional vs mirror sizing.
  - `MarketClock` holidays and DST.
  - `xyz:` name resolution.
  - Migration of live accounts to watch.
- In game (dev client): `-Pnyse -Pshots` and `-Psurvival -Pshots` screenshots; a scripted demo deposits gold into a
  UNIT.XYZ, withdraws from a 1x and a 3x long, and runs a Copy Desk following a basket signal.
