# Paper account vs Hyperliquid: gap analysis and plan

Status: implemented, P0 on 2026-10-31 and P1 to P3 on 2026-11-01; what's left out is listed under "Left out on purpose" · Date: 2026-10-31

The reference is [the Hyperliquid UI review](../research/2026-10-31-hyperliquid-ui-review.md). It is a feature
inventory of app.hyperliquid.xyz as it stood on 2026-10-31 (build `rev 2026-10-31-a69ea1e19`), taken from the live
app, its label file, its code bundle and the official docs. Section numbers like "§2.6" point into it.

HyperCraft is a game first. "Gap" means Hyperliquid works differently. Some gaps are deliberate: real-world plumbing
(EVM, staking, referrals) and some differences that make it more fun as a game (money earned from goods,
trading only at a terminal block). Those are listed as **won't do**, with the reason.

## What prompted this

A play session turned up two problems:

1. **Size buttons were fixed dollar amounts** ($25 / $100 / $500 / $1K), unrelated to the account's balance.
   Hyperliquid sizes orders as a share of **Available to Trade × leverage** (§2.6).
2. **Spot and perps weren't kept apart.** Selling goods bridged in at UNIT.XYZ paid USDC straight into perp margin.
   Hyperliquid now offers three account types (§1.1):
   - **Unified** (the default since early 2026): one USDC balance for spot and cross perps. This is what HyperCraft
     did by accident.
   - **Manual**: separate spot and perps balances, with a Spot⇄Perps Transfer.
   - **Portfolio Margin** (beta): spot assets count as collateral at a discount.

## Summary of gaps

Legend: ✅ done · **P0** to **P3** the priority it was built at · ✖ won't do. The Plan column keeps what each row asked for.

