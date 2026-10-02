package org.zenith.module.player.autosell;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.zenith.core.GameService;
import org.zenith.managers.MotorIntentModel;
import org.zenith.rotation.MotorIntentRotationStrategy;

/**
 * Плавное перенаведение на библиотеке записанных жестов — той же, что ведёт прицел в режиме HolyWorld у ауры.
 *
 * <p>Аура подаёт модели хитбокс цели; здесь цель — виртуальный бокс в нужном направлении, поэтому модель
 * играет те же записанные движения руки для обычного поворота камеры. Состояние жеста у каждого хоста своё
 * (боты тикают в своих потоках), общая только разобранная библиотека, которую модель лишь читает.
 */
public final class AutoSellMotorAim {
   private static final double VIRTUAL_DISTANCE = 4.0;
   /** Сколько тиков даём модели дойти до цели, дальше доводим простым сглаживанием. */
   private static final int MODEL_TICKS = 60;
   public static final int GIVE_UP_TICKS = 140;
   private static final float MAX_BOX_HALF = 2.0F;

   private MotorIntentModel model;
   private MotorIntentModel librarySource;
   private boolean modelBroken;
   private boolean active;
   private float targetYaw;
   private float targetPitch;
   private float boxHalf;
   private int ticks;
   private int maxTicks;
   private boolean primed;
   private float lastYaw;
   private float lastPitch;
   private boolean hasPrevious;
   private double previousYawClicks;
   private double previousPitchClicks;

   public void aimAt(float yaw, float pitch, float currentYaw, float currentPitch, int maxTicks) {
      float yawDelta = MathHelper.wrapDegrees(yaw - currentYaw);
      this.targetYaw = currentYaw + yawDelta;
      this.targetPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
      float distance = Math.max(Math.abs(yawDelta), Math.abs(this.targetPitch - currentPitch));
      // Модель учили на хитбоксах: слишком узкий бокс заставляет её «дрожать» доводками вокруг цели.
      this.boxHalf = MathHelper.clamp(distance * 0.3F, gcdStep(), MAX_BOX_HALF);
      this.ticks = 0;
      this.maxTicks = Math.min(maxTicks, GIVE_UP_TICKS);
      this.primed = false;
      this.hasPrevious = false;
      this.active = true;
      MotorIntentModel intentModel = this.resolveModel();
      if (intentModel != null) {
         intentModel.reset();
      }
   }

   public boolean isActive() {
      return this.active;
   }

   public void cancel() {
      this.active = false;
   }

   /** Один тик поворота: {yaw, pitch} для применения или null, если двигаться не нужно. */
   public float[] tick(float yaw, float pitch, Vec3d motion) {
      if (!this.active) {
         return null;
      }

      float yawDelta = MathHelper.wrapDegrees(this.targetYaw - yaw);
      float pitchDelta = this.targetPitch - pitch;
      if (Math.abs(yawDelta) <= this.boxHalf && Math.abs(pitchDelta) <= this.boxHalf || ++this.ticks > this.maxTicks) {
         this.active = false;
         return null;
      }

      float gcd = gcdStep();
      MotorIntentModel intentModel = this.ticks <= MODEL_TICKS ? this.resolveModel() : null;
      if (intentModel != null) {
         // Как у стратегии ауры: первый тик только запоминает угол, от него считается фактический ход.
         if (!this.primed) {
            this.primed = true;
            this.lastYaw = yaw;
            this.lastPitch = pitch;
            return null;
         }

         try {
            int[] clicks = this.modelStep(intentModel, yaw, pitch, yawDelta, pitchDelta, gcd, motion);
            int yawClicks = limitClicks(clicks[0], yawDelta, gcd);
            int pitchClicks = limitClicks(clicks[1], pitchDelta, gcd);
            return new float[]{yaw + yawClicks * gcd, MathHelper.clamp(pitch + pitchClicks * gcd, -90.0F, 90.0F)};
         } catch (Throwable throwable) {
            this.modelBroken = true;
            this.model = null;
         }
      }

      return smoothStep(yaw, pitch, yawDelta, pitchDelta, gcd);
   }

