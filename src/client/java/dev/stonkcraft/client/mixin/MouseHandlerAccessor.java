package dev.stonkcraft.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the UI tour park the cursor, so a stray hover doesn't put a tooltip in every screenshot. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void stonkcraft$setX(double x);

	@Accessor("ypos")
	void stonkcraft$setY(double y);
}
