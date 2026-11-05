package dev.stonkcraft.client;

import com.google.gson.JsonObject;
import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.block.AccountVaultBlockEntity;
import dev.stonkcraft.block.HcBlockEntity;
import dev.stonkcraft.client.screen.TerminalScreen;
import dev.stonkcraft.client.screen.VaultScreen;
import dev.stonkcraft.item.CoinItem;
import dev.stonkcraft.item.Coins;
import dev.stonkcraft.registry.ModComponents;
import dev.stonkcraft.registry.ModItems;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.OrderRequest;
import dev.stonkcraft.trading.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Dev-only showcase, enabled with -Dstonkcraft.demo=true: builds a trading floor with two funded
 * accounts, cards in the redstone blocks and a hopper feeding coins into a vault, then screenshots it.
 */
public final class DevDemo {
	private DevDemo() {}

	public static final boolean ENABLED = Boolean.getBoolean("stonkcraft.demo");
	private static int ticks = -1;
	private static String momentumId = "";

	/** Measure fill-to-redstone latency on a busy watched account (adds ~70s). */
	public static final boolean LATENCY = Boolean.getBoolean("stonkcraft.demo.latency");

	private static String fundId;
	private static String watchId = "";

	public static void tick(Minecraft mc) {
		if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
		ticks++;
		MinecraftServer server = mc.getSingleplayerServer();
		if (LATENCY && ticks > 2340 && ticks < 3170) server.execute(() -> latencySample(server));
		switch (ticks) {
			case 100 -> server.execute(() -> {
				build(server);
				claimBlocks(server);
			});
			case 240, 296, 336 -> mc.setScreen(null);
			case 300 -> shot(mc, "stonkcraft_world_1.png");
			case 340 -> shot(mc, "stonkcraft_world_2.png");
			case 360 -> {
				ClientConfig.get().lastCoin = "BTC";
				ClientConfig.get().interval = "15m";
				mc.setScreen(new TerminalScreen(1).view(1));
			}
			case 480 -> shot(mc, "stonkcraft_terminal_1.png");
			case 490 -> mc.setScreen(new TerminalScreen(2).view(2));
			case 530 -> shot(mc, "stonkcraft_terminal_2.png");
			case 540 -> {
				JsonObject o = new JsonObject();
				o.addProperty("pos", new BlockPos(-1, -60, -6).asLong());
				o.addProperty("id", momentumId);
				o.addProperty("owner", true);
				mc.setScreen(new VaultScreen(o));
			}
			case 570 -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", momentumId);
				ClientNet.send("vault_card", o);
			}
			case 600 -> shot(mc, "stonkcraft_vault.png");
			case 610 -> server.execute(() -> run(server, "setblock 0 -60 -2 minecraft:redstone_block"));
			case 620 -> {
				JsonObject o = new JsonObject();
				o.addProperty("name", "Scalper");
				o.addProperty("kind", "paper");
				ClientNet.send("account_new", o);
			}
			case 640 -> mc.setScreen(new dev.stonkcraft.client.screen.BuildMenuScreen());
			case 720 -> shot(mc, "stonkcraft_build.png");
			case 730 -> {
				fundId = ClientState.portfolio.get("accountId").getAsString();
				mc.setScreen(new dev.stonkcraft.client.screen.FundScreen(fundId, null));
			}
			case 750 -> {
				JsonObject o = new JsonObject();
				o.addProperty("usd", 10_000);
				ClientNet.send("paper_mint", o);
			}
			case 780 -> shot(mc, "stonkcraft_fund_1.png");
			case 790 -> {
				JsonObject o = new JsonObject();
				o.addProperty("id", fundId);
				ClientNet.send("deposit_coins", o);
			}
			case 830 -> shot(mc, "stonkcraft_fund_2.png");
			case 840 -> mc.setScreen(new TerminalScreen(1));
			case 880 -> shot(mc, "stonkcraft_fund_3.png");
			case 900 -> {
				mc.setScreen(null);
				server.execute(() -> run(server, "tp @p -0.5 -59 -3.6 180 -8"));
			}
			case 950 -> shot(mc, "stonkcraft_account_displays.png");
			case 960 -> server.execute(() -> {
				ServerLevel lvl = server.overworld();
				BlockPos ex = new BlockPos(-4, -60, -2);
				run(server, "setblock -5 -60 -2 minecraft:redstone_torch");
				run(server, "setblock -4 -60 -1 minecraft:comparator[facing=south]");
				run(server, "setblock -4 -60 0 minecraft:composter[level=5]");
				JsonObject cfg = ((HcBlockEntity) lvl.getBlockEntity(ex)).configJson();
				JsonObject ins = cfg.getAsJsonObject("inputs");
				ins.getAsJsonObject("LEVERAGE").addProperty("source", "WEST");
				ins.getAsJsonObject("LEVERAGE").addProperty("lo", 2);
				ins.getAsJsonObject("LEVERAGE").addProperty("hi", 12);
				ins.getAsJsonObject("SIZE").addProperty("source", "SOUTH");
				ins.getAsJsonObject("SIZE").addProperty("lo", 500);
				ins.getAsJsonObject("SIZE").addProperty("hi", 5000);
				((HcBlockEntity) lvl.getBlockEntity(ex)).applyConfig(cfg);
				run(server, "tp @p -6.0 -59.2 0.2 -130 30");
			});
			case 1000 -> shot(mc, "stonkcraft_executor_faces.png");
			case 1010 -> server.execute(() -> {
				JsonObject json = ((HcBlockEntity) server.overworld().getBlockEntity(new BlockPos(-4, -60, -2))).screenJson();
				mc.execute(() -> mc.setScreen(new dev.stonkcraft.client.screen.ExecutorScreen(json).showInputs()));
			});
			case 1040 -> shot(mc, "stonkcraft_executor_inputs.png");
			case 1045 -> {
				server.execute(() -> {
					JsonObject j = ((HcBlockEntity) server.overworld().getBlockEntity(new BlockPos(-4, -60, -2))).screenJson();
					mc.execute(() -> mc.setScreen(new dev.stonkcraft.client.screen.ExecutorScreen(j)));
				});
			}
			case 1070 -> shot(mc, "stonkcraft_executor_order.png");
			case 1080 -> {
				mc.setScreen(null);
				server.execute(() -> run(server, "tp @p 3.9 -59.4 -3.3 180 5"));
			}
			case 1120 -> shot(mc, "stonkcraft_watch_world.png");
			case 1130 -> {
				JsonObject o = new JsonObject();
				o.addProperty("pos", new BlockPos(3, -60, -6).asLong());
				o.addProperty("id", watchId);
				o.addProperty("owner", true);
				mc.setScreen(new VaultScreen(o));
			}
			case 1160 -> shot(mc, "stonkcraft_watch_vault.png");
			case 1170 -> server.execute(() -> {
				JsonObject j = ((HcBlockEntity) server.overworld().getBlockEntity(new BlockPos(4, -60, -6))).screenJson();
				mc.execute(() -> mc.setScreen(new dev.stonkcraft.client.screen.SensorScreen(j)));
			});
			case 1200 -> shot(mc, "stonkcraft_watch_sensor.png");
			case 1210 -> mc.setScreen(null);
			// ---- batch 2: trailing (amend) executor, My-order comparator, cancel-after, feeds
			case 1230 -> server.execute(() -> {
				batch2Setup(server);
				claimBlocks(server);
			});
			case 1250 -> server.execute(() -> pulse(server, -6, 1));
			case 1290 -> server.execute(() -> {
				logExecutor(server, "after pulse 1 (west 0 -> -2%)", -6, 1);
				// comparator first: placing the composter next to it is what makes it sample the composter
				run(server, "setblock -7 -60 1 minecraft:comparator[facing=west]");
				run(server, "setblock -8 -60 1 minecraft:composter[level=5]");
			});
			case 1310 -> server.execute(() -> pulse(server, -6, 1));
			case 1350 -> {
				server.execute(() -> logExecutor(server, "after pulse 2 (west 5 -> -1%)", -6, 1));
				server.execute(() -> run(server, "tp @p -9.5 -59 7.5 -152 25"));
			}
			case 1380 -> shot(mc, "stonkcraft_amend_resting.png");
			case 1390 -> server.execute(() -> {
				run(server, "setblock -7 -60 1 minecraft:redstone_block");
				run(server, "setblock -8 -60 1 minecraft:air");
			});
			case 1410 -> server.execute(() -> pulse(server, -6, 1));
			case 1450 -> server.execute(() -> logExecutor(server, "after pulse 3 (west 15 -> +1%, crosses)", -6, 1));
			case 1470 -> shot(mc, "stonkcraft_amend_filled.png");
			case 1480 -> server.execute(() -> pulse(server, -3, 1));
			case 1520 -> server.execute(() -> logExecutor(server, "cancel-after executor at 2s", -3, 1));
			case 1640 -> server.execute(() -> {
				logExecutor(server, "cancel-after executor at 8s", -3, 1);
				var md = Stonkcraft.market();
				var sock = md.socket();
				int[] fc = Stonkcraft.accounts().feedCounts();
				Stonkcraft.LOG.info("[batch2] feedLive={} connected={} subs={} users={} liveFedAccounts={}/{}", md.feedLive(),
						sock != null && sock.connected(), sock == null ? 0 : sock.subscriptionCount(), sock == null ? 0 : sock.userCount(), fc[0], fc[1]);
			});
			case 1650 -> shot(mc, "stonkcraft_cancel_after.png");
			case 1750 -> {
				mc.setScreen(null);
				server.execute(() -> securitySetup(server));
			}
			case 1770 -> server.execute(() -> securityPulse(server, "pulse 1: Alice $400 BTC (allowed), Alice $900 (over cap), Mallory (no access)"));
			case 1800 -> server.execute(() -> {
				securityLog(server, "after pulse 1");
				var reg = Stonkcraft.accounts();
				Account fund = secFund();
				reg.revokeCard(fund, secMalloryCard);
				reg.setFrozen(fund, true);
			});
			case 1810 -> server.execute(() -> securityPulse(server, "pulse 2: account frozen"));
			case 1840 -> server.execute(() -> {
				securityLog(server, "after pulse 2 (frozen)");
				Stonkcraft.accounts().setFrozen(secFund(), false);
			});
			case 1850 -> mc.setScreen(new dev.stonkcraft.client.screen.AccessScreen(secFundId, null));
			case 1880 -> shot(mc, "stonkcraft_access_people.png");
			case 1890 -> mc.setScreen(accessTab(1));
			case 1920 -> shot(mc, "stonkcraft_access_cards.png");
			case 1930 -> mc.setScreen(accessTab(2));
			case 1960 -> shot(mc, "stonkcraft_access_activity.png");
			case 2200 -> {
				JsonObject o = new JsonObject();
				o.addProperty("pos", 0L);
				o.addProperty("id", watchId);
				o.addProperty("owner", true);
				mc.setScreen(new VaultScreen(o));
			}
			case 2230 -> shot(mc, "stonkcraft_net_watch_vault.png");
			case 2280 -> {
				JsonObject d = new JsonObject();
				d.addProperty("pos", 0L);
				d.add("mine", new com.google.gson.JsonArray());
				mc.setScreen(new dev.stonkcraft.client.screen.VaultSetupScreen(d));
			}
			case 2310 -> shot(mc, "stonkcraft_net_setup.png");
			case 2330 -> {
				mc.setScreen(null);
				if (LATENCY) server.execute(() -> latencySetup(server));
			}
			case 3170 -> {
				if (LATENCY) server.execute(DevDemo::latencyReport);
			}
			case 3190 -> mc.stop();
			case 2350 -> {
				if (!LATENCY) mc.stop();
			}
			default -> {}
		}
	}

	private static void build(MinecraftServer server) {
		String[] coins = {"BTC", "ETH", "SOL", "HYPE", "DOGE", "XRP", "SUI", "AVAX", "LINK", "BNB"};
		run(server, "time set 6000");
		run(server, "weather clear");
		run(server, "gamerule doDaylightCycle false");
		run(server, "difficulty peaceful");
		run(server, "kill @e[type=!player]");
		run(server, "fill -8 -60 -1 8 -58 3 minecraft:air");
		run(server, "fill -5 -61 -10 5 -55 -1 minecraft:air");
		run(server, "fill -5 -61 -10 5 -61 -1 minecraft:polished_deepslate");
		for (int i = 0; i < 10; i++) {
			int x = -2 + (i % 5);
			int y = i < 5 ? -57 : -58;
			run(server, "setblock " + x + " " + y + " -9 stonkcraft:ticker_display[facing=south]{Coin:\"" + coins[i] + "\"}");
		}
		// two vaults side by side, a hopper of coins feeding the second one
		run(server, "setblock -1 -60 -6 stonkcraft:account_vault[facing=south]");
		run(server, "setblock 1 -60 -6 stonkcraft:account_vault[facing=south]");
		run(server, "setblock 1 -59 -6 minecraft:hopper[facing=down]");
		// strategy blocks, spaced so their labels don't collide
		run(server, "setblock -4 -60 -4 stonkcraft:market_sensor{Coin:\"BTC\",Mode:\"PRICE_ABOVE\",A:50000d}");
		run(server, "setblock -4 -60 -3 minecraft:redstone_wire");
		run(server, "setblock -4 -60 -2 stonkcraft:trade_executor{Coin:\"BTC\",Side:\"LONG\",Usd:5000d,Cooldown:5}");
		run(server, "setblock 4 -60 -4 stonkcraft:market_sensor{Coin:\"ETH\",Mode:\"PRICE_ABOVE\",A:1d}");
		run(server, "setblock 4 -60 -3 minecraft:redstone_wire");
		run(server, "setblock 4 -60 -2 stonkcraft:trade_executor{Coin:\"ETH\",Side:\"BUY\",Usd:500d,Cooldown:30}");
		run(server, "tp @p 0.5 -60 2.5 180 12");

		AccountRegistry reg = Stonkcraft.accounts();
		ServerPlayer player = server.getPlayerList().getPlayers().get(0);
		ServerLevel level = player.serverLevel();
		Account momentum = reg.createPaper(player, "Momentum Bot");
		Account funding = reg.createPaper(player, "Funding Farm");
		momentumId = momentum.id.toString();
		reg.deposit(momentum, 50_000);
		reg.deposit(funding, 10_000);
		((AccountVaultBlockEntity) level.getBlockEntity(new BlockPos(-1, -60, -6))).bind(momentum);
		((AccountVaultBlockEntity) level.getBlockEntity(new BlockPos(1, -60, -6))).bind(funding);
		// coins in the hopper drain into Funding Farm on their own
		Container hopper = (Container) level.getBlockEntity(new BlockPos(1, -59, -6));
		hopper.setItem(0, Coins.stack((CoinItem) ModItems.NOTE, 25));
		// cards into the executors (BTC trades Momentum, ETH trades Funding)
		((HcBlockEntity) level.getBlockEntity(new BlockPos(-4, -60, -2))).swapCard(card(momentum));
		((HcBlockEntity) level.getBlockEntity(new BlockPos(4, -60, -2))).swapCard(card(funding));
		player.getInventory().add(card(momentum));
		reg.select(player, momentum.id);
		// hand trading needs a terminal: the demo goes straight to the server, with USDC moved to spot for the spot buy
		reg.paper(momentum.id).transfer(5_000, false);
		dev.stonkcraft.net.ServerNet.placeOrder(player, "ETH", Side.SHORT, 8000);
		dev.stonkcraft.net.ServerNet.placeOrder(player, "HYPE/USDC", Side.BUY, 3000);
		// a resting limit with a bracket, a protective stop, a spot buy and a leverage change
		dev.stonkcraft.trading.TradingAccount t = reg.trading(momentum.id);
		t.setLeverage("BTC", 5, true);
		OrderRequest limit = OrderRequest.market("BTC", Side.BUY, 4000);
		limit.type = OrderRequest.Type.LIMIT;
		limit.offsetPct = -1.5;
		limit.tpPct = 4;
		limit.slPct = 2;
		t.submit(limit);
		OrderRequest stop = OrderRequest.market("ETH", Side.CLOSE, 0);
		stop.type = OrderRequest.Type.STOP;
		stop.offsetPct = 3;
		t.submit(stop);
		t.submit(OrderRequest.market("HYPE/USDC", Side.BUY, 1500));
		OrderRequest twap = OrderRequest.market("BTC", Side.BUY, 3000);
		twap.type = OrderRequest.Type.TWAP;
		twap.minutes = 10;
		t.submit(twap);
		t.scheduleCancel(System.currentTimeMillis() + 300_000);

		// leverage dial: a redstone block (signal 15) sets BTC to the top of its 2-12x range
		run(server, "setblock -2 -60 -4 stonkcraft:leverage_dial{Coin:\"BTC\",Min:2,Max:12}");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(-2, -60, -4))).swapCard(card(momentum));
		run(server, "setblock -2 -60 -3 minecraft:redstone_block");
		// fill detector lights a lamp whenever Momentum Bot fills on BTC
		run(server, "setblock 2 -60 -4 stonkcraft:market_sensor{Coin:\"BTC\",Mode:\"FILLED\"}");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(2, -60, -4))).swapCard(card(momentum));
		run(server, "setblock 2 -60 -3 minecraft:redstone_lamp");
		// kill switch, pulsed later
		run(server, "setblock 0 -60 -3 stonkcraft:kill_switch");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(0, -60, -3))).swapCard(card(momentum));
		String[] metrics = {"EQUITY", "PNL", "UPNL", "MARGIN_USAGE"};
		for (int i = 0; i < metrics.length; i++) {
			BlockPos dp = new BlockPos(-2 + i, -58, -6);
			run(server, "setblock " + dp.getX() + " -58 -6 stonkcraft:account_display[facing=south]{Metric:\"" + metrics[i] + "\"}");
			((HcBlockEntity) level.getBlockEntity(dp)).swapCard(card(momentum));
		}

		// watch-only: HLP's public address, with an equity board, a 24h PnL board and a sensor + lamp
		Account hlp = reg.createWatch(player, "HLP (watch)", "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303");
		watchId = hlp.id.toString();
		run(server, "setblock 3 -60 -6 stonkcraft:account_vault[facing=south]");
		((dev.stonkcraft.block.AccountVaultBlockEntity) level.getBlockEntity(new BlockPos(3, -60, -6))).bind(hlp);
		run(server, "setblock 3 -59 -6 stonkcraft:account_display[facing=south]{Metric:\"PNL_DAY\"}");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(3, -59, -6))).swapCard(card(hlp));
		run(server, "setblock 4 -59 -6 stonkcraft:account_display[facing=south]{Metric:\"EQUITY\"}");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(4, -59, -6))).swapCard(card(hlp));
		run(server, "setblock 4 -60 -6 stonkcraft:market_sensor{Mode:\"ACCOUNT_ABOVE\",Metric:\"PNL_DAY\",A:0d,Coin:\"\"}");
		((HcBlockEntity) level.getBlockEntity(new BlockPos(4, -60, -6))).swapCard(card(hlp));
		run(server, "setblock 5 -60 -6 minecraft:redstone_lamp");
	}

	/** Trailing executor (amend, price from the west face, My-order comparator -> 5-block wire -> lamp) and a cancel-after one. */
	private static void batch2Setup(MinecraftServer server) {
		ServerLevel level = server.overworld();
		var reg = Stonkcraft.accounts();
		Account momentum = null;
		for (Account a : reg.accessible(server.getPlayerList().getPlayers().get(0))) if (a.name.equals("Momentum Bot")) momentum = a;
		run(server, "fill -9 -61 0 -1 -61 9 minecraft:polished_deepslate");
		run(server, "fill -9 -60 0 -1 -57 9 minecraft:air");
		run(server, "setblock -6 -60 1 stonkcraft:trade_executor");
		var e1 = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(new BlockPos(-6, -60, 1));
		JsonObject c1 = e1.configJson();
		c1.addProperty("coin", "BTC");
		c1.addProperty("side", "BUY");
		c1.addProperty("usd", 2000);
		c1.addProperty("type", "LIMIT");
		c1.addProperty("offsetMode", true);
		c1.addProperty("cooldown", 1);
		c1.addProperty("onRepeat", "AMEND");
		c1.addProperty("comparatorOrder", true);
		JsonObject px = c1.getAsJsonObject("inputs").getAsJsonObject("PRICE");
		px.addProperty("source", "WEST");
		px.addProperty("lo", -2);
		px.addProperty("hi", 1);
		e1.applyConfig(c1);
		e1.swapCard(card(momentum));
		run(server, "setblock -6 -60 2 minecraft:comparator[facing=north]");
		run(server, "fill -6 -60 3 -6 -60 7 minecraft:redstone_wire");
		run(server, "setblock -6 -60 8 minecraft:redstone_lamp");

		run(server, "setblock -3 -60 1 stonkcraft:trade_executor");
		var e2 = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(new BlockPos(-3, -60, 1));
		JsonObject c2 = e2.configJson();
		c2.addProperty("coin", "ETH");
		c2.addProperty("side", "BUY");
		c2.addProperty("usd", 1000);
		c2.addProperty("type", "LIMIT");
		c2.addProperty("offsetMode", true);
		c2.addProperty("offsetPct", -3);
		c2.addProperty("cooldown", 1);
		c2.addProperty("cancelAfter", 5);
		c2.addProperty("comparatorOrder", true);
		e2.applyConfig(c2);
		e2.swapCard(card(momentum));
		run(server, "setblock -3 -60 2 minecraft:comparator[facing=north]");
		run(server, "setblock -3 -60 3 minecraft:redstone_lamp");
	}

	// ---- fill-to-redstone latency: On-fill sensors on a busy public account (HLP Strategy A), watched read-only

	private static final String[] LAT_COINS = {"ALT", "ZORA", "ZK", "BIO", "VVV", "XAI", "W", "BTC", "ETH", "SOL", "HYPE"};
	private static final java.util.List<Long> LATENCIES = new java.util.ArrayList<>();
	private static final int[] LAT_PREV = new int[LAT_COINS.length];
	private static int latFedSamples;
	private static long latStart;
	private static java.util.UUID latAccount;

	private static void latencySetup(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerPlayer player = server.getPlayerList().getPlayers().get(0);
		Account strat = Stonkcraft.accounts().createWatch(player, "HLP Strategy A", "0x010461c14e146ac35fe42271bdc1134ee31c703a");
		latAccount = strat.id;
		for (int i = 0; i < LAT_COINS.length; i++) {
			BlockPos p = new BlockPos(-9 + i, -60, 9);
			run(server, "setblock " + p.getX() + " -60 9 stonkcraft:market_sensor{Coin:\"" + LAT_COINS[i] + "\",Mode:\"FILLED\"}");
			((HcBlockEntity) level.getBlockEntity(p)).swapCard(card(strat));
		}
		latStart = System.currentTimeMillis();
		Stonkcraft.LOG.info("[latency] watching HLP Strategy A with {} On-fill sensors", LAT_COINS.length);
	}

	/** Every client tick: when a sensor's output rises, how long after the exchange's fill timestamp did redstone see it? */
	private static void latencySample(MinecraftServer server) {
		if (latAccount == null) return;
		long now = System.currentTimeMillis();
		var acct = Stonkcraft.accounts().trading(latAccount);
		if (acct == null) return;
		for (int i = 0; i < LAT_COINS.length; i++) {
			BlockPos p = new BlockPos(-9 + i, -60, 9);
			var state = server.overworld().getBlockState(p);
			if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)) continue;
			int power = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER);
			if (power > 0 && LAT_PREV[i] == 0 && now - latStart > 15_000) {
				long fill = acct.lastFillTime(LAT_COINS[i]);
				if (fill > 0) {
					LATENCIES.add(now - fill);
					if (acct instanceof dev.stonkcraft.trading.WatchAccount la && la.hasLiveFeed()) latFedSamples++;
				}
			}
			LAT_PREV[i] = power;
		}
	}

	private static void latencyReport() {
		var sorted = new java.util.ArrayList<>(LATENCIES);
		java.util.Collections.sort(sorted);
		if (sorted.isEmpty()) {
			Stonkcraft.LOG.info("[latency] no fills seen");
			return;
		}
		long under = sorted.stream().filter(v -> v < 1000).count();
		Stonkcraft.LOG.info("[latency] fill->redstone over {} sensor pulses ({} while live-fed): median {} ms, p90 {} ms, max {} ms, under 1s: {}/{}",
				sorted.size(), latFedSamples, sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.9)), sorted.get(sorted.size() - 1),
				under, sorted.size());
	}

	// ---- permissions: a fund shared with "Alice" (trade, $500/order, BTC only, 5x) and attempted by "Mallory"

	private static final java.util.UUID ALICE = java.util.UUID.nameUUIDFromBytes("demo-alice".getBytes());
	private static final java.util.UUID MALLORY = java.util.UUID.nameUUIDFromBytes("demo-mallory".getBytes());
	private static String secFundId = "";
	private static String secMalloryCard = "";

	private static Account secFund() {
		return Stonkcraft.accounts().get(java.util.UUID.fromString(secFundId));
	}

	private static net.minecraft.client.gui.screens.Screen accessTab(int tab) {
		var s = new dev.stonkcraft.client.screen.AccessScreen(secFundId, null);
		try {
			var f = s.getClass().getDeclaredField("tab");
			f.setAccessible(true);
			f.setInt(s, tab);
		} catch (ReflectiveOperationException ignored) {
		}
		return s;
	}

	private static void securitySetup(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerPlayer owner = server.getPlayerList().getPlayers().get(0);
		var reg = Stonkcraft.accounts();
		Account fund = reg.createPaper(owner, "Shared Fund");
		reg.deposit(fund, 20_000);
		secFundId = fund.id.toString();
		Account.Grant alice = new Account.Grant();
		alice.player = ALICE;
		alice.name = "Alice";
		alice.role = Account.Role.TRADE;
		alice.maxOrderUsd = 500;
		alice.maxLeverage = 5;
		alice.markets.add("BTC");
		alice.expiresAt = System.currentTimeMillis() + 48 * 3_600_000L;
		reg.setGrant(fund, alice);
		Account.Grant bob = new Account.Grant();
		bob.player = java.util.UUID.nameUUIDFromBytes("demo-bob".getBytes());
		bob.name = "Bob";
		bob.role = Account.Role.VIEW;
		reg.setGrant(fund, bob);
		reg.logEvent(fund, owner, "Gave Alice trade access, Bob view access");
		run(server, "fill 7 -61 -10 12 -61 -6 minecraft:polished_deepslate");
		// three executors holding the owner's trade cards, owned by Alice (x2) and Mallory
		int[][] spots = {{8, -8}, {10, -8}, {12, -8}};
		java.util.UUID[] owners = {ALICE, ALICE, MALLORY};
		String[] names = {"Alice", "Alice", "Mallory"};
		double[] sizes = {400, 900, 300};
		for (int i = 0; i < 3; i++) {
			BlockPos p = new BlockPos(spots[i][0], -60, spots[i][1]);
			run(server, "setblock " + p.getX() + " -60 " + p.getZ() + " stonkcraft:trade_executor");
			var e = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(p);
			e.setOwner(owners[i], names[i]);
			JsonObject c = e.configJson();
			c.addProperty("coin", "BTC");
			c.addProperty("side", "BUY");
			c.addProperty("usd", sizes[i]);
			c.addProperty("type", "MARKET");
			c.addProperty("cooldown", 1);
			e.applyConfig(c);
			ItemStack card = dev.stonkcraft.net.ServerNet.cardItem(reg, fund, "TRADE", names[i] + "'s executor");
			e.swapCard(card);
			if (i == 2) secMalloryCard = card.get(ModComponents.ACCOUNT).cardId();
		}
		reg.issueCard(fund, "READ", "lobby display");
	}

	private static void securityPulse(MinecraftServer server, String what) {
		Stonkcraft.LOG.info("[security] {}", what);
		for (int x : new int[]{8, 10, 12}) pulse(server, x, -8);
	}

	private static void securityLog(MinecraftServer server, String when) {
		ServerLevel level = server.overworld();
		for (int x : new int[]{8, 10, 12}) {
			var e = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(new BlockPos(x, -60, -8));
			Stonkcraft.LOG.info("[security] {} executor @{} ({}): {}", when, x, e.ownerName(), e.lastMessage);
		}
		Account fund = secFund();
		Stonkcraft.LOG.info("[security] {} fund BTC position {}", when, Stonkcraft.accounts().trading(fund.id).positionSize("BTC"));
	}

	/** A one-shot redstone pulse into the executor's north face. */
	private static void pulse(MinecraftServer server, int x, int z) {
		run(server, "setblock " + x + " -60 " + (z - 1) + " minecraft:redstone_block");
		server.execute(() -> run(server, "setblock " + x + " -60 " + (z - 1) + " minecraft:air"));
	}

	private static void logExecutor(MinecraftServer server, String when, int x, int z) {
		var e = (dev.stonkcraft.block.TradeExecutorBlockEntity) server.overworld().getBlockEntity(new BlockPos(x, -60, z));
		var acct = Stonkcraft.accounts().trading(e.cardRef().uuid());
		String px = "";
		for (var el : acct.snapshot().getAsJsonArray("orders")) {
			JsonObject o = el.getAsJsonObject();
			if (o.get("coin").getAsString().equals(e.coin) && o.get("type").getAsString().startsWith("Limit")) px += " " + o.get("px").getAsString();
		}
		Stonkcraft.LOG.info("[batch2] {}: status={} comparator={} lastMessage='{}' limit orders on {}:{}", when, e.myOrderStatus(), e.comparatorOutput(),
				e.lastMessage, e.coin, px);
	}

	/** A real, revocable trade card (setblock'd blocks are claimed for the player by {@link #claimBlocks}). */
	private static ItemStack card(Account a) {
		return dev.stonkcraft.net.ServerNet.cardItem(Stonkcraft.accounts(), a, "TRADE", "demo");
	}

	/** Blocks placed with /setblock have no owner; give unowned ones to the demo player, as placing them would. */
	private static void claimBlocks(MinecraftServer server) {
		ServerPlayer p = server.getPlayerList().getPlayers().get(0);
		ServerLevel level = server.overworld();
		for (BlockPos pos : BlockPos.betweenClosed(-24, -62, -24, 24, -50, 24)) {
			if (level.getBlockEntity(pos) instanceof HcBlockEntity be && be.owner() == null) be.setOwner(p.getUUID(), p.getGameProfile().getName());
		}
	}

	private static void run(MinecraftServer server, String cmd) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
	}
}
