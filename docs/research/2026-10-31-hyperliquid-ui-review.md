# Hyperliquid web app (app.hyperliquid.xyz): trading UI feature inventory

Researched 2026-10-31. A gap-analysis reference for the HyperCraft paper-trading mod.

## How this was gathered (read first)

Three kinds of source, from strongest to weakest:

- **[LIVE]**: I loaded the live app in a headless browser, logged out, on 2026-10-31. The build string in Settings was `rev 2026-10-31-a69ea1e19`. I clicked through every order type, modal, tooltip and bottom tab. To see real account data I also used the app's read-only `?hloa=<address>` URL parameter, which shows any public address, on accounts taken from the public leaderboard. Source URL: https://app.hyperliquid.xyz/trade/BTC
- **[STRINGS]**: The app's English UI string table, https://app.hyperliquid.xyz/assets/en-US-CplP9yLy.js. It holds the exact labels and tooltip/explainer text, including screens I could not open without a wallet.
- **[CODE]**: The app's main JS bundle, https://app.hyperliquid.xyz/assets/config-Carig3W2.js. I read the minified source for the size-slider and max-size logic.
- **Docs**: Official GitBook pages at hyperliquid.gitbook.io, cited per fact.
- **3rd-party**: Third-party guides or posts, used only where nothing else exists, and marked as such.

Anything I could not confirm is marked **unverified**.

---

## 1. Account structure

### 1.1 Account modes ("Account Type"), the most important 2026 change
- The order form header has three buttons: **`Cross` | `20x` | `Unified`**. The third one opens the **"Account Type"** modal. [LIVE] https://app.hyperliquid.xyz/trade/BTC
- The modal offers three options, with this exact text: [LIVE]
  - **Unified Account (Recommended)**: "The default account setting where each collateral asset has a separate balance. Perps can only use the settlement asset as collateral, and margining is only shared across cross margin assets with the same collateral asset."
  - **Portfolio Margin**: "For advanced users who want greater flexibility and capital efficiency. All trading is unified across spot and perps. Eligible collateral assets such as HYPE and BTC can be used directly as collateral for perp positions without converting to the settlement asset. Collateral assets earn or pay interest depending on whether they are at rest or actively borrowed to open positions." The modal footer adds: "Account value must be <$25m to use portfolio margin in beta mode."
  - **Manual**: "Only recommended for automated traders. All DEXs have separate balances and cross margin applies separately within each DEX."
- The API names for the modes are `unifiedAccount`, `portfolioMargin`, `disabled` (= Manual/standard), `dexAbstraction` (legacy), and `default` (no choice made yet, which resolves to Unified). [CODE]; https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint ; `default` resolving to Unified is from https://github.com/infinitefield/hypersdk/pull/100 (3rd-party).
- From the docs: Unified gives each asset a single balance that collateralizes all cross positions in that asset and is shared with spot. Portfolio Margin unifies the eligible assets HYPE, BTC, USDC and USDT. In Standard/Manual, perp and spot balances are separate, each DEX has its own balance, and cross margin applies per DEX. DEX abstraction is discontinued. Unified and PM accounts are limited to 50,000 user actions per day. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/account-abstraction-modes
- Under Unified, the **USDC spot balance** is the single source of collateral for validator-operated perps, XYZ (trade.xyz) perps, and spot trading against USDC. The **USDT** balance plays the same role for CASH perps and USDT spot pairs. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/account-abstraction-modes
- **Migration** for existing users is a dialog titled "Account Type Update": "The new default account type is Unified Account. I acknowledge that spot and perps balances will be unified, which means that the entire collateral balance will go towards protecting against cross margin liquidations." The body continues: "Opt out of Unified Account by clicking on the Unified button at the top right of the order form." [STRINGS]
- The enable text reads: "Spot and Perps balances are merged for all tokens, and cross margin is supported on all available assets. IMPORTANT: All USDC collateral will be used as cross margin once Unified Account is enabled." [STRINGS]
- **Dates (3rd-party):**
  - New users defaulted to Unified around Feb 2026: https://x.com/lordjorx/status/2019006772145693046
  - A guide says all new accounts default to Unified, the change applies immediately, and there is no lock-up: https://hyperliquidguide.com/guides/trading/unified-accounts-guide
  - The exact official launch date is **unverified**.
- In live data, Unified accounts are common among leaderboard accounts, and `disabled` (Manual) and `portfolioMargin` also appear. [LIVE] (`userAbstraction` queries on 25 leaderboard addresses)

### 1.2 Spot vs perps balances and the Spot/Perps "Transfer"
- **Manual mode (classic):** perps and spot are separate wallets. The UI strings include:
  - "Spot to Perps Transfer", "Perps to Spot Transfer"
  - "Transfer {token} between your Perps and Spot balances."
  - "Transfer to Perps", "Transfer to Spot"
  - "Available to transfer"
  - Withdraw modal: "If you have USDC in your Spot Balances, transfer to Perps to make it available to withdraw."

  [STRINGS] The API action is `usdClassTransfer` (`toPerp` true/false). https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
- **Unified mode:** there is no Spot↔Perps transfer. On a live Unified account, the Balances table shows **one USDC row**, for example Total 233,997.71 USDC, **Available 0.00 USDC** (all used as cross margin), USDC Value $233,997.70. The only per-row actions were `Send` and `Transfer to/from EVM`. [LIVE] (`?hloa=0x020ca66c…`)
- Tooltip text when hovering USDC available in Unified: "{amount} {tokenName} is available to withdraw or transfer. Some perps may have a larger available to trade amount, which can be seen in the order form for that asset." [STRINGS]
- Manual perps tooltip: "Available balance to open positions ignoring open orders. {withdrawable} {tokenName} is available to withdraw, transfer, or open HIP-3 positions." [STRINGS]
- Other transfer types in the UI:
  - "Transfer to/from EVM" (HyperCore spot ↔ HyperEVM)
  - "Sub-Account Transfer"
  - "Spot ↔ Staking Balance Transfer": "Transfers out of Staking Balance are locked for 7 days."
  - "Send Tokens" ("Send tokens to another account on the Hyperliquid blockchain (HyperCore)")

  [STRINGS]

