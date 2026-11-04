package dev.hypercraft.client.screen;

import dev.hypercraft.client.ClientConfig;
import dev.hypercraft.client.ui.HcButton;
import dev.hypercraft.client.ui.HcField;
import dev.hypercraft.client.ui.HcScreen;
import dev.hypercraft.client.ui.Ui;
import dev.hypercraft.trading.Fmt;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;

/** The terminal's own settings, kept per player: max slippage, confirmations, sounds, and the keys it answers to. */
public class SettingsScreen extends HcScreen {
	private final Screen parent;
	private HcField slippage;

	public SettingsScreen(Screen parent) {
		super("Terminal settings");
		this.parent = parent;
	}

	@Override
	protected int panelWidth() {
		return 300;
	}

	private void toggle(Flow f, String label, String tip, Supplier<Boolean> get, Consumer<Boolean> set) {
		int y = f.row("", ROW);
		f.add(new HcButton(f.left, y, f.width, ROW, (get.get() ? "■ " : "□ ") + label, b -> {
			set.accept(!get.get());
			ClientConfig.save();
			relayout();
		})).tip(tip);
	}

	private void commitSlippage() {
		if (slippage == null) return;
		double v = slippage.num(ClientConfig.get().maxSlippagePct);
		if (v > 0 && v <= 100) ClientConfig.get().maxSlippagePct = v;
		ClientConfig.save();
	}

	@Override
	protected void layout(Flow f) {
		ClientConfig cfg = ClientConfig.get();
		commitSlippage();
		f.heading("Orders");
		int y = f.row("Max slippage", 14);
		slippage = f.add(field(f.left + 80, y, 70, "max slippage", Fmt.plain(cfg.maxSlippagePct)));
		slippage.numeric(1).suffix("%");
		slippage.validate(s -> slippage.num(0) <= 0 || slippage.num(0) > 100 ? "Between 0 and 100" : null);
		f.text("How far a market order from the order form may slip. Closes use 8% and market TP/SL 10%, whatever this says.", Ui.DIM);
		toggle(f, "Confirm large orders", "Ask twice before an order that ties up over half your free margin", () -> cfg.confirmOrders, v -> cfg.confirmOrders = v);
		toggle(f, "Confirm closing positions", "Ask twice before closing a position, or selling all of a holding", () -> cfg.confirmCloses, v -> cfg.confirmCloses = v);
		toggle(f, "Hold to Close All", "Close All takes a one-second hold, not a second click", () -> cfg.holdToCloseAll, v -> cfg.holdToCloseAll = v);
		toggle(f, "Sounds", "A note when an order's answered: high for done, low for refused", () -> cfg.sounds, v -> cfg.sounds = v);
		f.rule();
		f.heading("Keys");
		f.text("B / S  place a buy or a sell   ·   / or Ctrl+K  search markets   ·   Ctrl+S  star this market", Ui.DIM);
	}

	@Override
	protected void footer(Footer f) {
		f.primary("Done", this::onClose);
	}

	@Override
	public void onClose() {
		commitSlippage();
		minecraft.setScreen(parent);
	}
}
