package org.zenith.module.combat;

import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.module.ModuleManager;
import org.zenith.module.combat.*;
import org.zenith.module.movement.*;
import org.zenith.module.player.*;
import org.zenith.module.render.*;
import org.zenith.module.misc.*;

import com.darkmagician6.eventapi.EventTarget;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.GrassBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.MaceItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.zenith.ZenithClient;
import org.zenith.core.EffectEngine;
import org.zenith.core.MovementController;
import org.zenith.core.PlayerStateService;
import org.zenith.event.RotationUpdateStartEvent;
import org.zenith.event.TargetAcquireEvent;
import org.zenith.event.EventInjectAddEntity;
import org.zenith.event.EventTick;
import org.zenith.event.MovementInputEvent;
import org.zenith.event.PacketEvent;
import org.zenith.event.PacketReceiveEvent;
import org.zenith.event.PreventActionEvent;
import org.zenith.event.SprintStateEvent;
import org.zenith.managers.MotorIntentModel;
import org.zenith.managers.TargetSelector;
import org.zenith.rotation.Rotation;
import org.zenith.rotation.RotationEasingBase;
import org.zenith.rotation.RotationLegitStrategy;
import org.zenith.rotation.RotationLegitStrategy;
import org.zenith.rotation.RotationMath;
import org.zenith.rotation.RotationTask;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.SettingGroup;
import org.zenith.setting.MultiSelectSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.MultiSelectSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.util.CooldownTimer;
import org.zenith.util.MovementUtils;
import org.zenith.util.RandomSource;
import org.zenith.util.RaycastUtils;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;