### 1.3 Margin rules affected by the mode
- Unrealized PnL can only be withdrawn or transferred if `max(initial_margin_required, 0.1 * total_position_value)` remains. "Transfers" here include withdrawals, transfers to spot, and isolated-margin transfers. Initial margin on cross positions cannot be withdrawn. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining
- Margin modes:
  - Cross (default)
  - Isolated
  - **Strict isolated**: margin can't be removed, and it shrinks proportionally as the position closes
  - HIP-3 **"no cross"**: isolated with margin removal allowed

  https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining
- In Manual (non-unified) mode, the error string is: "Cross margin on HIP-3 not supported for current account. Use isolated margin or enable unified account in Settings." [STRINGS]

### 1.4 Portfolio Margin (beta)
- **Eligibility:** master account with more than $5M weighted volume or more than $10k account value, and account value under $25M. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin
- **LTVs:** HYPE 0.65, BTC 0.5. The live Earn page also shows BTC 50.00% and HYPE 65.00%. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin ; [LIVE] https://app.hyperliquid.xyz/earn
- **Borrowing:** borrows happen automatically, up to `balance * oracle * ltv`. The stablecoin borrow rate is `0.05 + 4.75*max(0, util-0.8)` APY. Interest is indexed hourly, and the protocol keeps 10%. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin
- **Liquidation:** an account is liquidatable when the PM margin ratio is above 0.95. There is no market-liquidation phase: the backstop takes over in 20% steps. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin
- **Summary panel labels:** "Portfolio Margin Summary", "Portfolio Margin Ratio", "Portfolio Account Leverage = Total Cross Positions Value / LTV-adjusted Portfolio Value", "PM Cap Used". [STRINGS]
- **Caps:** if supply or borrow caps are hit, the account falls back to non-PM behaviour. Sub-accounts are margined separately. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin

### 1.5 Earn (lend/borrow), new in 2026
- The Earn page has Supply / Withdraw / Borrow / Repay actions, "Your Health Factor", and supply and borrow tables. The supply table columns are LTV, APY, Oracle Price, Liquidation Price, Your Supplied, Your Interest Earned, Total Supplied, and Action. [LIVE] https://app.hyperliquid.xyz/earn
- Live supply APYs: USDC 6.74%, USDT 3.42%. Live borrow APYs: USDC 9.26%, USDT 5.00%. [LIVE]
- Page text: "Supply quote assets like USDC or USDT to earn interest or supply assets like HYPE or BTC as collateral to borrow against…" [STRINGS]
- The Balances table has a **Repay** column. [LIVE]

### 1.6 Sub-accounts
- Up to 10 sub-accounts unlock after $100k volume, plus 1 more per additional $100M volume, to a maximum of 50. They share the master's fee tier, but referral discounts don't apply. API wallets: 3 per master plus 2 per sub-account. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/sub-accounts
- Only the master account can send: "Can only send from master account." [STRINGS]

### 1.7 Vaults
- **Legacy HyperCore vaults:**
  - Leader takes a 10% profit share
  - Lockup is 1 day for user vaults and **4 days for HLP**
  - Withdrawals may close positions with slippage

  https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/vaults/for-vault-depositors-legacy.md ; https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/vaults/protocol-vaults.md
- Vault creation fee: "The vault creation fee is 10,000 USDC. This fee is not refunded even if you close the vault." [STRINGS]
- Leader requirement: "As the leader you must maintain greater than 5% of the shares in the vault and a minimum of 100 USDC in liquidity." [STRINGS]
- Withdrawal behaviour: "To withdraw {X} USDC, which represents {p}% of the vault, {p}% of each position of the vault may be closed at market price if necessary to free up margin." [STRINGS]
- **Newer EVM-tokenized vaults:**
  - Builders can create them on HyperEVM
  - Legacy vaults "do not support HIP-3 or spot trading"

  https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/vaults

### 1.8 HIP-3 builder-deployed perp DEXs (e.g. trade.xyz)
- **Markets:** shown in the market list with a dex tag, for example `S&P500-USDC 50x xyz`. The URL format is `/trade/xyz:SP500` and the page title reads "S&P500 (xyz)". [LIVE] https://app.hyperliquid.xyz/trade/xyz:SP500
- **Banner:** "S&P500-USDC is a perp market deployed independently by xyz. See xyz's Docs for more details. Beware of low liquidity, high volatility, and increased liquidation risk." There is also a "HIP-3 Deployer Disclaimer" modal. [LIVE]/[STRINGS]
- **Order form on an xyz market:** in Unified it showed `Cross | 20x | Unified`, so cross is available. Fees were shown as **0.0090% / 0.0030%** next to a struck-through 0.0450% / 0.0150%. [LIVE]
- **Collateral and margin:** each perp DEX has independent margining, order books and deployer settings. Any quote asset can be collateral. Enabling cross on an asset is irreversible. https://hyperliquid.gitbook.io/hyperliquid-docs/hyperliquid-improvement-proposals-hips/hip-3-builder-deployed-perpetuals
  - In Manual, each DEX has its own balance and its own cross margin.
  - In Unified, cross margin is shared across DEXs with the same collateral token (USDC for core + xyz, USDT for CASH).

  https://hyperliquid.gitbook.io/hyperliquid-docs/trading/account-abstraction-modes
