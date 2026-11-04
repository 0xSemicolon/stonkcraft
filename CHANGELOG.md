# Changelog

The release workflow publishes the section whose heading contains the tag's version.

## 0.9.0-beta

First public beta of **Stonkcraft**: paper-trade stocks, indices and commodities at live prices in Minecraft.

- **Trading Terminal**: a block with live candles, an order book and an exchange-style order form. Market, limit, stop, take-profit, scale, trailing-stop and TWAP orders, TP/SL brackets, leverage, cross or isolated margin. Opens on the Stocks tab: AAPL, NVDA, the S&P 500, gold, silver, oil, gas, FX and more.
- **Realistic paper accounts**: maintenance margin, staged liquidation, hourly funding, fee tiers by volume, and separate spot, perps and stocks balances with a **⇄ Transfer** between them (or one **Unified** balance).
- **Money is earned**: no starter money by default. Bridge twelve goods (gold, copper, iron, netherite, diamonds, emeralds, redstone, quartz, glowstone, coal, lava, blaze rods) at a **UNIT[XYZ]** block at live commodity prices, and withdraw profits back out as items. Other mods' gold, silver, platinum and uranium count too.
- **Craft your trading floor**: press **H** for the build menu: every block's recipe, what you're short of, and Craft from what you carry. The first recipes need no Nether goods.
- **Redstone trading**: Market Sensor, Trade Executor, Leverage Dial, Kill Switch, Ticker Display and Account Display. **Order Tickets** carry an executor's order; with Cycle, an executor works through a chest of them.
- **Signals and copy trading**: Signal Mixers blend, fade, filter, gate and delay portfolios; Copy Desks follow any public trading account on your paper account. Every block that reads something can print a **Signal Card** for any other block. `/stonk signal score` puts a signal on the scoreboard.
- **Accounts and cards**: Account Vaults, gold **Trade Cards** that act and blue **Watch Cards** that only look, scoped card limits, shared access with per-player limits, and blocks that touch a vault share its account.
- **Wall Street speedrun** (`/stonk speedrun start`), a Stonkcraft advancement tab, and a margin call when you're liquidated.
- A welcome screen on first join. Stock logos are opt-in (`stockLogos` in `stonkcraft-client.json`).