@ModuleInfo(name = "Aura", category = Category.COMBAT, description = "Бьет таргета")
public final class Aura extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final Aura aura = new Aura();
   /**
    * Пре-дистанция с булавой в руке. 50 блоков — это «держать цель всегда»: дальше
    * неё цель уже не отрисована как игрок и выбирать её незачем.
    */
   public static final float float500 = 50.0F;
   /**
    * Во сколько раз быстрее вести прицел с булавой в руке. Булава бьёт из падения:
    * цель проходит под игроком за единицы тиков, и обычная скорость не успевает
    * довести яв и питч до момента удара.
    */
   public static final float float501 = 2.0F;
   /**
    * Допуск на касание хитбоксов. Столкновение игроков — это не покой: пока боксы
    * пересекаются, ванильный расчёт толчка разводит их обратно, и на следующем тике
    * пересечения уже нет. Строгая проверка {@code intersects} на такой паре мигала бы
    * тик через тик, а вместе с ней мигала бы и заморозка наводки. 0.05 блока заметно
    * больше амплитуды этого развода и заметно меньше ширины хитбокса (0.6), поэтому
    * касание держится стабильно, а «рядом, но не вплотную» под него не попадает.
    */
   public static final double hitboxTouchSlack = 0.05;
   public final ModeSetting rotationMode = new ModeSetting("module.aura.rotationMode", "module.aura.rotationMode.desc");
   public final ModeSetting.Option modeSetting3Var1593 = new ModeSetting.Option(this.rotationMode, "HolyWorld").int210();
   public final ModeSetting.Option modeSetting3Var1594 = new ModeSetting.Option(this.rotationMode, "FunTime");
   public final ModeSetting.Option modeSetting3Var1595 = new ModeSetting.Option(this.rotationMode, "SpokyTime");
   public final ModeSetting.Option modeSetting3Var1596 = new ModeSetting.Option(this.rotationMode, "module.aura.legit");
   public final ModeSetting.Option modeSetting3Var1597 = new ModeSetting.Option(this.rotationMode, "module.aura.snap");
   public final ModeSetting.Option modeSetting3Var1598 = new ModeSetting.Option(this.rotationMode, "module.aura.hvh");
   public final ModeSetting legitMode = new ModeSetting("module.aura.legitMode", "module.aura.legitMode.desc", this.modeSetting3Var1596::isSelected);
   public final ModeSetting.Option modeSetting3Var1599 = new ModeSetting.Option(this.legitMode, "module.aura.legitSnap").int210();
   public final ModeSetting.Option modeSetting3Var15910 = new ModeSetting.Option(this.legitMode, "module.aura.legitJerk");
   public final ModeSetting.Option modeSetting3Var15911 = new ModeSetting.Option(this.legitMode, "module.aura.legitConstant");
   public final NumberSetting legitMaxAngle = new NumberSetting(
      "module.aura.legitMaxAngle", 5.0F, 0.5F, 180.0F, 0.5F, "module.aura.legitMaxAngle.desc", "°", this.modeSetting3Var1596::isSelected, null
   );
   public final ModeSetting sprintMode = new ModeSetting("module.aura.sprintMode", "module.aura.sprintMode.desc");
   public final ModeSetting.Option modeSetting3Var15912 = new ModeSetting.Option(this.sprintMode, "module.aura.sprintHvh");
   public final ModeSetting.Option modeSetting3Var15913 = new ModeSetting.Option(this.sprintMode, "module.aura.sprintNormal").int210();
   public final ModeSetting.Option modeSetting3Var15914 = new ModeSetting.Option(this.sprintMode, "module.aura.sprintLegit");
   public final ModeSetting.Option modeSetting3Var15915 = new ModeSetting.Option(this.sprintMode, "module.aura.sprintSuperLegit");
   public final ModeSetting.Option modeSetting3Var15916 = new ModeSetting.Option(this.sprintMode, "module.aura.sprintNone");
   public final ModeSetting correction2 = new ModeSetting("module.aura.correction", "module.aura.correction.desc");
   public final ModeSetting.Option modeSetting3Var15917 = new ModeSetting.Option(this.correction2, "module.aura.correctionFocus");
   public final ModeSetting.Option modeSetting3Var15918 = new ModeSetting.Option(this.correction2, "module.aura.correctionGood").int210();
   public final ModeSetting.Option modeSetting3Var15919 = new ModeSetting.Option(this.correction2, "module.aura.correctionNone");
   public final NumberSetting distance2 = new NumberSetting("module.aura.distance", 3.0F, 0.5F, 6.0F, 0.1F, "module.aura.distance.desc", "b");
   public final NumberSetting distanceRotation = new NumberSetting(
      "module.aura.distanceRotation", 0.1F, 0.0F, 6.0F, 0.1F, "module.aura.distanceRotation.desc", "b"
   );
   /**
    * Пре-дистанция: с какого расстояния аура уже держит цель и ведёт по ней прицел,
    * ещё не доставая до удара. Верх диапазона 50 блоков — выше булавного порога, чтобы
    * ручное значение могло перебить автоматику, а не только уступать ей.
    */
   public final NumberSetting preDistance = new NumberSetting(
      "module.aura.preDistance", 0.0F, 0.0F, 50.0F, 0.05F, "module.aura.preDistance.desc", "b"
   );
   public final MultiSelectSetting settings = new MultiSelectSetting("module.aura.settings", "module.aura.settings.desc");
   public final MultiSelectSetting.Option modeSettingVar159 = new MultiSelectSetting.Option(this.settings, "module.aura.shieldBreak", true);
   public final MultiSelectSetting.Option modeSettingVar1592 = new MultiSelectSetting.Option(this.settings, "module.aura.shielRealese", true);
   public final MultiSelectSetting.Option modeSettingVar1593 = new MultiSelectSetting.Option(this.settings, "module.aura.eatUseAttack", true);
   public final MultiSelectSetting.Option modeSettingVar1594 = new MultiSelectSetting.Option(this.settings, "module.aura.attackIgnoreWals", true);
   public final ModeSetting cooldownMode = new ModeSetting("module.aura.cooldownMode", "module.aura.cooldownMode.desc");
   public final ModeSetting.Option modeSetting3Var15920 = new ModeSetting.Option(this.cooldownMode, "module.aura.slowMode");
   public final ModeSetting.Option modeSetting3Var15921 = new ModeSetting.Option(this.cooldownMode, "module.aura.speedMode").int210();
   public final ModeSetting.Option modeSetting3Var15922 = new ModeSetting.Option(this.cooldownMode, "module.aura.customMode");
   public final NumberSetting timeCooldown = new NumberSetting(
      "module.aura.timeCooldown", 0.0F, -1.0F, 1.0F, 0.5F, "module.aura.timeCooldown.desc", "t", this.modeSetting3Var15922::isSelected, null
   );
   public final NumberSetting attackCooldown = new NumberSetting(
      "module.aura.attackCooldown", 1.0F, 0.0F, 1.0F, 0.01F, "module.aura.attackCooldown.desc", "%", this.modeSetting3Var15922::isSelected, null
   );
   public final SettingGroup targetSettingWindow2 = new SettingGroup(
      "module.aura.targetSettingWindow",
      "module.aura.targetWindow.desc",
      () -> true,
      MultiSelectSetting.on23(
         "module.aura.targetTypeSetting",
         "module.aura.targetTypeSetting.desc",
         List.of("module.aura.targetPlayers", "module.aura.noarmor", "module.aura.targetHostile", "module.aura.targetPeaceful")
      ),
      MultiSelectSetting.on23(
         "module.aura.targetSortSetting",
         "module.aura.targetSortSetting.desc",
         List.of("module.aura.targetFov", "module.aura.targetArmor", "module.aura.targetHp", "module.aura.targetDistance")
      ),
      new BooleanSetting("module.aura.safeTarget", "module.aura.safeTarget.desc", true)
   );
   public final BooleanSetting onlyCrit = new BooleanSetting("module.aura.onlyCrit", "module.aura.onlyCrit.desc", true);
   public final BooleanSetting smartCrit = new BooleanSetting("module.aura.smartCrit", "module.aura.smartCrit.desc", false, this.onlyCrit::isEnabled);
   public final BooleanSetting wallbypass = new BooleanSetting(
      "module.aura.wallbypass", "module.aura.wallbypass.desc", true, () -> !this.modeSettingVar1594.isEnabled()
   );
   public final BooleanSetting bups = new BooleanSetting("bups", "bups", false);
   /** Показывается только в HolyWorld — в остальных режимах удержание не работает. */
   public final BooleanSetting holdAim = new BooleanSetting(
      "module.aura.holdAim", "module.aura.holdAim.desc", true, this.modeSetting3Var1593::isSelected
   );
   /**
    * Реже выбирать жесты слежения. Видно в HolyWorld и FunTime — ровно те режимы, что уходят в
    * моторную модель; в остальных ручка ничего не значит.
    */
   public final BooleanSetting lessTracking = new BooleanSetting(
      "module.aura.lessTracking", "module.aura.lessTracking.desc", true, this::motorModelMode
   );
   public final NumberSetting lessTrackingFactor = new NumberSetting(
      "module.aura.lessTrackingFactor",
      2.0F,
      1.0F,
      10.0F,
      0.5F,
      "module.aura.lessTrackingFactor.desc",
      "x",
      () -> this.motorModelMode() && this.lessTracking.isEnabled(),
      null
   );
   /**
    * Snap instead of continuous tracking: wait at HolyWorld ready moments, issue discrete snaps at
    * attack time. Only visible in HolyWorld since it replaces the motor-model branch.
    */
   public final BooleanSetting snapOnAttack = new BooleanSetting(
      "module.aura.snapOnAttack", "module.aura.snapOnAttack.desc", true, this.modeSetting3Var1593::isSelected
   );
   /**
    * То же удержание, но без требования соприкосновения хитбоксов: держать угол всюду,
    * где рейтрейс уже достаёт до цели. Выключен по умолчанию — это более широкое поведение,
    * чем {@link #holdAim}, и включать его должен пользователь, а не апдейт клиента.
    */
   public final BooleanSetting holdAimNoClinch = new BooleanSetting(
      "module.aura.holdAimNoClinch", "module.aura.holdAimNoClinch.desc", false, this.modeSetting3Var1593::isSelected
   );
   public final RandomSource zClass046 = new RandomSource();
   public LivingEntity livingEntity = null;
   public Slot slot = null;
   public LivingEntity livingEntity2 = null;
   public final CooldownTimer zClass0674 = new CooldownTimer();
   public boolean booleanField = false;
   public boolean boolean2 = false;
   public boolean boolean3 = false;
   /** Bups выполняется один раз на открытие окна атаки, а не каждый тик за стеной. */
   public boolean bupsAttackLatched = false;
   /** Наводка удерживается: точка не пересчитывается, угол переотправляется как есть. */
   public boolean aimHeld = false;
   /**
    * Snap-жест запущен и ещё доигрывается.
    *
    * <p>Готовность удара (var3) держится один тик, а жест из библиотеки идёт несколько: щелчки
    * выдаются моторной моделью по кадрам. Без защёлки ожидание перебивало бы жест на следующем же
    * тике, и от разворота осталось бы первое движение. Защёлка снимается, когда прицел добрался до
    * цели, то есть жест сделал свою работу.
    */
   public boolean snapRunning = false;
   int val215 = 0;

   @EventTarget
   public void UiAnimation(RotationUpdateStartEvent var1) {
      // Каждый тик, а не на включении: слайдер можно двинуть на ходу, и это запись одного поля.
      MotorIntentModel.trackingOdds = this.lessTracking.isEnabled() ? this.lessTrackingFactor.getCurrent() : 1.0F;
      if (minecraftClient3.player.isDead()) {
         this.livingEntity = null;
         this.snapRunning = false;
         val001.ArgbColor().botWorld3();
      } else {
         this.boolean3 = false;
         this.livingEntity = this.zClass022();
         if (this.livingEntity != null) {
            if (minecraftClient3.world.getEntityById(this.livingEntity.getId()) instanceof LivingEntity livingentity) {
               this.livingEntity = livingentity;
            } else {
               this.livingEntity = null;
            }

            this.livingEntity2 = this.livingEntity;
         }

         this.booleanField = false;
         this.boolean2 = false;
         this.aimHeld = false;
         if (this.livingEntity == null) {
            // Цели нет — жест доигрывать некуда, защёлка не должна пережить смену цели.
            this.snapRunning = false;
            val001.ArgbColor().botWorld3();
         } else {
            this.booleanField = this.zClass110();
            this.boolean2 = this.var162();
            // Резолвер точки наводки не вызывается вовсе, пока наводка удержана: смысл удержания
            // именно в том, чтобы новая точка не искалась, а не в том, чтобы найти её и не поехать.
            this.aimHeld = this.shouldHoldAim();
            Rotation ililiiili1ll1li11 = this.aimHeld ? val002.LineShader() : this.call427();
            Slot slot1 = ScreenUtils.on23(minecraftClient3.player.playerScreenHandler, var0 -> var0.getStack().getItem() instanceof AxeItem);
            boolean flag = this.modeSettingVar159.isEnabled()
               && this.zClass0674.EventModifyMouseRotationInput(300L)
               && ZenithClient.on23().FileLogger().ImageEncoder()
               && slot1 != null
               && EffectEngine.NbtEditor(this.livingEntity);
            if (flag && minecraftClient3.player.getMainHandStack().getItem() instanceof AxeItem && this.var135()) {
               PlayerStateService.Easing(this.livingEntity);
               this.zClass0674.reset();
               this.booleanField = false;
               this.boolean2 = false;
            }

            this.on23(ililiiili1ll1li11, this.booleanField || flag, this.boolean2 || flag);
            if (flag) {
               if (!(minecraftClient3.player.getMainHandStack().getItem() instanceof AxeItem) && TaskScheduler.Easing(Aura.class)) {
                  if (!(minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler)) {
                     ScreenUtils.closeScreen();
                  }

                  TaskScheduler.on23(Aura.class, () -> {
                     if (!(minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler)) {
                        ScreenUtils.closeScreen();
                     }

                     ScreenUtils.on23(slot1, Hand.MAIN_HAND, true);
                  });
                  if (this.slot == null) {
                     this.slot = slot1;
                  }
               }
            } else if (this.slot != null && this.modeSettingVar159.isEnabled() && !EffectEngine.NbtEditor(this.livingEntity)) {
               if (!(minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler)) {
                  ScreenUtils.closeScreen();
               }

               Slot slot = this.slot;
               TaskScheduler.on23(Aura.class, () -> {
                  if (!(minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler)) {
                     ScreenUtils.closeScreen();
                  }

                  ScreenUtils.on23(slot, Hand.MAIN_HAND, true);
               });
               this.slot = null;
            }

            if (this.booleanField
               && this.modeSettingVar1592.isEnabled()
               && minecraftClient3.player.isUsingItem()
               && minecraftClient3.player.getActiveItem().getItem() instanceof ShieldItem) {
               minecraftClient3.interactionManager.stopUsingItem(minecraftClient3.player);
            }
         }
      }
   }

   public void ConfigJsonUtil(boolean var1) {
      boolean flag = this.modeSetting3Var15911.isSelected();
      RotationLegitStrategy illlil1iiii = val001.ArgbColor();
      RotationLegitStrategy.LegitRotation illlil1iiii_ii1il11l111ii11iil;
      if (flag) {
         illlil1iiii_ii1il11l111ii11iil = illlil1iiii.on23(
            new Rotation(minecraftClient3.player.getYaw(), minecraftClient3.player.getPitch(), true),
            val002.LineShader(),
            this.var1357(),
            this.var11812(),
            this.legitMaxAngle.getCurrent(),
            this.livingEntity.getId(),
            minecraftClient3.player.age
         );
      } else {
         illlil1iiii.botWorld3();
         illlil1iiii_ii1il11l111ii11iil = illlil1iiii.on23(val002.LineShader(), this.var1357(), this.var11812(), this.legitMaxAngle.getCurrent(), false);
      }

      if (illlil1iiii_ii1il11l111ii11iil.botPlayer3()) {
         if (this.modeSetting3Var1599.isSelected()) {
            if (!var1) {
               return;
            }
         } else if (!this.modeSetting3Var15910.isSelected() && !flag) {
            return;
         }

         if (flag || illlil1iiii_ii1il11l111ii11iil.packet()) {
            int i = this.modeSetting3Var1599.isSelected() ? 3 : 1;
            val002.on23(new RotationTask(illlil1iiii_ii1il11l111ii11iil.rotation(), illlil1iiii_ii1il11l111ii11iil::rotation, val001.HudPreviewItem()), i, this);
         }
      }
   }

   @EventTarget
   public void on23(EventInjectAddEntity var1) {
      if (var1.ElytraFly() instanceof ClientPlayerEntity) {
         this.livingEntity = null;
         this.livingEntity2 = null;
      }
   }

   @EventTarget
   public void on23(PacketReceiveEvent var1) {
   }

   @EventTarget
   public void on23(TargetAcquireEvent var1) {
      if (this.livingEntity != null && this.var135() && this.boolean2) {
         this.booleanField = false;
         this.boolean2 = false;
         this.boolean3 = true;
      }
   }

   @EventTarget
   public void ColorAnimator(EventTick var1) {
      if (!this.boolean3) {
         this.bupsAttackLatched = false;
         return;
      }
      if (this.bupsAttackLatched) {
         return;
      }
      this.bupsAttackLatched = true;
      {
         if (this.modeSetting3Var15912.isSelected() && ZenithClient.on23().CloudApiClient().var11917()) {
            minecraftClient3.player.setSprinting(false);
            minecraftClient3.player.sendSprintingPacket();
         }

         if (this.bups.isEnabled()) {
            this.sendBupsRotation();
         }
         PlayerStateService.Easing(this.livingEntity);
      }
   }

   private void sendBupsRotation() {
      Rotation rotate = val002.LineShader();
      float yaw = rotate.GrimGlide();
      float pitch = rotate.GuiWalk();
      double yawDelta = MathHelper.wrapDegrees(yaw) + 180.0;
      float spoofedYaw;
      if (yawDelta > 100.0 && yawDelta < 200.0) {
         spoofedYaw = yaw - 50.0F;
      } else if (yawDelta >= 200.0 && yawDelta < 240.0) {
         spoofedYaw = yaw + 50.0F;
      } else {
         spoofedYaw = yaw + 25.0F;
      }

      minecraftClient3.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
         minecraftClient3.player.getX(), minecraftClient3.player.getY(), minecraftClient3.player.getZ(),
         spoofedYaw, pitch, minecraftClient3.player.isOnGround(), minecraftClient3.player.horizontalCollision
      ));
      minecraftClient3.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
         minecraftClient3.player.getX(), minecraftClient3.player.getY(), minecraftClient3.player.getZ(),
         rotate.GrimGlide(), rotate.GuiWalk(), minecraftClient3.player.isOnGround(), minecraftClient3.player.horizontalCollision
      ));
   }

   public RotationEasingBase zClass088() {
      return val001.HudPreviewItem();
   }

   public boolean var135() {
      return this.ColorAnimator(val002.LineShader());
   }

   public boolean ColorAnimator(Rotation var1) {
      return this.UiAnimation(var1, this.var1357(), this.livingEntity.getBoundingBox()) || this.UiAnimation(var1, this.var1357(), this.var11812());
   }

   public boolean var1352() {
      return RaycastUtils.on23(
         val003.CloudRouter().LineShader(),
         MovementController.TargetAcquireEvent(1).TriggerBot.add(0.0, minecraftClient3.player.getEyeHeight(minecraftClient3.player.getPose()), 0.0),
         this.var1353(),
         6.0,
         false
      );
   }

   public Box var1353() {
      return this.livingEntity instanceof PlayerEntity playerentity ? MovementController.ColorAnimator(playerentity, 1).box9 : this.livingEntity.getBoundingBox();
   }

   public boolean UiAnimation(Rotation var1, Vec3d var2, Box var3) {
      return this.var1354()
         ? this.on23(var3, var2)
         : ElytraTarget.elytraTarget.call084() != null
               && !ElytraTarget.elytraTarget.call085().isEnabled()
               && ElytraTarget.elytraTarget.call084().getCenter().squaredDistanceTo(minecraftClient3.player.getBoundingBox().getCenter()) < 16.0
            || RaycastUtils.on23(var1, var2, var3, this.var1356(), !this.var1355());
   }

   public boolean var1354() {
      return WallBypass.wallBypass.float34();
   }

   public boolean var1355() {
      return this.modeSettingVar1594.isEnabled() || this.var1354();
   }

   public float var1356() {
      return this.var1354() ? WallBypass.wallBypass.var1356() : this.distance2.getCurrent();
   }

   /**
    * Дистанция наводки: с какого расстояния цель уже держится и ведётся прицелом.
    * Максимум из дистанции удара с доп-дистанцией, слайдера пре-дистанции и — с булавой
    * в руке — {@link #float500}.
    *
    * Именно максимум, а не «булава вместо слайдера»: тернарник означал бы, что с булавой
    * слайдер не читается вовсе, и выставленные вручную 50 блоков молча превращались бы в
    * булавные 50 — то есть настройка не работала бы ровно там, где нужнее всего.
    *
    * Ударом эта дистанция не управляет: {@link #var1356()} остаётся при своём, иначе аура
    * била бы с 50 блоков. Смысл в том, чтобы прицел уже стоял на цели к моменту входа в
    * радиус удара — булаве это нужно больше всех, она бьёт с падения и подходит сверху
    * и быстро.
    */
   public float aimDistance() {
      float f = this.var1356() + this.distanceRotation.getCurrent();
      float f1 = this.preDistance.getCurrent();
      if (maceInHand()) {
         f1 = Math.max(f1, float500);
      }

      return Math.max(f, f1);
   }

   /**
    * Режим, который ведёт прицел моторной моделью, — HolyWorld или FunTime. Ровно эти два уходят в
    * {@code RoundedRectBatch()} в диспетчере ниже, остальные ведут другими стратегиями.
    */
   public boolean motorModelMode() {
      return this.modeSetting3Var1593.isSelected() || this.modeSetting3Var1594.isSelected();
   }

   /** Булава в главной руке. */
   public static boolean maceInHand() {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      return clientplayerentity != null && clientplayerentity.getMainHandStack().getItem() instanceof MaceItem;
   }

   public boolean on23(Box var1, Vec3d var2) {
      Vec3d vec3d = new Vec3d(
         MathHelper.clamp(var2.x, var1.minX, var1.maxX),
         MathHelper.clamp(var2.y, var1.minY, var1.maxY),
         MathHelper.clamp(var2.z, var1.minZ, var1.maxZ)
      );
      return vec3d.isInRange(var2, this.var1356());
   }

   public Vec3d var1357() {
      return minecraftClient3.player.getCameraPosVec(1.0F);
   }

   @EventTarget
   public void onPacket(PacketEvent var1) {
      if (var1.Arrows()) {
         Packet packet = var1.ItemScroller();
         if (packet instanceof PlayerPositionLookS2CPacket) {
            this.val215 = 3;
         }
      }
   }

   /**
    * Держать ли текущий угол вместо пересчёта наводки. Общие условия для обоих чекбоксов:
    * режим HolyWorld, ротация уже активна, и текущий угол сам достаёт до цели рейтрейсом —
    * тот же предикат {@link #var135()}, по которому идёт удар. Различие только в требовании
    * столкновения: {@link #holdAim} держит угол лишь в упор (хитбоксы соприкасаются),
    * {@link #holdAimNoClinch} — на любой дистанции, где рейтрейс проходит.
    *
    * Смысл в том, что пока угол достаёт до цели, искать новую точку нечего: удар и так
    * проходит, а резолвер каждый тик берёт свежую точку хитбокса и уводит прицел с уже
    * рабочего угла. Вблизи угловой размер цели максимален, значит и шаг между двумя соседними
    * точками в градусах максимален — там пересчёт вредит сильнее всего, поэтому вариант в упор
    * и стоит по умолчанию. Дальше по дистанции шаг мельче, но и он не нужен, если угол уже
    * рабочий — этот случай и закрывает второй чекбокс.
    *
    * Дистанцию удержание при этом не расширяет: {@link #var135()} считает рейтрейс по
    * {@link #var1356()}, то есть по дистанции удара. Как только цель выходит из неё,
    * предикат перестаёт выполняться и удержание отпускается само.
    *
    * Проверка идёт по серверному углу (условие {@code !Var05()} — ротация активна),
    * а не по углу мыши. Если аура ещё не вела прицел, а игрок сам смотрит на цель, держать
    * нечего: удержание переотправляет свой угол, и на пустой ротации оно бы просто защёлкнуло
    * прицел там, где стоит мышь.
    */
   public boolean shouldHoldAim() {
      boolean flag = this.holdAimNoClinch.isEnabled() || this.holdAim.isEnabled() && this.hitboxesTouching();
      return flag && this.modeSetting3Var1593.isSelected() && !val002.Var05() && this.var135();
   }

   /** Соприкасаются ли хитбоксы игрока и цели — с допуском {@link #hitboxTouchSlack}. */
   public boolean hitboxesTouching() {
      // Живой бокс цели, без бэктрека и подмен: речь о физическом столкновении здесь и сейчас,
      // а не о том, где цель числилась для удара.
      return this.livingEntity != null
         && minecraftClient3.player.getBoundingBox().expand(hitboxTouchSlack).intersects(this.livingEntity.getBoundingBox());
   }

   public Rotation call427() {
      Vec3d vec3d = minecraftClient3.player.getCameraPosVec(1.0F);
      if (this.livingEntity instanceof PlayerEntity playerentity) {
         Box box = Backtrack.reachV3.isEnabled() && Backtrack.reachV3.float73() != null
            ? this.livingEntity.dimensions.getBoxAt(Backtrack.reachV3.float73())
            : MovementController.ColorAnimator(playerentity, 1).box9;
         // Угол считается от текущего глаза (vec3d), а не от предсказанного на 2 тика вперёд.
         // Раньше здесь стояло MovementController.TargetAcquireEvent(2).TriggerBot + рост глаза, то
         // есть начало вектора брали в будущей позиции, а поворот применяется к игроку в текущей —
         // это параллакс. Яв от него почти не страдает (он зависит только от x/z), а питч считается
         // как -atan2(y, hypot(x,z)) и целиком висит на вертикали, поэтому ошибка высоты глаза уходит
         // прямо в него. При падении предсказанная позиция ниже, dy до цели больше, и питч уводит
         // вверх: полблока ошибки на трёх блоках дистанции — это уже около 11°. Криты требуют
         // падения, так что аура работала ровно в той фазе, где промах максимален. Ниже строка 383
         // возвращает угол без проверки всякий раз, когда boolean2 снят, то есть почти всегда.
         // Предсказание цели (box на +1 тик) остаётся — это правильный лид, ломало только своё.
         Rotation ililiiili1ll1li11 = RotationMath.Event08(this.on23(box, 0.5F).subtract(vec3d));
         if (this.ColorAnimator(ililiiili1ll1li11) || !this.boolean2) {
            return ililiiili1ll1li11;
         }
      }

      // Точка наводки тоже по дистанции наводки. На дистанции удара ни одна точка хитбокса
      // далёкой цели не проходит фильтр достижимости, и резолвер отдал бы запасной вариант —
      // центр бокса, то есть наводку не на выбранную точку.
      Vec3d vec3d1 = this.zClass046.on23(vec3d, this.var11812(), this.aimDistance(), new Vec3d(0.0, 0.0, 0.0), this.var1355());
      return RotationMath.Event08(vec3d1.subtract(vec3d));
   }

   public Vec3d on23(Box var1, float var2) {
      return new Vec3d(
         MathHelper.lerp(0.5, var1.minX, var1.maxX),
         MathHelper.lerp(var2, var1.minY, var1.maxY),
         MathHelper.lerp(0.5, var1.minZ, var1.maxZ)
      );
   }

   public Box var11812() {
      if (!(this.livingEntity instanceof PlayerEntity playerentity)) {
         return this.livingEntity.getBoundingBox();
      } else {
         return ElytraTarget.elytraTarget.isEnabled() && !ElytraTarget.elytraTarget.call085().isEnabled() && ElytraTarget.elytraTarget.call084() != null
            ? ElytraTarget.elytraTarget.call084()
            : this.livingEntity.getBoundingBox();
      }
   }

   public void on23(Rotation var1, boolean var2, boolean var3) {
      if (this.modeSetting3Var1596.isSelected()) {
         this.ConfigJsonUtil(var3);
      } else {
         val001.ArgbColor().botWorld3();
         if (this.aimHeld) {
            // Тот же угол переотправляется задачей, а не оставляется без задачи: пустая очередь
            // для менеджера — это не «стой на месте», а сигнал доводить ротацию к углу мыши и
            // отпускать её. Без переотправки «не искать новую точку» превратилось бы в «уехать
            // с цели». Стратегия при удержании не применяется — шаг здесь нулевой по построению.
            val002.on23(new RotationTask(var1, () -> var1, val001.RoundedRectBatch()), 1, this);
         } else if (this.modeSetting3Var1595.isSelected()) {
            val002.on23(new RotationTask(var1, () -> val001.on23(val001.HudPreviewRenderQueue(), var1), val001.HudPreviewRenderQueue()), 1, this);
         } else if (!this.modeSetting3Var1593.isSelected() && !this.modeSetting3Var1594.isSelected()) {
            if (var3 && this.modeSetting3Var1597.isSelected() || this.modeSetting3Var1598.isSelected()) {
               val002.on23(new RotationTask(var1, () -> val001.on23(val001.HudPreviewItem(), var1), val001.HudPreviewItem()), 3, this);
            }
         } else if (this.modeSetting3Var1593.isSelected() && this.snapOnAttack.isEnabled()) {
            // Разворот идёт скоростью библиотеки, а не мгновенным доворотом: как только окно удара
            // открылось, ставится обычная моторная стратегия и доигрывает жест до попадания на цель.
            // В ожидании нельзя передавать свежий var1 — он пересчитан по хитбоксу текущего тика,
            // и даже нулевая easing-функция продолжала бы вести цель, поэтому держим прежний угол.
            if (var3) {
               this.snapRunning = true;
            } else if (this.snapRunning && this.var135()) {
               this.snapRunning = false;
            }

            if (this.snapRunning) {
               val002.on23(new RotationTask(var1, () -> val001.on23(val001.RoundedRectBatch(), var1), val001.RoundedRectBatch()), 1, this);
            } else {
               Rotation rotation = val002.LineShader();
               val002.on23(new RotationTask(rotation, () -> rotation, val001.HudPreviewItem()), 1, this);
            }
         } else {
            val002.on23(new RotationTask(var1, () -> val001.on23(val001.RoundedRectBatch(), var1), val001.RoundedRectBatch()), 1, this);
         }
      }
   }

   @EventTarget
   public void Easing(MovementInputEvent var1) {
      if (this.modeSetting3Var15917.isSelected() && this.livingEntity != null) {
         MovementController il11i11i111i1i1l1il = MovementController.TargetAcquireEvent(1);
         Box box = Backtrack.reachV3.isEnabled() && Backtrack.reachV3.float272() != null
            ? this.livingEntity.dimensions.getBoxAt(Backtrack.reachV3.float272())
            : (
               this.livingEntity instanceof PlayerEntity
                  ? MovementController.ColorAnimator((PlayerEntity)this.livingEntity, 2).box9
                  : this.livingEntity.getBoundingBox()
            );
         Rotation ililiiili1ll1li11 = RotationMath.Event08(box.getCenter().subtract(il11i11i111i1i1l1il.TriggerBot));
         MovementUtils.on23(var1, val002.LineShader().GrimGlide(), ililiiili1ll1li11.GrimGlide());
      } else if (this.modeSetting3Var15918.isSelected() || this.livingEntity == null) {
         MovementUtils.on23(var1, val002.LineShader().GrimGlide(), minecraftClient3.player.getYaw());
      }

      if (this.livingEntity != null) {
         this.ColorAnimator(var1);
      }
   }

   @EventTarget(0)
   public void on23(PreventActionEvent var1) {
      if (this.boolean3) {
         var1.cancel();
      }
   }

   public boolean zClass110() {
      return this.CommandManager(1);
   }

   public boolean CommandManager(int var1) {
      MovementController il11i11i111i1i1l1il = MovementController.TargetAcquireEvent(var1);
      if (EffectEngine.NbtEditor(this.livingEntity)) {
         return false;
      } else if (minecraftClient3.player.isUsingItem()
         && (!this.modeSettingVar1592.isEnabled() || !(minecraftClient3.player.getActiveItem().getItem() instanceof ShieldItem))
         && !this.modeSettingVar1593.isEnabled()) {
         return false;
      } else {
         return !this.onlyCrit.isEnabled()
               || PlayerStateService.on23(il11i11i111i1i1l1il)
               || PlayerStateService.UiAnimation(il11i11i111i1i1l1il)
               || this.smartCrit.isEnabled() && !minecraftClient3.options.jumpKey.isPressed()
            ? this.on23(1 + var1, !this.zClass013())
            : false;
      }
   }

   public boolean zClass013() {
      if (!minecraftClient3.player.isGliding() && !PlayerStateService.ScreenProjection()) {
         return !this.onlyCrit.isEnabled() ? false : !this.smartCrit.isEnabled() || minecraftClient3.options.jumpKey.isPressed();
      } else {
         return false;
      }
   }

   public boolean var162() {
      if (this.val215 > 0) {
         this.val215--;
         return false;
      } else if (EffectEngine.NbtEditor(this.livingEntity)) {
         return false;
      } else if (minecraftClient3.player.isUsingItem() && !this.modeSettingVar1593.isEnabled()) {
         return false;
      } else {
         return !this.zClass013()
               || PlayerStateService.RandomUtils()
                  && (!minecraftClient3.player.lastSprinting || this.modeSetting3Var15916.isSelected() || this.modeSetting3Var15912.isSelected())
            ? this.on23(0.0F, !this.zClass013() || this.booleanField)
            : false;
      }
   }

   public boolean on23(float var1, boolean var2) {
      if (this.cooldownMode.is(1)) {
         return !PlayerStateService.ScreenProjection() && !minecraftClient3.player.isOnGround()
            ? minecraftClient3.player.getAttackCooldownProgress(0.5F) > 0.9
            : minecraftClient3.player.getAttackCooldownProgress(0.5F) >= 1.0F;
      }

      if (!this.modeSetting3Var15920.isSelected()) {
         var2 = false;
      }

      float f = this.modeSetting3Var15922.isSelected() ? this.timeCooldown.getCurrent() : 1.0F;
      float f1 = this.modeSetting3Var15922.isSelected() ? this.attackCooldown.getCurrent() : 1.0F;
      return var2 && !minecraftClient3.player.isSubmergedInWater()
         ? minecraftClient3.player.getAttackCooldownProgress(0.0F + var1) == 1.0F
         : minecraftClient3.player.getAttackCooldownProgress(f + var1) >= f1;
   }

   @Override
   public void onEnable() {
      // Библиотека жестов перечитывается на включении, а не только при старте клиента: запись
      // комбат-рекордера ложится в run/intent_library_rt.json, и без этого она подхватывалась бы
      // лишь следующим запуском. Проверка идёт по отпечатку файла, поэтому обычное включение
      // ничего не разбирает — цена платится один раз после записи.
      //
      // Без привязки к режиму: моторную модель берут не только HolyWorld, но и FunTime, а
      // проверка отпечатка на неизменном файле — это stat, её незачем экономить режимом.
      val001.ColorUtils().reloadIfChanged();
      super.onEnable();
   }

   @Override
   public void onDisable() {
      // Последний угол наводки: пока ротация активна, он лежит в менеджере,
      // иначе берётся угол последнего отправленного пакета.
      Rotation ililiiili1ll1li11 = val002.ZClass092();
      if (ililiiili1ll1li11 == null) {
         ililiiili1ll1li11 = val002.list50();
      }

      val002.releaseAt(ililiiili1ll1li11);
      this.livingEntity = null;
      // Иначе следующее включение стартовало бы с недоигранной защёлкой прошлого боя.
      this.snapRunning = false;
      // Смещение интента снимается вместе с аурой: моторная модель шарится — тот же экземпляр
      // читают AppleFarm и CropFarmer через RectBatch, — и оставленное значение молча правило бы
      // их ротации до перезапуска клиента.
      MotorIntentModel.trackingOdds = 1.0F;
      super.onDisable();
   }

   public void ColorAnimator(MovementInputEvent var1) {
      if (this.module4()) {
         boolean flag = minecraftClient3.options.sprintKey.isPressed();
         boolean flag1 = minecraftClient3.options.forwardKey.isPressed();
         if (this.modeSetting3Var15915.isSelected()) {
            if (!minecraftClient3.player.isOnGround() && (!(minecraftClient3.player.fallDistance > 0.0F) || this.on23(0.0F, false))) {
               flag = false;
               if (minecraftClient3.player.isSprinting()) {
                  flag1 = false;
               }
            }
         } else if (!this.boolean3 && !minecraftClient3.player.isOnGround() && this.zClass110()) {
            if (this.modeSetting3Var15914.isSelected()) {
               flag = false;
               if (minecraftClient3.player.isSprinting()) {
                  flag1 = false;
               }
            }

            if (this.modeSetting3Var15913.isSelected()) {
               if (minecraftClient3.player.isSprinting()) {
                  minecraftClient3.player.setSprinting(false);
               }

               flag = false;
            }
         }

         if (!flag) {
            minecraftClient3.options.sprintKey.setPressed(false);
            var1.TextScanner(false);
         }

         if (!flag1) {
            var1.ItemSpec(false);
         }
      }
   }

   @EventTarget
   public void on23(SprintStateEvent var1) {
      if (this.livingEntity != null
         && !this.boolean3
         && !minecraftClient3.player.isOnGround()
         && this.zClass110()
         && this.modeSetting3Var15913.isSelected()) {
         var1.PotionItemBuilder(false);
      }
   }

   public boolean module4() {
      return !this.modeSetting3Var15916.isSelected() && !PlayerStateService.ScreenProjection();
   }

   public LivingEntity zClass022() {
      return TargetSelector.on23(
         minecraftClient3.world.getEntities(),
         // Выбор по дистанции наводки, а не удара: цель должна попасть в ауру раньше,
         // чем станет достижимой, иначе предварительная наводка бессмысленна.
         this.aimDistance(),
         this.var1355(),
         ((MultiSelectSetting)this.targetSettingWindow2.getSettings().get(0)).zClass100Var143Var143(),
         ((MultiSelectSetting)this.targetSettingWindow2.getSettings().get(1)).zClass100Var143Var143(),
         ((BooleanSetting)this.targetSettingWindow2.getSettings().get(2)).isEnabled()
      );
   }

   public LivingEntity zClass054() {
      return this.isEnabled() ? this.livingEntity : null;
   }

   public boolean on23(BlockHitResult var1) {
      if (var1 != null && this.wallbypass.isEnabled()) {
         BlockState blockstate = minecraftClient3.world.getBlockState(var1.getBlockPos());
         return !(blockstate.getBlock() instanceof StairsBlock)
            && blockstate.getBlock() != Blocks.KELP
            && blockstate.getBlock() != Blocks.KELP_PLANT
            && blockstate.getBlock() != Blocks.TALL_SEAGRASS
            && blockstate.getBlock() != Blocks.TALL_GRASS
            && !(blockstate.getBlock() instanceof GrassBlock)
            && !(blockstate.getBlock() instanceof TrapdoorBlock)
            && blockstate.getBlock() != Blocks.COBWEB
            && !(blockstate.getBlock() instanceof DoorBlock);
      } else {
         return true;
      }
   }

   public LivingEntity var11813() {
      return this.livingEntity2;
   }

   public boolean int391() {
      return this.booleanField;
   }

   public boolean zClass007Var159() {
      return this.boolean2;
   }


   public static class Service {
      public double x;
      public double y;
      public double z;
      public int ticks;

      public Service(double var1, double var3, double var5) {
         this.x = var1;
         this.y = var3;
         this.z = var5;
      }

      public boolean var11810() {
         return this.ticks++ > 2;
      }

      public double getX() {
         return this.x;
      }

      public double getY() {
         return this.y;
      }

      public double getZ() {
         return this.z;
      }
   }
}