   /**
    * Жесты записаны в бою и на мелкой подстройке иногда «перелетают» цель на порядок или уходят от неё.
    * Шаг к цели режется до max(2°, 1.2 × остаток), от цели — до одного щелчка: на развороте это ничего
    * не меняет, а мелкий поворот не превращается во флик и не блуждает.
    * Модель считывает фактический ход обратно, поэтому обрезка не раскачивает петлю.
    */
   private static int limitClicks(int clicks, float remaining, float gcd) {
      if (clicks != 0 && Math.signum(clicks) != Math.signum(remaining)) {
         return Integer.signum(clicks);
      }

      int limit = Math.max(1, Math.round(Math.max(2.0F, Math.abs(remaining) * 1.2F) / gcd));
      return MathHelper.clamp(clicks, -limit, limit);
   }

   private int[] modelStep(MotorIntentModel intentModel, float yaw, float pitch, float yawDelta, float pitchDelta, float gcd, Vec3d motion) {
      int movedYaw = Math.round(MathHelper.wrapDegrees(yaw - this.lastYaw) / gcd);
      int movedPitch = Math.round((pitch - this.lastPitch) / gcd);
      this.lastYaw = yaw;
      this.lastPitch = pitch;
      double centerYaw = yawDelta / gcd;
      double centerPitch = pitchDelta / gcd;
      double half = this.boxHalf / gcd;
      // Признаки те же, что собирает MotorIntentRotationStrategy, только для неподвижного виртуального бокса.
      MotorIntentModel.Prediction prediction = new MotorIntentModel.Prediction();
      prediction.double11 = centerYaw;
      prediction.double12 = centerPitch;
      prediction.double13 = centerYaw - half;
      prediction.double14 = centerYaw + half;
      prediction.double15 = centerPitch - half;
      prediction.double16 = centerPitch + half;
      if (this.hasPrevious) {
         prediction.double17 = centerYaw - this.previousYawClicks;
         prediction.double18 = centerPitch - this.previousPitchClicks;
         prediction.double19 = centerYaw - (this.previousYawClicks - movedYaw);
         prediction.double20 = centerPitch - (this.previousPitchClicks - movedPitch);
      }

      prediction.double21 = centerYaw;
      prediction.double22 = centerPitch;
      prediction.double23 = 0.0;
      prediction.double24 = VIRTUAL_DISTANCE;
      if (motion != null) {
         prediction.double28 = motion.x;
         prediction.double29 = motion.y;
         prediction.double30 = motion.z;
      }

      this.previousYawClicks = centerYaw;
      this.previousPitchClicks = centerPitch;
      this.hasPrevious = true;
      int[] clicks = intentModel.on23(prediction);
      return clicks != null && clicks.length >= 2 ? clicks : new int[2];
   }

   /** Шаг мыши игрока, как у моторной стратегии ауры; без клиента — типичный шаг на средней чувствительности. */
   private static float gcdStep() {
      try {
         return MotorIntentRotationStrategy.gcdStep();
      } catch (RuntimeException | LinkageError exception) {
         return 0.15F;
      }
   }

   private MotorIntentModel resolveModel() {
      if (this.modelBroken) {
         return null;
      }

      MotorIntentModel shared;
      try {
         shared = GameService.val001.ColorUtils().zClass026;
      } catch (Throwable throwable) {
         shared = null;
      }

      if (shared == null) {
         return this.model;
      }

      // Аура перечитывает библиотеку при включении — тогда пересобираем своё состояние поверх новой.
      if (this.model == null || this.librarySource != shared) {
         this.model = new MotorIntentModel(shared.var132, shared.var2);
         this.librarySource = shared;
      }

      return this.model;
   }

   private static float[] smoothStep(float yaw, float pitch, float yawDelta, float pitchDelta, float gcd) {
      float share = 0.25F + ThreadLocalRandom.current().nextFloat() * 0.2F;
      float yawStep = quantize(MathHelper.clamp(yawDelta * share, -35.0F, 35.0F), yawDelta, gcd);
      float pitchStep = quantize(MathHelper.clamp(pitchDelta * share, -20.0F, 20.0F), pitchDelta, gcd);
      return new float[]{yaw + yawStep, MathHelper.clamp(pitch + pitchStep, -90.0F, 90.0F)};
   }

   private static float quantize(float step, float remaining, float gcd) {
      float quantized = Math.round(step / gcd) * gcd;
      if (quantized == 0.0F && Math.abs(remaining) > gcd) {
         quantized = Math.copySign(gcd, remaining);
      }

      return quantized;
   }
}