- **Deployers:** must stake 500k HYPE. https://hyperliquid.gitbook.io/hyperliquid-docs/hyperliquid-improvement-proposals-hips/hip-3-builder-deployed-perpetuals
- **Fees:** the deployer can add a 0–300% share. Growth mode cuts fees by 90% or more. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees
- **API:** for an HIP-3 user with no balance, `activeAssetData` returns isolated leverage and `availableToTrade` ["0.0","0.0"]. https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals
- **Other market types in the selector** [LIVE]:
  - "Hyperps" (pre-launch perps), described as "Trading is on low leverage and isolated margin only" [STRINGS]
  - **Outcome markets (HIP-4)**, live since May 2026 according to a 3rd-party guide: https://hyperliquidguide.com/guides/trading/unified-accounts-guide

### 1.9 Deposit / withdraw
- **Deposit:**
  - Wallet users click Connect, then "Enable Trading" (a gas-less signature), then Deposit USDC from Arbitrum.
  - Email users get an address for USDC on Arbitrum, Ethereum, Base or Polygon.
  - Other assets (BTC, ETH, SOL, etc.) come in via Unit addresses.

  https://hyperliquid.gitbook.io/hyperliquid-docs/onboarding/how-to-start-trading
- **Legacy bridge:** minimum deposit is 5 USDC (anything less is lost), credited in under 1 minute. Withdrawals take 3–4 minutes. https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/bridge2
- **Withdrawals:**
  - `withdraw3` carries a $1 withdrawal fee and finalizes in about 5 minutes: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
  - The UI says "Withdrawals should arrive within 5 minutes." [STRINGS]
  - CCTP fee strings: "A {fee} USDC fee will be deducted…" [STRINGS]
- **Account activation:** a one-time "Account Activation Gas" fee of 1 USD-stablecoin. [STRINGS]
- **UI placement:** Deposit and Withdraw buttons sit under the order form. The Portfolio page has Deposit, Withdraw, Send, EVM↔Core, "Account Type", "Swap Stablecoins" and "Link Staking". [LIVE] https://app.hyperliquid.xyz/portfolio

---

## 2. Order entry panel (perps)

### 2.1 Layout (top to bottom) [LIVE]
1. `Cross` | `{lev}x` | `Unified` (margin-mode, leverage, and account-type buttons)
2. Tabs: `Market` | `Limit` | `Pro ▾`
3. Side toggle: `Buy / Long` | `Sell / Short` (spot shows `Buy` | `Sell`)
4. `Available to Trade` (e.g. `0.00 USDC`) and `Current Position` (e.g. `0.00000 BTC`)
5. `Price (USDC)` with a `Mid` button (limit orders only)
6. `Size` input with a unit dropdown (`BTC` ↔ `USDC`)
7. **Slider** with a free-text **`%` box**
8. Checkboxes: `Reduce Only`, `Take Profit / Stop Loss`, and `TIF [GTC ▾]` (limit only)
9. Big button (`Connect` / `Enable Trading` / place order)
10. Info rows: `Liquidation Price`, `Order Value`, `Margin Required`, `Slippage` (`Est: 0% / Max: 8.00%`, market only), `Fees` (`0.0450% / 0.0150%` = taker / maker)

### 2.2 Order types (exact menu) [LIVE]
- Top-level tabs: **Market**, **Limit**
- `Pro` dropdown on perps: **Chase, Scale, Stop Limit, Stop Market, Take Limit, Take Market, Trailing Stop, TWAP**, plus "Learn More"
- `Pro` dropdown on spot: **Chase, Scale, TWAP** only. [LIVE] https://app.hyperliquid.xyz/trade/HYPE/USDC
- Fields per type [LIVE]:
  - **Market**: Size, %, Reduce Only, TP/SL, Slippage row.
  - **Limit**: Price (USDC) + Mid, Size, %, Reduce Only, TIF, TP/SL.
  - **Stop Market / Take Market**: Trigger Price (USDC), Size, %, Reduce Only.
  - **Stop Limit / Take Limit**: Trigger Price (USDC), Price (USDC) + Mid, Size, %, Reduce Only.
  - **Scale**: Size, %, Start (USDC), End (USDC), Total Orders, Size Skew, Reduce Only, TIF. Summary rows show Start, End, Order Value and Margin Required.
    - Up to 100 orders. [STRINGS]
    - Size Skew: "Ratio between end and start prices… if size skew is 2.0 then a limit order for end price will be placed with twice the size… 1.0 creates equally sized orders." [STRINGS]
  - **TWAP**: Total Size, %, "Running Time (5m - 7d)" as Day(s)/Hour(s)/Min(s) (default 30 minutes), Randomize, Reduce Only, Advanced Settings. Summary rows show Runtime and Size per Suborder.
    - Docs: suborders every 30 s or more, max slippage 3% per suborder, Randomize varies size by ±20%, minimum total $100, catch-up suborders up to 3× normal size. Optional Trigger Price and Max/Min Price; in 2026 the Max/Min price changed to act as a limit instead of cancelling (live announcement). https://hyperliquid.gitbook.io/hyperliquid-docs/trading/order-types
  - **Trailing Stop** (perps only, new): Retracement with % presets **5% / 10%**, Size, %, Reduce Only, Activation Price.
    - "When the mark price retraces by {retracement} from the best price recorded since activation, a Stop Market Order will be placed." [STRINGS]
  - **Chase** (new): Chase Price (live best price), Size, %, Reduce Only, Max Chase Distance.
    - Post-only order that re-prices one tick inside the best bid/ask.
    - Runs **in the browser tab only**, with at most 5 active. Max distance is 5% or less of price.

    [STRINGS]; https://hyperliquid.gitbook.io/hyperliquid-docs/trading/order-types

