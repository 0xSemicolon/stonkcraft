package dev.hypercraft.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hypercraft.HyperCraft;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;

public final class ModComponents {
	private ModComponents() {}

	/** Which money a coin is. Always "paper" now; older coins may say "testnet:…" or "mainnet:…" and count as paper. */
	public static final DataComponentType<String> BACKING = register("backing",
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

	/** The account a vault item or account card points at. */
	public static final DataComponentType<AccountRef> ACCOUNT = register("account",
			DataComponentType.<AccountRef>builder().persistent(AccountRef.CODEC).networkSynchronized(AccountRef.STREAM_CODEC).build());

	/**
	 * @param cardId the printed card's id in the account ("" for vault items and cards from before card ids)
	 * @param level  "READ", "TRADE", or "" for legacy cards (treated as TRADE)
	 * @param network unused; old cards may carry "testnet"
	 */
	public record AccountRef(String id, String name, String cardId, String level, String network) {
		public AccountRef(String id, String name) {
			this(id, name, "", "", "");
		}

		public AccountRef(String id, String name, String cardId, String level) {
			this(id, name, cardId, level, "");
		}

		public static final Codec<AccountRef> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("id").forGetter(AccountRef::id),
				Codec.STRING.fieldOf("name").forGetter(AccountRef::name),
				Codec.STRING.optionalFieldOf("card", "").forGetter(AccountRef::cardId),
				Codec.STRING.optionalFieldOf("level", "").forGetter(AccountRef::level),
				Codec.STRING.optionalFieldOf("network", "").forGetter(AccountRef::network)
		).apply(i, AccountRef::new));
		public static final StreamCodec<ByteBuf, AccountRef> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, AccountRef::id,
				ByteBufCodecs.STRING_UTF8, AccountRef::name,
				ByteBufCodecs.STRING_UTF8, AccountRef::cardId,
				ByteBufCodecs.STRING_UTF8, AccountRef::level,
				ByteBufCodecs.STRING_UTF8, AccountRef::network,
				AccountRef::new);

		public boolean readOnly() {
			return "READ".equals(level);
		}

		@Nullable
		public UUID uuid() {
			try {
				return UUID.fromString(id);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** A scoped card's limits, as printed, for its tooltip. The account's record decides what the card may do. */
	public static final DataComponentType<String> CARD_SCOPE = register("card_scope",
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

	/** An Order Ticket's order: the executor settings it carries, as JSON, with a "summary" line. */
	public static final DataComponentType<String> ORDER_SPEC = register("order_spec",
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

	/** The signal a Signal Card reads. */
	public static final DataComponentType<SignalRef> SIGNAL = register("signal",
			DataComponentType.<SignalRef>builder().persistent(SignalRef.CODEC).networkSynchronized(SignalRef.STREAM_CODEC).build());

	public record SignalRef(String id, String name) {
		public static final Codec<SignalRef> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("id").forGetter(SignalRef::id),
				Codec.STRING.fieldOf("name").forGetter(SignalRef::name)
		).apply(i, SignalRef::new));
		public static final StreamCodec<ByteBuf, SignalRef> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SignalRef::id,
				ByteBufCodecs.STRING_UTF8, SignalRef::name,
				SignalRef::new);

		@Nullable
		public UUID uuid() {
			try {
				return UUID.fromString(id);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	private static <T> DataComponentType<T> register(String name, DataComponentType<T> type) {
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, HyperCraft.id(name), type);
	}

	public static void init() {}
}
