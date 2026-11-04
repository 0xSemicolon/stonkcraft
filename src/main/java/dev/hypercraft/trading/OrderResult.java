package dev.hypercraft.trading;

public record OrderResult(boolean ok, String message, String coin, double size, double price) {
	public static OrderResult fail(String coin, String message) {
		return new OrderResult(false, message, coin, 0, 0);
	}
}