### 2.3 TIF [LIVE]/[STRINGS]
- Dropdown values: **GTC, IOC, ALO**.
- Explainers:
  - GTC: "Order will rest until filled or canceled."
  - IOC: "Any portion that is not immediately filled will be canceled."
  - ALO: "Order will exist only as a limit order on the book. Also known as post-only."
- Market orders are sent as IOC limit orders at a slippage-bounded price; the internal tif is `FrontendMarket`. [CODE]

### 2.4 TP/SL attachment [LIVE]/[STRINGS]
- Ticking "Take Profit / Stop Loss" adds `TP Price` + `Gain [% ▾]` and `SL Price` + `Loss [% ▾]`. The unit dropdown options were not captured (**unverified**; likely % / USD).
- Explainer: "By default take-profit and stop-loss orders apply to the entire position. Take-profit and stop-loss automatically cancel after closing the position. A market order is triggered when the stop loss or take profit price is reached." Also: "If the order size is configured above, the TP/SL order will be for that size no matter how the position changes."
- Optional "TP Limit Price" / "SL Limit Price". Validation: "TP price must be {above/below} than {pivot}".
- TP/SL is triggered by the **mark price** (from the Mark tooltip). [LIVE]

### 2.5 Size input and units [LIVE]/[CODE]
- The Size field has a unit dropdown with **coin (e.g. BTC)** and **USDC** (notional). Switching converts the size using the limit price, or the mark price if there is none. USDC is shown to 2 decimals and coin to the asset's `szDecimals`.
- The choice persists globally (stored in a global setting). [CODE]

### 2.6 Size slider and percentage: the exact behaviour [LIVE]/[CODE]
- **UI:** an unlabeled slider with 5 tick dots at **0%, 25%, 50%, 75%, 100%**, plus a free-text `%` box. There are **no separate percentage buttons**. The `%` box accepts integers only, matched by the regex `^[1-9][0-9]?$|^100$|^0$|^$` (0–100). The slider is continuous. [LIVE] (tick positions measured), [CODE]
- **What 100% means (perps):**
  - Size is calculated as `size = floor(pct/100 × maxTradeSz)`. If the unit is USDC, the result is multiplied by (limitPx or markPx). [CODE `kY`]
  - `maxTradeSz` is `nonLeverageCapMaxTradeSz`, calculated as `floor(availableToTrade[side] / markPx × leverage)` in coin. If the server's `maxTradeSzs[side] × 1.01` is larger, the server value is used instead. [CODE `d8`]
  - **100% = Available to Trade × current leverage, converted to coin at mark price**, so it is roughly the **buying power** for that side.
- **Limit-price adjustment:** if a buy limit is below mark (or a sell limit above mark), max size is scaled by `markPx/limitPx`. [CODE `Ule`]
- **`Available to Trade`** is the per-side `availableToTrade[side]` from the `activeAssetData` API: free margin in USDC for opening that side. The docs example confirms `maxTradeSz ≈ availableToTrade × leverage / markPx`: 37,019,438 × 3 / 4.4716 ≈ 24,836,370. https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals
- **Reduce Only ticked:** 100% = abs(current position) if the order is opposite to the position, otherwise 0. [CODE]
- **Spot:**
  - Buy: 100% = (quote token `total − hold`) / (limitPx or markPx), and Available to Trade is shown in the **quote** token (e.g. `USDC`).
  - Sell: 100% = base token `total − hold`, shown in the **base** token (e.g. `0.00 HYPE`).
  - Leverage is fixed at 1.

  [CODE `d8`, `Z8`], [LIVE]
- **Close-position modals** (Market Close, Limit Close, Trailing) reuse the same slider, defaulting to **100%** of the position. At 99.5% or more they snap to the exact full size. [CODE]

### 2.7 Leverage selector [LIVE]/[STRINGS]
- Clicking `20x` opens "Adjust Leverage": "Control the leverage used for BTC positions. The maximum leverage is 40x." It also says "Max position size decreases the higher your leverage." It has a slider plus a numeric box (`20 x`), a Confirm button, and the warning "Note that setting a higher leverage increases the risk of liquidation."
- The default shown for BTC was **20x** while logged out. Max is 40x.
- Leverage is an integer from 1 up to the max. It can be raised on an open position and is only checked at open. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining
- Clicking `Cross` opens "{coin} Margin Mode" with two choices:
  - **Cross**: "All cross positions share the same cross margin as collateral. In the event of liquidation, your cross margin balance and any remaining open positions under assets in this mode may be forfeited."
  - **Isolated**: "…If the margin ratio of an isolated position reaches 100%, the position will be liquidated. Margin can be added or removed to individual positions in this mode."

  [LIVE]
- Max leverage in the market list examples: BTC 40x, ETH 25x, SOL 20x, HYPE 10x, xyz S&P500 50x, XYZ100 30x, XMR 5x. [LIVE] Tier tables are in §6.

### 2.8 Info rows [LIVE]/[STRINGS]
- **Liquidation Price**: an estimate. If risk is low it shows none: "Position risk is low, so there is no liquidation price for the time being…" If margin is insufficient, the estimate assumes a top-up to initial margin. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/liquidations
- **Order Value**: notional.
- **Margin Required** = notional / leverage. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining
- **Slippage**: `Est: x% / Max: 8.00%`. Clicking opens "Adjust Max Slippage": "Max slippage only affects market orders placed from the order form. Closing positions will use max slippage of 8% and market TP/SL orders will use max slippage of 10%." The Est tooltip reads "Average execution price compared to mid price based on current order book." The **default max is 8%**.
- **Fees**: `taker / maker`, e.g. `0.0450% / 0.0150%`. Discounted fees are shown with the base rate struck through. Clicking opens the Fee Schedule modal (§7).
- **Confirmations:** "Open Order Confirmation" and "Close Position Confirmation" can be toggled in Settings. The onboarding docs mention a "Don't show this again" checkbox. https://hyperliquid.gitbook.io/hyperliquid-docs/onboarding/how-to-start-trading
- **One-way positions:** "Your position on this coin is either short or long. Orders specify a size and direction only; there is no distinction between open and close when placing an order." [STRINGS]
- **Error mapping examples** [CODE]:
  - "Order could not match against any resting orders. Consider increasing max slippage."
  - "This order was rejected because it would exceed the open interest cap."
  - "Order placed exceeds the max position size at your current leverage. Reduce leverage to increase your max position size." [STRINGS]

