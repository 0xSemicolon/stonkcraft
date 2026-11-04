<!--
Modrinth project settings (project YBz078rg, slug stonkcraft)
  Title:       Stonkcraft
  Summary:     Paper-trade real markets in Minecraft, and build the trading bots out of redstone.
  Categories:  Economy, Technology, Game Mechanics
  Environment: client required, server required
  License:     MIT    Source: https://github.com/0xSemicolon/stonkcraft    Issues: .../issues
  Icon:        docs/launch/media/stonkcraft-icon-512.png
  Gallery:     docs/launch/media/stonkcraft-banner.png, then terminal, unit_bridge, build_menu, trade_executor,
               copy_desk, blocks (.jpg)
  Versions:    pushing a v* tag publishes one (.github/workflows/build.yml)
The body below goes in the Description field.
-->

![Stonkcraft](https://raw.githubusercontent.com/0xSemicolon/stonkcraft/main/docs/launch/media/stonkcraft-banner.png)

**Stonkcraft is a paper-trading mod.** It streams live prices from Hyperliquid and trade.xyz (crypto perps and spot,
plus stocks, indices, gold, oil and FX) into your world and lets you trade them with in-game money. No real money is
involved at any point: the mod holds no keys, has no wallet, and never sends an order to an exchange.

![The Trading Terminal](https://raw.githubusercontent.com/0xSemicolon/stonkcraft/main/docs/launch/media/terminal.jpg)

### How it plays

- **Mine for your stake.** Money is earned, not handed out. Bridge ores, gems, redstone and fuel at a **UNIT.XYZ**
  block and they're credited at a live market price: gold as gold, iron as silver, diamonds as palladium, redstone as
  chip stocks, a lava bucket as a barrel of crude. Withdraw profits back out as ingots.
- **Craft your trading floor.** Press **H** for the build menu. It shows every block's recipe and what you're
  still missing, and crafts it from your inventory. Recipes unlock as you pick up copper, iron, gold, paper or
  redstone, and none of the first ones need the Nether.
- **Trade at a Trading Terminal.** It works like a real exchange front end: market, limit, stop, take-profit, scale,
  trailing-stop and TWAP orders, TP/SL brackets, leverage, cross or isolated margin, live candles, an order book and
  funding.
- **Real margin rules, fake money.** Paper accounts follow Hyperliquid's rules: maintenance margin, staged
  liquidation, hourly funding, fee tiers by volume, and separate spot and perps balances. That makes getting
  liquidated in Minecraft very educational.
- **Build bots out of redstone.** A **Market Sensor** emits redstone on a price move, a fill, funding, your PnL or the
  NYSE's opening bell. A **Trade Executor** places an order on a pulse. Add a **Kill Switch**, a **Leverage Dial** and
  **Order Tickets**.
- **Compose signals and copy-trade.** **Signal Mixers** blend, fade, filter, gate and delay portfolios. A **Copy
  Desk** follows any public Hyperliquid address, live, on your paper account.
- **Wall Street speedrun**: mine, trade, withdraw, kill the dragon. `/hl speedrun start`.

![UNIT.XYZ](https://raw.githubusercontent.com/0xSemicolon/stonkcraft/main/docs/launch/media/unit_bridge.jpg)

### Install

Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api) on Minecraft **1.21.1**. Install it on the server
and on every client. (The mod id, commands and config files keep the working name: `hypercraft`, `/hl`,
`config/hypercraft*.json`.)

### Network use

Stonkcraft needs internet access. The server connects to Hyperliquid's public API (`api.hyperliquid.xyz`, over HTTPS and
WebSocket) to read prices, order books and funding, and to read any public addresses you choose to watch or copy. It
sends no player data, has no telemetry, and makes no other connections.

### Good to know

- Everything is paper trading. Nothing in the game is worth real money, and nothing here is financial advice.
- It's a fan project, not affiliated with or endorsed by Hyperliquid, trade.xyz, Unit, the NYSE or Mojang.
- Stock logos are off by default (they're other companies' trademarks). Set `"stockLogos": true` in
  `config/hypercraft-client.json` to turn them on.
- Server owners: `config/hypercraft.json` controls starter grants, the paper mint, leverage and fees.

Source, full docs and issues: [GitHub](https://github.com/0xSemicolon/stonkcraft)
