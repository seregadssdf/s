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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.Vec3d;
import org.zenith.core.PlayerStateService;
import org.zenith.event.EventInjectAddEntity;
import org.zenith.event.EventTick;
import org.zenith.event.PacketSendEvent;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;

/**
 * Булава в руку на быстром падении — порт AutoMaceModule из Expensive.
 *
 * <p>Условие: включена Aura и вертикальная скорость вниз не меньше той, что игрок
 * набирает при свободном падении с заданной высоты. Порог задан в блоках, а не в
 * блоках/тик, потому что «с трёх блоков» — то, что видно глазом; в скорость он
 * переводится симуляцией ванильной гравитации.
 *
 * <p>Два пути к булаве. В хотбаре — просто выбор слота, без единого клика по
 * контейнеру. В инвентаре — SWAP через {@link ScreenUtils}, то есть через ту самую
 * воронку, которая читает {@link InventorySetting}: там сидят и стоп-мув, и режим
 * задержки перекладываний. Своего чекбокса на способ доставки здесь нет намеренно —
 * это ровно та роль, которую в Expensive играл ScreenWalk → Swap Method.
 *
 * <p>Возврат — при выходе из условия, но с задержкой: урон булавы считается в момент
 * приземления, а скорость к этому тику уже ноль. Свап назад сразу отобрал бы удар.
 *
 * <p>Отдельно от свапа — буст вверх после юза заряда ветра, см. {@link #windBoost}.
 * Он не про смэш с плоского места: прыжок сам по себе даёт максимум 1.1309 блока
 * падения в воздухе против порога 1.5. Он про связку — заряд ветра под ноги, подброс,
 * буст сверху, и уже оттуда падение, на котором свапается булава и добирается смэш.
 *
 * <p>От {@link MaceHelper} этот модуль отличается тем, что делает только свап: удара
 * и игл здесь нет. Включать оба разом смысла нет — свапать будут оба.
 */