---

## 3. Spot trading specifics

- **Spot pair page** (`/trade/HYPE/USDC`):
  - Header: Price, 24h Change, 24h Volume (in USDC), **Market Cap**, **Contract** (token address).
  - There are no Mark/Oracle/Funding fields, and no leverage or margin-mode buttons.
  - Order form: `Market | Limit | Pro`, `Buy | Sell`, Available to Trade, Size, %, Order Value, Slippage, Fees `0.0700% / 0.0400%`.
  - There is no Liquidation Price, Margin Required or Reduce Only row.

  [LIVE] https://app.hyperliquid.xyz/trade/HYPE/USDC
- **Which balance spot draws on:** buys use the **spot quote-token balance** (`total − hold`) and sells use the base-token balance. [CODE]
  - In **Manual**, USDC must sit in the Spot wallet. Perps USDC must first be moved with "Perps to Spot Transfer". [STRINGS]
  - In **Unified**, spot and perps share the one USDC balance, so no transfer is needed. Cross perp margin in use reduces what spot can spend. On the live Unified account, Available Balance was 0.00 USDC while the total was $234k. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/account-abstraction-modes ; [LIVE]
- **Spot holdings as perp collateral:**
  - **Manual / Unified:** only the settlement (quote) asset counts, e.g. USDC for USDC perps. HYPE, BTC and similar holdings do **not** collateralize perps ("Perps can only use the settlement asset as collateral").
  - **Portfolio Margin only:** HYPE and BTC count at their LTVs.

  [LIVE]; https://hyperliquid.gitbook.io/hyperliquid-docs/trading/portfolio-margin
- **Balances table columns:** Asset, Total Balance, Available Balance, USDC Value, PNL (ROE %), Send, Transfer, Repay, Contract. There is a "Hide Small Balances" toggle ("Balances under $5 will not be displayed."). [LIVE]/[STRINGS]
  - "Available" = total − hold. Hold is the amount reserved by open orders and, in Unified, by margin. [CODE]
  - Spot PnL (ROE %) is computed against `entryNtl` (the cost basis held by the API). https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint
- **Settlement timing:**
  - HyperCore matches and settles on-chain each block. Median end-to-end latency is 0.2 s (p99 0.9 s). https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/overview
  - There is no documented T+N or lock: bought tokens show in `total`/`available` on the next state update and can immediately be sold, sent or withdrawn. This is **unverified as an explicit doc statement**. It follows from the on-chain order book model and from the UI using only `total − hold`, with no vesting field. [CODE]
  - Exception: HYPE moved from Staking to Spot is locked for 7 days. [STRINGS]
- **Spot fees:** a higher schedule than perps (§7). Spot volume counts **2×** toward the fee tier. Pairs between two quote assets have 80% lower fees. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees
- **Other spot features:**
  - "Swap Stablecoins" (with the warning "The swap will be partially filled if slippage is >1%")
  - "Spot Dusting" setting
  - "Minimum swap value is 10 {token}"

  [STRINGS]/[LIVE]

---

## 4. Bottom tables / tabs

**Tabs (trade page):** Balances, Positions, Open Orders, TWAP, Chase, Trade History, Funding History, Order History. Counts appear in tab names, e.g. "Balances (4)", "Positions (2)". Filter dropdowns: `Side ▾`, `Market ▾`. The Portfolio page adds the tabs **Interest** and **Deposits and Withdrawals**. [LIVE]

Columns [LIVE]:

- **Balances:** Asset | Total Balance | Available Balance | USDC Value | PNL (ROE %) | Send | Transfer | Repay | Contract
- **Positions:** Market (coin + leverage badge, e.g. `ETH 25x`) | Size | Position Value ▾ (sortable) | Entry Price | Mark Price | PNL (ROE %) | Liq. Price | Margin | Funding | **Close All** (header button) | TP/SL
  - Live row example: `ETH 25x | 2,150.0000 ETH | 4,842,316.00 USDC | 2,235.9 | 2,252.2 | +$35,081.19 (+18.2%) | 2,196.9 | $193,496.72 (Cross) | -$640.85 | [Close actions] | -- / --`.
  - The Margin cell shows "(Cross)" or "(Isolated)".
- **Open Orders:** Time | Type | Market | Direction | Size | Original Size | Order Value | Price | Reduce Only | Trigger Conditions | TP/SL (plus a Cancel All header button; "Confirm Cancel All" string [STRINGS])
- **TWAP:** sub-tabs Active / History / Fill History. Columns: Market | Size | Executed Size | Average Price | Running Time / Total | Trigger Price | Max/Min Price | Reduce Only | Creation Time | Terminate
- **Chase:** sub-tabs Active / History. Columns: Market | Size | Executed Size | Average Price | Max Distance | % Completed | Reduce Only | Creation Time | Terminate
- **Trade History:** Time | Market | Direction | Price | Size | Trade Value | Fee | Closed PNL. Filters: Type, Side, Role, Market, Aggregate. "Export as CSV" / "Export More". Closed PNL tooltip: "Closed PNL includes fees and rebates". [STRINGS]
- **Funding History:** Time | Market | Size | Side | Payment | Rate. "Export as CSV".
- **Order History:** Time | Type | Market | Direction | Size | Filled Size | Order Value | Price | Reduce Only | Trigger Conditions | TP/SL | Status | Order ID