| # | Area | Hyperliquid | HyperCraft before | Plan |
|---|---|---|---|---|
| 1 | Account types | Unified (default), Portfolio Margin, Manual (§1.1) | One balance, no choice | **P0** ✅ Manual (default) and Unified; PM is P3 |
| 2 | Spot/Perps transfer | Manual: Spot→Perps / Perps→Spot; only free perps USDC moves (§1.2, §1.3) | None | **P0** ✅ Transfer screen |
| 3 | Spot draws on | Spot quote balance (Manual) or the unified balance minus margin (Unified) (§3) | Perp balance | **P0** ✅ |
| 4 | Spot proceeds | Credit the spot side; perps needs a transfer in Manual | Credited perp margin | **P0** ✅ |
| 5 | Size by % | Slider 0–100 (ticks 25/50/75) + % box; 100% = available × leverage ÷ mark; spot buy = quote balance, spot sell = base balance; reduce-only = position (§2.6) | $25/$100/$500/$1K | **P0** ✅ 10/25/50/75/Max of buying power; spot sell and reduce-only rules are P1 |
| 6 | Available to Trade | Shown above Size, per side (§2.1) | Only "Free" in the footer | **P0** ✅ beside Size, click to transfer; per-side value P1 |
| 7 | Side toggle | Buy/Long ⇄ Sell/Short toggle, one button (§2.1) | Two buttons, no side state | ✅ **P1**: needed for the spot-sell % rule and per-side available |
| 8 | Size unit | Coin ⇄ USDC dropdown, remembered (§2.5) | USD only | ✅ **P1** |
| 9 | Maintenance margin and liquidation price | MM = half of initial margin at max leverage, tiered; liq price shown on the order form and per position (§6, §2.8) | No MM: liquidates everything at perp equity ≤ 0; no liq price | ✅ **P1**: the biggest realism gap |
| 10 | Partial liquidation | To the book first, 20% steps over $100k, then backstop below 2/3 MM (§6) | All positions closed at once | ✅ **P1** (with #9) |
| 11 | Isolated margin | Real isolated margin, add/remove; liq on its own margin (§2.7, §4) | Flag stored; everything margins from the shared balance; adjustMargin refuses | ✅ **P1** |
| 12 | Order form info rows | Liquidation Price, Order Value, Margin Required, Slippage est/max, Fees taker/maker (§2.8) | Margin + size estimate line | ✅ **P1** |
| 13 | Slippage | Max 8% on form orders (adjustable), 8% on closes, 10% on market TP/SL (§2.8) | 1% server config | ✅ **P2**: 8% default plus a per-player setting |
| 14 | Spot fees | Taker 0.07% / maker 0.04%; spot volume counts double (§7) | Perp rates on spot | ✅ **P1**: one-line change |
| 15 | Fee tiers and staking discounts | 7 volume tiers, staking discounts (§7) | Flat | ✅ **P3**: fun as a goal, but needs 14-day volume tracking |
| 16 | Positions table | Market+lev, Size, Position Value, Entry, Mark, PnL (ROE%), Liq, Margin (Cross/Iso), Funding since open, Close (Limit/Market/Reverse), TP/SL (§4) | Coin, Size, Entry, Mark, uPnL, Lev, × close | ✅ **P1**: Value, ROE%, Liq, Margin, Funding; **P2**: Limit close, Reverse, TP/SL edit |
| 17 | Open Orders table | Time, Type, Market, Direction, Size, Original Size, Order Value, Price, Reduce Only, Trigger, TP/SL, Cancel All (§4) | Most of it | ✅ **P2**: Original/Order Value, Trigger column |
| 18 | Trade History | Price, Size, Trade Value, **Fee**, Closed PNL (incl. fees); filters; CSV (§4) | Fills (last 40): time, coin, size, price, PnL | ✅ **P2**: fee + value columns, 200-deep history |
| 19 | Order History | Every order with status and id (§4) | None (fills only) | ✅ **P2** |
| 20 | Funding history | Time, Market, Size, Side, Payment, Rate (§4) | Time, coin, payment, rate | ✅ **P2**: size/side |
| 21 | Account summary | Unified: account ratio, portfolio value, uPnL, perps MM, account leverage. Manual: spot/perps equity, cross margin ratio, MM, cross account leverage (§5) | Equity, Return, uPnL; footer Free / In use | ✅ **P1** with #9: margin ratio, MM, account leverage; spot/perps equity |
| 22 | Order types | Market, Limit, Stop Market/Limit, Take Market/Limit, Scale, TWAP, Trailing Stop, Chase (§2.2) | Market, Limit, Stop, TP, TWAP | ✅ **P2**: Stop/Take **Limit**, Scale; **P3**: Trailing Stop; ✖ Chase (it runs in the browser tab) |
| 23 | TP/SL | By price or gain/loss %; limit TP/SL optional; triggered by mark (§2.4) | By % only, market | ✅ **P2**: price entry |
| 24 | TWAP | 5m–7d, randomize ±20%, catch-up up to 3×, min $100, optional max/min price (§2.2) | 5m–24h, randomize, no catch-up | ✅ **P3** |
| 25 | Mark price | Median of oracle-EMA, book, CEX mids (§6) | Mid | ✅ **P2**: use Hyperliquid's own `markPx` for PnL, margin, liquidation and triggers: it's in the `metaAndAssetCtxs` data `MarketData` already fetches, just not read |
| 26 | Funding | Hourly on **oracle** price, 4%/h cap, countdown in the header (§8) | Hourly on mid, no countdown | ✅ **P2**: countdown in the market header; `oraclePx` comes in the same `metaAndAssetCtxs` data, so no new feed is needed |
| 27 | HIP-3 dexes (trade.xyz) | Own margining per dex in Manual; shared by collateral in Unified (§1.8) | One margin pool for all | ✅ **P2**: in Manual, xyz positions margin from their own balance |
| 28 | Margin tiers | Max leverage falls above tier notional (§6) | Max leverage only | ✅ **P3**: only matters at sizes paper players rarely reach |
| 29 | Withdraw rule | uPnL withdrawable only above max(IM, 10% of position value) (§1.3) | Free = equity − margin | ✅ **P2** |
| 30 | Portfolio page | Account value / PnL charts, volume, max drawdown (§5) | Sparkline on Account Display, Return in header | ✅ **P3**: a Portfolio tab |
| 31 | Settings | Order/close confirmations, hold-to-close-all, sounds (§9) | Large-order confirm only | ✅ **P3** |
| 32 | Keyboard | ⌘K market search, ⌘S favourite (§9) | B / S to buy / sell, search box | ✅ **P3** |
| 33 | Deposit / withdraw | USDC bridge, Unit for BTC/ETH/SOL… (§1.9) | UNIT.XYZ for goods; coins | ✖ by design: money is earned |
| 34 | Sub-accounts, vaults, staking, referrals, Earn | §1.5–1.7, §7, §9 | Several accounts per player, sharing | ✖ for now: sharing and copy desks already cover the play value; vaults could come back as "funds" later |
| 35 | Settlement | Spot settles in the block, tradeable at once (§3, unverified as a doc statement) | Instant | ✅ same: once bought, a token is yours to trade |

On #35: the spot sale you made was tradeable at once, and on Hyperliquid it is too. What felt wrong was that the
**proceeds** were perp margin with no transfer. Manual mode (#1 to #4) fixes that.

## P0: in this change

- **Account types (#1 to #4).** `PaperAccount.State` gains `spotUsdc` and `unified`.
  - **Manual (default):** perps USDC (`balance`) is margin, and deposits and withdrawals land on that side, as on
    Hyperliquid. Spot buys spend `spotUsdc` and spot sales pay into it.
  - **Transfer:** `transfer(usd, toPerps)` moves USDC either way. Only free perps USDC can move to spot, never margin
    held by positions.
  - **What counts as collateral:** `perpEquity()` is perps USDC plus open PnL, and margin checks, leverage changes and
    liquidation use only that. Spot holdings and spot USDC are never perp collateral.
  - **Unified:** one balance, as Hyperliquid's default has it. Spot buys spend the USDC not held as margin, and sales
    pay straight in.
  - **Switching:** Manual → Unified merges spot USDC into the one balance.
  - **Packets:** `account_type` (owner only) switches type, and `spot_transfer` moves USDC (anyone with trade access).
  - **UI:** the terminal has a Transfer screen (account type, direction, amount, Max), reached from the Balances tab's
    **⇄ Transfer** chip and from the ⇄ beside Size. Balances lists spot USDC and perps USDC first.
- **Size by % (#5, #6).** The order panel's size buttons are **10% · 25% · 50% · 75% · Max** of buying power:
  - Perps: free perps USDC × the coin's leverage, less the fee.
  - Spot: spendable spot USDC, less the fee.

  Hyperliquid uses a slider with 25/50/75 ticks; a row of chips works better in a block UI. Beside Size, the panel
  shows what's available (`free $X ⇄` / `spot $X ⇄`), and clicking it opens Transfer.

Tests: `PaperAccountTest` covers spot needing spot USDC, a sale paying spot only (so a transfer is needed before it
can margin a perp), margin in use not moving, and a Unified account sharing one balance.

Verified in game by the UI tour (both GUI scales, 0 layout issues):
- **Terminal:** a buy 30 blocks from any terminal is refused (ETH +0); the same buy at a terminal fills.
- **Spot and perps:** spot USDC and perps USDC are reported separately.
- **Survival, crafting and money:**
  - With advancements and recipes revoked, picking up a copper ingot unlocks HyperCraft's recipes (7 of 7 checked:
    terminal, vault, UNIT.XYZ, ticker, card, executor, sensor).
  - The Build tab's free-block request is refused (+0 executors), and so is the paper mint (+0 coins).
  - `canBuild` is false, and there's no starter grant.
- **Screens:** the Transfer screen in both account types, the survival Build tab with a recipe on hover, and the
  order panel's % sizes.

## P1 to P3: as built

**Margin and liquidation (#9, #10, #21, #28).** `MarginMath` holds the sums, shared with the order form's estimates.
- Maintenance margin is half of initial at max leverage, tiered from §6's table (a market with no published tiers has
  one rate). Initial margin uses the lower of the player's leverage and the position's tier.
- `PaperAccount.liquidate` runs once a second. At 100% margin ratio, market orders close positions, largest maintenance
  margin first, stopping once the account is healthy. Over $100k they go a fifth at a time with a 30 s cooldown. The
  player keeps what's left. Under 2/3 of maintenance margin the backstop takes the positions at mark, with the cross
  margin. Fills are labelled "Market Order Liquidation" or "Backstop Liquidation". A partial step says "Margin call"; the
  lightning and title are kept for the real thing.
- Liquidation price is §6's formula, shown per position, on the chart and as an estimate on the order form.

**Isolated margin (#11).** `Pos.margin` is set aside within its pool's USDC, so `perpEquity` still counts it. An isolated
position liquidates on margin plus its own PnL, and its loss stops there. `adjust_margin` adds free USDC or takes margin
above initial. Switching an open position between cross and isolated works if the account can cover it.

**Prices and funding (#25, #26).** PnL, margin, liquidation and triggers run on Hyperliquid's `markPx`, falling back to the
mid when the poll is over 30 s old. Fills are still at the mid. Funding pays on `oraclePx`, capped at 4% an hour, and the
chart header counts down to it.

**Dex balances (#27).** In Manual, trade.xyz margins from `dexUsdc`, moved with `dex_transfer` from the Transfer screen. Each
pool liquidates alone. Old saves and switches back to Manual fund open dex positions from the one balance, so they aren't
left without collateral. Unified shares one balance.

**Withdrawing (#29).** `withdrawable` is the larger of free cash not counting profit, and account value above
max(initial margin, 10% of position value). `available` is what new margin can use, and includes open profit.

**Portfolio Margin (P3).** From $10,000 of account value, on top of Unified. Spot HYPE counts at 65% and BTC at 50%, USDC can
go negative against them, and collateral can't buy spot or leave. Over a 95% ratio the backstop takes positions a fifth at
a time, then sells collateral if the debt is still uncovered.

**Fees (#14, #15).** `FeeSchedule` has §7's table: spot and perp rates, seven tiers on rolling 14-day volume with spot counted
double. Reaching a tier says so. `paperTakerFee` scales the whole table.

**Order types (#22, #23, #24).** Stop Limit and Take Limit rest at a limit once triggered. Scale spreads 2 to 100 limits with
skew, and places nothing if any leg fails. Trailing Stop follows the mark's best, with an optional activation price. A TWAP
runs up to 7 days with a $100 minimum and an optional limit, catching up at most 3× a normal slice. Brackets take a price
or a %. A position's own TP/SL covers it as it grows and shrinks, and goes when it closes or flips. Blocks and tickets
stay on Market, Limit, Stop, TP and TWAP (`Type.BLOCK_TYPES`).

**Slippage (#13).** 8% by default (`HyperConfig.version` 2 moves files still on 1%), set per player in ⚙. Closes use 8%, market
TP/SL 10%. A paper market order always slips a fixed 0.02%, so the setting only refuses orders under that, with
Hyperliquid's own message.

**The terminal (#6, #7, #8, #12, #16 to #20, #30 to #32).**
- Order form: Buy/Sell toggle with one button; Market, Limit, Pro ▾; size in USD or coin; per-side % (§2.6); info rows.
- Positions: Value, PnL (ROE), Liq., Margin, Funding and TP/SL as width allows, the rest on hover. ⋯ opens `PositionScreen`:
  market close by share, limit close, Reverse, TP/SL by price, isolated margin. Close All (a second click or a hold).
- Open Orders: Original, Order Value, Trigger. Fills: Value, Fee, Closed PnL including fees, 200 rows. Order History behind
  a chip on the Fills tab. Funding: Size, Side.
- Chart panel: an **Account** view with margin ratio gauge, maintenance margin, leverage, fee tier, volume, drawdown, and
  a value or PnL chart over an hour or a month.
- `SettingsScreen` (⚙): max slippage, confirmations, hold to Close All, sounds. Ctrl+K searches, Ctrl+S stars a market.

Tests: `PaperMarginTest` and `PaperOrderTypesTest`.

## Left out on purpose

These differ from Hyperliquid and aren't planned:
- **Borrow interest and supply caps** in Portfolio Margin: there's no lending market behind a paper account.
- **HIP-3 deployer fees**: xyz markets charge the standard schedule, not their discounted rates.
- **Staking discounts, maker rebates and referrals**: no staking to discount from (#34).
- **Estimated slippage from the book**: paper fills don't walk the book, so the row shows the fixed 0.02%.
- **Limit TP/SL on a bracket, Trailing close, CSV export and history filters**: Stop Limit and Take Limit cover the first,
  and the rest didn't earn their space in a block UI.
- **Margin tier tables** are the ones in the review (§6), not fetched live. Markets not listed there have one tier.
## Gameplay changes made alongside (same play session)

These aren't Hyperliquid gaps. They're the other feedback from the session:

- **Trading only at a Trading Terminal.** H no longer opens a terminal: it opens the build menu (`BuildMenuScreen`), which crafts blocks from the inventory anywhere and has no trading in it. The server accepts hand-trading packets (`order`,
  `cancel`, `cancel_all`, `set_leverage`, `twap_cancel`, `schedule_cancel`, `adjust_margin`, `spot_transfer`) and
  `/hl buy|sell|long|short|close` only from a player within 8 blocks of the terminal they opened. Walking away closes
  the screen. Blocks (executors, kill switches, copy desks) still trade on their own.
- **Crafting.** Every recipe unlocks in the recipe book as soon as the player picks up copper, iron, gold, paper or
  redstone. The first blocks need no Nether goods: glowstone is out of the Ticker Display and Blank Card recipes, and
  UNIT.XYZ takes a gold ingot instead of a gold block. The terminal's Build tab shows survival players each block's
  crafting grid on hover. Free blocks are for creative players only, or servers that set `buildMenu`; being an op no
  longer counts.
- **Money is earned.** There is no starter grant and no paper mint by default (`paperStarterGrant` 0,
  `paperFaucet` false). Configs still on the old defaults move over (`HyperConfig.version` 1). `/hl mint` is an op
  tool. The Fund screen leads with UNIT.XYZ, and the welcome screen tells you to craft a vault and a UNIT.XYZ.