@ModuleInfo(name = "Mace Helper 2", category = Category.COMBAT, description = "module.maceHelper2.desc")
public final class MaceHelper2 extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final MaceHelper2 maceHelper2 = new MaceHelper2();

   /**
    * Тиков ждать отложенный свап. С большим запасом: очередь разносит шаги на
    * единицы тиков, а 40 тиков — это две секунды, дольше любого падения, после
    * которого свап ещё имел бы смысл.
    */
   public static final int int423 = 40;
   /**
    * Минимальный питч пакета юза, при котором юз считается «в пол». Заряд ветра
    * подбрасывает только из-под ног, и бустить под юз, нацеленный в горизонт,
    * незачем: подброса от взрыва там нет, и объяснять серверу вертикальную скорость
    * станет нечем.
    */
   public static final float windMinDownPitch = 45.0F;

   // --- настройки ----------------------------------------------------------
   public final NumberSetting fallHeight = new NumberSetting(
      "module.maceHelper2.fallHeight", 3.0F, 1.0F, 20.0F, 0.5F, "module.maceHelper2.fallHeight.desc", "b"
   );
   public final NumberSetting holdTicks = new NumberSetting(
      "module.maceHelper2.holdTicks", 4.0F, 0.0F, 40.0F, 1.0F, "module.maceHelper2.holdTicks.desc", "t"
   );
   public final BooleanSetting requireTarget = new BooleanSetting(
      "module.maceHelper2.requireTarget", "module.maceHelper2.requireTarget.desc", true
   );
   public final BooleanSetting fromInventory = new BooleanSetting(
      "module.maceHelper2.fromInventory", "module.maceHelper2.fromInventory.desc", true
   );
   /**
    * Вертикальный буст ПОСЛЕ юза заряда ветра. Задаётся в единицах вертикальной
    * скорости: 0.42 — ванильный прыжок, 0 — буста нет вовсе (значение по умолчанию).
    *
    * <p>Буст именно после юза, а не прыжок до него, и это не косметика. Прыжок с земли
    * за тик до юза сервер объяснить не может: на клиенте вертикальная скорость взялась
    * из ниоткуда, движение разъезжается с предсказанием, и это флаг. После юза та же
    * скорость укладывается в разлёт от взрыва заряда — то есть в то, чего сервер и сам
    * ждёт.
    *
    * <p>Скорость ставится напрямую, а не через прыжок по вводу. Две причины. Ванильный
    * прыжок жёстко 0.42, настраиваемой высоты из него не выжать. И к моменту буста
    * игрок уже не на земле — прыжок по вводу здесь просто не сработал бы.
    */
   public final NumberSetting windBoost = new NumberSetting(
      "module.maceHelper2.windBoost", 0.0F, 0.0F, 0.42F, 0.01F, "module.maceHelper2.windBoost.desc", ""
   );
   /**
    * Порог заряда удара, с которого модуль бьёт сам, не дожидаясь Ауры. 0 — не бить
    * вовсе: тогда модуль остаётся чистым свапом, каким и был.
    *
    * <p>Ноль как «выключено», а не отдельный чекбокс: 0% означало бы «бить с нулевым
    * зарядом», то есть спамить каждый тик падения — настройки с таким смыслом всё равно
    * не бывает, и место под ней свободно. Так же сделан {@link #windBoost}.
    *
    * <p>Смысл в том, что Аура бьёт по своим условиям — криты, кулдаун, режим спринта, —
    * и в падении с булавой она часто молчит ровно там, где смэш и нужен. Модуль бьёт
    * только когда Аура промолчала: её удар этого тика уже виден по флагу, дублировать
    * его нечего.
    *
    * <p>Порог именно по заряду, а не по высоте: урон булавы считает сервер от пройденного
    * падения, а вот пройдёт ли удар вообще — решает кулдаун атаки. Заряд и есть та
    * величина, которой здесь можно управлять.
    */
   public final NumberSetting strikeCharge = new NumberSetting(
      "module.maceHelper2.strikeCharge", 0.0F, 0.0F, 100.0F, 5.0F, "module.maceHelper2.strikeCharge.desc", "%"
   );

   // --- состояние: свап ----------------------------------------------------
   /** Слот, который был в руке до свапа. -1 — сессии нет. */
   public int savedHotbarSlot = -1;
   /** Слот инвентаря, откуда вытянута булава. -1 — булава была в хотбаре. */
   public int pulledInventorySlot = -1;
   /** Слот хотбара, в который булава легла из инвентаря. */
   public int pulledHotbarSlot = -1;
   /** Тиков осталось держать булаву после выхода из условия. */
   public int holdRemaining = 0;
   /**
    * Тиков ждать свап, поставленный в очередь задач: пока счётчик не ноль, второй
    * свап не ставится. Именно счётчик, а не флаг: шаг очереди ждёт остановки
    * движения, а в падении игрок обычно жмёт вперёд, и один не доехавший свап
    * навсегда заблокировал бы все следующие.
    */
   public int swapPending = 0;

   // --- состояние: буст ----------------------------------------------------
   /** Юз заряда ушёл в этом тике — буст применяется в следующем. */
   public boolean windBoostPending = false;

   // --- состояние: удар ----------------------------------------------------
   /** В этом падении булава уже ударила. Снимается на земле. */
   public boolean struckThisFall = false;

   @Override
   public void onDisable() {
      // Сначала вернуть булаву, и только потом стирать сессию: после resetSwap()
      // возвращать было бы уже нечем — слоты забыты.
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (this.hasSession() && clientplayerentity != null) {
         this.endSession(clientplayerentity);
      }

      super.onDisable();
      this.resetSwap();
      this.windBoostPending = false;
      this.struckThisFall = false;
   }

   @EventTarget
   public void on23(EventInjectAddEntity var1) {
      if (var1.ElytraFly() instanceof ClientPlayerEntity) {
         this.resetSwap();
         this.windBoostPending = false;
         this.struckThisFall = false;
      }
   }

   /**
    * Момент юза заряда ветра берётся из исходящего пакета, а не из расписания.
    * Пакет — это и есть факт юза: он уходит уже ПОСЛЕ того, как доводка сошлась,
    * поэтому «голова повернулась вниз» проверять отдельно нечем и незачем.
    *
    * <p>Взвода из чужого кода, как в Expensive, здесь нет: там его ставил
    * InventoryService, единственная воронка автоюзов, и служил он ровно тому, чтобы
    * буст не улетел под посторонний правый клик. В Zenith такой воронки нет, а
    * заряд ветра юзается и по бинду ClickAction, и руками. Вместо взвода решают сами
    * условия пакета: заряд в главной руке и питч в пол. Под ними подброс от взрыва
    * уже случился, так что вертикальная скорость объясняется им и в ручном случае
    * тоже — а буст под юз в горизонт отсекается порогом питча.
    */
   @EventTarget
   public void on23(PacketSendEvent var1) {
      if (!this.windBoostPending
         && !var1.isCancelled()
         && this.windBoost.getCurrent() > 0.0F
         && var1.ItemScroller() instanceof PlayerInteractItemC2SPacket playerinteractitemc2spacket) {
         ClientPlayerEntity clientplayerentity = minecraftClient3.player;
         if (clientplayerentity != null && clientplayerentity.getMainHandStack().isOf(Items.WIND_CHARGE)) {
            // Питч берётся из пакета, а не у игрока: расходятся они на остаток доводки,
            // и решает здесь именно тот угол, с которым юз ушёл на сервер.
            if (playerinteractitemc2spacket.getPitch() >= windMinDownPitch) {
               this.windBoostPending = true;
            }
         }
      }
   }

   /**
    * Буст в тик после юза заряда ветра.
    *
    * <p>Скорость не складывается с текущей, а берётся по максимуму. Разлёт от взрыва
    * к этому тику мог уже прийти (или не прийти — он едет с сервера, то есть с пингом).
    * Сложение в первом случае дало бы двойной подброс и скорость, которой взрыв не
    * объясняется; max даёт ровно запрошенную высоту в худшем случае и не отбирает уже
    * полученную в лучшем.
    *
    * <p>EventTick висит на HEAD ClientPlayerEntity.tick(), поэтому поставленная здесь
    * скорость уезжает в движение этого же тика, а не следующего.
    */
   public void tickWindBoost(ClientPlayerEntity var1) {
      if (this.windBoostPending) {
         this.windBoostPending = false;
         float f = this.windBoost.getCurrent();
         if (f > 0.0F) {
            Vec3d vec3d = var1.getVelocity();
            if (vec3d.y < f) {
               var1.setVelocity(vec3d.x, f, vec3d.z);
            }
         }
      }
   }

   /**
    * Приоритет 1 (HIGH): Aura бьёт на приоритете по умолчанию (2), поэтому булава
    * оказывается в руке раньше, чем она посмотрит на предмет. Выбор слота доезжает
    * до сервера в том же тике: удар начинается с синхронизации слота.
    */
   @EventTarget(1)
   public void on23(EventTick var1) {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (clientplayerentity == null || minecraftClient3.world == null) {
         // Мира нет — вернуть слот некуда и нечем, состояние только сбросить.
         this.resetSwap();
         this.windBoostPending = false;
         this.struckThisFall = false;
         return;
      }

      this.tickWindBoost(clientplayerentity);
      if (this.swapPending > 0) {
         this.swapPending--;
      }

      if (this.shouldHoldMace(clientplayerentity)) {
         this.holdRemaining = (int)this.holdTicks.getCurrent();
         if (!this.hasSession()) {
            this.beginSession(clientplayerentity);
         }
      } else if (this.hasSession()) {
         // Удержание после приземления: скорость уже ноль, а удар булавы ещё не
         // посчитан.
         if (this.holdRemaining > 0) {
            this.holdRemaining--;
         } else {
            this.endSession(clientplayerentity);
         }
      }
   }

   /**
    * Удар по порогу заряда, отдельным слушателем на приоритете 3 (LOW).
    *
    * <p>Аура бьёт на приоритете по умолчанию (2), поэтому к этому моменту её удар этого
    * тика уже случился и виден по флагу — свой удар делаем только тогда, когда она
    * промолчала. Свап при этом стоит на приоритете 1, то есть булава к обеим попыткам
    * уже в руке.
    */
   @EventTarget(3)
   public void ColorAnimator(EventTick var1) {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (clientplayerentity == null || minecraftClient3.world == null) {
         return;
      }

      if (clientplayerentity.isOnGround()) {
         this.struckThisFall = false;
      } else {
         this.tryStrike(clientplayerentity);
      }
   }

   /**
    * Ударить, если заряд дошёл до порога.
    *
    * <p>Один удар на падение. Порог может стоять низко, а заряд после удара сразу
    * начинает расти обратно — без этого флага модуль спамил бы ударами весь полёт,
    * каждым сбрасывая заряд следующего.
    */
   public void tryStrike(ClientPlayerEntity var1) {
      float f = this.strikeCharge.getCurrent();
      if (f <= 0.0F || this.struckThisFall || !Aura.aura.isEnabled()) {
         return;
      }

      // Аура уже ударила в этом тике — её удар и есть смэш этого падения.
      if (Aura.aura.boolean3) {
         this.struckThisFall = true;
         return;
      }

      // Падение, а не подъём: булава считает урон от пройденного падения, и на взлёте
      // бить незачем.
      if (var1.getVelocity().y >= 0.0 || var1.isUsingItem() || !this.holdingMace()) {
         return;
      }

      if (var1.getAttackCooldownProgress(0.0F) < f / 100.0F) {
         return;
      }

      // Цель берём у Ауры: она уже отобрана её фильтрами и сортировкой, и наводка на
      // неё уже сделана её режимом.
      net.minecraft.entity.LivingEntity livingentity = Aura.aura.zClass054();
      if (livingentity == null || var1.distanceTo(livingentity) > Aura.aura.var1356() + 0.5) {
         return;
      }

      // Спрашиваем саму Ауру, попадает ли её ТЕКУЩАЯ ротация по хитбоксу: иначе сервер
      // удар не зачтёт, и смэш сгорит впустую вместе с падением. Ротацию не трогаем —
      // наводка уже сделана Аурой.
      if (!Aura.aura.var135()) {
         return;
      }

      PlayerStateService.Easing(livingentity);
      this.struckThisFall = true;
   }

   /** В руке сейчас булава? */
   public boolean holdingMace() {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      return clientplayerentity != null && clientplayerentity.getMainHandStack().getItem() instanceof MaceItem;
   }

   /** Условие свапа: Aura работает и падение достаточно быстрое. */
   public boolean shouldHoldMace(ClientPlayerEntity var1) {
      if (!Aura.aura.isEnabled()) {
         return false;
      } else {
         return this.requireTarget.isEnabled() && Aura.aura.zClass054() == null ? false : this.fallingFastEnough(var1);
      }
   }

   public boolean fallingFastEnough(ClientPlayerEntity var1) {
      return var1.isOnGround() ? false : -var1.getVelocity().y >= fallSpeedFromHeight(this.fallHeight.getCurrent());
   }

   public boolean hasSession() {
      return this.savedHotbarSlot != -1;
   }

   /**
    * Забрать булаву. Хотбар предпочтительнее инвентаря: выбор слота не создаёт ни
    * одного клика по контейнеру, и терять там нечего.
    *
    * <p>Из инвентаря — воронкой {@link ScreenUtils}, первым аргументом слот хотбара:
    * так она уходит в быстрый путь (ванильный SWAP по номеру слота), а не в разбор
    * через три клика. Свап ставится в очередь задач, а очередь читает
    * {@link InventorySetting} — стоп-мув оттуда и решает, дожидаться ли остановки.
    *
    * <p>Поля сессии выставляются ВНУТРИ задачи: шаг отложенный, и если он не доедет,
    * снаружи не должно остаться записи о свапе, которого не было.
    */
   public void beginSession(ClientPlayerEntity var1) {
      // Свап уже поставлен в очередь и ещё не отработал: hasSession() пока false, и
      // без этой проверки каждый тик добавлял бы ещё один свап.
      if (this.swapPending <= 0) {
         int i = this.maceInHotbar(var1);
         if (i != -1) {
            if (i != var1.getInventory().getSelectedSlot()) {
               this.savedHotbarSlot = var1.getInventory().getSelectedSlot();
               var1.getInventory().setSelectedSlot(i);
            }
         } else if (this.fromInventory.isEnabled()) {
            // Слоты чужого контейнера нумеруются иначе, и хотбарный SWAP там не
            // работает: пока открыт сундук, свапа не будет.
            if (var1.currentScreenHandler instanceof PlayerScreenHandler) {
               Slot slot = this.maceInInventory();
               if (slot != null && TaskScheduler.Easing(MaceHelper2.class)) {
                  int j = var1.getInventory().getSelectedSlot();
                  this.swapPending = int423;
                  TaskScheduler.on23(MaceHelper2.class, () -> {
                     ScreenUtils.on23(36 + j, slot.id, false, false);
                     this.savedHotbarSlot = j;
                     this.pulledInventorySlot = slot.id;
                     this.pulledHotbarSlot = j;
                     this.swapPending = 0;
                  });
               }
            }
         }
      }
   }

   /**
    * Отдать булаву назад. Тем же SWAP с теми же слотами: булава уходит в инвентарь,
    * а предмет, который там лежал, возвращается в хотбар.
    */
   public void endSession(ClientPlayerEntity var1) {
      int i = this.pulledInventorySlot;
      int j = this.pulledHotbarSlot;
      int k = this.savedHotbarSlot;
      boolean flag = var1.getMainHandStack().getItem() instanceof MaceItem;
      this.resetSwap();
      if (i == -1) {
         // Булава жила в хотбаре — только вернуть прежний слот. Но если игрок сам
         // сменил слот, булавы в руке уже нет: возврат отобрал бы его выбор.
         if (flag && k >= 0 && k < 9) {
            var1.getInventory().setSelectedSlot(k);
         }
      } else if (j >= 0
         && j < 9
         && var1.getInventory().getStack(j).getItem() instanceof MaceItem
         && var1.currentScreenHandler instanceof PlayerScreenHandler
         && TaskScheduler.Easing(MaceHelper2.class)) {
         // Обратный свап только если булава действительно там, где мы её оставили.
         // Иначе (свап не дошёл, игрок переложил предмет сам) мы бы отправили в
         // инвентарь чужой предмет, а оттуда достали неизвестно что.
         TaskScheduler.on23(MaceHelper2.class, () -> ScreenUtils.on23(36 + j, i, false, false));
      }
   }

   /** Слот хотбара с булавой или -1. */
   public int maceInHotbar(ClientPlayerEntity var1) {
      for (int i = 0; i < 9; i++) {
         if (var1.getInventory().getStack(i).getItem() instanceof MaceItem) {
            return i;
         }
      }

      return -1;
   }

   /** Слот основного инвентаря с булавой или null. */
   public Slot maceInInventory() {
      return ScreenUtils.ColorAnimator(
         var0 -> var0.inventory instanceof PlayerInventory
            && var0.getIndex() >= 9
            && var0.getIndex() < 36
            && var0.getStack().getItem() instanceof MaceItem
      );
   }

   public void resetSwap() {
      this.savedHotbarSlot = -1;
      this.pulledInventorySlot = -1;
      this.pulledHotbarSlot = -1;
      this.holdRemaining = 0;
      this.swapPending = 0;
   }

   /**
    * Скорость (блоков/тик) в конце свободного падения с заданной высоты по ванильной
    * гравитации: v ← (v - 0.08) · 0.98 за тик. Замкнутой формулы нет из-за
    * сопротивления, поэтому симуляция. На 3 блоках выходит ~0.65 бл/тик.
    */
   public static double fallSpeedFromHeight(double var0) {
      double d0 = 0.0;
      double d1 = 0.0;
      // Страховка от зацикливания: скорость упирается в терминальную ~3.92 бл/тик,
      // так что любая разумная высота набирается за считанные десятки тиков.
      for (int i = 0; i < 200 && d1 < var0; i++) {
         d0 = (d0 - 0.08) * 0.98;
         d1 -= d0;
      }

      return -d0;
   }
}