Per-row position actions [CODE]/[STRINGS]:

- **Close:**
  - `Limit` ("Close at a limit price"; also "Limit Close at Mid Price", "Hold to Limit Close at Mid")
  - `Market` ("Close at market price", modal "Market Close: This will attempt to immediately close the position", with a size slider defaulting to 100%)
  - `Reverse` ("Close your position and open a position with the same size in the opposite direction.")
  - `Trailing`
  - "Chase Limit Close"
- **Close All:** "This will close all your positions and cancel their associated TP/SL orders." There is a "Hold to Close All Positions" setting.
- **TP/SL cell:** shows `-- / --` with an edit pencil that opens "TP/SL for Position", plus "view orders".
- **Margin:** for isolated positions, add or remove margin ("Decrease the chance of liquidation by adding more margin or remove excess margin to use for other positions"; "Margin available to {add/remove}"). Isolated-only assets: "margin cannot be removed without closing position". The API actions are `updateIsolatedMargin` and `topUpIsolatedOnlyMargin`. https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
- **Column tooltips** [LIVE]:
  - PNL: "Mark price is used to estimate unrealized PNL. Only trade prices are used for realized PNL."
  - Margin: "For isolated positions, margin includes unrealized pnl."
  - Funding: "Net funding payments since the position was opened. Hover for all-time and since changed."
- **Share:** a PnL card with "Share on X". [STRINGS]

---

## 5. Account summary panel (under Deposit / Withdraw)

**Unified (default), title "Unified Account Summary"** [LIVE]:

- **Unified Account Ratio** (gauge + %). Tooltip: "Represents the risk of portfolio liquidation. When the value is greater than 95%, your portfolio may be liquidated."
- **Portfolio Value**
- **Unrealized PNL**
- **Perps Maintenance Margin**. Tooltip: "The minimum portfolio value required to keep your cross positions open"
- **Unified Account Leverage**. Tooltip: "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance."
- Live example: Ratio 50.05%, Portfolio Value $233,646.36, Unrealized PNL $20,451.52, Perps Maintenance Margin $116,941.17, Leverage 22.45x.
- The docs ship `computeUnifiedAccountRatio`, which returns the max over collateral tokens of cross maintenance margin / available balance. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/account-abstraction-modes

**Manual mode labels** (from the string table; not seen live, since the manual-mode account failed to load):

- "Account Equity", with "Spot Account Equity" / "Perps Account Equity" (Account Equity tooltip: "Balance + Unrealized PNL (approximate account value if all positions were closed)")
- "Perps Overview": Balance (tooltip "Total Net Transfers + Total Realized Profit + Total Net Funding Fees"), Unrealized PNL, **Cross Margin Ratio** ("Maintenance Margin / Portfolio Value. Your cross positions will be liquidated if Margin Ratio reaches 100%."), **Maintenance Margin**, **Cross Account Leverage** ("Total Cross Positions Value / Cross Account Value.")
- Exact layout and order are **unverified**.

**Portfolio Margin:** "Portfolio Margin Summary", "Portfolio Margin Ratio", "Portfolio Account Leverage", "PM Cap Used". [STRINGS]

**Withdrawable:** the API field `withdrawable` in clearinghouseState. The UI shows it as "Available to Withdraw" or inside tooltips ("{withdrawable} USDC is available to withdraw or transfer"). https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals ; [STRINGS]

**Portfolio page:**
- Header cards: 14 Day Volume ("View Volume"), Fees (Taker / Maker) per Perps ("View Fee Schedule").
- Scope selector "Perps + Spot + Vaults" and window "30D" with PNL, Volume, Max Drawdown, Total Equity, Trading Equity, Earn Balance.
- Charts: "Account Value" / "PNL" (and "Perps PNL").

[LIVE] https://app.hyperliquid.xyz/portfolio

---

## 6. Liquidation and margin rules

- **Initial margin** = notional / leverage. **Maintenance margin** = half of initial margin at max leverage. That ranges from 1.25% (40x) to 16.7% (3x). https://hyperliquid.gitbook.io/hyperliquid-docs/trading/liquidations ; https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining
- **Tiered maintenance:** `MM = notional × mmr(tier) − deduction(tier)`, with `deduction_n = deduction_{n-1} + lowerBound_n × (mmr_n − mmr_{n-1})`. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margin-tiers
- **Mainnet margin tiers** (notional; max leverage), https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margin-tiers:

  | Asset(s) | Tier 0 | Tier 1 |
  |---|---|---|
  | BTC | 0–150M: 40x | >150M: 20x |
  | ETH | 0–100M: 25x | >100M: 15x |
  | SOL | 0–70M: 20x | >70M: 10x |
  | XRP | 0–40M: 20x | >40M: 10x |
  | AAVE, ADA, APT, AVAX, BCH, CRV, DOGE, ENA, FARTCOIN, HYPE, kBONK, kPEPE, LINK, LTC, NEAR, PUMP, SUI, TRUMP, UNI, WLD, ZEC | 0–20M: 10x | >20M: 5x |
  | ARB, BNB, DOT, JUP, kSHIB, MKR, ONDO, PAXG, TON, TRX, XPL | 0–3M: 10x | >3M: 5x |

