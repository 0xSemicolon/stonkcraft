package dev.hypercraft.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.config.HyperConfig;
import dev.hypercraft.market.MarketData;
import dev.hypercraft.net.ServerNet;
import dev.hypercraft.trading.Account;
import dev.hypercraft.trading.AccountRegistry;
import dev.hypercraft.trading.Fmt;
import dev.hypercraft.trading.Side;
import dev.hypercraft.trading.TradingAccount;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /hl price|buy|sell|long|short|close|portfolio|accounts|select|status|mint|deposit|speedrun|signals|market */
public final class HlCommand {
	private HlCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		var root = Commands.literal("hl")
				.then(Commands.literal("price").then(Commands.argument("coin", StringArgumentType.word()).executes(HlCommand::price)))
				.then(Commands.literal("close").then(Commands.argument("coin", StringArgumentType.word())
						.executes(c -> order(c, Side.CLOSE, 0))))
				.then(Commands.literal("portfolio").executes(HlCommand::portfolio))
				.then(Commands.literal("accounts").executes(HlCommand::accounts))
				.then(Commands.literal("select").then(Commands.argument("name", StringArgumentType.greedyString()).executes(HlCommand::select)))
				.then(Commands.literal("status").executes(HlCommand::status))
				// money from nowhere: an op's tool only
				.then(Commands.literal("mint").requires(s -> s.hasPermission(2))
						.executes(c -> mint(c, (long) HyperConfig.get().paperFaucetUsd))
						.then(Commands.argument("usd", DoubleArgumentType.doubleArg(1))
								.executes(c -> mint(c, (long) DoubleArgumentType.getDouble(c, "usd")))))
				.then(Commands.literal("deposit").executes(HlCommand::deposit))
				.then(Commands.literal("speedrun")
						.then(Commands.literal("start").executes(c -> say(c, dev.hypercraft.game.Speedruns.start(c.getSource().getPlayerOrException()))))
						.then(Commands.literal("stop").executes(c -> say(c, dev.hypercraft.game.Speedruns.stop(c.getSource().getPlayerOrException()))))
						.then(Commands.literal("top").executes(c -> {
							for (String line : dev.hypercraft.game.Speedruns.top()) c.getSource().sendSuccess(() -> Component.literal(line), false);
							return 1;
						})))
				.then(Commands.literal("signals").executes(HlCommand::signals))
				// command blocks and datapacks read a signal through the scoreboard
				.then(Commands.literal("signal").requires(s -> s.hasPermission(2))
						.then(Commands.literal("score")
								.then(Commands.literal("clear").executes(HlCommand::scoreClear))
								.then(Commands.argument("objective", StringArgumentType.word()).executes(c -> score(c, 1))
										.then(Commands.argument("scale", DoubleArgumentType.doubleArg(-1e9, 1e9))
												.executes(c -> score(c, DoubleArgumentType.getDouble(c, "scale")))))))
				.then(Commands.literal("market").executes(c -> say(c, "§b" + dev.hypercraft.game.MarketClock.describe(dev.hypercraft.game.MarketClock.now()))));
		for (Side side : new Side[]{Side.BUY, Side.SELL, Side.LONG, Side.SHORT}) {
			root.then(Commands.literal(side.name().toLowerCase())
					.then(Commands.argument("coin", StringArgumentType.word())
							.then(Commands.argument("usd", DoubleArgumentType.doubleArg(0))
									.executes(c -> order(c, side, DoubleArgumentType.getDouble(c, "usd"))))));
		}
		d.register(root);
	}

	private static int say(CommandContext<CommandSourceStack> c, String msg) {
		c.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	/** The Signal Card in the player's main hand, or null (having said why). */
	@org.jetbrains.annotations.Nullable
	private static dev.hypercraft.registry.ModComponents.SignalRef heldSignal(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var ref = c.getSource().getPlayerOrException().getMainHandItem().get(dev.hypercraft.registry.ModComponents.SIGNAL);
		if (ref == null) c.getSource().sendFailure(Component.literal("Hold a Signal Card in your main hand"));
		return ref;
	}

	private static int score(CommandContext<CommandSourceStack> c, double scale) throws CommandSyntaxException {
		var ref = heldSignal(c);
		var sig = HyperCraft.signals();
		if (ref == null || sig == null) return 0;
		String objective = StringArgumentType.getString(c, "objective");
		if (c.getSource().getServer().getScoreboard().getObjective(objective) == null) {
			c.getSource().sendFailure(Component.literal("No objective " + objective + ": make it first with /scoreboard objectives add " + objective + " dummy"));
			return 0;
		}
		String holder = sig.scoreTap(ref.id(), ref.name(), objective, scale);
		return say(c, "§aEvery second, " + ref.name() + " × " + Fmt.size(scale) + " goes to §f" + holder + "§a in §f" + objective
				+ "§7 (/scoreboard players get " + holder + " " + objective + ")");
	}

	private static int scoreClear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		var ref = heldSignal(c);
		var sig = HyperCraft.signals();
		if (ref == null || sig == null) return 0;
		int n = sig.clearScoreTaps(ref.id());
		return say(c, n == 0 ? "§7" + ref.name() + " wasn't going to the scoreboard" : "§a" + ref.name() + " no longer goes to the scoreboard");
	}

	private static int signals(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		var sig = HyperCraft.signals();
		if (sig == null) return 0;
		var nodes = sig.ownedNodes(p.getUUID());
		var desks = sig.ownedDesks(p.getUUID());
		if (nodes.isEmpty() && desks.isEmpty()) return say(c, "§7No signals or copy desks yet. Place a Signal Mixer or a Copy Desk (terminal → Build).");
		for (var n : nodes) say(c, "§d◇ §f" + sig.describe(n));
		for (var d : desks) say(c, "§b⧉ §f" + sig.describe(d) + " §7" + d.status);
		return 1;
	}

	private static int price(CommandContext<CommandSourceStack> c) {
		MarketData md = HyperCraft.market();
		String coin = StringArgumentType.getString(c, "coin");
		MarketData.Ticker t = md == null ? null : md.ticker(coin);
		if (t == null) {
			c.getSource().sendFailure(Component.literal("Unknown coin " + coin));
			return 0;
		}
		double ch = t.dayChangePct();
		c.getSource().sendSuccess(() -> Component.literal(String.format("§b%s §f%s %s%s §7funding %.1f%% APR, vol %s",
				t.asset.name(), Fmt.price(t.mid), ch >= 0 ? "§a" : "§c", Fmt.pct(ch), t.funding * 24 * 365 * 100, Fmt.usdCompact(t.volume))), false);
		return 1;
	}

	private static int order(CommandContext<CommandSourceStack> c, Side side, double usd) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		// commands trade by hand too, so only at a terminal
		String away = ServerNet.terminalCheck(p);
		if (away != null) {
			c.getSource().sendFailure(Component.literal(away));
			return 0;
		}
		ServerNet.placeOrder(p, StringArgumentType.getString(c, "coin"), side, usd);
		return 1;
	}

	private static int mint(CommandContext<CommandSourceStack> c, long usd) throws CommandSyntaxException {
		String err = ServerNet.mintPaper(c.getSource().getPlayerOrException(), usd, true);
		if (err != null) c.getSource().sendFailure(Component.literal(err));
		return err == null ? 1 : 0;
	}

	private static int deposit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		AccountRegistry reg = HyperCraft.accounts();
		Account a = reg == null ? null : reg.selected(p);
		if (a == null) {
			c.getSource().sendFailure(Component.literal("No account selected. Press H to craft an Account Vault and open one there."));
			return 0;
		}
		ServerNet.depositCoins(p, reg, a);
		return 1;
	}

	private static int portfolio(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		AccountRegistry reg = HyperCraft.accounts();
		Account a = reg == null ? null : reg.selected(p);
		TradingAccount acct = a == null ? null : reg.trading(a.id);
		if (acct == null) {
			c.getSource().sendFailure(Component.literal("No usable account selected. Place an Account Vault, or /hl accounts."));
			return 0;
		}
		JsonObject snap = acct.snapshot();
		c.getSource().sendSuccess(() -> Component.literal(String.format("§b%s §8[%s] §fEquity %s §7(uPnL %s, free %s)", a.name, reg.tag(a),
				Fmt.usd(snap.get("equity").getAsDouble()), Fmt.usdSigned(snap.get("upnl").getAsDouble()), Fmt.usd(acct.withdrawable()))), false);
		for (JsonElement el : snap.getAsJsonArray("positions")) {
			JsonObject pos = el.getAsJsonObject();
			double size = pos.get("size").getAsDouble();
			double upnl = pos.get("upnl").getAsDouble();
			c.getSource().sendSuccess(() -> Component.literal(String.format("  %s%s %s §7%s @ %s §f→ %s%s",
					size > 0 ? "§a" : "§c", size > 0 ? "LONG" : "SHORT", pos.get("coin").getAsString(), Fmt.size(Math.abs(size)),
					Fmt.price(pos.get("entry").getAsDouble()), upnl >= 0 ? "§a" : "§c", Fmt.usdSigned(upnl))), false);
		}
		return 1;
	}

	private static int accounts(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		AccountRegistry reg = HyperCraft.accounts();
		if (reg == null) return 0;
		Account sel = reg.selected(p);
		var list = reg.accessible(p);
		if (list.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("§7No accounts yet. Place an §fAccount Vault§7 and right-click it."), false);
			return 0;
		}
		for (Account a : list) {
			TradingAccount t = reg.trading(a.id);
			String eq = t == null ? "§8unavailable" : "§f" + Fmt.usd(t.equity());
			String mark = sel != null && sel.id.equals(a.id) ? "§a▶ " : "  ";
			String who = a.owner.equals(p.getUUID()) ? "" : " §8(card, " + a.ownerName + ")";
			c.getSource().sendSuccess(() -> Component.literal(mark + "§b" + a.name + " §8[" + reg.tag(a) + "] " + eq + who), false);
		}
		return 1;
	}

	private static int select(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		AccountRegistry reg = HyperCraft.accounts();
		if (reg == null) return 0;
		String name = StringArgumentType.getString(c, "name");
		for (Account a : reg.accessible(p)) {
			if (a.name.equalsIgnoreCase(name)) {
				reg.select(p, a.id);
				c.getSource().sendSuccess(() -> Component.literal("§aNow trading " + a.name), false);
				return 1;
			}
		}
		c.getSource().sendFailure(Component.literal("No account named " + name));
		return 0;
	}

	private static int status(CommandContext<CommandSourceStack> c) {
		MarketData md = HyperCraft.market();
		HyperConfig cfg = HyperConfig.get();
		AccountRegistry reg = HyperCraft.accounts();
		String feed = md == null ? "§coffline" : md.healthy() ? "§alive (" + md.coinCount() + " perps)" : "§cstale: " + md.lastError();
		c.getSource().sendSuccess(() -> Component.literal("§bStonkcraft §7paper trading · feed " + feed + (cfg.isMainnet() ? "" : " §e(API " + cfg.apiBase() + ")")), false);
		var socket = md == null ? null : md.socket();
		if (socket != null) {
			int[] fc = reg == null ? new int[]{0, 0} : reg.feedCounts();
			c.getSource().sendSuccess(() -> Component.literal("§7WebSocket " + (md.feedLive() ? "§astreaming" : socket.connected() ? "§econnected" : "§creconnecting")
					+ " §7(" + socket.subscriptionCount() + " subscriptions, " + socket.userCount() + "/" + cfg.websocketMaxUsers + " users) · live-fed accounts §f"
					+ fc[0] + "/" + fc[1] + (fc[1] > fc[0] ? " §7(the rest poll every 3s)" : "")), false);
		} else {
			c.getSource().sendSuccess(() -> Component.literal("§7WebSocket off: polling REST"), false);
		}
		return 1;
	}
}
