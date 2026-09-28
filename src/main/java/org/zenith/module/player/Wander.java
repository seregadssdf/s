package org.zenith.module.player;

import com.darkmagician6.eventapi.EventTarget;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.MathHelper;
import org.zenith.event.EventTick;
import org.zenith.event.MovementInputEvent;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;

/** Анти-АФК: изредка проходит туда-обратно по взгляду, без Baritone — обычное движение клиента. */
@ModuleInfo(name = "Wander", category = Category.PLAYER, description = "Иногда ходит туда-сюда, чтобы не стоять на месте")
public final class Wander extends Module {
   public static final Wander wander = new Wander();
   private static final long EPISODE_MIN = 800L;
   private static final long EPISODE_MAX = 2200L;
   private static final long PAUSE_MIN = 5000L;
   private static final long PAUSE_MAX = 18000L;

   private int step;
   private long phaseEnd;
   private long nextEpisode;
   private float turnTarget = Float.NaN;

   @Override
   public void onEnable() {
      step = 0;
      nextEpisode = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(2000L, PAUSE_MAX);
      turnTarget = Float.NaN;
      super.onEnable();
   }

   @Override
   public void onDisable() {
      step = 0;
      turnTarget = Float.NaN;
      super.onDisable();
   }

   @EventTarget
   public void onTick(EventTick event) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player == null || client.player.networkHandler == null) return;
      long now = System.currentTimeMillis();
      if (turning(client)) return;
      switch (step) {
         case 0 -> {
            if (now < nextEpisode) return;
            if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) {
               nextEpisode = now + 3000L;
               return;
            }
            if (ThreadLocalRandom.current().nextInt(4) == 0) turnTarget = client.player.getYaw() + 180.0F;
            phaseEnd = now + ThreadLocalRandom.current().nextLong(EPISODE_MIN, EPISODE_MAX);
            step = 1;
         }
         case 1 -> {
            if (now >= phaseEnd || client.player.horizontalCollision) {
               phaseEnd = now + ThreadLocalRandom.current().nextLong(EPISODE_MIN, EPISODE_MAX);
               step = 2;
            }
         }
         default -> {
            if (now >= phaseEnd || client.player.horizontalCollision) {
               step = 0;
               nextEpisode = now + ThreadLocalRandom.current().nextLong(PAUSE_MIN, PAUSE_MAX);
            }
         }
      }
   }

   @EventTarget
   public void onMoveInput(MovementInputEvent event) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (!isEnabled() || client.player == null) return;
      if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) return;
      switch (step) {
         case 1 -> event.on23(1.0F, 0.0F);
         case 2 -> event.on23(-1.0F, 0.0F);
         default -> { }
      }
   }

   /** Плавный доворот на 180 перед обратным проходом, по 25° за тик. */
   private boolean turning(MinecraftClient client) {
      if (Float.isNaN(turnTarget)) return false;
      float delta = MathHelper.wrapDegrees(turnTarget - client.player.getYaw());
      if (Math.abs(delta) <= 25.0F) {
         client.player.setYaw(turnTarget);
         turnTarget = Float.NaN;
         return false;
      }
      client.player.setYaw(client.player.getYaw() + MathHelper.clamp(delta, -25.0F, 25.0F));
      return true;
   }
}
