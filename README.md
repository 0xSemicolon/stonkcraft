# Stonkcraft

Paper-trade Hyperliquid's live markets from inside Minecraft: crypto perps, spot, and stocks, indices and
commodities on trade.xyz. Build trading bots and copy-trading desks out of redstone and signals, mine gold to fund
your account, and cash profits out as metal.

Fabric mod for **Minecraft 1.21.1** (Java 21). **Paper trading only**: prices and watched accounts come live from
Hyperliquid mainnet; no order ever reaches an exchange and the mod holds no keys.

![Stonkcraft](docs/launch/media/stonkcraft-banner.png)

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and the Stonkcraft jar
   ([Modrinth](https://modrinth.com/mod/stonkcraft) or [Releases](https://github.com/0xSemicolon/stonkcraft/releases))
   in your `mods` folder. [Mod Menu](https://modrinth.com/mod/modmenu) is optional. On a server, the server and every
   player need the mod.
3. Join a world and craft an **Account Vault**, a **UNIT.XYZ** and a **Trading Terminal**. The recipes appear in your
   recipe book once you pick up copper, iron, gold, paper or redstone. The game needs internet access for live prices.

Stonkcraft is a fan-made game mod (mod id `hypercraft`). It isn't affiliated with or endorsed by Hyperliquid, trade.xyz, Unit, the New York
Stock Exchange or Mojang, and nothing in it is financial advice. Every dollar in it is paper money.

## What's in the box

| Thing | What it does |
|---|---|
| **Trading Terminal** (block) | **Perps**, **Stocks** (trade.xyz's HIP-3 markets: AAPL, NVDA, TSLA, SP500, GOLD, oil, FX…) and **Spot** tabs with a ★ watchlist. **Chart / Book / Funding / Account** views: live candles (1m–1d) with your orders, entry and liquidation price drawn on, a depth ladder with spread, 72 hours of funding, and an account summary with the margin ratio, fee tier and a PnL chart. Order panel: a **Buy / Sell** toggle, **Market, Limit (GTC / post-only / IOC)** and under **Pro**: **Stop and Take (market or limit), Scale, Trailing Stop, TWAP**; size in USD or coin, a TP/SL bracket by % or price, reduce-only, per-coin **leverage** and cross/isolated, and rows showing the liquidation price, order value, margin and fees. Tabs for Positions (⋯ to close at a limit, reverse, set TP/SL or move isolated margin), Orders (cancel one or all, plus a **dead-man's switch**), TWAP progress, Fills and Order History, Funding payments and spot Balances. ⚙ holds max slippage, confirmations and sounds. A **⧉ COPYING** badge shows when a Copy Desk drives the account. |
| **Build menu** (**H**) | Every HyperCraft block with its recipe: craft it from what you carry, anywhere (creative players get them free). No trading here: that takes a Trading Terminal, which this makes. |
| **Ticker tape HUD** (toggle with **J**) | Scrolling account value, uPnL and watchlist prices. Shows the speedrun timer while a run is on. |
| **Ticker Display** | Glowing price board: price, 24h change, sparkline, funding, volume. |
| **Account Display** | Insert a Watch or Trade Card and pick a figure: **equity, PnL, PnL 24h / 7d / 30d, return, uPnL, realized PnL, free collateral, margin used, margin usage, account leverage, gross / net exposure, open positions, open orders, 24h funding**, or one coin's **position**. Or insert a **Signal Card** to show a signal (a number, or a portfolio's weights). **Print card** makes a Signal Card of the figure. |
| **Account Vault** | One account in block form: deposit and withdraw physical coins, print cards. |
| **UNIT.XYZ** | A bridge between goods and money. Click what you carry on its screen, or right-click the block with it, to bridge it into your account at a live trade.xyz price, as a **spot balance** or a fully collateralised **1x long**. It takes twelve goods. Gold and copper have markets of their own; the rest are priced by the market that feels closest: iron as silver, netherite as platinum, diamonds as palladium, emeralds (villager money) as euros, redstone as chip stocks (SMH), quartz as memory chips (DRAM), glowstone as uranium miners (URNM), coal as natural gas, a lava bucket as a barrel of crude (you keep the bucket) and blaze rods as heating oil. Hover a good to see why. The **Withdraw** tab shows the same grid with how much of each you can take out, from either; a long gives back its own equity, prorated by leverage. Hoppers feed the inserted card's account, so a gold farm can fund a bot. |
| **Signal Mixer** | One composable **signal**: a number, a target portfolio, or an event. Sources: any Watch or Trade Card (its positions; type any Hyperliquid address and **Watch** it to get one), a fixed basket, a market's price / % change / funding / spread / book tilt, an account's figure, position, side, position PnL, open orders or **fills**, redstone, NYSE hours and the **NYSE bell**. Ops: **blend, scale, invert (fade), filter, top N, cap, delay, smooth, gate, compare, map, pick, change-by**. Chain mixers by printing **Signal Cards** and feeding them into other mixers. Outputs redstone too. |
| **Copy Desk** | **Copy trading**: drives your paper account (its Trade Card) to a leader's portfolio. The leader is any Watch or Trade Card or a Signal Card; type a trader's address and press **Watch** to make its Watch Card the leader. **Proportional** (your equity × weights) or **mirror** (leader's size × ratio) sizing, a leverage cap, rebalance band and step, interval, a drawdown stop, and redstone pause/run. Keeps copying while its chunk is unloaded. The comparator reads tracking error, and **Print card** makes a Signal Card of it. |
| **Market Sensor** | Redstone from a **reading** and a **test**. Readings: price, % change, funding, spread, book tilt; the card's position, side, position PnL, open orders, any Account Display figure, or fills; a Signal Card; NYSE open and the NYSE bell. Tests: at least, at most, analog (0–15), rises by, falls by (over a window), or a pulse for events. **Either side** tests the size and ignores the sign. The **Preset** menu sets both at once (pumping, dumping, long?, PnL ≤ −500, margin usage…). **Print card** makes a Signal Card of the reading, for mixers, displays or other sensors. |
| **Trade Executor** | Market, Limit, Stop, Take-profit and TWAP orders on a redstone pulse, with brackets, leverage and cross/isolated. **Inputs tab**: size, leverage, price offset, TP %, SL % and TWAP minutes can each be fixed, or read from a face, the trigger's strength, or a **Signal Card** (0..1 mapped onto a range). Amend-on-repeat, cancel-after, and an order-status comparator. **Fires on** redstone, a Signal Card's **events** (another executor's order, a fill, a bell), or both. Runs an **Order Ticket** if one is in, and with **Cycle** works through a chest of them. **Print card** makes a Signal Card of its orders, so one executor can set off the next. |
| **Order Ticket** | An order written down (paper, paper, redstone makes two blanks). Right-click an executor with a blank to copy its order on, or click **▤ ticket** on the terminal's order panel. Right-click an executor with a written one to load it; sneak with an empty hand takes it back. Craft a ticket with paper to copy it. It carries no account, so it's harmless to share. |
| **Leverage Dial** | Signal strength 1–15 sets a coin's leverage across a range. |
| **Kill Switch** | On a pulse: cancel orders, or cancel and flatten. Dead-man mode while powered. Can also fire on a Signal Card's events. |

Paper accounts simulate all of it against live prices: resting limits, triggers, brackets with one-cancels-other,
TWAP slicing every 30 s with catch-up, hourly funding on the oracle price, spot balances, and Hyperliquid's margin rules:
maintenance margin at half of initial at max leverage, margin tiers, and liquidation in stages. Under maintenance margin,
market orders close positions (a fifth at a time over $100k, every 30 s) and you keep what's left; under 2/3 of it the
backstop takes the positions and the margin. PnL, margin, liquidation and triggers run on Hyperliquid's own mark price.

Commands: `/hl price <coin>` (`gold`, `aapl`, `btc`…), `/hl buy|sell|long|short <coin> <usd>`, `/hl close <coin>`,
`/hl portfolio`, `/hl accounts`, `/hl select <name>`, `/hl status`, `/hl deposit`,
`/hl signals`, `/hl market` (NYSE hours), `/hl speedrun start|stop|top`, and for ops `/hl mint [usd]` and
`/hl signal score <objective> [scale]` (holding a Signal Card: writes its value to a scoreboard
every second, for command blocks and datapacks; `/hl signal score clear` stops it).

## Accounts, money and cards

* **Account Vault**: each vault holds one named account, paper or watch, and shows its equity on the front.
* **Physical USDC**: $1 Chips, $100 Notes and $10,000 Bars, all paper money. Right-click a vault with coins to
  deposit, or withdraw from its screen. Hoppers can feed coins into a vault.
* **Getting money**: money is earned. Ores, gems, redstone and fuel bridged in at a UNIT.XYZ count at a live price,
  and that's the only way new money comes in. Coins move money that's already in an account. Server owners can bring
  back a starter grant (`paperStarterGrant`) and the free paper mint (`paperFaucet`); ops always have `/hl mint`.
* **Spot and perps**: an account is **Manual** by default, like Hyperliquid's Manual account type. Perps USDC margins
  positions; spot buys spend spot USDC and spot sales pay into it. **⇄ Transfer** in the terminal moves USDC between
  the two (only free perps USDC, never margin in use, and profit only above 10% of position value). trade.xyz's stocks
  and commodities margin from **their own USDC**, so a liquidation there can't touch your perps: move some over with
  Transfer first. Switch the account to **Unified**, Hyperliquid's default, for one balance that does it all, or to
  **Portfolio Margin** (from $10,000) to count spot HYPE at 65% and BTC at 50% as collateral.
* **Isolated margin**: a position on Iso holds its own margin and liquidates on it alone. Add or take margin from its
  **⋯** menu in the Positions table.
* **Fee tiers**: your rolling 14-day volume sets your rates, from 0.045% / 0.015% on perps and 0.07% / 0.04% on spot,
  as on Hyperliquid. Spot volume counts double. The terminal's **Account** view shows how far the next tier is.
* **Trading Terminal**: hand trading happens only at a Trading Terminal block, within reach of it, for terminal
  screens and `/hl buy|sell|long|short|close` alike. Blocks you've wired up trade on their own. Order sizes go by
  **10% / 25% / 50% / 75% / Max** of what's available on that side: free margin × leverage (plus what an order against a
  position gets back), spot USDC, what you hold when selling spot, or the position when reduce-only.
* **Crafting**: every block has a recipe, and none of the first ones needs the Nether. Press **H** for the build
  menu: each block's crafting grid and what you're short of, and **Craft** makes it from what you carry. Free blocks are
  for creative players (or servers that set `buildMenu`).
* **Blocks that touch share an account**: a Trading Terminal or UNIT.XYZ touching an Account Vault, directly or through a
  chain of other HyperCraft blocks, uses the vault's account, so `[vault] [UNIT.XYZ] [terminal]` in a row just works. The
  nearest vault wins, a card in the UNIT.XYZ or in your hand overrides it, and someone else's vault doesn't open for you.
  Blocks with a card slot (executors, sensors, displays...) still go by their card.
* **Watch-only accounts**: paste any Hyperliquid address into an empty vault. It mirrors that account read-only,
  including its trade.xyz positions and Hyperliquid's own PnL. Use its cards in sensors, displays, signals and copy
  desks.
* **Trade Cards and Watch Cards**: craft Blank Cards (paper + gold nuggets + glowstone) and print them at your vault:
  right-click for a gold **Trade Card**, sneak-right-click for a blue **Watch Card**. A Watch Card only looks: Market
  Sensors, Account Displays, Signal Mixer inputs and a Copy Desk's leader take it. A Trade Card acts: Trade
  Executors, Kill Switches, Leverage Dials, UNIT.XYZ and a Copy Desk's follower take only Trade Cards, and turn a
  Watch Card away at the slot. A Trade Card also works anywhere a Watch Card does. Any Hyperliquid address becomes a
  Watch Card with **Watch** on a Copy Desk or Signal Mixer. Read cards printed before this turn into Watch Cards.
* **Sharing**: open your vault → **Access…** to give players view or trade access (with $ per order, leverage,
  markets, reduce-only and expiry limits), print and revoke cards, freeze the account, and read its activity log.
  A Trade Card only works in a block whose owner has trade access, so a stolen card is useless.
* **Scoped cards**: a card can carry limits of its own (markets, $ per order, leverage, reduce-only, expiry), set
  under **Print a card** on the Access screen. An order through a block must pass both the card's limits and its
  owner's access. **Limit** on a printed card tightens it later; limits never loosen, so print a new card for that.

## UNIT.XYZ: mining is money

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

Raw metal and ore count for 90% (refining isn't free). Prices are trade.xyz's live mids; a 0.25% fee applies each
way. Server owners can change or add pegs in `config/hypercraft-unit.json`; a key can be an item tag (`#c:ingots/gold`).
Each vanilla good also accepts its common tag, so other mods' gold, diamonds and so on count too. If a mod adds
**silver, platinum or uranium ingots**, UNIT.XYZ takes those as well (as silver, platinum, and a share of URNM).

* **Spot mode** credits a Unit token (UGOLD, UIRON…), which you can sell for USDC (or buy more of) in the terminal's Spot tab.
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

`/hl speedrun start` times you from now to the Ender Dragon's death, and closes the paper mint to you for the run.
The route: mine gold → bridge it → trade it up → withdraw gold → barter with piglins → the End. Your time, gold bridged
in and out, and trading PnL go in `/hl speedrun top`.

Also: advancements in a **HyperCraft** tab (Gold Rush, Copycat, Rekt, Millionaire…), and a margin
call when you're liquidated.

## Live data

Prices, watched order books and watched accounts stream over Hyperliquid's WebSocket (`websocket`, default on), with
REST polling as a fallback, for both the main dex and the xyz dex. Hyperliquid caps distinct users per IP across
account feeds (`websocketMaxUsers`, 10): addresses followed by copy desks and signals get the feed first, then
accounts read by loaded blocks, then those selected in a terminal. `/hl status` shows what's streaming.

## Later: real execution

Real trading will come back one way only: you bring the URL and key of your own execution server (local or hosted)
that speaks a published protocol, and HyperCraft sends it orders. The mod will still never hold exchange keys. See
[docs/specs/2026-10-21-paper-signals-unit-nyse.md](docs/specs/2026-10-21-paper-signals-unit-nyse.md) §9.

## Art

Every block, item and market logo is 8-bit pixel art drawn in code by `tools/textures/make_textures.py` (one palette:
bevelled graphite, gold trim, lit glass). Over 100 markets have their own logo, among them BTC, ETH, SOL, HYPE, AAPL,
NVDA, MSFT, gold, oil, the S&P 500 and the euro, and every other market gets a lettered badge in its own colour. Logos appear on Ticker Display faces, in the terminal's
market list and chart title, on the ticker tape, and as decals on the sides of sensors, executors and leverage
dials. Stock, index and fund markets show lettered badges unless a player sets `"stockLogos": true` in
`config/hypercraft-client.json`: those logos are other companies' trademarks. Re-run the script after editing it:

```sh
python tools/textures/make_textures.py --sheet sheet.png   # writes the textures, plus a contact sheet to review
```

## Build and run

```sh
./gradlew build                 # jar in build/libs/stonkcraft-<version>.jar (needs Fabric API in your mods folder)
./gradlew runClient             # dev client
./gradlew runClient -Pshowroom  # small flat dev world; -Puitour screenshots every screen, -Pshots every block and logo
./gradlew runClient -Psurvival  # the survival world in run/saves/nyse-survival
./gradlew runClient -Pdemo      # the old showcase floor in run/saves/hcdemo, screenshot, quit
./gradlew runServer             # dev server
./gradlew test
```

Gradle has to run on JDK 25 (Loom 1.18 needs it; the mod itself targets Java 21). Use JDK 25 as your `JAVA_HOME`, or
set `org.gradle.java.home` in `~/.gradle/gradle.properties`. `-Pcompat` adds Sodium, Lithium and Mod Menu to the dev
client, and `-Pworld=<save>` opens any world in `run/saves`. Pushing a `v*` tag publishes a release
(`.github/workflows/build.yml`).

## Layout

* `hl/`: wire formatting, read-only HTTP and WebSocket clients
* `market/`: prices for the main dex, the xyz dex and Unit tokens
* `trading/`: paper and watch accounts, access control
* `unit/`: UNIT.XYZ items, pegs and settlement
* `signal/`: signals, the engine, copy desks
* `game/`: market clock, speedruns, advancements, liquidation effects
* `block/`, `net/`, `command/`; `client/`: screens, renderers, HUD
