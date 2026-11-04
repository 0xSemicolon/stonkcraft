package dev.stonkcraft.client;

import dev.stonkcraft.Stonkcraft;
import dev.stonkcraft.block.AccountVaultBlockEntity;
import dev.stonkcraft.block.SignalMixerBlockEntity;
import dev.stonkcraft.client.screen.AccessScreen;
import dev.stonkcraft.client.screen.FundScreen;
import dev.stonkcraft.client.screen.TerminalScreen;
import dev.stonkcraft.client.screen.VaultSetupScreen;
import dev.stonkcraft.client.screen.WelcomeScreen;
import dev.stonkcraft.client.ui.UiLint;
import dev.stonkcraft.net.ServerNet;
import dev.stonkcraft.trading.Account;
import dev.stonkcraft.trading.AccountRegistry;
import dev.stonkcraft.trading.OrderRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code ./gradlew runClient -Pshowroom -Puitour}: a UX review tour. Opens every Stonkcraft screen as a player would meet
 * it (each block first bare, then with a card in) and screenshots each as ui_*.png, then quits.
 */
public final class UiTour {
	public static final boolean ENABLED = Boolean.getBoolean("stonkcraft.uitour");
	private static final String[] BLOCKS = {"account_vault", "trading_terminal", "ticker_display", "account_display", "unit_bridge",
			"market_sensor", "trade_executor", "leverage_dial", "kill_switch", "signal_mixer", "copy_desk"};
	private static final int STEP = 50;
	/** -Puiscale=3: run at that GUI scale and prefix every file with s3_ (1280x720 at scale 3 is 427x240, a small window at scale 2). */
	private static final int SCALE = Integer.getInteger("stonkcraft.uitour.scale", 0);
	private static final String PREFIX = SCALE > 0 ? "ui_s" + SCALE + "_" : "ui_";
	private static int ticks;
	/** Where the cursor rests while a screen is open (window pixels); the top-left corner unless a step needs a hover. */
	private static int[] cursor = {1, 1};
	private static String lintShot;
	private static int lintTotal;
	private static Account acct;
	private static final List<Runnable> steps = new ArrayList<>();

	private UiTour() {}

	private static BlockPos spot(int i) {
		BlockPos o = Showroom.origin();
		return new BlockPos(o.getX() - 15 + i * 3, o.getY() + 1, o.getZ() + 100);
	}

