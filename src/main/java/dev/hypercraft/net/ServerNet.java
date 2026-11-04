package dev.hypercraft.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hypercraft.HyperCraft;
import dev.hypercraft.block.AccountVaultBlockEntity;
import dev.hypercraft.block.HcBlockEntity;
import dev.hypercraft.config.HyperConfig;
import dev.hypercraft.item.Coins;
import dev.hypercraft.market.MarketData;
import dev.hypercraft.trading.AccessPolicy;
import dev.hypercraft.trading.Account;
import dev.hypercraft.trading.AccountRegistry;
import dev.hypercraft.trading.Fmt;
import dev.hypercraft.trading.OrderRequest;
import dev.hypercraft.trading.OrderResult;
import dev.hypercraft.trading.PaperAccount;
import dev.hypercraft.trading.Side;
import dev.hypercraft.trading.TradingAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.hypercraft.registry.ModBlocks;
import dev.hypercraft.registry.ModComponents;
import dev.hypercraft.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Server half of the protocol.
 * <p>Client → server: subscribe, unsubscribe, select_account, order, candles, configure,
 * vault_create, vault_link, vault_withdraw, vault_rename, vault_close.
 * <p>Server → client: market, accounts, portfolio, order_result, vault_result, candles, open_terminal,
 * open_sensor, open_executor, open_ticker, open_vault, open_vault_setup.
 */
public final class ServerNet {
	private ServerNet() {}

