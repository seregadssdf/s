package org.zenith.module.misc;

import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.module.ModuleManager;
import org.zenith.module.combat.*;
import org.zenith.module.movement.*;
import org.zenith.module.player.*;
import org.zenith.module.render.*;
import org.zenith.module.misc.*;


import net.minecraft.item.Item;
import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import org.zenith.ZenithClient;
import org.zenith.core.BooleanValue;
import org.zenith.core.EffectEngine;
import org.zenith.core.MovementController;
import org.zenith.event.EventHookWorldRender;
import org.zenith.event.EventTick;
import org.zenith.event.EventTriggerKeyEvent;
import org.zenith.event.PacketSendEvent;
import org.zenith.event.RefreshCacheEvent;
import org.zenith.rotation.Rotation;
import org.zenith.rotation.RotationMath;
import org.zenith.rotation.RotationTask;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.setting.Setting;
import org.zenith.setting.KeySetting;
import org.zenith.util.CooldownTimer;
import org.zenith.util.RaycastUtils;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;

@ModuleInfo(name = "ClickAction", description = "Делает что то по бинду", category = Category.MISC)
public final class ClickAction extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public final KeySetting friendBind = new KeySetting("module.clickAction.friendBind", "module.clickAction.friendBind.desc");
   public final KeySetting expBind = new KeySetting("module.clickAction.expBind", "module.clickAction.expBind.desc");
   public final List<ClickAction.ItemAction> list12 = new ArrayList<>();
   public final BooleanSetting windPitchSnap = new BooleanSetting(
      "module.clickAction.windPitchSnap", "module.clickAction.windPitchSnap.desc", false
   );
   public final NumberSetting windSweepSpeed = new NumberSetting(
      "module.clickAction.windSweepSpeed",
      45.0F,
      10.0F,
      90.0F,
      1.0F,
      "module.clickAction.windSweepSpeed.desc",
      "°/tick",
      this.windPitchSnap::isEnabled,
      null
   );
   public final CooldownTimer zClass06721 = new CooldownTimer();
   public static final ClickAction clickAction = new ClickAction();
   public Slot slot2 = null;
   public boolean boolean44 = false;

   // --- снап питча под заряд ветра -----------------------------------------
   /** Питч, на который смотрим под юз заряда: строго под ноги. */
   public static final float float400 = 90.0F;
   /**
    * Сколько тиков держать питч 90, ожидая пакет юза. Юз идёт через очередь
    * ScreenUtils (свап из инвентаря, закрытие экрана), это единицы тиков, но
    * без таймаута незашедший юз оставил бы камеру смотреть в пол.
    */
   public static final int int500 = 40;
   /**
    * Приоритет свипа в очереди ротаций. Выше того, с которым ставит задачи Aura
    * (1 и 3): пока идёт свип, питч обязан дойти до пола, иначе юз уйдёт с углом
    * выше порога буста и подброса не будет.
    */
   public static final int int501 = 10;
   /** Тиков осталось держать питч 90 в ожидании пакета юза. 0 — не держим. */
   public int windHoldTicks = 0;
   /** Питч свипа: идёт от угла на момент нажатия к полу, шагом за тик. */
   public float windSweepPitch = 0.0F;

   public ClickAction() {
      this.list12
         .add(
            new ClickAction.ItemAction(
               Items.ENDER_PEARL, new KeySetting("module.clickAction.enderPearl", "module.clickAction.enderPearl.desc"), new BooleanValue()
            )
         );
      this.list12
         .add(
            new ClickAction.ItemAction(
               Items.WIND_CHARGE, new KeySetting("module.clickAction.windCharge", "module.clickAction.windCharge.desc"), new BooleanValue()
            )
         );
   }

   @Override
   public List<Setting> getSettings() {
      List<Setting> arraylist = new ArrayList<>();
      arraylist.add(this.expBind);
      arraylist.add(this.friendBind);
      arraylist.addAll(this.list12.stream().map(ClickAction.ItemAction::double22).toList());
      arraylist.add(this.windPitchSnap);
      arraylist.add(this.windSweepSpeed);
      return arraylist;
   }

   @Override
   public void onDisable() {
      super.onDisable();
      // Камеру возвращать не нужно — она и не двигалась. Ротацию отпускает сам
      // менеджер, как только задачи в очереди закончились.
      this.windHoldTicks = 0;
   }

   @EventTarget
   public void on23(EventTriggerKeyEvent var1) {
      if (var1.ItemRegistry(this.friendBind.getKeyCode()) && minecraftClient3.player != null && minecraftClient3.world != null) {
         EntityHitResult entityhitresult = RaycastUtils.on23(
            new Rotation(minecraftClient3.player.getYaw(), minecraftClient3.player.getPitch()),
            var0 -> var0 instanceof PlayerEntity && var0 != minecraftClient3.player
         );
         if (entityhitresult != null && entityhitresult.getEntity() instanceof PlayerEntity playerentity) {
            String s = playerentity.getGameProfile().name();
            if (ZenithClient.on23().MediaTrackInfo().getItems().contains(s)) {
               ZenithClient.on23().MediaTrackInfo().ItemServiceBase(s);
            } else {
               ZenithClient.on23().MediaTrackInfo().add(s);
            }

            ZenithClient.on23().MediaTrackInfo().save();
         }
      }

      this.list12
         .stream()
         .filter(var1xx -> var1.ItemRegistry(var1xx.stringSetting22().getKeyCode()) && ScreenUtils.SimpleItemBuilder(var1xx.item5()) != null)
         .forEach(var0 -> {
            var0.var42().setValue(true);
            // Снап на нажатие, а не на отпускание: юз уходит по отпусканию, и к
            // тому моменту камера уже должна смотреть в пол — пакет юза берёт
            // питч у самого игрока.
            if (var0.item5() == Items.WIND_CHARGE) {
               this.startWindSnap();
            }
         });
      this.list12.stream().filter(var1xx -> var1.ItemSpec(var1xx.stringSetting22().getKeyCode())).forEach(var0 -> {
         ScreenUtils.ItemServiceBase(var0.item5());
         var0.var42().setValue(false);
      });
      if (var1.ItemRegistry(this.expBind.getKeyCode())) {
         Slot slot = ScreenUtils.SimpleItemBuilder(Items.EXPERIENCE_BOTTLE);
         if (slot == null) {
            ZenithClient.on23()
               .ConfigJsonUtil()
               .on23(
                  "M",
                  Text.of(
                     Items.EXPERIENCE_BOTTLE
                        .getName()
                        .copy()
                        .setStyle(Style.EMPTY.withColor(val003.TextScanner().getCurrentStyle().getPrimaryColor().getColor().call001()))
                        .append(
                           Text.of("не найден")
                              .copy()
                              .setStyle(Style.EMPTY.withColor(val003.TextScanner().getCurrentStyle().getTextEnable().getColor().call001()))
                        )
                  )
               );
            return;
         }
      }
   }

   @EventTarget
   public void ColorAnimator(EventHookWorldRender var1) {
      Predictions.predictions
         .on23(var1.ClanUpgrade(), this.list12.stream().filter(var0 -> var0.var42().isValue()).map(var0 -> var0.item5().getDefaultStack()).toList());
   }

   @EventTarget(4)
   public void Easing(RefreshCacheEvent var1) {
      if (!var1.isCancelled() && minecraftClient3.player != null) {
         if (this.boolean44) {
            this.boolean44 = false;
         } else {
            boolean flag = minecraftClient3.player.getMainHandStack().getItem().equals(Items.EXPERIENCE_BOTTLE);
            Slot slot = ScreenUtils.SimpleItemBuilder(Items.EXPERIENCE_BOTTLE);
            if (EffectEngine.on23(this.expBind) && slot != null) {
               MovementController il11i11i111i1i1l1il = MovementController.TargetAcquireEvent(3);
               Rotation ililiiili1ll1li11 = new Rotation(
                  minecraftClient3.player.getYaw(), RotationMath.BotChatEvent(il11i11i111i1i1l1il.box9.getCenter()).GuiWalk()
               );
               val002.on23(new RotationTask(ililiiili1ll1li11, () -> val001.on23(val001.HudPreviewItem(), ililiiili1ll1li11), val001.HudPreviewItem()), 5, this);
               if (!flag) {
                  if (TaskScheduler.Easing(ClickAction.class)) {
                     TaskScheduler.on23(ClickAction.class, () -> {
                        if (this.slot2 == null) {
                           this.slot2 = slot;
                        }

                        ScreenUtils.on23(slot, Hand.MAIN_HAND, true);
                        this.boolean44 = true;
                     });
                  }
               } else if (this.zClass06721.EventModifyMouseRotationInput(70L) && val002.LineShader().EmoteManager(ililiiili1ll1li11).BotChatEvent(180.0F, 10.0F)) {
                  EffectEngine.useItem(Hand.MAIN_HAND);
                  var1.cancel();
                  this.zClass06721.reset();
               }
            } else if (this.slot2 != null) {
               TaskScheduler.on23(ClickAction.class, () -> {
                  if (!EffectEngine.on23(this.expBind)) {
                     ScreenUtils.on23(this.slot2, Hand.MAIN_HAND, true);
                     this.slot2 = null;
                  }
               });
            }
         }
      }
   }

   // --- свип питча под заряд ветра -----------------------------------------
   /**
    * Начать свип: питч поедет в пол серверной ротацией.
    *
    * <p>Камера не трогается вообще — угол живёт только в менеджере ротаций,
    * который подставляет его в пакеты (движение, юз) и снимает обратно сразу
    * после. Игрок движения прицела не видит, сервер видит обычную наводку, как
    * от киллауры.
    */
   public void startWindSnap() {
      if (this.windPitchSnap.isEnabled() && minecraftClient3.player != null) {
         // Стартовый питч — от серверного угла, если ротация уже идёт, иначе от
         // самого игрока. Иначе свип прыгнул бы с угла мыши, потеряв то, что уже
         // навела Aura.
         if (this.windHoldTicks <= 0) {
            this.windSweepPitch = val002.LineShader().GuiWalk();
         }

         this.windHoldTicks = int500;
         this.tickWindSweep();
      }
   }

   /**
    * Шаг свипа за тик и постановка задачи в очередь.
    *
    * <p>Задача живёт один тик, поэтому ставится заново каждый тик, пока держим
    * питч — так же, как это делает Aura.
    */
   public void tickWindSweep() {
      float f = Math.max(1.0F, this.windSweepSpeed.getCurrent());
      // Шаг обрезается по остатку до пола, иначе свип пронесло бы за 90 и
      // clamp внутри Rotation дёрнул бы питч назад.
      this.windSweepPitch = Math.min(float400, this.windSweepPitch + f);
      // Яв берётся из текущей серверной ротации: свип ведёт только питч, а по
      // яву не отбирает наводку у Aura на те тики, что перебивает её приоритетом.
      Rotation ililiiili1ll1li11 = new Rotation(val002.LineShader().GrimGlide(), this.windSweepPitch);
      val002.on23(new RotationTask(ililiiili1ll1li11, () -> val001.on23(val001.HudPreviewItem(), ililiiili1ll1li11), val001.HudPreviewItem()), int501, this);
   }

   /**
    * Пакет юза ушёл — держать питч больше незачем, дальше пружина.
    *
    * <p>Момент берётся из пакета, а не из отпускания бинда: юз идёт через
    * очередь ScreenUtils (свап, закрытие экрана), и между отпусканием и
    * пакетом проходят тики, в которые питч обязан оставаться в полу.
    */
   @EventTarget
   public void on23(PacketSendEvent var1) {
      if (this.windHoldTicks > 0 && !var1.isCancelled() && var1.ItemScroller() instanceof PlayerInteractItemC2SPacket) {
         // Держать больше нечего: задачи в очередь не ставятся, и менеджер сам
         // сводит серверный угол обратно к прицелу.
         this.windHoldTicks = 0;
      }
   }

   /**
    * Питч на тике: то, что увидит пакет юза и движения. Отдельно от кадрового
    * шага, потому что EventTick — единственная точка, где значение гарантированно
    * стоит на момент отправки пакетов.
    */
   @EventTarget(1)
   public void on23(EventTick var1) {
      if (minecraftClient3.player == null) {
         this.windHoldTicks = 0;
      } else if (this.windHoldTicks > 0) {
         this.tickWindSweep();
         // Таймаут: юз не дошёл (заряда нет, свап не сложился) — свип прекратить,
         // иначе питч остался бы в полу до конца сессии.
         --this.windHoldTicks;
      }
   }

   public List<ClickAction.ItemAction> double18() {
      return Collections.unmodifiableList(this.list12);
   }

   public KeySetting double19() {
      return this.expBind;
   }


   public record ItemAction(Item item5, KeySetting stringSetting22, BooleanValue var42) {
      public Item double21() {
         return this.item5;
      }

      public KeySetting double22() {
         return this.stringSetting22;
      }

      public BooleanValue double23() {
         return this.var42;
      }
   }
}
