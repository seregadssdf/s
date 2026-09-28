package org.zenith.utility.mixin.network;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.s2c.play.RecipeBookAddS2CPacket;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Headless bots do not use recipe-book contents; discard only an undecodable recipe-book frame. */
@Mixin(RecipeBookAddS2CPacket.class)
public abstract class MixinRecipeBookAddS2CPacket {
   private static final Logger LOGGER = LogUtils.getLogger();

   @Shadow @Final @Mutable
   public static PacketCodec<RegistryByteBuf, RecipeBookAddS2CPacket> CODEC;

   @Inject(method = "<clinit>", at = @At("TAIL"))
   private static void zenith_skipUnsupportedRecipeBookEntries(CallbackInfo callbackInfo) {
      PacketCodec<RegistryByteBuf, RecipeBookAddS2CPacket> original = CODEC;
      CODEC = PacketCodec.ofStatic(original::encode, buffer -> {
         try {
            return original.decode(buffer);
         } catch (RuntimeException exception) {
            int skipped = buffer.readableBytes();
            buffer.skipBytes(skipped);
            LOGGER.warn("Skipping unsupported recipe_book_add payload ({} unread bytes): {}", skipped, exception.toString());
            return new RecipeBookAddS2CPacket(List.of(), false);
         }
      });
      LOGGER.info("Installed tolerant recipe_book_add decoder for headless bots (native Minecraft 1.21.11)");
   }
}
