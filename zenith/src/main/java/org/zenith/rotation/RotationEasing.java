package org.zenith.rotation;

import org.zenith.core.GameService;
import org.zenith.core.MenuEaseA;
import org.zenith.core.MenuEaseB;
import org.zenith.core.MenuEaseC;
import org.zenith.core.MenuEaseD;
import org.zenith.core.MenuEaseE;
import org.zenith.core.MenuEaseF;
import org.zenith.core.RotationBotStrategy;
import org.zenith.module.combat.Aura;

public class RotationEasing implements GameService {
   public final RotationSnapStrategy zClass049 = new RotationSnapStrategy();
   public final RotationLegitStrategy zClass054 = new RotationLegitStrategy();
   public final AimPolicyRotationStrategy zClass022 = new AimPolicyRotationStrategy();
   public final MotorIntentRotationStrategy zClass094 = new MotorIntentRotationStrategy();
   public final RotationBotStrategy zClass087 = new RotationBotStrategy();
   public final RotationSmoothStrategy zClass029 = new RotationSmoothStrategy();
   public final RotationBurstStrategy zClass088 = new RotationBurstStrategy();
   public final RotationEasingBase var135 = new MenuEaseF();
   public final RotationEasingBase var1352 = new MenuEaseB();
   public final RotationEasingBase var1353 = new MenuEaseC();
   public final RotationEasingBase var1354 = RoundedRectEasing.call270().call200();
   public final RotationEasingBase var1355 = MenuEaseD.call201().call202();
   public final RotationEasingBase var1356 = MenuEaseA.screen().call457();
   public final RotationEasingBase var1357 = MenuEaseE.call469().call465();

   public Rotation on23(RotationEasingBase var1, Rotation var2) {
      Rotation ililiiili1ll1li11 = switch (var1.call110()) {
         case call415 -> this.zClass049.Easing(var2);
         case call414 -> this.zClass029.Easing(var2);
         case call269 -> this.zClass094.Easing(var2);
         case call416 -> this.zClass094.Easing(var2);
         case call411 -> this.zClass094.Easing(var2);
         case call412 -> this.zClass094.Easing(var2);
         case call441 -> this.zClass088.Easing(var2);
         default -> val003.CloudRouter().LineShader();
      };
      ililiiili1ll1li11 = maceSpeedUp(ililiiili1ll1li11, var2);
      return val002.LineShader().equals(ililiiili1ll1li11)
         ? ililiiili1ll1li11
         : val002.LineShader().on23(val002.LineShader().EmoteManager(ililiiili1ll1li11)).CosmeticManager(val002.LineShader());
   }

   /**
    * Умножает шаг ротации с булавой в руке. Точка одна на все стратегии: и снап, и плавная,
    * и моторная, и очередями — все проходят здесь, поэтому множитель не приходится вписывать
    * в каждую и расходиться с ней при правках.
    *
    * Масштабируется шаг от текущей ротации к выходу стратегии, а не сам выход: выход — это
    * «куда встать в этом тике», и умножать его как угол значило бы улететь мимо цели тем
    * дальше, чем дальше цель.
    *
    * Шаг обрезается по шагу до цели, отдельно по яву и по питчу. Без обрезки удвоение
    * проносило бы прицел за цель на последних тиках доводки, а следующий тик тянул бы назад —
    * то есть вместо быстрого захода получился бы звон вокруг цели. С обрезкой стратегия,
    * которая и так встаёт на цель (снап), остаётся нетронутой.
    *
    * Порядок с квантованием важен: множитель ложится до {@code CosmeticManager}, поэтому
    * результат остаётся на сетке GCD мыши. Моторной стратегии это и нужно — её щелчки
    * умножаются как щелчки, а не превращаются в дробные углы между ними.
    */
   public static Rotation maceSpeedUp(Rotation var0, Rotation var1) {
      if (var0 == null || var1 == null || var1.string68() || !Aura.maceInHand()) {
         return var0;
      }

      Rotation ililiiili1ll1li11 = val002.LineShader();
      RotationDelta liiilliiilil1l1i1111li1ii11 = ililiiili1ll1li11.EmoteManager(var0);
      float f = liiilliiilil1l1i1111li1ii11.type2();
      float f1 = liiilliiilil1l1i1111li1ii11.path15();
      if (!isFinite(f) || !isFinite(f1) || f == 0.0F && f1 == 0.0F) {
         return var0;
      }

      RotationDelta liiilliiilil1l1i1111li1ii112 = ililiiili1ll1li11.EmoteManager(var1);
      float f2 = liiilliiilil1l1i1111li1ii112.type2();
      float f3 = liiilliiilil1l1i1111li1ii112.path15();
      float f4 = clampToward(f * Aura.float501, f2);
      float f5 = clampToward(f1 * Aura.float501, f3);
      return isFinite(f4) && isFinite(f5) ? ililiiili1ll1li11.Event08(f4, f5) : var0;
   }

   /**
    * Обрезает шаг по шагу до цели, сохраняя знак шага. Если до цели шага нет или знаки
    * разошлись (стратегия сама ведёт мимо — уводы, перелёты, дрожь), шаг остаётся как есть:
    * обрезать его нулём значило бы отменить стратегию, а не ускорить.
    */
   public static float clampToward(float var0, float var1) {
      if (!isFinite(var0) || !isFinite(var1) || var1 == 0.0F || Math.signum(var0) != Math.signum(var1)) {
         return var0;
      }

      return Math.abs(var0) > Math.abs(var1) ? var1 : var0;
   }

   public static boolean isFinite(float var0) {
      return !Float.isNaN(var0) && !Float.isInfinite(var0);
   }

   public RotationLegitStrategy ArgbColor() {
      return this.zClass054;
   }

   public MotorIntentRotationStrategy ColorUtils() {
      return this.zClass094;
   }

   public RotationBotStrategy RenderCommandQueue() {
      return this.zClass087;
   }

   public RotationEasingBase HudPreviewItem() {
      return this.var135;
   }

   public RotationEasingBase HudPreviewRenderQueue() {
      return this.var1352;
   }

   public RotationEasingBase RectBatch() {
      return this.var1353;
   }

   public RotationEasingBase RoundedRectBatch() {
      return this.var1354;
   }

   public RotationEasingBase FillShader() {
      return this.var1355;
   }

   public RotationEasingBase ShapeRenderer() {
      return this.var1356;
   }

   public RotationEasingBase ShaderWrapper() {
      return this.var1357;
   }
}
