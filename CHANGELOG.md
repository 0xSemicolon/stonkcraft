# Changelog

The release workflow publishes the section whose heading contains the tag's version.

## 0.9.0-beta

First public beta, as **Stonkcraft**. The mod id stays `hypercraft`, so worlds and configs carry over.

- Just the trading: the NYSE and island world builders (`/hl nyse`) are gone, along with the Ring the Bell advancement. Market-hours and bell signals stay.
- Paper-trade live Hyperliquid perps and spot, and trade.xyz stocks, indices and commodities, at a Trading Terminal block.
- **H opens a build menu**: recipes, what you're short of, and Craft from what you carry. The terminal is for trading only: its Build tab is gone.
- **Blocks that touch share an account**: a Trading Terminal or UNIT.XYZ touching an Account Vault, directly or through other HyperCraft blocks, uses the vault's account.
- Redstone trading: Market Sensor, Trade Executor, Leverage Dial, Kill Switch, Ticker and Account Displays.
- Signal Mixers and Copy Desks: build a strategy out of blocks, or copy any Hyperliquid address.
- UNIT.XYZ: bridge twelve goods (gold, copper, iron, netherite, diamonds, emeralds, redstone, quartz, glowstone, coal, lava, blaze rods) into your account at live prices, and back out again.
- **The paper account now works like Hyperliquid's**: maintenance margin, liquidation prices and staged liquidation; real isolated margin; Hyperliquid's mark and oracle prices; margin tiers; each HIP-3 dex's own balance in Manual; Portfolio Margin; fee tiers with spot's own rates.
- **The order form too**: one Buy / Sell toggle, a Pro menu with Stop and Take limits, Scale and Trailing Stop, size in USD or coin, TP/SL by price, and rows for liquidation price, order value, margin, slippage and fees. Positions can be closed at a limit, reversed and given their own TP/SL. New: Order History, an Account view with a PnL chart, and a settings screen.
- Two kinds of card: gold **Trade Cards** act on an account (executors, kill switches, dials, UNIT.XYZ, a copy desk's follower), blue **Watch Cards** only look (sensors, displays, mixer inputs, a copy desk's leader). Any Hyperliquid address becomes a Watch Card with one click.
- Every block that reads something can **print a Signal Card** of it (sensors, displays, copy desks, executors), so any reading feeds any block. The Market Sensor is now a reading plus a test, with the old modes as presets. Signals can be **events** (fills, the NYSE bell, an executor's orders) that fire executors and kill switches.
- **Scoped cards** carry their own limits (markets, $ per order, leverage, reduce-only, expiry). **Order Tickets** carry an executor's order; with Cycle, an executor works through a chest of them.
- UNIT.XYZ accepts items by common tag (other mods' gold, diamonds…) and mods' silver, platinum and uranium ingots. `/hl signal score` puts a signal on the scoreboard.
- **Money is earned and trading is a place.** No starter money or free mint by default: fund accounts by bridging goods at UNIT.XYZ. Hand trading only at a Trading Terminal block. Every block is crafted (recipes unlock in the recipe book; the first ones need no Nether goods), and the H build menu shows the recipes to survival players.
- **Spot and perps kept apart** (Hyperliquid's Manual account type) with a **⇄ Transfer** between them, or one balance with **Unified**. Order sizes by **10/25/50/75/Max %** of what's available.
- Wall Street speedrun, advancements, a margin call when you're liquidated.
- A welcome screen on the first join. Stock logos are opt-in (`stockLogos` in `hypercraft-client.json`).
