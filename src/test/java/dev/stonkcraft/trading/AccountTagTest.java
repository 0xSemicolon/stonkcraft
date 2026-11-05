package dev.stonkcraft.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Every account is paper or a read-only watch: the tag is just the kind. */
class AccountTagTest {
	private static Account account(Account.Kind kind) {
		Account a = new Account();
		a.kind = kind;
		a.address = "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303";
		return a;
	}

	@Test
	void tagsAreTheKind() {
		assertEquals("PAPER", account(Account.Kind.PAPER).tag());
		assertEquals("WATCH", account(Account.Kind.WATCH).tag());
	}
}
