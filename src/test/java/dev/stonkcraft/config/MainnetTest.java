package dev.stonkcraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** Mainnet by default; testnet only through the hidden developer override; no network keys in the config file. */
class MainnetTest {
	@Test
	void mainnetByDefault() {
		HyperConfig cfg = new HyperConfig();
		assertEquals("https://api.hyperliquid.xyz", cfg.apiBase());
		assertTrue(cfg.isMainnet());
	}

	@Test
	void oldNetworkKeysAreIgnoredAndNotWrittenBack() {
		HyperConfig cfg = new Gson().fromJson("{\"network\":\"testnet\",\"liveTrading\":true,\"privateKey\":\"0xabc\"}", HyperConfig.class);
		assertTrue(cfg.isMainnet());
		String out = new Gson().toJson(cfg);
		assertFalse(out.contains("network") || out.contains("liveTrading") || out.contains("privateKey") || out.contains("testnet"));
	}

	@Test
	void hiddenOverrideForDevelopers() {
		System.setProperty("stonkcraft.api", "https://api.hyperliquid-testnet.xyz/");
		try {
			assertEquals("https://api.hyperliquid-testnet.xyz", new HyperConfig().apiBase());
			assertFalse(new HyperConfig().isMainnet());
		} finally {
			System.clearProperty("stonkcraft.api");
		}
	}
}
