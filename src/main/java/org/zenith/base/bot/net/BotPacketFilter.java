package org.zenith.base.bot.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.List;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.encoding.VarInts;
import net.minecraft.network.packet.s2c.play.RecipeBookAddS2CPacket;
import net.minecraft.network.state.NetworkState;

/**
 * Выкидывает пакеты, которые бот не использует, ещё до декодера. recipe_book_add после перевода Via
 * часто не разбирается нашим кодеком, и из-за книги рецептов бот вылетал с сервера.
 */
public final class BotPacketFilter extends ChannelInboundHandlerAdapter {
   public static final String NAME = "bot_packet_filter";
   private final int recipeBookAddId;

   private BotPacketFilter(int recipeBookAddId) {
      this.recipeBookAddId = recipeBookAddId;
   }

   /** Фильтр для play-состояния или null, если id пакета узнать не удалось. */
   @SuppressWarnings("unchecked")
   public static BotPacketFilter forPlay(NetworkState<?> state) {
      ByteBuf buf = Unpooled.buffer();
      try {
         // id берётся из того же кодека, что у декодера: пустой пакет кодируется без реестров.
         ((PacketCodec<ByteBuf, Object>)(Object)state.codec()).encode(buf, new RecipeBookAddS2CPacket(List.of(), false));
         return new BotPacketFilter(VarInts.read(buf));
      } catch (RuntimeException exception) {
         return null;
      } finally {
         buf.release();
      }
   }

   @Override
   public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof ByteBuf buf && buf.isReadable() && this.peekId(buf) == this.recipeBookAddId) {
         buf.release();
         return;
      }

      super.channelRead(ctx, msg);
   }

   private int peekId(ByteBuf buf) {
      int index = buf.readerIndex();
      try {
         return VarInts.read(buf);
      } catch (RuntimeException exception) {
         return -1;
      } finally {
         buf.readerIndex(index);
      }
   }
}