	private static final Map<String, Long> INTERVALS = Map.of(
			"1m", 60_000L, "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(HcNet.ToServer.TYPE, (payload, ctx) -> {
			ServerPlayer player = ctx.player();
			ctx.server().execute(() -> {
				try {
					handle(player, payload.kind(), payload.body());
				} catch (RuntimeException e) {
					HyperCraft.LOG.warn("Bad HyperCraft packet {} from {}", payload.kind(), player.getName().getString(), e);
				}
			});
		});
	}

	/** Trading packets each player may send: a 10-deep bucket refilled at 5 per second. */
	private static final Map<UUID, double[]> BUCKETS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Set<String> TRADING = Set.of("order", "cancel", "cancel_all", "set_leverage", "twap_cancel", "schedule_cancel",
			"adjust_margin", "vault_withdraw", "deposit_coins", "paper_mint", "unit_withdraw", "unit_deposit", "spot_transfer", "dex_transfer",
			"position_tpsl", "history", "craft");

	/** Where each player last opened a Trading Terminal: trading by hand happens there and nowhere else. */
	private static final Map<UUID, net.minecraft.core.GlobalPos> TERMINALS = new java.util.concurrent.ConcurrentHashMap<>();
	/** How far a player may stand from the terminal they opened and still trade on it. */
	public static final double TERMINAL_REACH = 8;
	/** Hand trading: what only works at a Trading Terminal. Blocks (executors, kill switches...) trade on their own. */
	private static final Set<String> AT_TERMINAL = Set.of("order", "cancel", "cancel_all", "set_leverage", "twap_cancel", "schedule_cancel",
			"adjust_margin", "spot_transfer", "dex_transfer", "position_tpsl");

	public static void openedTerminal(ServerPlayer p, BlockPos pos) {
		TERMINALS.put(p.getUUID(), net.minecraft.core.GlobalPos.of(p.level().dimension(), pos));
	}

	/** Null if the player stands at the Trading Terminal they opened (it's still there), else why they can't trade. */
	@Nullable
	public static String terminalCheck(ServerPlayer p) {
		net.minecraft.core.GlobalPos g = TERMINALS.get(p.getUUID());
		if (g == null || g.dimension() != p.level().dimension() || !p.level().getBlockState(g.pos()).is(ModBlocks.TERMINAL)) {
			return "Trade at a Trading Terminal: right-click one to open it";
		}
		if (p.distanceToSqr(g.pos().getCenter()) > TERMINAL_REACH * TERMINAL_REACH) return "You walked away from the Trading Terminal: go back to it to trade";
		return null;
	}

	private static boolean allow(ServerPlayer player) {
		long now = System.currentTimeMillis();
		double[] b = BUCKETS.computeIfAbsent(player.getUUID(), k -> new double[]{10, now});
		b[0] = Math.min(10, b[0] + (now - b[1]) / 200.0);
		b[1] = now;
		if (b[0] < 1) return false;
		b[0] -= 1;
		return true;
	}

	private static void handle(ServerPlayer player, String kind, JsonObject body) {
		AccountRegistry reg = HyperCraft.accounts();
		if (reg == null) return;
		if (TRADING.contains(kind) && !allow(player)) {
			orderResult(player, OrderResult.fail("", "Slow down: too many requests"), "HyperCraft");
			return;
		}
		String away = AT_TERMINAL.contains(kind) ? terminalCheck(player) : null;
		if (away != null) {
			orderResult(player, OrderResult.fail("", away), "HyperCraft");
			return;
		}
		switch (kind) {
			case "subscribe" -> {
				sendAccounts(player, reg);
				sendPortfolio(player, reg);
			}
			case "unsubscribe" -> {}
			case "select_account" -> {
				UUID id = uuid(body, "id");
				if (id != null) reg.select(player, id);
				sendAccounts(player, reg);
				sendPortfolio(player, reg);
			}
			case "order" -> {
				UUID id = uuid(body, "account");
				if (id != null) reg.select(player, id);
				placeOrder(player, OrderRequest.fromJson(body));
			}
			case "account_type" -> {
				Account a = reg.selected(player);
				PaperAccount p = a == null ? null : reg.paper(a.id);
				if (p == null || !a.owner.equals(player.getUUID())) {
					orderResult(player, OrderResult.fail("", "Only the owner of a paper account can change its type"), a == null ? "--" : a.name);
					return;
				}
				if (body.has("portfolioMargin")) {
					boolean pm = body.get("portfolioMargin").getAsBoolean();
					String why = p.setPortfolioMargin(pm);
					if (why == null) reg.logEvent(a, player, "Account type: " + (pm ? "Portfolio Margin" : "Unified"));
					orderResult(player, why != null ? OrderResult.fail("", why) : new OrderResult(true, a.name + (pm
							? " is now on Portfolio Margin: spot HYPE and BTC count as collateral" : " is back on Unified"), "", 0, 0), a.name);
					sendPortfolio(player, reg);
					return;
				}
				boolean unified = body.get("unified").getAsBoolean();
				if (!unified && p.portfolioMargin()) {
					String why = p.setPortfolioMargin(false);
					if (why != null) {
						orderResult(player, OrderResult.fail("", why), a.name);
						sendPortfolio(player, reg);
						return;
					}
				}
				p.setUnified(unified);
				reg.logEvent(a, player, "Account type: " + (unified ? "Unified" : "Manual"));
				orderResult(player, new OrderResult(true, a.name + " is now a " + (unified ? "Unified account: one USDC balance for spot and perps"
						: "Manual account: spot and perps USDC kept apart"), "", 0, 0), a.name);
				sendPortfolio(player, reg);
			}
			case "dex_transfer" -> {
				// USDC between the perps account and a HIP-3 dex's own balance, in Manual
				Account a = reg.selected(player);
				PaperAccount p = a == null ? null : reg.paper(a.id);
				String why = a == null ? "No account selected" : p == null ? "Only paper accounts move USDC between dexes"
						: AccessPolicy.checkTrading(a, player.getUUID(), System.currentTimeMillis());
				double usd = body.has("usd") ? body.get("usd").getAsDouble() : 0;
				String dex = body.has("dex") ? body.get("dex").getAsString() : "";
				boolean toDex = body.has("toDex") && body.get("toDex").getAsBoolean();
				if (why == null) why = p.transferDex(usd, dex, toDex);
				if (why == null) reg.logEvent(a, player, "Moved " + Fmt.usd(usd) + " USDC " + (toDex ? "perps → " + dex : dex + " → perps"));
				orderResult(player, why == null ? new OrderResult(true, "Moved " + Fmt.usd(usd) + " USDC to " + (toDex ? dex : "perps"), "", 0, 0)
						: OrderResult.fail("", why), a == null ? "--" : a.name);
				sendPortfolio(player, reg);
			}
			case "position_tpsl" -> {
				double tp = body.has("tp") ? body.get("tp").getAsDouble() : 0;
				double sl = body.has("sl") ? body.get("sl").getAsDouble() : 0;
				if (!Double.isFinite(tp) || !Double.isFinite(sl) || tp < 0 || sl < 0) return;
				withAccount(player, reg, acct -> acct.setPositionTpSl(body.get("coin").getAsString(), tp, sl));
			}
			case "history" -> {
				Account a = reg.selected(player);
				TradingAccount t = a == null ? null : reg.trading(a.id);
				if (t != null) HcNet.send(player, "history", t.history());
			}
			case "spot_transfer" -> {
				// USDC between the spot and perps accounts: anyone who may trade the account may move it
				Account a = reg.selected(player);
				PaperAccount p = a == null ? null : reg.paper(a.id);
				String why = a == null ? "No account selected" : p == null ? "Only paper accounts move USDC between spot and perps"
						: AccessPolicy.checkTrading(a, player.getUUID(), System.currentTimeMillis());
				double usd = body.has("usd") ? body.get("usd").getAsDouble() : 0;
				boolean toPerps = body.has("toPerps") && body.get("toPerps").getAsBoolean();
				if (why == null) why = p.transfer(usd, toPerps);
				if (why == null) reg.logEvent(a, player, "Moved " + Fmt.usd(usd) + " USDC " + (toPerps ? "spot → perps" : "perps → spot"));
				orderResult(player, why == null ? new OrderResult(true, "Moved " + Fmt.usd(usd) + " USDC to " + (toPerps ? "perps" : "spot"), "", 0, 0)
						: OrderResult.fail("", why), a == null ? "--" : a.name);
				sendPortfolio(player, reg);
			}
			case "cancel" -> withAccount(player, reg, acct -> acct.cancel(body.get("coin").getAsString(), body.get("oid").getAsLong()));
			case "cancel_all" -> withAccount(player, reg, acct -> acct.cancelAll(body.has("coin") ? body.get("coin").getAsString() : null));
			case "set_leverage" -> {
				int lev = Math.max(1, Math.min(100, body.get("leverage").getAsInt()));
				withAccount(player, reg, acct -> acct.setLeverage(body.get("coin").getAsString(), lev, !body.has("cross") || body.get("cross").getAsBoolean()));
			}
			case "candles" -> fetchCandles(player, body.get("coin").getAsString(), body.get("interval").getAsString());
			case "twap_cancel" -> withAccount(player, reg, acct -> acct.cancelTwap(body.get("coin").getAsString(), body.get("id").getAsLong()));
			case "schedule_cancel" -> {
				long secs = Math.max(0, Math.min(86_400, body.get("seconds").getAsLong()));
				withAccount(player, reg, acct -> acct.scheduleCancel(secs <= 0 ? 0 : System.currentTimeMillis() + secs * 1000));
			}
			case "adjust_margin" -> {
				double usd = body.get("usd").getAsDouble();
				if (!Double.isFinite(usd) || Math.abs(usd) > 1e7) return;
				withAccount(player, reg, acct -> acct.adjustMargin(body.get("coin").getAsString(), usd));
			}
			case "access_get" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a != null && a.owner.equals(player.getUUID())) sendAccess(player, a);
			}
			case "grant_set" -> grantSet(player, reg, body);
			case "grant_remove" -> {
				Account a = reg.get(uuid(body, "id"));
				UUID who = uuid(body, "player");
				if (a == null || who == null || !a.owner.equals(player.getUUID())) return;
				Account.Grant g = a.grant(who);
				if (reg.removeGrant(a, who)) reg.logEvent(a, player, "Removed access for " + (g == null ? who : g.name));
				sendAccess(player, a);
			}
			case "card_scope" -> {
				// a card's limits only ever get tighter; loosening them takes a new card
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !a.owner.equals(player.getUUID())) return;
				Account.CardRecord c = a.card(body.get("card").getAsString());
				if (c == null || c.revoked) return;
				Account.Scope next = scope(body.getAsJsonObject("scope"), "READ".equals(c.level));
				if (next == null) {
					vaultResult(player, false, "Set at least one limit to apply");
				} else if (c.scope != null && !next.within(c.scope)) {
					vaultResult(player, false, "A card's limits can only get tighter: print a new card to loosen them");
				} else {
					c.scope = next;
					reg.save();
					reg.logEvent(a, player, "Limited card " + c.id.substring(0, 6) + ": " + next.summary());
					vaultResult(player, true, "Card " + c.id.substring(0, 6) + " now: " + next.summary());
				}
				sendAccess(player, a);
			}
			case "card_revoke" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !a.owner.equals(player.getUUID())) return;
				String cid = body.get("card").getAsString();
				if (reg.revokeCard(a, cid)) reg.logEvent(a, player, "Revoked card " + cid);
				sendAccess(player, a);
			}
			case "freeze" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !a.owner.equals(player.getUUID())) return;
				boolean on = body.get("on").getAsBoolean();
				reg.setFrozen(a, on);
				reg.logEvent(a, player, on ? "Froze the account: only the owner trades, blocks stopped" : "Unfroze the account");
				sendAccess(player, a);
				sendAccounts(player, reg);
			}
			case "book" -> {
				MarketData md = HyperCraft.market();
				MarketData.Book b = md == null ? null : md.book(body.get("coin").getAsString());
				if (b == null) return;
				JsonObject o = new JsonObject();
				o.addProperty("coin", md.resolve(body.get("coin").getAsString()));
				o.add("bids", levels(b.bids()));
				o.add("asks", levels(b.asks()));
				HcNet.send(player, "book", o);
			}
			case "funding_history" -> {
				MarketData md = HyperCraft.market();
				if (md == null) return;
				String coin = md.resolve(body.get("coin").getAsString());
				if (coin == null) return;
				MinecraftServer server = player.getServer();
				md.fundingHistory(coin, 72).thenAccept(rows -> server.execute(() -> {
					JsonObject o = new JsonObject();
					o.addProperty("coin", coin);
					o.add("rates", rows);
					HcNet.send(player, "funding_history", o);
				})).exceptionally(e -> null);
			}
			case "configure" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (!near(player, pos)) return;
				if (player.level().getBlockEntity(pos) instanceof HcBlockEntity be && be.canEdit(player)) {
					if (be.owner() == null) be.setOwner(player.getUUID(), player.getGameProfile().getName());
					be.applyConfig(body);
				}
			}
			case "fire_now" -> {
				// a block screen's test button: one pulse, exactly as redstone would fire it
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (!near(player, pos)) return;
				if (player.level().getBlockEntity(pos) instanceof HcBlockEntity be && be.canEdit(player)
						&& be instanceof dev.hypercraft.block.PulseBlock.Target t) t.onPulse((net.minecraft.server.level.ServerLevel) player.level(), 15);
			}
			case "vault_create" -> vaultCreate(player, reg, body);
			case "vault_link" -> {
				AccountVaultBlockEntity vault = blankVault(player, body);
				Account a = reg.get(uuid(body, "id"));
				if (vault == null || a == null) return;
				if (!reg.canManage(player, a)) return;
				vault.bind(a);
				openVault(player, vault, a);
			}
			case "vault_withdraw" -> vaultWithdraw(player, reg, body);
			case "deposit_coins" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !reg.canAccess(player, a)) return;
				depositCoins(player, reg, a);
			}
			case "unit_info" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (near(player, pos) && player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.UnitBridgeBlockEntity be) {
					HcNet.send(player, "unit_info", be.info(player));
				}
			}
			case "unit_deposit" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (near(player, pos) && player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.UnitBridgeBlockEntity be) {
					be.depositFrom(player, true, body.get("token").getAsString());
					HcNet.send(player, "unit_info", be.info(player));
				}
			}
			case "unit_withdraw" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				double units = body.get("units").getAsDouble();
				if (!Double.isFinite(units) || units <= 0 || !near(player, pos)) return;
				if (player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.UnitBridgeBlockEntity be) {
					be.withdrawTo(player, body.get("token").getAsString(), dev.hypercraft.unit.UnitBridge.Mode.parse(body.get("from").getAsString()), units);
					HcNet.send(player, "unit_info", be.info(player));
				}
			}
			case "ticket_save" -> {
				// the terminal's order, written the way an executor writes its own
				if (!canBuild(player) && !consumeBlankTicket(player)) {
					vaultResult(player, false, "Needs a Blank Order Ticket in your inventory (paper, paper and redstone)");
					return;
				}
				var scratch = new dev.hypercraft.block.TradeExecutorBlockEntity(BlockPos.ZERO, ModBlocks.EXECUTOR.defaultBlockState());
				JsonObject cfg = scratch.configJson();
				for (var e : body.entrySet()) cfg.add(e.getKey(), e.getValue());
				cfg.addProperty("offsetMode", false);
				cfg.addProperty("absPrice", body.has("price") ? body.get("price").getAsDouble() : 0);
				scratch.applyConfig(cfg);
				ItemStack t = scratch.acceptTicket(new ItemStack(ModItems.ORDER_TICKET));
				give(player, t);
				player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1.3f);
				vaultResult(player, true, "Wrote " + dev.hypercraft.item.OrderTicketItem.summary(t) + " on a ticket");
			}
			case "print_signal" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (!near(player, pos) || !(player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.HcBlockEntity be) || !be.canEdit(player) || !be.hasTap()) return;
				if (!canBuild(player) && !consumeBlankCard(player)) {
					blockResult(player, pos, false, "Needs a Blank Card in your inventory");
				} else {
					ItemStack card = be.printTap();
					give(player, card);
					blockResult(player, pos, true, "Printed a Signal Card: " + card.get(dev.hypercraft.registry.ModComponents.SIGNAL).name());
				}
			}
			case "mixer_info", "mixer_eject", "mixer_print" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (!near(player, pos) || !(player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.SignalMixerBlockEntity be) || !be.canEdit(player)) return;
				if (kind.equals("mixer_eject")) give(player, be.ejectInput(body.get("i").getAsInt()));
				if (kind.equals("mixer_print")) {
					if (!canBuild(player) && !consumeBlankCard(player)) {
						vaultResult(player, false, "Needs a Blank Card in your inventory");
					} else {
						give(player, be.printCard());
						vaultResult(player, true, "Printed a Signal Card for " + be.name);
					}
				}
				HcNet.send(player, "mixer_info", be.info());
			}
			case "desk_info", "desk_eject" -> {
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (!near(player, pos) || !(player.level().getBlockEntity(pos) instanceof dev.hypercraft.block.CopyDeskBlockEntity be) || !be.canEdit(player)) return;
				if (kind.equals("desk_eject")) give(player, "leader".equals(body.get("which").getAsString()) ? be.ejectLeader() : be.swapCard(ItemStack.EMPTY));
				HcNet.send(player, "desk_info", be.info());
			}
			case "paper_mint" -> {
				String err = mintPaper(player, body.has("usd") ? body.get("usd").getAsLong() : 10_000);
				if (err != null) vaultResult(player, false, err);
			}
			case "give" -> {
				if (!canBuild(player)) return;
				String id = body.get("item").getAsString();
				if (!GIVEABLE.contains(id)) return;
				Item item = BuiltInRegistries.ITEM.get(HyperCraft.id(id));
				give(player, new ItemStack(item, id.equals("account_card") ? 4 : 1));
			}
			case "craft" -> {
				// the build menu: a block's recipe out of what the player carries, wherever they stand
				String id = body.get("item").getAsString();
				if (!GIVEABLE.contains(id)) return;
				var holder = player.getServer().getRecipeManager().byKey(HyperCraft.id(id)).orElse(null);
				if (holder == null) return;
				var recipe = holder.value();
				ItemStack made = recipe.getResultItem(player.registryAccess()).copy();
				if (!dev.hypercraft.item.BlockCrafting.take(player.getInventory(), recipe.getIngredients())) {
					vaultResult(player, false, "Missing materials for " + made.getHoverName().getString());
					return;
				}
				String name = made.getHoverName().getString();
				give(player, made);
				vaultResult(player, true, "Crafted " + name);
			}
			case "starter_kit" -> {
				// everything a first redstone bot needs: a vault, a sensor, an executor, blank cards, a lever and dust
				if (!canBuild(player)) return;
				for (String id : new String[]{"account_vault", "market_sensor", "trade_executor"}) give(player, new ItemStack(BuiltInRegistries.ITEM.get(HyperCraft.id(id))));
				give(player, new ItemStack(BuiltInRegistries.ITEM.get(HyperCraft.id("account_card")), 4));
				give(player, new ItemStack(net.minecraft.world.item.Items.LEVER));
				give(player, new ItemStack(net.minecraft.world.item.Items.REDSTONE, 16));
				vaultResult(player, true, "Starter kit in your inventory: place the vault and open an account first");
			}
			case "account_new" -> {
				String name = body.has("name") ? body.get("name").getAsString() : "";
				Account a;
				try {
					a = switch (body.get("kind").getAsString()) {
						case "watch" -> reg.createWatch(player, name, body.get("address").getAsString());
						default -> reg.createPaper(player, name);
					};
				} catch (IllegalStateException e) {
					vaultResult(player, false, e.getMessage());
					return;
				}
				reg.select(player, a.id);
				if (canBuild(player)) {
					give(player, accountItem(a, true));
					give(player, cardItem(reg, a, "TRADE", ""));
					vaultResult(player, true, "Opened " + a.name + ". Its vault and a card are in your inventory.");
				} else {
					vaultResult(player, true, "Opened " + a.name + ".");
				}
				sendAccounts(player, reg);
				sendPortfolio(player, reg);
			}
			case "watch_address" -> {
				// any Hyperliquid address as a Watch Card: straight into a copy desk's leader slot or a mixer's inputs,
				// else into the inventory. The card is the one way an address reaches a block.
				Account a;
				try {
					a = reg.watchFor(player, body.get("address").getAsString());
				} catch (IllegalStateException e) {
					vaultResult(player, false, e.getMessage());
					return;
				}
				if (!canBuild(player) && !consumeBlankCard(player)) {
					vaultResult(player, false, "Needs a Blank Card in your inventory");
					return;
				}
				ItemStack card = cardItem(reg, a, "READ", "");
				reg.logEvent(a, player, "Printed a Watch Card");
				String where = "in your inventory";
				if (body.has("pos")) {
					BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
					var be = near(player, pos) ? player.level().getBlockEntity(pos) : null;
					if (be instanceof dev.hypercraft.block.CopyDeskBlockEntity desk && desk.canEdit(player)) {
						give(player, desk.watchLeader(card));
						card = ItemStack.EMPTY;
						where = "leading this desk";
						HcNet.send(player, "desk_info", desk.info());
					} else if (be instanceof dev.hypercraft.block.SignalMixerBlockEntity mixer && mixer.canEdit(player) && mixer.addInput(player, card) == null) {
						card = ItemStack.EMPTY;
						where = "an input of this mixer";
						HcNet.send(player, "mixer_info", mixer.info());
					}
				}
				give(player, card);
				player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1.4f);
				vaultResult(player, true, "Watching " + a.name + ": its Watch Card is " + where);
				sendAccounts(player, reg);
			}			case "vault_card" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !reg.canManage(player, a)) return;
				if (!canBuild(player) && !consumeBlankCard(player)) {
					vaultResult(player, false, "Needs a Blank Card in your inventory");
					return;
				}
				String level = body.has("level") && "READ".equals(body.get("level").getAsString()) ? "READ" : "TRADE";
				String label = body.has("label") ? body.get("label").getAsString() : "";
				Account.Scope scope = body.has("scope") ? scope(body.getAsJsonObject("scope"), "READ".equals(level)) : null;
				give(player, cardItem(reg, a, level, label, scope));
				String cardKind = "READ".equals(level) || a.kind == Account.Kind.WATCH ? "Watch Card" : "Trade Card";
				reg.logEvent(a, player, "Printed a " + cardKind + (label.isBlank() ? "" : " \"" + label.trim() + "\""));
				player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1.4f);
				vaultResult(player, true, "Printed a " + cardKind + " for " + a.name);
				sendAccess(player, a);
			}
			case "account_item" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !canBuild(player) || !reg.canManage(player, a)) return;
				give(player, accountItem(a, "vault".equals(body.get("what").getAsString())));
			}
			case "vault_rename" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !reg.canManage(player, a)) return;
				reg.rename(a, body.get("name").getAsString());
				vaultResult(player, true, "Renamed to " + a.name);
				sendAccounts(player, reg);
			}
			case "vault_close" -> {
				Account a = reg.get(uuid(body, "id"));
				if (a == null || !reg.canManage(player, a)) return;
				String err = reg.close(a);
				if (err != null) {
					vaultResult(player, false, err);
					return;
				}
				BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
				if (player.level().getBlockEntity(pos) instanceof AccountVaultBlockEntity vault) vault.bind(null);
				HcNet.send(player, "close_screen", new JsonObject());
				player.sendSystemMessage(Component.literal("§7Closed account §f" + a.name));
			}
			default -> HyperCraft.LOG.debug("Unknown packet kind {}", kind);
		}
	}

	/** Sweeps every coin of the account's backing out of the player's inventory and deposits it. */
	public static void depositCoins(ServerPlayer player, AccountRegistry reg, Account a) {
		var inv = player.getInventory();
		List<ItemStack> taken = new ArrayList<>();
		long usd = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			long v = Coins.value(s, a.backing());
			if (v == 0) continue;
			usd += v;
			taken.add(s.copy());
			inv.setItem(i, ItemStack.EMPTY);
		}
		if (usd == 0) {
			vaultResult(player, false, "No " + a.label().toLowerCase() + " coins in your inventory");
			return;
		}
		String err = reg.deposit(a, usd);
		if (err != null) {
			taken.forEach(s -> give(player, s));
			vaultResult(player, false, err);
			return;
		}
		player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.2f);
		vaultResult(player, true, "Deposited " + Fmt.usd(usd) + " into " + a.name);
		sendAccounts(player, reg);
		sendPortfolio(player, reg);
	}

	private static final Map<UUID, Long> LAST_MINT = new HashMap<>();

	/** Prints paper money into the player's inventory. Null on success, else why not. */
	public static String mintPaper(ServerPlayer player, long usd) {
		return mintPaper(player, usd, false);
	}

	/** {@code admin}: an op's /hl mint, which works with the mint turned off and has no cap or cooldown. */
	public static String mintPaper(ServerPlayer player, long usd, boolean admin) {
		HyperConfig cfg = HyperConfig.get();
		if (!cfg.paperFaucet && !admin) return "There's no free money here: bridge goods at a UNIT.XYZ to fund an account";
		if (dev.hypercraft.game.Speedruns.active(player.getUUID()) != null) return "No paper mint during a speedrun: mine gold and bridge it at a UNIT.XYZ";
		if (!admin) usd = Math.min(usd, (long) cfg.paperFaucetUsd);
		if (usd <= 0) return "Enter an amount";
		long now = System.currentTimeMillis();
		if (!canBuild(player) && !admin) {
			long wait = LAST_MINT.getOrDefault(player.getUUID(), 0L) + cfg.paperFaucetCooldownSeconds * 1000L - now;
			if (wait > 0) return "The mint is cooling down, try again in " + (wait / 1000 + 1) + "s";
		}
		LAST_MINT.put(player.getUUID(), now);
		for (ItemStack s : Coins.make(usd, "paper")) give(player, s);
		vaultResult(player, true, "Minted " + Fmt.usd(usd) + " in paper coins");
		return null;
	}

	private static final Set<String> GIVEABLE = Set.of("account_vault", "unit_bridge", "signal_mixer", "copy_desk", "trading_terminal", "ticker_display", "account_display", "market_sensor", "trade_executor",
			"leverage_dial", "kill_switch", "account_card");

	/** Free blocks, cards and prints: creative players, or everyone when the server turns the build menu on. */
	public static boolean canBuild(ServerPlayer p) {
		return HyperConfig.get().buildMenu || p.isCreative();
	}

	private static void give(ServerPlayer p, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!p.getInventory().add(stack)) p.drop(stack, false);
		p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.6f);
	}

	private static boolean consumeBlankTicket(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ModItems.ORDER_TICKET) && !s.has(ModComponents.ORDER_SPEC)) {
				s.shrink(1);
				return true;
			}
		}
		return false;
	}

	private static boolean consumeBlankCard(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ModItems.ACCOUNT_CARD) && !s.has(ModComponents.ACCOUNT)) {
				s.shrink(1);
				return true;
			}
		}
		return false;
	}

	private static void grantSet(ServerPlayer player, AccountRegistry reg, JsonObject body) {
		Account a = reg.get(uuid(body, "id"));
		if (a == null || !a.owner.equals(player.getUUID())) return;
		String name = body.get("name").getAsString().trim();
		com.mojang.authlib.GameProfile profile = null;
		ServerPlayer online = player.getServer().getPlayerList().getPlayerByName(name);
		if (online != null) profile = online.getGameProfile();
		else if (player.getServer().getProfileCache() != null) profile = player.getServer().getProfileCache().get(name).orElse(null);
		if (profile == null) {
			vaultResult(player, false, "No player called " + name + " has been on this server");
			return;
		}
		Account.Grant g = new Account.Grant();
		g.player = profile.getId();
		g.name = profile.getName();
		g.role = "TRADE".equals(body.get("role").getAsString()) ? Account.Role.TRADE : Account.Role.VIEW;
		g.maxOrderUsd = finite(body, "maxOrderUsd", 0, 1e9);
		g.maxLeverage = (int) finite(body, "maxLeverage", 0, 100);
		g.reduceOnly = body.has("reduceOnly") && body.get("reduceOnly").getAsBoolean();
		double hours = finite(body, "hours", 0, 24 * 365);
		g.expiresAt = hours > 0 ? System.currentTimeMillis() + (long) (hours * 3_600_000) : 0;
		if (body.has("markets")) {
			MarketData md = HyperCraft.market();
			for (String m : body.get("markets").getAsString().split("[,\\s]+")) {
				if (m.isBlank()) continue;
				String resolved = md == null ? null : md.resolve(m);
				g.markets.add(resolved != null ? resolved : m.toUpperCase(java.util.Locale.ROOT));
			}
		}
		String err = reg.setGrant(a, g);
		if (err != null) {
			vaultResult(player, false, err);
			return;
		}
		String summary = describeGrant(g);
		reg.logEvent(a, player, "Gave " + g.name + " " + summary);
		vaultResult(player, true, g.name + " can now " + (g.role == Account.Role.TRADE ? "trade" : "view") + " " + a.name);
		ServerPlayer grantee = player.getServer().getPlayerList().getPlayer(g.player);
		if (grantee != null) {
			grantee.sendSystemMessage(Component.literal("§b[Stonkcraft] §f" + player.getGameProfile().getName() + "§7 gave you " + summary + " on §f" + a.name));
			sendAccounts(grantee, reg);
		}
		sendAccess(player, a);
	}

	/** A card scope from the screen's fields (markets, maxOrderUsd, maxLeverage, reduceOnly, hours); null if it sets nothing. A Watch Card only expires. */
	@org.jetbrains.annotations.Nullable
	private static Account.Scope scope(JsonObject o, boolean watch) {
		Account.Scope s = new Account.Scope();
		double hours = finite(o, "hours", 0, 24 * 365);
		s.expiresAt = hours > 0 ? System.currentTimeMillis() + (long) (hours * 3_600_000) : 0;
		if (!watch) {
			s.maxOrderUsd = finite(o, "maxOrderUsd", 0, 1e9);
			s.maxLeverage = (int) finite(o, "maxLeverage", 0, 100);
			s.reduceOnly = o.has("reduceOnly") && o.get("reduceOnly").getAsBoolean();
			if (o.has("markets")) {
				MarketData md = HyperCraft.market();
				for (String m : o.get("markets").getAsString().split("[,\\s]+")) {
					if (m.isBlank()) continue;
					String resolved = md == null ? null : md.resolve(m);
					s.markets.add(resolved != null ? resolved : m.toUpperCase(java.util.Locale.ROOT));
				}
			}
		}
		return s.empty() ? null : s;
	}

	private static double finite(JsonObject o, String key, double min, double max) {
		if (!o.has(key)) return 0;
		double v = o.get(key).getAsDouble();
		return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : 0;
	}

	/** "trade access (≤ $500/order, ≤ 5x, BTC ETH, reduce-only, 24h)". */
	public static String describeGrant(Account.Grant g) {
		List<String> parts = new ArrayList<>();
		if (g.role == Account.Role.TRADE) {
			if (g.maxOrderUsd > 0) parts.add("≤ " + Fmt.usd(g.maxOrderUsd).replace(".00", "") + "/order");
			if (g.maxLeverage > 0) parts.add("≤ " + g.maxLeverage + "x");
			if (!g.markets.isEmpty()) parts.add(String.join(" ", g.markets));
			if (g.reduceOnly) parts.add("reduce-only");
		}
		if (g.expiresAt > 0) {
			long h = Math.max(0, (g.expiresAt - System.currentTimeMillis()) / 3_600_000);
			parts.add(h >= 48 ? (h / 24) + "d left" : h + "h left");
		}
		return (g.role == Account.Role.TRADE ? "trade" : "view") + " access" + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
	}

	/** How the account is held, for the custody badge: PAPER or WATCH. */
	public static String custody(AccountRegistry reg, Account a) {
		return a.kind == Account.Kind.PAPER ? "PAPER" : "WATCH";
	}

	/** Owner's Access screen: grants, cards, freeze and the activity log. */
	private static void sendAccess(ServerPlayer player, Account a) {
		JsonObject o = new JsonObject();
		o.addProperty("id", a.id.toString());
		o.addProperty("name", a.name);
		o.addProperty("frozen", a.frozen);
		o.addProperty("kind", a.kind.name());
		long now = System.currentTimeMillis();
		JsonArray grants = new JsonArray();
		for (Account.Grant g : a.grants) {
			JsonObject j = new JsonObject();
			j.addProperty("player", g.player.toString());
			j.addProperty("name", g.name);
			j.addProperty("role", g.role.name());
			j.addProperty("summary", describeGrant(g));
			j.addProperty("expired", g.expired(now));
			grants.add(j);
		}
		o.add("grants", grants);
		JsonArray cards = new JsonArray();
		for (int i = a.cards.size() - 1; i >= 0; i--) {
			Account.CardRecord c = a.cards.get(i);
			JsonObject j = new JsonObject();
			j.addProperty("id", c.id);
			j.addProperty("level", c.level);
			j.addProperty("label", c.label);
			j.addProperty("issued", c.issued);
			j.addProperty("revoked", c.revoked);
			j.addProperty("scope", c.scope == null ? "" : c.scope.summary());
			j.addProperty("expired", c.scope != null && c.scope.expired(System.currentTimeMillis()));
			cards.add(j);
		}
		o.add("cards", cards);
		JsonArray log = new JsonArray();
		int n = 0;
		for (Account.Activity e : a.activity) {
			if (n++ >= 60) break;
			JsonObject j = new JsonObject();
			j.addProperty("t", e.time());
			j.addProperty("actor", e.actor());
			j.addProperty("via", e.via());
			j.addProperty("text", e.text());
			j.addProperty("ok", e.ok());
			log.add(j);
		}
		o.add("activity", log);
		HcNet.send(player, "access", o);
	}

	/**
	 * A newly issued, revocable card: a Watch Card at READ level, or for a watch-only account (which can't trade
	 * anyway), else a Trade Card.
	 */
	public static ItemStack cardItem(AccountRegistry reg, Account a, String level, String label) {
		return cardItem(reg, a, level, label, null);
	}

	/** As {@link #cardItem(AccountRegistry, Account, String, String)}, carrying its own limits (null = none). */
	public static ItemStack cardItem(AccountRegistry reg, Account a, String level, String label, @org.jetbrains.annotations.Nullable Account.Scope scope) {
		boolean watch = "READ".equals(level) || a.kind == Account.Kind.WATCH;
		Account.CardRecord c = reg.issueCard(a, watch ? "READ" : "TRADE", label);
		if (scope != null) {
			c.scope = scope;
			reg.save();
		}
		ItemStack s = new ItemStack(watch ? ModItems.WATCH_CARD : ModItems.ACCOUNT_CARD);
		s.set(ModComponents.ACCOUNT, new ModComponents.AccountRef(a.id.toString(), a.name, c.id, c.level, ""));
		if (scope != null) s.set(ModComponents.CARD_SCOPE, scope.summary());
		if (!c.label.isEmpty()) s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(a.name + " · " + c.label));
		return s;
	}

	/** A vault item or card that carries the account. */
	private static ItemStack accountItem(Account a, boolean vault) {
		ItemStack s = new ItemStack(vault ? ModBlocks.VAULT.asItem() : a.kind == Account.Kind.WATCH ? ModItems.WATCH_CARD : ModItems.ACCOUNT_CARD);
		s.set(ModComponents.ACCOUNT, new ModComponents.AccountRef(a.id.toString(), a.name, "", "", ""));
		return s;
	}

	private static JsonArray levels(double[][] side) {
		JsonArray out = new JsonArray();
		for (double[] l : side) {
			JsonArray row = new JsonArray();
			row.add(l[0]);
			row.add(l[1]);
			out.add(row);
		}
		return out;
	}

	private static boolean near(ServerPlayer p, BlockPos pos) {
		return p.distanceToSqr(pos.getCenter()) <= 64 * 64;
	}

	@Nullable
	private static UUID uuid(JsonObject o, String key) {
		try {
			return o.has(key) && !o.get(key).getAsString().isEmpty() ? UUID.fromString(o.get(key).getAsString()) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Nullable
	private static AccountVaultBlockEntity blankVault(ServerPlayer player, JsonObject body) {
		BlockPos pos = BlockPos.of(body.get("pos").getAsLong());
		if (!near(player, pos)) return null;
		if (player.level().getBlockEntity(pos) instanceof AccountVaultBlockEntity vault && vault.canEdit(player) && vault.accountData() == null) {
			return vault;
		}
		return null;
	}

	private static void vaultCreate(ServerPlayer player, AccountRegistry reg, JsonObject body) {
		AccountVaultBlockEntity vault = blankVault(player, body);
		if (vault == null) return;
		String name = body.has("name") ? body.get("name").getAsString() : "";
		Account a;
		try {
			a = switch (body.get("kind").getAsString()) {
				case "watch" -> reg.createWatch(player, name, body.get("address").getAsString());
				default -> reg.createPaper(player, name);
			};
		} catch (IllegalStateException e) {
			vaultResult(player, false, e.getMessage());
			return;
		}
		vault.bind(a);
		reg.select(player, a.id);
		player.level().playSound(null, vault.getBlockPos(), SoundEvents.VAULT_ACTIVATE, SoundSource.BLOCKS, 1f, 1f);
		// "Open & deposit": the paper coins the player carries go straight in
		if (body.has("deposit") && body.get("deposit").getAsBoolean() && a.kind == Account.Kind.PAPER) depositCoins(player, reg, a);
		openVault(player, vault, a);
	}

	private static void openVault(ServerPlayer player, AccountVaultBlockEntity vault, Account a) {
		AccountRegistry reg = HyperCraft.accounts();
		JsonObject o = new JsonObject();
		o.addProperty("pos", vault.getBlockPos().asLong());
		o.addProperty("id", a.id.toString());
		o.addProperty("owner", HyperCraft.accounts() != null && HyperCraft.accounts().canManage(player, a));
		if (reg != null) sendAccounts(player, reg);
		HcNet.send(player, "open_vault", o);
	}

	private static void vaultWithdraw(ServerPlayer player, AccountRegistry reg, JsonObject body) {
		Account a = reg.get(uuid(body, "id"));
		if (a == null) return;
		if (!reg.canManage(player, a)) {
			vaultResult(player, false, "Only " + a.ownerName + " can withdraw from " + a.name);
			return;
		}
		double raw = body.get("usd").getAsDouble();
		if (!Double.isFinite(raw) || raw <= 0 || raw > 1e9) {
			vaultResult(player, false, "Enter an amount");
			return;
		}
		long usd = (long) Math.floor(raw);
		BlockPos pos = body.has("pos") ? BlockPos.of(body.get("pos").getAsLong()) : player.blockPosition();
		String err = reg.withdraw(a, usd);
		if (err != null) {
			vaultResult(player, false, err);
			return;
		}
		reg.logEvent(a, player, "Withdrew " + Fmt.usd(usd) + " as coins");
		for (ItemStack s : Coins.make(usd, a.backing())) {
			if (!player.getInventory().add(s)) player.drop(s, false);
		}
		player.level().playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 1f, 1.4f);
		vaultResult(player, true, "Withdrew " + Fmt.usd(usd) + " from " + a.name);
		sendAccounts(player, reg);
	}

	/** An outcome shown in the open config screen of the block at {@code pos}, as a block's own actions are. */
	private static void blockResult(ServerPlayer player, BlockPos pos, boolean ok, String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("pos", pos.asLong());
		o.addProperty("ok", ok);
		o.addProperty("msg", msg);
		HcNet.send(player, "block_result", o);
	}

	private static void vaultResult(ServerPlayer player, boolean ok, String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", ok);
		o.addProperty("msg", msg);
		HcNet.send(player, "vault_result", o);
	}

	public static void placeOrder(ServerPlayer player, String coin, Side side, double usd) {
		placeOrder(player, OrderRequest.market(coin, side, usd));
	}

	public static void placeOrder(ServerPlayer player, OrderRequest request) {
		AccountRegistry reg = HyperCraft.accounts();
		if (request.type != OrderRequest.Type.TWAP) request.cloid = dev.hypercraft.trading.Cloids.next(dev.hypercraft.trading.Cloids.playerPrefix(player.getUUID()));
		if (reg != null) withAccount(player, reg, acct -> acct.submit(request));
	}

	/** Runs an operation on the player's selected account and reports the result back to them. */
	private static void withAccount(ServerPlayer player, AccountRegistry reg,
			java.util.function.Function<TradingAccount, java.util.concurrent.CompletableFuture<OrderResult>> op) {
		Account a = reg.selected(player);
		TradingAccount acct = reg.guarded(a, AccountRegistry.actor(player, "terminal"));
		if (acct == null) {
			String why = a == null ? "No account. Press H to craft an Account Vault and open one there." : a.name + " isn't available right now";
			orderResult(player, OrderResult.fail("", why), "--");
			return;
		}
		MinecraftServer server = player.getServer();
		String label = a.name;
		op.apply(acct).whenComplete((r, err) -> server.execute(() -> {
			OrderResult result = err != null ? OrderResult.fail("", String.valueOf(err.getMessage())) : r;
			orderResult(player, result, label);
			sendPortfolio(player, reg);
		}));
	}

	private static void orderResult(ServerPlayer player, OrderResult result, String label) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", result.ok());
		o.addProperty("msg", result.message());
		HcNet.send(player, "order_result", o);
		player.sendSystemMessage(Component.literal("§b[" + label + "] " + (result.ok() ? "§a" : "§c") + result.message()));
	}

	private static void fetchCandles(ServerPlayer player, String rawCoin, String interval) {
		MarketData md = HyperCraft.market();
		if (md == null) return;
		String coin = md.resolve(rawCoin);
		Long ms = INTERVALS.get(interval);
		if (coin == null || ms == null) return;
		long now = System.currentTimeMillis();
		MinecraftServer server = player.getServer();
		md.api().candles(md.ticker(coin).asset.apiCoin(), interval, now - ms * 120, now).thenAccept(resp -> {
			JsonArray out = new JsonArray();
			for (JsonElement el : resp.getAsJsonArray()) {
				JsonObject c = el.getAsJsonObject();
				JsonArray row = new JsonArray();
				row.add(c.get("t").getAsLong());
				row.add(c.get("o").getAsDouble());
				row.add(c.get("h").getAsDouble());
				row.add(c.get("l").getAsDouble());
				row.add(c.get("c").getAsDouble());
				row.add(c.get("v").getAsDouble());
				out.add(row);
			}
			JsonObject o = new JsonObject();
			o.addProperty("coin", coin);
			o.addProperty("interval", interval);
			o.add("candles", out);
			server.execute(() -> HcNet.send(player, "candles", o));
		}).exceptionally(e -> {
			HyperCraft.LOG.debug("Candle fetch failed", e);
			return null;
		});
	}

	private static void sendPortfolio(ServerPlayer player, AccountRegistry reg) {
		Account a = reg.selected(player);
		TradingAccount acct = a == null ? null : reg.trading(a.id);
		JsonObject snap;
		if (acct == null) {
			snap = new JsonObject();
			snap.addProperty("none", true);
			snap.addProperty("mode", a == null ? "NO ACCOUNT" : a.label());
			snap.addProperty("tag", a == null ? "NO ACCOUNT" : reg.shortTag(a));
			snap.addProperty("accountName", a == null ? "" : a.name);
		} else {
			snap = acct.snapshot();
			snap.addProperty("accountId", a.id.toString());
			if (HyperCraft.signals() != null) snap.addProperty("copyDesks", HyperCraft.signals().desksFor(a.id).size());
			snap.addProperty("accountName", a.name);
			snap.addProperty("mode", a.label());
			snap.addProperty("tag", reg.shortTag(a));
		}
		HcNet.send(player, "portfolio", snap);
	}

	private static void sendAccounts(ServerPlayer player, AccountRegistry reg) {
		Account sel = reg.selected(player);
		JsonArray arr = new JsonArray();
		for (Account a : reg.accessible(player)) {
			TradingAccount t = reg.trading(a.id);
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id.toString());
			o.addProperty("name", a.name);
			o.addProperty("label", a.label());
			o.addProperty("tag", reg.tag(a));
			o.addProperty("owned", a.owner.equals(player.getUUID()));
			o.addProperty("ownerName", a.ownerName);
			o.addProperty("selected", sel != null && sel.id.equals(a.id));
			o.addProperty("available", t != null);
			o.addProperty("backing", a.backing());
			AccessPolicy.Level lvl = AccessPolicy.level(a, player.getUUID(), System.currentTimeMillis());
			o.addProperty("access", lvl == AccessPolicy.Level.NONE ? "CARD" : lvl.name());
			o.addProperty("canTrade", AccessPolicy.checkTrading(a, player.getUUID(), System.currentTimeMillis()) == null);
			o.addProperty("frozen", a.frozen);
			o.addProperty("custody", custody(reg, a));
			Account.Grant mine = a.grant(player.getUUID());
			if (mine != null) o.addProperty("grant", describeGrant(mine));
			if (a.kind == Account.Kind.WATCH) {
				o.addProperty("watch", true);
				o.addProperty("address", a.address);
			}
			if (t != null) {
				double eq = t.equity();
				o.addProperty("equity", Double.isNaN(eq) ? 0 : eq);
				o.addProperty("withdrawable", t.withdrawable());
				JsonObject snap = t.snapshot();
				o.addProperty("upnl", snap.get("upnl").getAsDouble());
				o.addProperty("positions", snap.getAsJsonArray("positions").size());
			}
			arr.add(o);
		}
		JsonObject body = new JsonObject();
		body.add("accounts", arr);
		body.addProperty("canBuild", canBuild(player));
		body.addProperty("faucet", HyperConfig.get().paperFaucet);
		body.addProperty("faucetUsd", HyperConfig.get().paperFaucetUsd);
		HcNet.send(player, "accounts", body);
	}

	/** Once per second on the server thread. */
	public static void tick(MinecraftServer server) {
		MarketData md = HyperCraft.market();
		AccountRegistry reg = HyperCraft.accounts();
		if (md == null || reg == null) return;
		JsonObject market = new JsonObject();
		market.addProperty("mode", "PAPER");
		market.addProperty("healthy", md.healthy());
		market.add("coins", md.snapshot());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			HcNet.send(p, "market", market);
			JsonObject run = dev.hypercraft.game.Speedruns.hud(p);
			HcNet.send(p, "speedrun", run == null ? new JsonObject() : run);
			sendAccounts(p, reg);
			sendPortfolio(p, reg);
		}
	}

	public static void reset() {}
}
