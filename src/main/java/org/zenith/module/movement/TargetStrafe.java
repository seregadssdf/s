package org.zenith.module.movement;

import com.darkmagician6.eventapi.EventTarget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.zenith.event.MovementInputEvent;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.module.combat.Aura;
import org.zenith.setting.NumberSetting;

/**
 * Автоматический стрейф вокруг текущей цели Aura, поддерживая заданную дистанцию.
 * Модуль заменяет направление ввода игрока, оставляя прыжок, приседание и спринт без изменения.
 * Сторона орбиты меняется пользователем или автоматически при обнаружении столкновения.
 */
@ModuleInfo(name = "Target Strafe", description = "module.targetStrafe.desc", category = Category.MOVEMENT)
public final class TargetStrafe extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final TargetStrafe targetStrafe = new TargetStrafe();

   /**
    * Желаемая горизонтальная дистанция до цели в блоках.
    * 0 — вплотную, 3 — максимальная периферийная орбита.
    */
   public final NumberSetting radius = new NumberSetting(
      "module.targetStrafe.radius", 2.0F, 0.0F, 3.0F, 0.1F, "module.targetStrafe.radius.desc", "b"
   );

   /**
    * Направление орбиты: +1 движение против часовой (влево при взгляде на цель),
    * -1 по часовой (вправо).
    */
   private int direction = 1;

   /**
    * Последняя дистанция до цели для обнаружения столкновения: если дистанция
    * не уменьшилась за тик, хотя радиальный компонент был направлен к цели,
    * это столкновение со стеной, и сторона переключается.
    */
   private double lastDistance = Double.MAX_VALUE;

   @Override
   public void onDisable() {
      this.direction = 1;
      this.lastDistance = Double.MAX_VALUE;
      super.onDisable();
   }

   @EventTarget
   public void on23(MovementInputEvent var1) {
      if (minecraftClient3.player == null || minecraftClient3.currentScreen != null || !Aura.aura.isEnabled()) {
         return;
      }

      LivingEntity livingentity = Aura.aura.zClass054();
      if (livingentity == null || !livingentity.isAlive()) {
         return;
      }

      double d0 = minecraftClient3.player.getX() - livingentity.getX();
      double d1 = minecraftClient3.player.getZ() - livingentity.getZ();
      double d2 = Math.sqrt(d0 * d0 + d1 * d1);

      // Пользовательский боковой ввод перебивает автоматическое направление.
      float f = minecraftClient3.player.input.getMovementInput().x;
      if (f != 0.0F) {
         this.direction = f > 0.0F ? -1 : 1;
      }

      // Обнаружение столкновения: если дистанция не уменьшается при движении к цели.
      float f1 = this.radius.getCurrent();
      if (d2 > this.lastDistance && d2 > f1 + 0.1) {
         this.direction = -this.direction;
      }
      this.lastDistance = d2;

      // Вектор к цели на горизонтальной плоскости: d0,d1.
      // Перпендикуляр (касательный) — поворот на 90° против часовой: (-d1, d0).
      // Направление d1' = -d1 * direction, d0' = d0 * direction.
      double d3 = -d1 * this.direction;
      double d4 = d0 * this.direction;

      // Радиальная коррекция: при дистанции больше радиуса добавляем движение к цели.
      double d5 = d2 - f1;
      if (Math.abs(d5) > 0.1) {
         double d6 = Math.signum(d5) * 0.3;
         d3 += -d0 / d2 * d6;
         d4 += -d1 / d2 * d6;
      }

      // Нормализуем до длины 1.
      double d7 = Math.sqrt(d3 * d3 + d4 * d4);
      if (d7 > 1.0E-8) {
         d3 /= d7;
         d4 /= d7;
      }

      // Преобразуем мировой вектор в угол относительно yaw игрока.
      float f2 = minecraftClient3.player.getYaw();
      double d8 = Math.toRadians(f2 + 90.0F);
      double d9 = Math.sin(d8);
      double d10 = Math.cos(d8);

      // forward = (d3 * cos - d4 * sin), strafe = (d3 * sin + d4 * cos).
      float f3 = (float)(d3 * d10 - d4 * d9);
      float f4 = (float)(d3 * d9 + d4 * d10);

      var1.on23(f3, f4);
   }
}

