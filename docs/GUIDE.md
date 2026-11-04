# Stonkcraft guide

Everything the mod does, block by block. For the overview, see the [README](../README.md).

## Blocks, items and screens

| Thing | What it does |
|---|---|
| **Trading Terminal** (block) | **Stocks** (stocks, indices and commodities: AAPL, NVDA, TSLA, SP500, GOLD, SILVER, oil, gas, FX…), **Perps** (the feed's other futures markets) and **Spot** tabs with a ★ watchlist. **Chart / Book / Funding / Account** views: live candles (1m–1d) with your orders, entry and liquidation price drawn on, a depth ladder with spread, 72 hours of funding, and an account summary with the margin ratio, fee tier and a PnL chart. Order panel: a **Buy / Sell** toggle, **Market, Limit (GTC / post-only / IOC)** and under **Pro**: **Stop and Take (market or limit), Scale, Trailing Stop, TWAP**; size in USD or coin, a TP/SL bracket by % or price, reduce-only, per-coin **leverage** and cross/isolated, and rows showing the liquidation price, order value, margin and fees. Tabs for Positions (⋯ to close at a limit, reverse, set TP/SL or move isolated margin), Orders (cancel one or all, plus a **dead-man's switch**), TWAP progress, Fills and Order History, Funding payments and spot Balances. ⚙ holds max slippage, confirmations and sounds. A **⧉ COPYING** badge shows when a Copy Desk drives the account. |
| **Build menu** (**H**) | Every Stonkcraft block with its recipe: craft it from what you carry, anywhere (creative players get them free). No trading here: that takes a Trading Terminal, which this makes. |
| **Ticker tape HUD** (toggle with **J**) | Scrolling account value, uPnL and watchlist prices. Shows the speedrun timer while a run is on. |
| **Ticker Display** | Glowing price board: price, 24h change, sparkline, funding, volume. |
| **Account Display** | Insert a Watch or Trade Card and pick a figure: **equity, PnL, PnL 24h / 7d / 30d, return, uPnL, realized PnL, free collateral, margin used, margin usage, account leverage, gross / net exposure, open positions, open orders, 24h funding**, or one coin's **position**. Or insert a **Signal Card** to show a signal (a number, or a portfolio's weights). **Print card** makes a Signal Card of the figure. |
| **Account Vault** | One account in block form: deposit and withdraw physical coins, print cards. |
| **Commodity Exchange** | Swaps goods for money and back. Click what you carry on its screen, or right-click the block with it, to deposit it into your account at a live commodity price, as a **spot balance** or a fully collateralised **1x long**. It takes twelve goods. Gold and copper have markets of their own; the rest are priced by the market that feels closest: iron as silver, netherite as platinum, diamonds as palladium, emeralds (villager money) as euros, redstone as chip stocks (SMH), quartz as memory chips (DRAM), glowstone as uranium miners (URNM), coal as natural gas, a lava bucket as a barrel of crude (you keep the bucket) and blaze rods as heating oil. Hover a good to see why. The **Withdraw** tab shows the same grid with how much of each you can take out, from either; a long gives back its own equity, prorated by leverage. Hoppers feed the inserted card's account, so a gold farm can fund a bot. |
| **Signal Mixer** | One composable **signal**: a number, a target portfolio, or an event. Sources: any Watch or Trade Card (its positions; type any public account's address and **Watch** it to get one), a fixed basket, a market's price / % change / funding / spread / book tilt, an account's figure, position, side, position PnL, open orders or **fills**, redstone, NYSE hours and the **NYSE bell**. Ops: **blend, scale, invert (fade), filter, top N, cap, delay, smooth, gate, compare, map, pick, change-by**. Chain mixers by printing **Signal Cards** and feeding them into other mixers. Outputs redstone too. |
| **Copy Desk** | **Copy trading**: drives your paper account (its Trade Card) to a leader's portfolio. The leader is any Watch or Trade Card or a Signal Card; type a trader's address and press **Watch** to make its Watch Card the leader. **Proportional** (your equity × weights) or **mirror** (leader's size × ratio) sizing, a leverage cap, rebalance band and step, interval, a drawdown stop, and redstone pause/run. Keeps copying while its chunk is unloaded. The comparator reads tracking error, and **Print card** makes a Signal Card of it. |
| **Market Sensor** | Redstone from a **reading** and a **test**. Readings: price, % change, funding, spread, book tilt; the card's position, side, position PnL, open orders, any Account Display figure, or fills; a Signal Card; NYSE open and the NYSE bell. Tests: at least, at most, analog (0–15), rises by, falls by (over a window), or a pulse for events. **Either side** tests the size and ignores the sign. The **Preset** menu sets both at once (pumping, dumping, long?, PnL ≤ −500, margin usage…). **Print card** makes a Signal Card of the reading, for mixers, displays or other sensors. |
| **Trade Executor** | Market, Limit, Stop, Take-profit and TWAP orders on a redstone pulse, with brackets, leverage and cross/isolated. **Inputs tab**: size, leverage, price offset, TP %, SL % and TWAP minutes can each be fixed, or read from a face, the trigger's strength, or a **Signal Card** (0..1 mapped onto a range). Amend-on-repeat, cancel-after, and an order-status comparator. **Fires on** redstone, a Signal Card's **events** (another executor's order, a fill, a bell), or both. Runs an **Order Ticket** if one is in, and with **Cycle** works through a chest of them. **Print card** makes a Signal Card of its orders, so one executor can set off the next. |
| **Order Ticket** | An order written down (paper, paper, redstone makes two blanks). Right-click an executor with a blank to copy its order on, or click **▤ ticket** on the terminal's order panel. Right-click an executor with a written one to load it; sneak with an empty hand takes it back. Craft a ticket with paper to copy it. It carries no account, so it's harmless to share. |
| **Leverage Dial** | Signal strength 1–15 sets a coin's leverage across a range. |
| **Kill Switch** | On a pulse: cancel orders, or cancel and flatten. Dead-man mode while powered. Can also fire on a Signal Card's events. |

Paper accounts simulate all of it against live prices: resting limits, triggers, brackets with one-cancels-other,
TWAP slicing every 30 s with catch-up, hourly funding on the oracle price, spot balances, and the exchange's own margin rules:
maintenance margin at half of initial at max leverage, margin tiers, and liquidation in stages. Under maintenance margin,
market orders close positions (a fifth at a time over $100k, every 30 s) and you keep what's left; under 2/3 of it the
backstop takes the positions and the margin. PnL, margin, liquidation and triggers run on the exchange's mark price.

## Commands

| Command | What it does |
|---|---|
| `/stonk price <market>` | Live price: `gold`, `aapl`, `sp500`… |
| `/stonk buy\|sell\|long\|short <market> <usd>` | Place a market order (within reach of a Trading Terminal) |
| `/stonk close <market>` | Close a position |
| `/stonk portfolio` | Positions and PnL of the selected account |
| `/stonk accounts`, `/stonk select <name>` | List your accounts, pick one |
| `/stonk deposit` | Deposit the coins you carry |
| `/stonk status` | Feed health and what's streaming |
| `/stonk signals` | Your signals and their values |
| `/stonk market` | NYSE hours and the next bell |
| `/stonk speedrun start\|stop\|top` | The Wall Street speedrun |
| `/stonk mint [usd]` | Ops: mint paper money |
| `/stonk signal score <objective> [scale]` | Holding a Signal Card: write its value to a scoreboard every second, for command blocks and datapacks (`clear` stops it) |

## Accounts, money and cards

* **Account Vault**: each vault holds one named account, paper or watch, and shows its equity on the front.
* **Physical cash**: $1 Chips, $100 Notes and $10,000 Bars, all paper money. Right-click a vault with coins to
  deposit, or withdraw from its screen. Hoppers can feed coins into a vault.
* **Getting money**: money is earned. Ores, gems, redstone and fuel deposited at a Commodity Exchange count at a live price,
  and that's the only way new money comes in. Coins move money that's already in an account. Server owners can bring
  back a starter grant (`paperStarterGrant`) and the free paper mint (`paperFaucet`); ops always have `/stonk mint`.
* **Spot and perps**: an account is **Manual** by default, like a real exchange's Manual account type. Perps cash margins
  positions; spot buys spend spot cash and spot sales pay into it. **⇄ Transfer** in the terminal moves cash between
  the two (only free perps cash, never margin in use, and profit only above 10% of position value). Stocks and
  commodities margin from **their own balance**, so a liquidation there can't touch your perps: move some over with
  Transfer first. Switch the account to **Unified**, the exchange default, for one balance that does it all, or to
  **Portfolio Margin** (from $10,000) to count some spot holdings as collateral.
* **Isolated margin**: a position on Iso holds its own margin and liquidates on it alone. Add or take margin from its
  **⋯** menu in the Positions table.
* **Fee tiers**: your rolling 14-day volume sets your rates, from 0.045% / 0.015% on perps and 0.07% / 0.04% on spot,
  as on the real exchange. Spot volume counts double. The terminal's **Account** view shows how far the next tier is.
* **Trading Terminal**: hand trading happens only at a Trading Terminal block, within reach of it, for terminal
  screens and `/stonk buy|sell|long|short|close` alike. Blocks you've wired up trade on their own. Order sizes go by
  **10% / 25% / 50% / 75% / Max** of what's available on that side: free margin × leverage (plus what an order against a
  position gets back), spot cash, what you hold when selling spot, or the position when reduce-only.
* **Crafting**: every block has a recipe, and none of the first ones needs the Nether. Press **H** for the build
  menu: each block's crafting grid and what you're short of, and **Craft** makes it from what you carry. Free blocks are
  for creative players (or servers that set `buildMenu`).
* **Blocks that touch share an account**: a Trading Terminal or Commodity Exchange touching an Account Vault, directly or through a
  chain of other Stonkcraft blocks, uses the vault's account, so `[vault] [exchange] [terminal]` in a row just works. The
  nearest vault wins, a card in the Commodity Exchange or in your hand overrides it, and someone else's vault doesn't open for you.
  Blocks with a card slot (executors, sensors, displays...) still go by their card.
* **Watch-only accounts**: paste any public trading account's address into an empty vault. It mirrors that account
  read-only, positions and PnL included. Use its cards in sensors, displays, signals and copy
  desks.
* **Trade Cards and Watch Cards**: craft Blank Cards (paper + gold nuggets + glowstone) and print them at your vault:
  right-click for a gold **Trade Card**, sneak-right-click for a blue **Watch Card**. A Watch Card only looks: Market
  Sensors, Account Displays, Signal Mixer inputs and a Copy Desk's leader take it. A Trade Card acts: Trade
  Executors, Kill Switches, Leverage Dials, Commodity Exchanges and a Copy Desk's follower take only Trade Cards, and turn a
  Watch Card away at the slot. A Trade Card also works anywhere a Watch Card does. Any public address becomes a
  Watch Card with **Watch** on a Copy Desk or Signal Mixer. Read cards printed before this turn into Watch Cards.
* **Sharing**: open your vault → **Access…** to give players view or trade access (with $ per order, leverage,
  markets, reduce-only and expiry limits), print and revoke cards, freeze the account, and read its activity log.
  A Trade Card only works in a block whose owner has trade access, so a stolen card is useless.
* **Scoped cards**: a card can carry limits of its own (markets, $ per order, leverage, reduce-only, expiry), set
  under **Print a card** on the Access screen. An order through a block must pass both the card's limits and its
  owner's access. **Limit** on a printed card tightens it later; limits never loosen, so print a new card for that.

## Commodity Exchange: mining is money

| Item | Priced as |
|---|---|
| Gold nugget / ingot / block | 1/9, 1, 9 oz of gold (`xyz:GOLD`) |
| Copper ingot / block | 1, 9 lb of copper (`xyz:COPPER`) |
| Iron nugget / ingot / block | 1/9, 1, 9 oz of silver (`xyz:SILVER`): Minecraft has no silver |
| Netherite scrap / ingot / block | 1, 4, 36 oz of platinum (`xyz:PLATINUM`); comes back out as scrap |
| Diamond / block | 1, 9 oz of palladium (`xyz:PALLADIUM`) |
| Emerald / block | 10, 90 euros (`xyz:EUR`): villager money |
| Redstone dust / block | 0.01, 0.09 shares of chip stocks (`xyz:SMH`) |
| Nether quartz / block | 0.1, 0.4 shares of memory chips (`xyz:DRAM`) |
| Glowstone dust / block | 0.1, 0.4 shares of uranium miners (`xyz:URNM`) |
| Coal, charcoal / coal block | 1, 9 MMBtu of natural gas (`xyz:NATGAS`) |
| Lava bucket | 1 barrel of crude (`xyz:CL`); you keep the bucket |
| Blaze rod / powder | 1, 0.5 gallons of heating oil (`xyz:HO`) |

Raw metal and ore count for 90% (refining isn't free). Prices are live market mids; a 0.25% fee applies each
way. Server owners can change or add pegs in `config/stonkcraft-exchange.json`; a key can be an item tag (`#c:ingots/gold`).
Each vanilla good also accepts its common tag, so other mods' gold, diamonds and so on count too. If a mod adds
**silver, platinum or uranium ingots**, the Commodity Exchange takes those as well (as silver, platinum, and a share of URNM).

* **Spot mode** credits a commodity token (UGOLD, UIRON…), which you can sell for dollars (or buy more of) in the terminal's Spot tab.
* **1x long mode** puts the value in as collateral and opens a matching long on the perp, at 1x. Lever it up, hedge
  it, trade around it. Depositing into a short settles it.
* **Withdrawing from a long** gives back the position's own equity, `(size·entry/leverage + uPnL) / price` ounces:
  all of it at 1x, roughly a third at 3x. The rest of the position keeps its leverage.

Withdrawn gold is real gold: barter it with piglins for ender pearls.

## Copy trading and signals

The quick way: place a **Copy Desk**, right-click it with your Trade Card, paste a trader's address and press
**Watch** (or right-click any Watch Card in), and press **Start**. It rebalances your account toward theirs every 10 s.

The composable way: build the leader out of **Signal Mixers**. Some examples:

* **Fade a whale**: `ADDRESS 0x…` → `INVERT` → Copy Desk.
* **Blend three traders**: three Watch Cards into one `BLEND` mixer, weights 0.5 / 0.3 / 0.2, then `CAP` at 3x gross.
* **Copy only while the NYSE is open**: `GATE` with a whale in input 1 and a `NYSE open` mixer in input 2.
* **Copy their stock trades only**: `FILTER` with `AAPL NVDA TSLA SP500`.
* **Lag a leader by an hour**: `DELAY 3600`.
* **Size a bot from a signal**: `PRICE gold` → `MAP 3900..4300` → a Signal Card in an executor's size input.
* **Chain trades**: print executor A's card into executor B, set B to fire on events: each order A places sets off B.
* **Warn when copying drifts**: print the Copy Desk's card into a Market Sensor, `At least 0.05`, wired to a bell.

Signals are evaluated once a second on the server, whether or not their mixers are loaded. A Signal Card lets
whoever holds it read the derived values, never trade or see the account itself, and revoking a card kills
every signal built on it.

## Wall Street speedrun

`/stonk speedrun start` times you from now to the Ender Dragon's death, and closes the paper mint to you for the run.
The route: mine gold → deposit it → trade it up → withdraw gold → barter with piglins → the End. Your time, gold deposited
in and out, and trading PnL go in `/stonk speedrun top`.

Also: advancements in a **Stonkcraft** tab (Gold Rush, Copycat, Rekt, Millionaire…), and a margin
call when you're liquidated.

## Live data

Prices, watched order books and watched accounts stream over Hyperliquid's WebSocket (`websocket`, default on), with
REST polling as a fallback, for both the main dex and the xyz dex. Hyperliquid caps distinct users per IP across
account feeds (`websocketMaxUsers`, 10): addresses followed by copy desks and signals get the feed first, then
accounts read by loaded blocks, then those selected in a terminal. `/stonk status` shows what's streaming.

## Art and logos

Every block, item and market logo is 8-bit pixel art drawn in code by `tools/textures/make_textures.py` (one palette:
bevelled graphite, gold trim, lit glass). Metals, energy, currencies and the S&P 500 have their own picture, and every other market gets a lettered badge in\nits own colour. Logos appear on Ticker Display faces, in the terminal's
market list and chart title, on the ticker tape, and as decals on the sides of sensors, executors and leverage
dials. Only generic pictures ship (metals, energy, currencies): companies', funds' and crypto projects' logos are their
owners' trademarks, so those markets get lettered badges. Re-run the script after editing it:

```sh
python tools/textures/make_textures.py --sheet sheet.png   # writes the textures, plus a contact sheet to review
```