- **Max order values by max leverage:** 25x or more: $30M market order; 20–25x: $5M; 10–20x: $2M; under 10x: $500k. Limit orders get 10× those amounts. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/contract-specifications
- **Cross vs isolated:**
  - Cross liquidates when account value is below the summed MM. The cross liq price does **not** depend on leverage.
  - Isolated only considers its own margin, and its liq price depends on leverage.
  - Formula: `liq_price = price − side × margin_available / position_size / (1 − l × side)`, where `l = 1/maintenance_leverage`.

  https://hyperliquid.gitbook.io/hyperliquid-docs/trading/liquidations
- **Liquidation sequence:**
  - First, market orders are sent to the book. The user keeps any remaining collateral, and there is no clearance fee.
  - Positions above $100k: only 20% goes to the book, followed by a 30 s cooldown.
  - **Backstop**: if equity falls below 2/3 of MM, the liquidator vault (part of HLP) takes over and MM is not returned. In cross, all cross positions and margin transfer; in isolated, only that position.

  https://hyperliquid.gitbook.io/hyperliquid-docs/trading/liquidations
- **Fill labels in history** [STRINGS]:
  - "Market Order Liquidation"
  - "Backstop Liquidation: …taken over by the protocol at a clearance price of {px}"
  - auto-deleveraging: "Your position was auto-deleveraged because the opposite side could not be liquidated."
- **Mark price** = median of three inputs:
  1. oracle + 150 s EMA of (mid − oracle)
  2. median(best bid, best ask, last trade)
  3. weighted median of CEX perp mids (Binance 3, OKX 2, Bybit 2, Gate 1, MEXC 1)

  The oracle is a weighted median of CEX spot prices, updated about every 3 s. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/robust-price-indices.md
- Mark is used "for margining, computing unrealized PNL, liquidations, and triggering TP/SL orders". [LIVE tooltip]

---

## 7. Fees

- **In-app Fee Schedule modal** [LIVE]: "Staking Discount / HYPE Staked: 0% / 0 HYPE" ("Staking discounts are updated at the end of each UTC day").

  | Tier | 14D Volume | Perps Taker | Perps Maker | Spot Taker | Spot Maker |
  |---|---|---|---|---|---|
  | 0 | ≤ $5M | 0.0450% | 0.0150% | 0.0700% | 0.0400% |
  | 1 | > $5M | 0.0400% | 0.0120% | 0.0600% | 0.0300% |
  | 2 | > $25M | 0.0350% | 0.0080% | 0.0500% | 0.0200% |
  | 3 | > $100M | 0.0300% | 0.0040% | 0.0400% | 0.0100% |
  | 4 | > $500M | 0.0280% | 0.0000% | 0.0350% | 0.0000% |
  | 5 | > $2B | 0.0260% | 0.0000% | 0.0300% | 0.0000% |
  | 6 | > $7B | 0.0240% | 0.0000% | 0.0250% | 0.0000% |

- **Maker rebate tiers** by 14D maker volume share: >0.5% earns −0.001%, >1.5% earns −0.002%, >3.0% earns −0.003%. [LIVE]
- **Modal footnotes:** "HIP-3 perp fees are set by the deployer and can be up to 90% lower than the base rate." "Spot pairs between two quote assets have 80% lower fees and rebates." "Perp, spot, and outcome volume are combined, and spot and outcome volume count double toward your fee tier." "Staking discounts don't apply to maker rebates, and negative fees are rebates." [LIVE]
- **Tier mechanics:** tiers use rolling 14-day volume, assessed at the end of each UTC day. Sub-accounts share the master's tier. Rebates are paid continuously to the trading wallet. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees
- **Staking discount by HYPE staked:** Wood >10 HYPE: 5%; Bronze >100: 10%; Silver >1k: 15%; Gold >10k: 20%; Platinum >100k: 30%; Diamond >500k: 40%. Staking linking is permanent. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees
- **Referrals:** referrers earn 10% of referees' fees (on the first $1B of volume). Referees get a 4% discount (on the first $25M). Creating a code requires $10k of volume. Vaults and sub-accounts are not eligible. https://hyperliquid.gitbook.io/hyperliquid-docs/referrals
  - Referrals page: Enter Code, Create Code, Claim Rewards, Traders Referred, Rewards Earned, Claimable Rewards. [LIVE] https://app.hyperliquid.xyz/referrals
- **"Fee trial":** "Fee trials can be activated once every 90 days…" Rewards are claimable if the trial tier is reached within 14 days. [STRINGS]
- **How fees are charged:** the fee is deducted in the quote/collateral asset on each fill and shown per fill in Trade History ("Fee" column). Closed PNL includes fees. [LIVE]/[STRINGS] The exact token charged for spot buys (base vs quote) is **unverified**.
- **The order form fee row** shows the user's effective taker/maker rate. Discounted rates show next to the struck-through base rate (seen on the xyz market and on high-tier accounts, e.g. `0.0196% / 0.0000%` beside `0.0280% / 0.0000%`). [LIVE]

---

## 8. Funding

- **Hourly payments:** each hour pays one eighth of the 8-hour rate. `F = avg premium + clamp(interest − premium, −0.0005, 0.0005)`. Interest is 0.01% per 8 h. Premium is sampled every 5 s. The cap is **4% per hour**. Payment = size × **oracle** price × rate, peer-to-peer with no fee. https://hyperliquid.gitbook.io/hyperliquid-docs/trading/funding
- **Header:** `Funding / Countdown` shows the current (predicted) hourly rate and an mm:ss countdown to the next hour, e.g. `0.0013%  00:34:58`. Tooltip: "The hourly rate at which longs pay shorts (if negative, shorts pay longs). There are no fees associated with funding…" [LIVE]
- **Market selector** shows an **"8h Funding"** column, e.g. BTC 0.0100%, so the hourly rate is shown ×8 there. [LIVE]
- **Chart area** has a `Funding` tab next to `Chart` (funding history chart). [LIVE] The positions Funding column shows net since open. Funding History tab columns are listed in §4.
- **Oracle tooltip:** "The median of external prices reported by validators, used for computing funding rates". [LIVE]

