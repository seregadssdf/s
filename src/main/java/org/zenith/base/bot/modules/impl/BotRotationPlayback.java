package org.zenith.base.bot.modules.impl;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.math.MathHelper;
import org.zenith.base.bot.world.BotPlayer;
import org.zenith.rotation.MotorIntentRotationStrategy;

/**
 * Smooth HolyWorld-style rotation for headless bots.
 * Reuses the same GCD grid as the KillAura motor model so every step lands on a real mouse click.
 */
public final class BotRotationPlayback {
   private float startYaw;
   private float startPitch;
   private float deltaYaw;
   private float deltaPitch;
   private int totalTicks;
   private int tick;
   private boolean active;

   public void start(BotPlayer player, float deltaYaw, float deltaPitch, long durationMs) {
      startYaw = player.getYaw();
      startPitch = player.getPitch();
      this.deltaYaw = deltaYaw;
      this.deltaPitch = deltaPitch;
      totalTicks = Math.max(2, (int) (durationMs / 50L));
      tick = 0;
      active = true;
   }

   /** @return true when the gesture finished. */
   public boolean tick(BotPlayer player) {
      if (!active) return true;
      tick++;
      float t = Math.min(1.0F, (float) tick / totalTicks);
      float eased = t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0F) / 2.0F;
      float step = MotorIntentRotationStrategy.gcdStep();
      float yaw = startYaw + deltaYaw * eased + jitter(step);
      float pitch = MathHelper.clamp(startPitch + deltaPitch * eased + jitter(step), -90.0F, 90.0F);
      player.setYaw(quantize(yaw, step));
      player.setPitch(quantize(pitch, step));
      if (tick >= totalTicks) active = false;
      return !active;
   }

   public boolean isActive() { return active; }
   public void stop() { active = false; }

   private float jitter(float step) {
      return ThreadLocalRandom.current().nextFloat(-0.5F, 0.5F) * step;
   }

   private float quantize(float value, float step) {
      if (!(step > 0.0F)) return value;
      return Math.round(value / step) * step;
   }
}