	public static void tick(Minecraft mc) {
		if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null || !Showroom.ready()) return;
		MinecraftServer server = mc.getSingleplayerServer();
		// wait for live markets, so the terminal and orders aren't shot cold
		if (ticks == 0 && ClientState.TICKERS.size() < 50) return;
		ticks++;
		if (ticks == 100) {
			ClientConfig.get().tape = true; // in memory only: the review shows the HUD as a new player gets it
			if (SCALE > 0) {
				mc.options.guiScale().set(SCALE);
				mc.resizeDisplay();
			}
			server.execute(() -> setup(server));
			plan(mc, server);
		}
		int t = ticks - 160;
		if (t < 0) return;
		int i = t / STEP, phase = t % STEP;
		if (i >= steps.size()) {
			if (phase == 10) {
				if (UiLint.ENABLED) {
					UiLint.write(PREFIX + "lint.json");
					Stonkcraft.LOG.info("[uitour] layout lint: {} issue(s) in total", lintTotal);
				}
				mc.stop();
			}
			return;
		}
		// cursor in the top-left corner, clear of every panel, so shots don't catch a hover tooltip (only while a screen
		// has a free cursor: in game the move would turn the camera)
		if (mc.screen != null && mc.mouseHandler instanceof dev.stonkcraft.client.mixin.MouseHandlerAccessor m) {
			m.stonkcraft$setX(cursor[0]);
			m.stonkcraft$setY(cursor[1]);
		}
		if (phase == 0) steps.get(i).run();
		// a second shot of the same frame with the lint's findings outlined in red
		if (phase == 3 && lintShot != null) Screenshot.grab(mc.gameDirectory, PREFIX + lintShot + "_lint.png", mc.getMainRenderTarget(), msg -> {});
		if (phase == 5 && lintShot != null) {
			UiLint.highlight = false;
			lintShot = null;
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void cmd(MinecraftServer server, String c) {
		server.getCommands().performPrefixedCommand(player(server).createCommandSourceStack(), c);
	}

	private static void setup(MinecraftServer server) {
		ServerPlayer p = player(server);
		AccountRegistry reg = Stonkcraft.accounts();
		p.serverLevel().setDayTime(6000);
		p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		acct = reg.createPaper(p, "Tour");
		reg.select(p, acct.id);
		reg.deposit(acct, 100_000);
		// trading by hand needs a terminal; the tour's setup goes straight to the server, with some USDC moved to spot
		reg.paper(acct.id).transfer(10_000, false);
		// stocks margin from trade[xyz]'s own USDC in a Manual account
		reg.paper(acct.id).transferDex(40_000, "xyz", true);
		ServerNet.placeOrder(p, "xyz:GOLD", dev.stonkcraft.trading.Side.LONG, 20_000);
		ServerNet.placeOrder(p, "xyz:SILVER", dev.stonkcraft.trading.Side.SHORT, 8_000);
		ServerNet.placeOrder(p, "AAPL", dev.stonkcraft.trading.Side.LONG, 5_000);
		ServerNet.placeOrder(p, "UGOLD/USDC", dev.stonkcraft.trading.Side.BUY, 3_000);
		// some iron and coal already bridged, so the UNIT[XYZ] shot has withdraw rows
		var paper = reg.paper(acct.id);
		dev.stonkcraft.unit.UnitBridge.deposit(paper, Stonkcraft.market(), "minecraft:iron_ingot", 20, dev.stonkcraft.unit.UnitBridge.Mode.SPOT);
		dev.stonkcraft.unit.UnitBridge.deposit(paper, Stonkcraft.market(), "minecraft:coal_block", 4, dev.stonkcraft.unit.UnitBridge.Mode.SPOT);
		// and some in the pocket, so the bridge lights the cells you can click to deposit
		p.getInventory().add(new ItemStack(net.minecraft.world.item.Items.EMERALD, 12));
		p.getInventory().add(new ItemStack(net.minecraft.world.item.Items.REDSTONE, 40));
		// a read card as printed before Watch Cards (on the trade item): it should turn into a Watch Card in the pocket
		ItemStack old = new ItemStack(dev.stonkcraft.registry.ModItems.ACCOUNT_CARD);
		var rec = reg.issueCard(acct, "READ", "legacy");
		old.set(dev.stonkcraft.registry.ModComponents.ACCOUNT, new dev.stonkcraft.registry.ModComponents.AccountRef(acct.id.toString(), acct.name, rec.id, rec.level, ""));
		p.getInventory().setItem(30, old);
		for (int i = 0; i < BLOCKS.length; i++) {
			BlockPos b = spot(i);
			p.serverLevel().setBlock(b.below(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
			var st = BuiltInRegistries.BLOCK.get(Stonkcraft.id(BLOCKS[i])).defaultBlockState();
			if (st.hasProperty(HorizontalDirectionalBlock.FACING)) st = st.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
			// a fresh block each run: the save keeps the last run's blocks, cards in them included (no drops: the entity goes first)
			p.serverLevel().removeBlockEntity(b);
			p.serverLevel().setBlock(b, Blocks.AIR.defaultBlockState(), 2);
			p.serverLevel().setBlock(b, st, 3);
			if (p.serverLevel().getBlockEntity(b) instanceof dev.stonkcraft.block.HcBlockEntity be) be.setOwner(p.getUUID(), p.getGameProfile().getName());
		}
	}

	/** Right-clicks block i with the stack (empty hand when the stack is empty), standing in front of it. */
	private static void use(MinecraftServer server, int i, ItemStack stack) {
		server.execute(() -> {
			ServerPlayer p = player(server);
			BlockPos b = spot(i);
			p.teleportTo(p.serverLevel(), b.getX() + 0.5, b.getY(), b.getZ() + 3.5, 180, 15);
			p.setItemInHand(InteractionHand.MAIN_HAND, stack);
			p.gameMode.useItemOn(p, p.serverLevel(), stack, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(b).add(0, 0, 0.5), Direction.SOUTH, b, false));
		});
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, PREFIX + name + ".png", mc.getMainRenderTarget(), msg -> {});
		if (!UiLint.ENABLED || mc.screen == null) return;
		List<UiLint.Issue> issues = UiLint.check(name);
		lintTotal += issues.size();
		for (UiLint.Issue is : issues) Stonkcraft.LOG.info("[uitour] {}: {} {}", name, is.kind(), is.what());
		if (!issues.isEmpty()) {
			UiLint.highlight = true;
			lintShot = name;
		}
	}

	/** Each step runs at the start of its slot; a screenshot is the following step. */
	private static void plan(Minecraft mc, MinecraftServer server) {
		steps.clear();
		// first contact: the welcome screen, then the terminal on H
		steps.add(() -> {
			mc.getToasts().clear(); // setup's trades award advancements a new player wouldn't have yet
			mc.setScreen(new WelcomeScreen());
		});
		steps.add(() -> shot(mc, "00_welcome"));
		String[] tables = {"positions", "orders", "twap", "fills", "funding", "balances"};
		for (int k = 0; k < tables.length; k++) {
			int tab = k;
			steps.add(() -> mc.setScreen(new TerminalScreen(tab)));
			steps.add(() -> shot(mc, "01_terminal_" + tables[tab]));
		}
		steps.add(() -> mc.setScreen(new TerminalScreen().view(1)));
		steps.add(() -> shot(mc, "02_terminal_book"));
		steps.add(() -> mc.setScreen(new TerminalScreen().view(2)));
		steps.add(() -> shot(mc, "02_terminal_funding"));
		steps.add(() -> mc.setScreen(new TerminalScreen().view(3)));
		steps.add(() -> shot(mc, "02_terminal_account"));
		// the order form's types: each lays out differently, and all must fit
		OrderRequest.Type[] forms = {OrderRequest.Type.MARKET, OrderRequest.Type.LIMIT, OrderRequest.Type.STOP_LIMIT, OrderRequest.Type.SCALE, OrderRequest.Type.TRAILING, OrderRequest.Type.TWAP};
		for (OrderRequest.Type form : forms) {
			steps.add(() -> mc.setScreen(new TerminalScreen().ticket("xyz:GOLD", form, form == OrderRequest.Type.STOP_LIMIT)));
			steps.add(() -> shot(mc, "02_form_" + form.name().toLowerCase(java.util.Locale.ROOT)));
		}
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.PositionScreen("xyz:GOLD", new TerminalScreen())));
		steps.add(() -> shot(mc, "02_position_cross"));
		steps.add(() -> server.execute(() -> Stonkcraft.accounts().paper(acct.id).setLeverage("xyz:GOLD", 10, false)));
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.PositionScreen("xyz:GOLD", new TerminalScreen())));
		steps.add(() -> shot(mc, "02_position_isolated"));
		steps.add(() -> server.execute(() -> Stonkcraft.accounts().paper(acct.id).setLeverage("xyz:GOLD", 10, true)));
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.SettingsScreen(new TerminalScreen())));
		steps.add(() -> shot(mc, "02_settings"));
		steps.add(() -> server.execute(UiTour::marginCheck));
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.BuildMenuScreen()));
		steps.add(() -> shot(mc, "02_build_menu_free"));
		// as a survival player: nothing to craft with is refused and takes nothing; with the materials, the block's made
		int[] crafted = new int[2];
		List<ItemStack> pocket = new ArrayList<>();
		steps.add(() -> {
			mc.setScreen(null);
			server.execute(() -> {
				ServerPlayer p = player(server);
				for (int i = 0; i < p.getInventory().getContainerSize(); i++) pocket.add(p.getInventory().getItem(i).copy());
				p.getInventory().clearContent();
				p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
			});
		});
		steps.add(() -> {
			ClientState.canBuild = false;
			mc.setScreen(new dev.stonkcraft.client.screen.BuildMenuScreen());
		});
		steps.add(() -> {
			ClientState.canBuild = false;
			shot(mc, "02_build_menu_craft_short");
			JsonObject o = new JsonObject();
			o.addProperty("item", "account_vault");
			dev.stonkcraft.client.ClientNet.send("craft", o);
		});
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			crafted[0] = p.getInventory().countItem(dev.stonkcraft.registry.ModBlocks.VAULT.asItem());
			var recipe = server.getRecipeManager().byKey(Stonkcraft.id("account_vault")).orElseThrow().value();
			for (var ing : recipe.getIngredients()) if (!ing.isEmpty()) p.getInventory().add(ing.getItems()[0].copy());
		}));
		steps.add(() -> {
			ClientState.canBuild = false;
			mc.setScreen(new dev.stonkcraft.client.screen.BuildMenuScreen());
		});
		steps.add(() -> {
			ClientState.canBuild = false;
			shot(mc, "02_build_menu_craft_ready");
			JsonObject o = new JsonObject();
			o.addProperty("item", "account_vault");
			dev.stonkcraft.client.ClientNet.send("craft", o);
		});
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			crafted[1] = p.getInventory().countItem(dev.stonkcraft.registry.ModBlocks.VAULT.asItem());
			int leftover = 0;
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) leftover += p.getInventory().getItem(i).isEmpty() || p.getInventory().getItem(i).is(dev.stonkcraft.registry.ModBlocks.VAULT.asItem()) ? 0 : p.getInventory().getItem(i).getCount();
			Stonkcraft.LOG.info("UiTour: build menu: vaults crafted with nothing {}, with the materials {}; materials left {}", crafted[0], crafted[1], leftover);
			p.getInventory().clearContent();
			p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			for (int i = 0; i < pocket.size(); i++) p.getInventory().setItem(i, pocket.get(i));
		}));
		steps.add(() -> {
			ClientState.canBuild = true;
			mc.setScreen(null);
		});
		// the Transfer screen, Manual (two balances) then Unified (one)
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.TransferScreen(new TerminalScreen(), false)));
		steps.add(() -> shot(mc, "02_transfer_manual"));
		steps.add(() -> server.execute(() -> Stonkcraft.accounts().paper(acct.id).setUnified(true)));
		steps.add(() -> mc.setScreen(new dev.stonkcraft.client.screen.TransferScreen(new TerminalScreen(), false)));
		steps.add(() -> shot(mc, "02_transfer_unified"));
		steps.add(() -> server.execute(() -> {
			var paper = Stonkcraft.accounts().paper(acct.id);
			paper.setUnified(false);
			// back to Manual with everything on perps: put the spot USDC back for the rest of the tour
			paper.transfer(7_000, false);
		}));
		steps.add(() -> mc.setScreen(new FundScreen(acct.id.toString(), null)));
		steps.add(() -> shot(mc, "04_fund"));
		steps.add(() -> mc.setScreen(new AccessScreen(acct.id.toString(), null)));
		steps.add(() -> shot(mc, "05_access"));
		// the Cards tab, with a scoped Trade Card printed
		steps.add(() -> server.execute(() -> {
			var s = new dev.stonkcraft.trading.Account.Scope();
			s.markets.add("xyz:GOLD");
			s.maxOrderUsd = 500;
			s.reduceOnly = true;
			player(server).getInventory().add(ServerNet.cardItem(Stonkcraft.accounts(), acct, "TRADE", "bot", s));
		}));
		steps.add(() -> {
			mc.setScreen(new AccessScreen(acct.id.toString(), null));
		});
		steps.add(() -> {
			for (var c : mc.screen == null ? java.util.List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : mc.screen.children()) {
				// pressing a tab rebuilds the widgets, so stop looking
				if (c instanceof dev.stonkcraft.client.ui.HcButton b && b.getMessage().getString().equals("Cards")) {
					b.onPress();
					break;
				}
			}
		});
		steps.add(() -> shot(mc, "05_access_cards"));
		steps.add(() -> {
			JsonObject d = new JsonObject();
			d.addProperty("pos", spot(0).asLong());
			JsonArray mine = new JsonArray();
			JsonObject m = new JsonObject();
			m.addProperty("id", acct.id.toString());
			m.addProperty("name", acct.name);
			m.addProperty("tag", "PAPER");
			mine.add(m);
			d.add("mine", mine);
			mc.setScreen(new VaultSetupScreen(d));
		});
		steps.add(() -> shot(mc, "05_vault_setup"));
		steps.add(() -> {
			mc.setScreen(null);
			server.execute(() -> {
				ServerPlayer p = player(server);
				BlockPos mid = spot(BLOCKS.length / 2);
				p.teleportTo(p.serverLevel(), mid.getX() + 0.5, mid.getY(), mid.getZ() + 6.5, 180, 0);
			});
		});
		steps.add(() -> shot(mc, "06_hud_tape"));
		// the blocks in a row, as placed
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			BlockPos mid = spot(BLOCKS.length / 2);
			p.teleportTo(p.serverLevel(), mid.getX() + 0.5, mid.getY() + 1.5, mid.getZ() + 12.5, 180, 10);
		}));
		steps.add(() -> shot(mc, "07_blocks_row"));
		// each block: bare, then a card in, then opened again
		for (int i = 0; i < BLOCKS.length; i++) {
			int n = i;
			String name = String.format("%02d_%s", 10 + i, BLOCKS[i]);
			steps.add(() -> {
				mc.setScreen(null);
				use(server, n, ItemStack.EMPTY);
			});
			steps.add(() -> shot(mc, name + "_a_bare"));
			if (BLOCKS[i].equals("account_vault")) {
				steps.add(() -> server.execute(() -> {
					if (player(server).serverLevel().getBlockEntity(spot(n)) instanceof AccountVaultBlockEntity v) v.bind(acct);
				}));
				steps.add(() -> mc.setScreen(null));
			} else {
				steps.add(() -> {
					mc.setScreen(null);
					use(server, n, ServerNet.cardItem(Stonkcraft.accounts(), acct, "TRADE", "tour"));
				});
				steps.add(() -> shot(mc, name + "_b_card_in"));
			}
			steps.add(() -> use(server, n, ItemStack.EMPTY));
			steps.add(() -> shot(mc, name + "_c_open"));
			if (BLOCKS[i].equals("trade_executor")) {
				// a Watch Card only looks: the executor turns it away at the slot and keeps its Trade Card
				steps.add(() -> use(server, n, ServerNet.cardItem(Stonkcraft.accounts(), acct, "READ", "tour watch")));
				steps.add(() -> server.execute(() -> {
					var inv = player(server).getInventory();
					Stonkcraft.LOG.info("UiTour: executor offered a Watch Card: its slot holds \"{}\"; old read card in the pocket is now a {}",
							((dev.stonkcraft.block.HcBlockEntity) player(server).serverLevel().getBlockEntity(spot(n))).card().getHoverName().getString(),
							inv.countItem(dev.stonkcraft.registry.ModItems.WATCH_CARD) > 0 ? "Watch Card" : "trade-item card");
				}));
			}
			if (BLOCKS[i].equals("copy_desk")) {
				// an address watched from the desk's screen becomes its leader, as a Watch Card
				steps.add(() -> {
					var kids = mc.screen == null ? java.util.List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : mc.screen.children();
					for (var c : kids) if (c instanceof dev.stonkcraft.client.ui.HcField f && f.getMessage().getString().equals("address")) f.setValue("0x010461c14e146ac35fe42271bdc1134ee31c703a" /* HLP */);
					for (var c : kids) if (c instanceof dev.stonkcraft.client.ui.HcButton b && b.getMessage().getString().equals("Watch")) b.onPress();
				});
				steps.add(() -> server.execute(() -> {
					var desk = (dev.stonkcraft.block.CopyDeskBlockEntity) player(server).serverLevel().getBlockEntity(spot(n));
					Stonkcraft.LOG.info("UiTour: copy desk after Watch: leader \"{}\", follower \"{}\"", desk.info().get("leader").getAsString(), desk.info().get("follower").getAsString());
				}));
			}
			if (BLOCKS[i].equals("unit_bridge")) {
				// UNIT[XYZ] has no fold: its "more" shot is a deposit made from the screen, a lava bucket whose bucket comes back
				steps.add(() -> server.execute(() -> player(server).getInventory().add(new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET))));
				steps.add(() -> {
					for (var c : mc.screen == null ? java.util.List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : mc.screen.children()) {
						if (c instanceof dev.stonkcraft.client.ui.HcButton b && b.getMessage().getString().equals("Lava")) {
							b.onPress();
							break;
						}
					}
				});
				steps.add(() -> server.execute(() -> {
					var inv = player(server).getInventory();
					Stonkcraft.LOG.info("UiTour: after the Lava deposit: {} lava buckets, {} buckets, ULAVA {}", inv.countItem(net.minecraft.world.item.Items.LAVA_BUCKET),
							inv.countItem(net.minecraft.world.item.Items.BUCKET), Stonkcraft.accounts().paper(acct.id).spotBalance("ULAVA/USDC"));
				}));
				// the Withdraw tab: the same grid, what's available on each good, and taking some out with the chosen good's buttons
				int[] ironBefore = new int[1];
				steps.add(() -> {
					ironBefore[0] = player(server).getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT);
					press(mc, "↑ Withdraw");
				});
				steps.add(() -> shot(mc, name + "_w_withdraw"));
				steps.add(() -> press(mc, "9"));
				steps.add(() -> server.execute(() -> Stonkcraft.LOG.info("UiTour: withdraw tab: pressed 9: iron ingots +{}, iron blocks {}, UIRON left {}",
						player(server).getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) - ironBefore[0], player(server).getInventory().countItem(net.minecraft.world.item.Items.IRON_BLOCK), Stonkcraft.accounts().paper(acct.id).spotBalance("UIRON/USDC"))));
				steps.add(() -> press(mc, "↓ Deposit"));
			}
			// the states a player opens next: a folded "More settings" section, then the first dropdown
			steps.add(() -> {
				for (var c : mc.screen == null ? java.util.List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : mc.screen.children()) {
					if (c instanceof dev.stonkcraft.client.ui.HcButton b && b.getMessage().getString().startsWith("▸")) {
						b.onPress();
						break;
					}
				}
			});
			steps.add(() -> shot(mc, name + "_d_more"));
			steps.add(() -> {
				for (var c : mc.screen == null ? java.util.List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : mc.screen.children()) {
					if (c instanceof dev.stonkcraft.client.ui.HcDropdown<?> d) {
						d.onPress();
						break;
					}
				}
			});
			steps.add(() -> shot(mc, name + "_e_menu"));
			if (BLOCKS[i].equals("signal_mixer")) {
				// print its card so the Copy Desk can follow a signal later
				steps.add(() -> server.execute(() -> {
					if (player(server).serverLevel().getBlockEntity(spot(n)) instanceof SignalMixerBlockEntity m) player(server).getInventory().add(m.printCard());
				}));
			}
		}
		steps.add(() -> mc.setScreen(null));
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			BlockPos mid = spot(BLOCKS.length / 2);
			p.teleportTo(p.serverLevel(), mid.getX() + 0.5, mid.getY() + 1.5, mid.getZ() + 8.5, 180, 10);
		}));
		steps.add(() -> shot(mc, "30_blocks_row_linked"));
		composability(server);
		terminalOnly(server);
		survival(server);
		blockLinks(server);
	}

	/** Blocks that touch share an account: a row of vault, UNIT[XYZ] and terminal, then with a gap in it. */
	private static void blockLinks(MinecraftServer server) {
		BlockPos vaultAt = spot(0).above(8), unitAt = vaultAt.east(), terminalAt = unitAt.east();
		java.util.UUID[] before = new java.util.UUID[1];
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			var reg = Stonkcraft.accounts();
			before[0] = reg.selected(p).id;
			var level = p.serverLevel();
			level.setBlockAndUpdate(vaultAt, dev.stonkcraft.registry.ModBlocks.VAULT.defaultBlockState());
			level.setBlockAndUpdate(unitAt, dev.stonkcraft.registry.ModBlocks.UNIT_BRIDGE.defaultBlockState());
			level.setBlockAndUpdate(terminalAt, dev.stonkcraft.registry.ModBlocks.TERMINAL.defaultBlockState());
			if (level.getBlockEntity(vaultAt) instanceof dev.stonkcraft.block.AccountVaultBlockEntity v) v.bind(reg.createPaper(p, "Linked"));
			var unit = (dev.stonkcraft.block.UnitBridgeBlockEntity) level.getBlockEntity(unitAt);
			Stonkcraft.LOG.info("UiTour: block links: UNIT[XYZ] acts on {} (selected {})", unit.target(p).name, reg.selected(p).name);
			var state = level.getBlockState(terminalAt);
			((dev.stonkcraft.block.TerminalBlock) state.getBlock()).useWithoutItem(state, level, terminalAt, p, null);
		}));
		steps.add(() -> {});
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			var level = p.serverLevel();
			Stonkcraft.LOG.info("UiTour: block links: terminal opened on {}", Stonkcraft.accounts().selected(p).name);
			level.removeBlock(unitAt, false);
			var linked = dev.stonkcraft.block.BlockLinks.account(level, terminalAt);
			Stonkcraft.LOG.info("UiTour: block links: with a gap the terminal links to {}", linked == null ? "nothing" : linked.name);
			level.removeBlock(terminalAt, false);
			level.removeBlock(vaultAt, false);
			Stonkcraft.accounts().select(p, before[0]);
		}));
		steps.add(() -> mc().setScreen(null));
	}

	/**
	 * As a survival player: picking up copper puts every Stonkcraft recipe in the recipe book; the build menu's free
	 * blocks and the paper mint are refused; there's no starter money.
	 */
	private static void survival(MinecraftServer server) {
		String[] recipes = {"trading_terminal", "account_vault", "unit_bridge", "ticker_display", "account_card", "trade_executor", "market_sensor"};
		int[] before = new int[2];
		steps.add(() -> {
			mc().setScreen(null);
			server.execute(() -> {
				ServerPlayer p = player(server);
				p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				cmd(server, "advancement revoke @s everything");
				cmd(server, "recipe take @s *");
				int known = 0;
				for (String r : recipes) if (p.getRecipeBook().contains(Stonkcraft.id(r))) known++;
				before[0] = p.getInventory().countItem(BuiltInRegistries.ITEM.get(Stonkcraft.id("trade_executor")));
				before[1] = coins(p);
				// an empty pocket, so the copper surely lands (and the coin count starts at zero)
				p.getInventory().clearContent();
				before[0] = 0;
				before[1] = 0;
				Stonkcraft.LOG.info("UiTour: survival: recipes known before picking anything up {}/{}", known, recipes.length);
			});
		});
		// the pickup a step later, as in play: the reset has settled and the unlocks are listening again
		steps.add(() -> server.execute(() -> {
			boolean added = player(server).getInventory().add(new ItemStack(net.minecraft.world.item.Items.COPPER_INGOT));
			Stonkcraft.LOG.info("UiTour: survival: copper in the pocket: {}", added);
		}));
		steps.add(() -> {
			// what the build menu and the Fund screen would send
			JsonObject give = new JsonObject();
			give.addProperty("item", "trade_executor");
			dev.stonkcraft.client.ClientNet.send("give", give);
			JsonObject mint = new JsonObject();
			mint.addProperty("usd", 10_000);
			dev.stonkcraft.client.ClientNet.send("paper_mint", mint);
		});
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			int known = 0;
			for (String r : recipes) if (p.getRecipeBook().contains(Stonkcraft.id(r))) known++;
			int execs = p.getInventory().countItem(BuiltInRegistries.ITEM.get(Stonkcraft.id("trade_executor"))) - before[0];
			var cfg = dev.stonkcraft.config.HyperConfig.get();
			var adv = server.getAdvancements().get(Stonkcraft.id("recipes/redstone/trading_terminal"));
			Stonkcraft.LOG.info("UiTour: survival: unlock advancement {} loaded, done {}", adv != null, adv != null && p.getAdvancements().getOrStartProgress(adv).isDone());
			Stonkcraft.LOG.info("UiTour: survival: after picking up copper, recipes known {}/{}; free executor from the build menu +{}; minted coins +{}; canBuild {}; starter grant {}; mint {}",
					known, recipes.length, execs, coins(p) - before[1], ServerNet.canBuild(p), cfg.paperStarterGrant, cfg.paperFaucet);
			p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		}));
	}

	/** Paper coins the player carries, in dollars. */
	private static int coins(ServerPlayer p) {
		long usd = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) usd += dev.stonkcraft.item.Coins.value(inv.getItem(i), "paper");
		return (int) usd;
	}

	/** Trading by hand only at a Trading Terminal; spot sales pay the spot side, which needs a transfer to margin perps. */
	private static void terminalOnly(MinecraftServer server) {
		BlockPos tPos = spot(BLOCKS.length + 6);
		double[] eth = new double[1];
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			var t = Stonkcraft.accounts().trading(acct.id);
			eth[0] = t.positionSize("xyz:SILVER");
			// far from any terminal it's refused
			p.teleportTo(p.serverLevel(), tPos.getX() + 30.5, tPos.getY(), tPos.getZ() + 0.5, 180, 0);
			cmd(server, "hl buy eth 100");
		}));
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			double away = Stonkcraft.accounts().trading(acct.id).positionSize("xyz:SILVER") - eth[0];
			// at one it goes through
			p.serverLevel().setBlock(tPos, BuiltInRegistries.BLOCK.get(Stonkcraft.id("trading_terminal")).defaultBlockState(), 3);
			p.teleportTo(p.serverLevel(), tPos.getX() + 0.5, tPos.getY(), tPos.getZ() + 2.5, 180, 0);
			ServerNet.openedTerminal(p, tPos);
			cmd(server, "hl buy eth 100");
			double at = Stonkcraft.accounts().trading(acct.id).positionSize("xyz:SILVER") - eth[0] - away;
			var paper = Stonkcraft.accounts().paper(acct.id);
			Stonkcraft.LOG.info("UiTour: terminal only: away from a terminal ETH +{}, at one ETH +{}; spot USDC {}, perps free {}",
					dev.stonkcraft.trading.Fmt.size(away), dev.stonkcraft.trading.Fmt.size(at), dev.stonkcraft.trading.Fmt.usd(paper.spotUsdc()), dev.stonkcraft.trading.Fmt.usd(paper.withdrawable()));
		}));
	}

	/** Presses the open screen's button whose label starts with this text. */
	private static void press(Minecraft mc, String label) {
		if (mc.screen == null) return;
		for (var c : List.copyOf(mc.screen.children())) {
			if (c instanceof dev.stonkcraft.client.ui.HcButton b && b.getMessage().getString().startsWith(label)) {
				b.onPress();
				return;
			}
		}
	}

	/** The paper engine on live prices, where the unit tests have stubs: mark and oracle, liquidation, isolated margin, the new order types. */
	private static void marginCheck() {
		var paper = Stonkcraft.accounts().paper(acct.id);
		var md = Stonkcraft.market();
		var btc = md.ticker("xyz:GOLD");
		Stonkcraft.LOG.info("UiTour: live prices: BTC mid {} mark {} oracle {} funding {}; AAPL mark {}", btc.mid, md.mark("xyz:GOLD"), md.oracle("xyz:GOLD"), btc.funding, md.mark(md.resolve("AAPL")));
		Stonkcraft.LOG.info("UiTour: BTC cross: size {} liq {}; margin ratio {}; used {}; free {}, withdrawable {}", paper.positionSize("xyz:GOLD"), paper.liquidationPrice("xyz:GOLD"), paper.marginRatio(),
				paper.marginUsed(), paper.available(""), paper.withdrawable());
		String xyz = md.resolve("AAPL");
		Stonkcraft.LOG.info("UiTour: {} on its own balance: size {} liq {}; xyz free {}, perps free {}", xyz, paper.positionSize(xyz), paper.liquidationPrice(xyz), paper.available("xyz"), paper.available(""));
		var iso = paper.setLeverage("xyz:GOLD", 10, false).join();
		double liqIso = paper.liquidationPrice("xyz:GOLD");
		var add = paper.adjustMargin("xyz:GOLD", 500).join();
		double liqMore = paper.liquidationPrice("xyz:GOLD");
		var take = paper.adjustMargin("xyz:GOLD", -400).join();
		var back = paper.setLeverage("xyz:GOLD", 10, true).join();
		Stonkcraft.LOG.info("UiTour: isolated: {}; liq {}; {} -> liq {}; {}; {}", iso.message(), liqIso, add.message(), liqMore, take.message(), back.message());
		double mid = btc.mid;
		int before = paper.openOrders("xyz:GOLD");
		dev.stonkcraft.trading.OrderRequest stop = dev.stonkcraft.trading.OrderRequest.market("xyz:GOLD", dev.stonkcraft.trading.Side.CLOSE, 0);
		stop.type = OrderRequest.Type.STOP_LIMIT;
		stop.price = Math.round(mid * 0.7);
		stop.limitPrice = Math.round(mid * 0.69);
		var stopR = paper.submit(stop).join();
		dev.stonkcraft.trading.OrderRequest scale = dev.stonkcraft.trading.OrderRequest.market("xyz:GOLD", dev.stonkcraft.trading.Side.BUY, 2_000);
		scale.type = OrderRequest.Type.SCALE;
		scale.scaleStart = Math.round(mid * 0.8);
		scale.scaleEnd = Math.round(mid * 0.9);
		scale.scaleOrders = 4;
		var scaleR = paper.submit(scale).join();
		dev.stonkcraft.trading.OrderRequest trail = dev.stonkcraft.trading.OrderRequest.market("xyz:GOLD", dev.stonkcraft.trading.Side.CLOSE, 0);
		trail.type = OrderRequest.Type.TRAILING;
		trail.trailPct = 30;
		var trailR = paper.submit(trail).join();
		var tpsl = paper.setPositionTpSl("xyz:GOLD", Math.round(mid * 1.5), Math.round(mid * 0.6)).join();
		Stonkcraft.LOG.info("UiTour: orders: {} | {} | {} | {}; open on BTC {} -> {}", stopR.message(), scaleR.message(), trailR.message(), tpsl.message(), before, paper.openOrders("xyz:GOLD"));
		paper.setPositionTpSl("xyz:GOLD", 0, 0).join();
		paper.cancelAll("xyz:GOLD").join();
		var snap = paper.snapshot();
		Stonkcraft.LOG.info("UiTour: fee info {}; order history {}; history rows {}", snap.get("feeInfo"), snap.getAsJsonArray("orderHistory").size(), paper.history().getAsJsonArray("orderHistory").size());
	}

	@org.jetbrains.annotations.Nullable
	private static <T> T at(MinecraftServer server, String block, Class<T> type) {
		var be = player(server).serverLevel().getBlockEntity(spot(java.util.Arrays.asList(BLOCKS).indexOf(block)));
		return type.isInstance(be) ? type.cast(be) : null;
	}

	/** Phase 1 and 2 of the composability spec: printed readings mix, a revoked card cuts its tap, one executor's order fires another. */
	private static void composability(MinecraftServer server) {
		ItemStack[] printed = new ItemStack[2];
		steps.add(() -> server.execute(() -> {
			var sensor = at(server, "market_sensor", dev.stonkcraft.block.MarketSensorBlockEntity.class);
			var display = at(server, "account_display", dev.stonkcraft.block.AccountDisplayBlockEntity.class);
			var mixer = at(server, "signal_mixer", SignalMixerBlockEntity.class);
			printed[0] = sensor.printTap();
			printed[1] = display.printTap();
			while (!mixer.inputCards.isEmpty()) mixer.ejectInput(0);
			mixer.node().op = dev.stonkcraft.signal.SignalOp.BLEND;
			String e0 = mixer.addInput(player(server), printed[0].copy());
			String e1 = mixer.addInput(player(server), printed[1].copy());
			Stonkcraft.LOG.info("UiTour: printed \"{}\" and \"{}\" into the mixer ({} / {})", printed[0].getHoverName().getString(), printed[1].getHoverName().getString(), e0, e1);
		}));
		steps.add(() -> {});
		steps.add(() -> server.execute(() -> {
			var reg = Stonkcraft.signals();
			var mixer = at(server, "signal_mixer", SignalMixerBlockEntity.class);
			Stonkcraft.LOG.info("UiTour: sensor card {} + display card {} = mixer {}",
					dev.stonkcraft.signal.SignalEngine.describe(reg.value(java.util.UUID.fromString(printed[0].get(dev.stonkcraft.registry.ModComponents.SIGNAL).id()))),
					dev.stonkcraft.signal.SignalEngine.describe(reg.value(java.util.UUID.fromString(printed[1].get(dev.stonkcraft.registry.ModComponents.SIGNAL).id()))),
					dev.stonkcraft.signal.SignalEngine.describe(reg.value(mixer.node().id)));
			// the sensor's card to the scoreboard, for command blocks
			ServerPlayer p = player(server);
			cmd(server, "scoreboard objectives add hc_tour dummy");
			p.setItemInHand(InteractionHand.MAIN_HAND, printed[0].copy());
			cmd(server, "hl signal score hc_tour 1");
			// the display's card comes out: its printed card should go dark
			var display = at(server, "account_display", dev.stonkcraft.block.AccountDisplayBlockEntity.class);
			player(server).getInventory().add(display.swapCard(ItemStack.EMPTY));
		}));
		steps.add(() -> {});
		steps.add(() -> server.execute(() -> {
			var reg = Stonkcraft.signals();
			Stonkcraft.LOG.info("UiTour: display card ejected: its printed card reads {}",
					dev.stonkcraft.signal.SignalEngine.describe(reg.value(java.util.UUID.fromString(printed[1].get(dev.stonkcraft.registry.ModComponents.SIGNAL).id()))));
			var board = server.getScoreboard();
			var obj = board.getObjective("hc_tour");
			StringBuilder sb = new StringBuilder();
			if (obj != null) for (var e : board.listPlayerScores(obj)) sb.append(e.owner()).append('=').append(e.value()).append(' ');
			Stonkcraft.LOG.info("UiTour: scoreboard hc_tour: {}", sb.length() == 0 ? "(empty)" : sb.toString().trim());
		}));
		// executor A's order fires executor B, once
		BlockPos aPos = spot(BLOCKS.length + 1), bPos = spot(BLOCKS.length + 2);
		double[] before = new double[1];
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			for (BlockPos b : new BlockPos[]{aPos, bPos}) {
				p.serverLevel().removeBlockEntity(b);
				p.serverLevel().setBlock(b.below(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
				p.serverLevel().setBlock(b, Blocks.AIR.defaultBlockState(), 2);
				p.serverLevel().setBlock(b, BuiltInRegistries.BLOCK.get(Stonkcraft.id("trade_executor")).defaultBlockState(), 3);
				var ex = (dev.stonkcraft.block.TradeExecutorBlockEntity) p.serverLevel().getBlockEntity(b);
				ex.setOwner(p.getUUID(), p.getGameProfile().getName());
				ex.swapCard(ServerNet.cardItem(Stonkcraft.accounts(), acct, "TRADE", "chain"));
			}
			var a = (dev.stonkcraft.block.TradeExecutorBlockEntity) p.serverLevel().getBlockEntity(aPos);
			var b = (dev.stonkcraft.block.TradeExecutorBlockEntity) p.serverLevel().getBlockEntity(bPos);
			JsonObject ca = a.configJson();
			ca.addProperty("coin", "xyz:GOLD");
			ca.addProperty("usd", 50);
			a.applyConfig(ca);
			JsonObject cb = b.configJson();
			cb.addProperty("coin", "xyz:SILVER");
			cb.addProperty("side", "BUY");
			cb.addProperty("usd", 50);
			cb.addProperty("onEvent", true);
			cb.addProperty("eventOnly", true);
			b.applyConfig(cb);
			b.acceptSignalCard(a.printTap());
			before[0] = Stonkcraft.accounts().trading(acct.id).positionSize("xyz:SILVER");
		}));
		steps.add(() -> server.execute(() -> {
			var a = (dev.stonkcraft.block.TradeExecutorBlockEntity) player(server).serverLevel().getBlockEntity(aPos);
			a.onPulse(player(server).serverLevel(), 15);
		}));
		steps.add(() -> {});
		steps.add(() -> {});
		steps.add(() -> server.execute(() -> {
			var b = (dev.stonkcraft.block.TradeExecutorBlockEntity) player(server).serverLevel().getBlockEntity(bPos);
			double eth = Stonkcraft.accounts().trading(acct.id).positionSize("xyz:SILVER");
			double mid = Stonkcraft.market().mid("xyz:SILVER");
			Stonkcraft.LOG.info("UiTour: executor chain: B says \"{}\", comparator {}, ETH bought ${}", b.lastMessage, b.comparatorOutput(),
					String.format(java.util.Locale.ROOT, "%.0f", (eth - before[0]) * mid));
		}));
		tickets(server, aPos, bPos);
	}

	/** Phase 5: a ticket written from one executor runs the same in another, and a chest of tickets cycles through one. */
	private static void tickets(MinecraftServer server, BlockPos aPos, BlockPos bPos) {
		BlockPos cPos = spot(BLOCKS.length + 4);
		double[] before = new double[2];
		steps.add(() -> server.execute(() -> {
			ServerPlayer p = player(server);
			var level = p.serverLevel();
			var a = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(aPos);
			var b = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(bPos);
			ItemStack btc = a.acceptTicket(new ItemStack(dev.stonkcraft.registry.ModItems.ORDER_TICKET));
			ItemStack eth = b.acceptTicket(new ItemStack(dev.stonkcraft.registry.ModItems.ORDER_TICKET));
			for (BlockPos q : new BlockPos[]{cPos.below(), cPos, cPos.above()}) {
				level.removeBlockEntity(q);
				level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
			}
			level.setBlock(cPos.below(), Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(cPos.above(), Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(cPos, BuiltInRegistries.BLOCK.get(Stonkcraft.id("trade_executor")).defaultBlockState(), 3);
			var c = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(cPos);
			c.setOwner(p.getUUID(), p.getGameProfile().getName());
			c.swapCard(ServerNet.cardItem(Stonkcraft.accounts(), acct, "TRADE", "cycle"));
			// round trip: A's order, through a ticket, in C
			c.acceptTicket(btc.copy());
			boolean same = a.toRequest(null, 15).toJson().equals(c.toRequest(null, 15).toJson());
			Stonkcraft.LOG.info("UiTour: ticket \"{}\" in another executor places the same order: {}", btc.getHoverName().getString(), same);
			c.ejectFirst();
			JsonObject cfg = c.configJson();
			cfg.addProperty("cycle", true);
			cfg.addProperty("cooldown", 1);
			c.applyConfig(cfg);
			var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(cPos.above());
			chest.setItem(0, btc);
			chest.setItem(1, eth);
			var t = Stonkcraft.accounts().trading(acct.id);
			before[0] = t.positionSize("xyz:GOLD") * Stonkcraft.market().mid("xyz:GOLD");
			before[1] = t.positionSize("xyz:SILVER") * Stonkcraft.market().mid("xyz:SILVER");
		}));
		for (int k = 0; k < 2; k++) {
			steps.add(() -> server.execute(() -> {
				var c = (dev.stonkcraft.block.TradeExecutorBlockEntity) player(server).serverLevel().getBlockEntity(cPos);
				c.onPulse(player(server).serverLevel(), 15);
			}));
			steps.add(() -> {});
		}
		steps.add(() -> server.execute(() -> {
			var level = player(server).serverLevel();
			var t = Stonkcraft.accounts().trading(acct.id);
			var below = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(cPos.below());
			var above = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(cPos.above());
			int down = 0, up = 0;
			for (int i = 0; i < below.getContainerSize(); i++) down += below.getItem(i).getCount();
			for (int i = 0; i < above.getContainerSize(); i++) up += above.getItem(i).getCount();
			var c = (dev.stonkcraft.block.TradeExecutorBlockEntity) level.getBlockEntity(cPos);
			Stonkcraft.LOG.info("UiTour: cycling executor: BTC +${}, ETH +${}; tickets above {}, below {}, loaded \"{}\"",
					String.format(java.util.Locale.ROOT, "%.0f", t.positionSize("xyz:GOLD") * Stonkcraft.market().mid("xyz:GOLD") - before[0]),
					String.format(java.util.Locale.ROOT, "%.0f", t.positionSize("xyz:SILVER") * Stonkcraft.market().mid("xyz:SILVER") - before[1]),
					up, down, c.ticket.isEmpty() ? "none" : c.ticket.getHoverName().getString());
		}));
		// the terminal's own Save as ticket, through its packet
		steps.add(() -> dev.stonkcraft.client.ClientNet.send("ticket_save",
				dev.stonkcraft.trading.OrderRequest.market("xyz:CL", dev.stonkcraft.trading.Side.BUY, 25).toJson()));
		steps.add(() -> server.execute(() -> {
			var inv = player(server).getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (dev.stonkcraft.item.OrderTicketItem.written(inv.getItem(i))) Stonkcraft.LOG.info("UiTour: in the pocket: {}", inv.getItem(i).getHoverName().getString());
			}
		}));
		steps.add(() -> mc().setScreen(new TerminalScreen()));
		steps.add(() -> shot(mc(), "31_terminal_ticket"));
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