---

## 9. Other UX

- **Header nav:** Trade, Portfolio, Earn, Vaults, Staking, Referrals, Leaderboard, More ▾, plus Connect, a language globe, and a Settings gear. [LIVE]
- **Market header (perps):** symbol + max-leverage badge, Mark, Oracle, 24h Change, 24h Volume, Open Interest (tooltip "Two sided-open interest…"), Funding / Countdown. [LIVE]
- **Market selector** (⌘K) [LIVE]:
  - Search box with a `Strict` | `All` toggle
  - Category tabs: All, Perps, Spot, Crypto, Tradfi, HIP-3, Trending, Pre-launch
  - Columns: Market, Last Price, 24h Change, 8h Funding, Volume ▾, Open Interest
  - Star to favourite
  - Footer shortcuts: **⌘K Open, ↑↓ Navigate, Enter Select, ⌘S Favorite, Esc Close**
  - No other global keyboard shortcuts were found (**unverified**)
- **Chart:**
  - TradingView charting library v28.5, with intervals 5m / 1h / D and a dropdown, plus Indicators, drawing tools, and range buttons 5y/1y/6m/3m/1m/5d/1d. [LIVE]
  - Settings: "Show Buys and Sells on Chart", "Show PNL". [LIVE]
- **Order book** [LIVE]:
  - Tabs: `Order Book` | `Trades`
  - Price-grouping dropdown: ETH offered 0.1 / 0.2 / 0.5 / 1 / 10 / 100; BTC defaulted to 1
  - Size unit dropdown: coin or USDC
  - Columns: Price, Size (coin), Total (cumulative)
  - Middle row shows Spread value and %
  - ⋮ menu for layout: Tab / Stacked / Large
  - Strings for "Show both sides / bids only / asks only" [STRINGS]
  - Settings: "Set Order Book Size on Click" ("Clicking a level on the order book always sets the limit price. When this setting is active, the order size will also be set."), "Animate Order Book" [LIVE]/[STRINGS]
- **Settings (gear)** [LIVE]:
  - **Trading:** Persist Trading Connection, Open Order Confirmation, Close Position Confirmation, Hold to Close All Positions, Transaction Delay Protection, Spot Dusting, Set Order Book Size on Click, Animate Order Book
  - **Display:** Show Buys and Sells on Chart, Show PNL, Layout Edit / Reset
  - **Notifications & Sounds:** Show Background Fill Notifications, Play Sound for Fills, Show All Warnings, Display Verbose Errors
- **Toasts:**
  - Success and error toasts last 6 s; loading toasts last up to 300 s. [CODE]
  - Messages include "Order filled", "Limit filled", "Market order placed", "Limit orders placed", "TWAP order sent", "Trailing stop order sent", "Chase order placed", "Transfer success.", "Deposit confirmed. Balance will update on the next block." [STRINGS]
  - An "Announcements" panel (bottom-right) lists new features. On 2026-10-31 it showed "TWAP max/min price update", "New listing: XTEN-USDC hyperp", and "New order type: Trailing stop". [LIVE]
- **Vaults page:**
  - Total Value Locked
  - Filter "Leading, Deposited, Others" and window 30D
  - Tables "Protocol Vaults" and "User Vaults" with columns Vault, Leader, APR, TVL, Your Deposit, Age (days), Snapshot
  - Pagination

  [LIVE] https://app.hyperliquid.xyz/vaults
- **Staking page:**
  - Totals: Total Staked (~440M HYPE live), Your Stake, Staking Balance, Available to Transfer to Staking Balance, Available to Stake, Pending Transfers to Spot Balance
  - Validator table: Name, Description, Stake, Your Stake, Uptime, Est. APR (~2.2%), Status, Commission
  - Tabs: Staking Reward History, Staking Action History

  [LIVE] https://app.hyperliquid.xyz/staking
- **Leaderboard:** excludes accounts under 100k USDC account value and under 10M volume. "ROI = PNL / max(100, starting account value + maximum net deposits)". [STRINGS]
- **Bottom status bar:** "Online" / "Offline" connection indicator; Docs, Support, Terms, Privacy Policy. [LIVE]
- **Mobile:**
  - The web app is responsive: the order form gets abbreviated labels such as "Avail. to Trade". [CODE]/[STRINGS]
  - PWA install instructions, "Enable App Lock", "Push Notifications", and a QR code for mobile login ("The QR code enables mobile trading, but not deposits and withdrawals"). [STRINGS]
  - An official Android app (MVP) reportedly launched on Google Play around April 2026, with no iOS app as of mid-2026. This is 3rd-party only, **unverified**: https://www.tekedia.com/hyperliquid-unveils-mvp-version-of-its-android-mobile-app/ , https://hyperliquidguide.pages.dev/guides/getting-started/hyperliquid-mobile-app-2026
- **API wallets / builder codes:** "API wallets (also known as agent wallets) can perform actions on behalf of an account without having withdrawal permissions." Builder code approvals are managed in the UI. [STRINGS]

---

## Unverified / gaps
- The TP/SL Gain/Loss unit dropdown options (presumably % and USDC).
- The exact Manual-mode account summary layout (labels come from the string table only).
- The official date the Unified default launched (3rd-party says ~Feb 2026; a guide published Mar 2026 describes it).
- Whether spot fees are charged in the received asset or the quote asset.
- The full list of global keyboard shortcuts beyond the market selector.
- What the order-confirmation modal contains (it needs a connected wallet).
