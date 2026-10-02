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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.MaceItem;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.zenith.core.PlayerStateService;
import org.zenith.event.AttackEntityEvent;
import org.zenith.event.EventDead;
import org.zenith.event.EventHookWorldRender;
import org.zenith.event.EventInjectAddEntity;
import org.zenith.event.EventTick;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.setting.Setting;
import org.zenith.setting.SettingGroup;

/**
 * Помощник для боя булавой: удар в падении и игольчатый салют из цели.
 *
 * <p>Порт скрипта mace_helper.py (Rockstar) на нативный код клиента. Две части
 * в одном модуле:
 *
 * <p>1. Удар. Когда включён, Aura может бить булавой в падении, не дожидаясь
 * полной зарядки удара. Помощник не трогает ротацию: наводка работает тем
 * режимом, который сейчас выбран в Aura. Работает только при включённой Ауре и
 * только когда в руке булава. Тайминг удара: 0% — бить сразу при достижении
 * минимальной высоты, 100% — максимально поздно, на последнем тике перед
 * приземлением, чтобы заряд и урон булавы были максимальными. Удар всегда
 * происходит в воздухе и никогда после приземления. Задержка применяется
 * только когда высота падения не меньше «Высоты для задержки»: на малых
 * высотах удар идёт сразу, чтобы быстрее сработал Порыв ветра и цепочка
 * смэшей не прерывалась.
 *
 * <p>2. Иглы. Из головы цели вылетает плотная ровная сфера длинных светящихся
 * игл: они резко тормозят, стукаются об пол, отскакивают пару раз и догорают.
 * Иглы — вытянутые квады вдоль своей скорости и лицом к камере, с аддитивным
 * блендингом; толщина одинаковая по всей длине, хвост сходит на нет
 * прозрачностью. Вся вспышка уходит на видеокарту одним draw call. Вспышку
 * можно привязать к любому удару булавой или только к смэшу помощника в
 * падении. Иглы чисто визуальны: на сервер ничего не отправляется.
 */
@ModuleInfo(name = "Mace Helper", category = Category.COMBAT, description = "module.maceHelper.desc")
public final class MaceHelper extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final MaceHelper maceHelper = new MaceHelper();

   /** Потолок против просадок при спаме киллов. */
   public static final int int420 = 1000;
   /** с; не повторять вспышку по той же цели. */
   public static final double double120 = 0.1;
   /**
    * Упругость и подарок жизни подобраны перебором по метрике «видно ли КАЖДОЕ
    * касание» — то есть какая у иглы яркость в кадр удара, а не просто был ли
    * удар. Пара 0.40 / 0.50 даёт два видимых касания (яркость 233 и 69),
    * подскок 0.26 бл. Выше упругость — первый подскок красивее, но игла
    * улетает и возвращается на пол уже погасшей; ниже — вспышка тянется
    * ДОЛЬШЕ, потому что игла вяло сползает вниз. Зависимость немонотонная, на
    * глаз не угадывается.
    */
   public static final double double121 = 0.4;
   /** Трение по касательной к поверхности. */
   public static final double double122 = 0.8;
   /**
    * Столько остатка жизни игле гарантируется на каждом отскоке: из-за
    * сопротивления назад она падает медленно (~3.5 бл/с) и иначе не доживает
    * до второго касания. Тратится только на тех, кто реально ударился обо
    * что-то.
    */
   public static final double double123 = 0.5;
   /**
    * Доля жизни, после которой игла уже не подскакивает: при t = 0.87 яркость
    * около 62 из 250, подскок ещё видно, дальше — нет смысла его считать.
    */
   public static final double double124 = 0.87;
   /** с; догорание на месте после последнего отскока. */
   public static final double double125 = 0.14;
   /** Прозрачность хвоста относительно головы. */
   public static final float float300 = 0.12F;
   /** Золотой угол спирали Фибоначчи. */
   public static final double double126 = Math.PI * (3.0 - Math.sqrt(5.0));
   /**
    * Мягкие края иглы: полосу поперёк режем на столько лент, у каждой своя
    * альфа по профилю 1 - across^2. Питоновая версия делала то же самое
    * фрагментным шейдером, здесь — геометрией, потому что своих шейдеров у
    * модулей клиента нет, а лент нужно мало: полоса ровной толщины читается
    * как светящаяся нить, а не как плашка, и лестницы по кромке не видно.
    */
   public static final int int421 = 4;
   /**
    * Тиков ждать отложенный свап. С большим запасом: очередь разносит шаги на
    * единицы тиков, а 40 тиков — это две секунды, дольше любого падения, после
    * которого свап ещё имел бы смысл.
    */
   public static final int int422 = 40;

   // --- настройки: удар ----------------------------------------------------
   public final NumberSetting minHeight = new NumberSetting(
      "module.maceHelper.minHeight", 1.5F, 0.5F, 12.0F, 0.5F, "module.maceHelper.minHeight.desc", "b"
   );
   public final NumberSetting minCharge = new NumberSetting(
      "module.maceHelper.minCharge", 0.0F, 0.0F, 100.0F, 5.0F, "module.maceHelper.minCharge.desc", "%"
   );
   public final BooleanSetting oncePerFall = new BooleanSetting("module.maceHelper.oncePerFall", "module.maceHelper.oncePerFall.desc", true);
   public final NumberSetting strikeTiming = new NumberSetting(
      "module.maceHelper.strikeTiming", 100.0F, 0.0F, 100.0F, 5.0F, "module.maceHelper.strikeTiming.desc", "%"
   );
   public final NumberSetting delayHeight = new NumberSetting(
      "module.maceHelper.delayHeight", 3.0F, 1.0F, 12.0F, 0.5F, "module.maceHelper.delayHeight.desc", "b"
   );
   public final BooleanSetting debug = new BooleanSetting("module.maceHelper.debug", "module.maceHelper.debug.desc", false);
   public final SettingGroup strikeGroup = new SettingGroup(
      "module.maceHelper.strike",
      "module.maceHelper.strike.desc",
      () -> true,
      this.minHeight,
      this.minCharge,
      this.oncePerFall,
      this.strikeTiming,
      this.delayHeight,
      this.debug
   );

   // --- настройки: иглы ----------------------------------------------------
   public final BooleanSetting needles = new BooleanSetting("module.maceHelper.needles", "module.maceHelper.needles.desc", true);
   public final ModeSetting needlesWhen = new ModeSetting(
      "module.maceHelper.when",
      "module.maceHelper.when.desc",
      this.needles::isEnabled,
      "module.maceHelper.whenHit",
      "module.maceHelper.whenKill",
      "module.maceHelper.whenBoth"
   );
   public final BooleanSetting smashOnly = new BooleanSetting(
      "module.maceHelper.smashOnly", "module.maceHelper.smashOnly.desc", false, this.needles::isEnabled
   );
   public final BooleanSetting maceOnly = new BooleanSetting(
      "module.maceHelper.maceOnly", "module.maceHelper.maceOnly.desc", true, this.needles::isEnabled
   );
   public final BooleanSetting pvpOnly = new BooleanSetting(
      "module.maceHelper.pvpOnly", "module.maceHelper.pvpOnly.desc", true, this.needles::isEnabled
   );
   public final BooleanSetting byMeOnly = new BooleanSetting(
      "module.maceHelper.byMeOnly", "module.maceHelper.byMeOnly.desc", false, this.needles::isEnabled
   );
   public final NumberSetting needleCount = new NumberSetting(
      "module.maceHelper.count", 120.0F, 40.0F, 200.0F, 10.0F, "module.maceHelper.count.desc", "x", this.needles::isEnabled, null
   );
   public final NumberSetting needleSpeed = new NumberSetting(
      "module.maceHelper.speed", 18.0F, 6.0F, 30.0F, 1.0F, "module.maceHelper.speed.desc", "b/s", this.needles::isEnabled, null
   );
   public final NumberSetting needleLife = new NumberSetting(
      "module.maceHelper.life", 0.45F, 0.15F, 1.2F, 0.05F, "module.maceHelper.life.desc", "s", this.needles::isEnabled, null
   );
   public final NumberSetting needleDrag = new NumberSetting(
      "module.maceHelper.drag", 78.0F, 60.0F, 96.0F, 1.0F, "module.maceHelper.drag.desc", "%", this.needles::isEnabled, null
   );
   public final NumberSetting needleLength = new NumberSetting(
      "module.maceHelper.length", 1.1F, 0.1F, 2.5F, 0.05F, "module.maceHelper.length.desc", "b", this.needles::isEnabled, null
   );
   public final NumberSetting needleWidth = new NumberSetting(
      "module.maceHelper.width", 0.045F, 0.01F, 0.14F, 0.005F, "module.maceHelper.width.desc", "b", this.needles::isEnabled, null
   );
   public final NumberSetting needleGravity = new NumberSetting(
      "module.maceHelper.gravity", 55.0F, 0.0F, 100.0F, 5.0F, "module.maceHelper.gravity.desc", "%", this.needles::isEnabled, null
   );
   public final NumberSetting maxBounce = new NumberSetting(
      "module.maceHelper.bounces", 2.0F, 0.0F, 3.0F, 1.0F, "module.maceHelper.bounces.desc", "x", this.needles::isEnabled, null
   );
   public final ModeSetting needleColor = new ModeSetting(
      "module.maceHelper.color",
      "module.maceHelper.color.desc",
      this.needles::isEnabled,
      "module.maceHelper.colorWhiteCyan",
      "module.maceHelper.colorWhite",
      "module.maceHelper.colorCyan"
   );
   public final BooleanSetting hideWalls = new BooleanSetting(
      "module.maceHelper.hideWalls", "module.maceHelper.hideWalls.desc", true, this.needles::isEnabled
   );
   public final SettingGroup needlesGroup = new SettingGroup(
      "module.maceHelper.needlesCat",
      "module.maceHelper.needlesCat.desc",
      () -> true,
      this.needles,
      this.needlesWhen,
      this.smashOnly,
      this.maceOnly,
      this.pvpOnly,
      this.byMeOnly,
      this.needleCount,
      this.needleSpeed,
      this.needleLife,
      this.needleDrag,
      this.needleLength,
      this.needleWidth,
      this.needleGravity,
      this.maxBounce,
      this.needleColor,
      this.hideWalls
   );

   // --- настройки: свап ----------------------------------------------------
   public final BooleanSetting autoSwap = new BooleanSetting("module.maceHelper.autoSwap", "module.maceHelper.autoSwap.desc", false);
   public final NumberSetting fallHeight = new NumberSetting(
      "module.maceHelper.fallHeight", 3.0F, 1.0F, 20.0F, 0.5F, "module.maceHelper.fallHeight.desc", "b", this.autoSwap::isEnabled, null
   );
   public final NumberSetting holdTicks = new NumberSetting(
      "module.maceHelper.holdTicks", 4.0F, 0.0F, 40.0F, 1.0F, "module.maceHelper.holdTicks.desc", "t", this.autoSwap::isEnabled, null
   );
   public final BooleanSetting requireTarget = new BooleanSetting(
      "module.maceHelper.requireTarget", "module.maceHelper.requireTarget.desc", true, this.autoSwap::isEnabled
   );
   public final BooleanSetting fromInventory = new BooleanSetting(
      "module.maceHelper.fromInventory", "module.maceHelper.fromInventory.desc", true, this.autoSwap::isEnabled
   );
   public final BooleanSetting invMoveSwap = new BooleanSetting(
      "module.maceHelper.invMoveSwap", "module.maceHelper.invMoveSwap.desc", false, () -> this.autoSwap.isEnabled() && this.fromInventory.isEnabled()
   );
   public final SettingGroup swapGroup = new SettingGroup(
      "module.maceHelper.swap",
      "module.maceHelper.swap.desc",
      () -> true,
      this.autoSwap,
      this.fallHeight,
      this.holdTicks,
      this.requireTarget,
      this.fromInventory,
      this.invMoveSwap
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
    * Тиков ждать свап, поставленный в очередь задач: пока счётчик не ноль,
    * второй свап не ставится. Именно счётчик, а не флаг: шаг очереди ждёт
    * остановки движения, а в падении игрок обычно жмёт вперёд, и один не
    * доехавший свап навсегда заблокировал бы все следующие.
    */
   public int swapPending = 0;

   // --- состояние: удар ----------------------------------------------------
   /** Высшая точка с момента отрыва от земли. */
   public Double peakY = null;
   /** В этом падении булава уже ударила. */
   public boolean struckThisFall = false;
   /** (Y земли, тиков до неё) с прошлой попытки — для отладки. */
   public Double scanGroundTop = null;
   public Integer scanTicksLeft = null;

   // --- состояние: иглы ----------------------------------------------------
   public final List<MaceHelper.Needle> list200 = new ArrayList<>();
   /** Время прошлого кадра, для честного dt. */
   public double lastFrame = -1.0;
   /** (bx, by, bz) -> твёрдый ли; кеш блоков на время вспышки. */
   public final Map<Long, Boolean> map70 = new HashMap<>();
   /** id цели -> время вспышки, чтобы не бить дважды. */
   public final Map<Integer, Double> map71 = new HashMap<>();
   /** id цели -> когда мы по ней били, для «только мои киллы». */
   public final Map<Integer, Long> map72 = new HashMap<>();
   /** Удар прямо сейчас делает сам помощник: иглы для «только смэш». */
   public boolean smashNow = false;

   @Override
   public List<Setting> getSettings() {
      return List.of(this.strikeGroup, this.needlesGroup, this.swapGroup);
   }

   @Override
   public void onEnable() {
      super.onEnable();
      this.call500();
   }

   @Override
   public void onDisable() {
      // Сначала вернуть булаву, и только потом стирать сессию: после call500()
      // возвращать было бы уже нечем — слоты забыты.
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (this.hasSession() && clientplayerentity != null) {
         this.endSession(clientplayerentity);
      }

      super.onDisable();
      this.call500();
   }

   /** Сброс всего состояния: смена мира, вкл/выкл. */
   public void call500() {
      this.resetSwap();
      this.peakY = null;
      this.struckThisFall = false;
      this.scanGroundTop = null;
      this.scanTicksLeft = null;
      this.list200.clear();
      this.map70.clear();
      this.map71.clear();
      this.map72.clear();
      this.lastFrame = -1.0;
      this.smashNow = false;
   }

   @EventTarget
   public void on23(EventInjectAddEntity var1) {
      if (var1.ElytraFly() instanceof ClientPlayerEntity) {
         this.call500();
      }
   }

   /** В руке сейчас булава? */
   public boolean holdingMace() {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      return clientplayerentity != null && clientplayerentity.getMainHandStack().getItem() instanceof MaceItem;
   }

   public static double now() {
      return System.nanoTime() / 1.0E9;
   }

   // --- свап булавы --------------------------------------------------------
   /**
    * Булава в руку на быстром падении.
    *
    * <p>Условие: включена Aura и вертикальная скорость вниз не меньше той, что
    * игрок набирает при свободном падении с заданной высоты. Порог задан в
    * блоках, а не в блоках/тик, потому что «с трёх блоков» — то, что видно
    * глазом; в скорость он переводится симуляцией ванильной гравитации.
    *
    * <p>Два пути к булаве. В хотбаре — просто выбор слота, без единого клика по
    * контейнеру. В инвентаре — SWAP тем же кликом, каким её тянут AutoTool и
    * AutoUse.
    *
    * <p>Возврат — при выходе из условия, но с задержкой: урон булавы считается в
    * момент приземления, а скорость к этому тику уже ноль. Свап назад сразу
    * отобрал бы удар.
    *
    * <p>Приоритет 1 (HIGH): Aura бьёт на приоритете 2, а сам смэш этого модуля
    * считается на 3, поэтому булава оказывается в руке раньше, чем кто-то из них
    * посмотрит на предмет. Выбор слота доезжает до сервера в том же тике:
    * attackEntity начинается с syncSelectedSlot().
    */
   @EventTarget(1)
   public void on23(EventTick var1) {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (clientplayerentity == null || minecraftClient3.world == null) {
         // Мира нет — вернуть слот некуда и нечем, состояние только сбросить.
         this.resetSwap();
         return;
      }

      if (this.swapPending > 0) {
         this.swapPending--;
      }

      if (!this.autoSwap.isEnabled()) {
         if (this.hasSession()) {
            this.endSession(clientplayerentity);
         }

         return;
      }

      if (this.shouldHoldMace(clientplayerentity)) {
         this.holdRemaining = (int)this.holdTicks.getCurrent();
         if (!this.hasSession()) {
            this.beginSession(clientplayerentity);
         }
      } else if (this.hasSession()) {
         // Удержание после приземления: скорость уже ноль, а удар булавы ещё
         // не посчитан.
         if (this.holdRemaining > 0) {
            this.holdRemaining--;
         } else {
            this.endSession(clientplayerentity);
         }
      }
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
    * Забрать булаву. Хотбар предпочтительнее инвентаря: выбор слота не создаёт
    * ни одного клика по контейнеру, и терять там нечего.
    */
   public void beginSession(ClientPlayerEntity var1) {
      // Свап уже поставлен в очередь и ещё не отработал: hasSession() пока
      // false, и без этой проверки каждый тик добавлял бы ещё один свап.
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
               if (slot != null) {
                  int j = var1.getInventory().getSelectedSlot();
                  if (this.invMoveSwap.isEnabled()) {
                     if (TaskScheduler.Easing(MaceHelper.class)) {
                        this.swapPending = int422;
                        TaskScheduler.on23(MaceHelper.class, () -> {
                           ScreenUtils.on23(slot, j, true);
                           // Поля сессии выставляются ВНУТРИ задачи: шаг
                           // отложенный, и если он не доедет, снаружи не должно
                           // остаться записи о свапе, которого не было.
                           this.savedHotbarSlot = j;
                           this.pulledInventorySlot = slot.id;
                           this.pulledHotbarSlot = j;
                           this.swapPending = 0;
                        });
                     }
                  } else {
                     ScreenUtils.on23(slot, j, true);
                     this.savedHotbarSlot = j;
                     this.pulledInventorySlot = slot.id;
                     this.pulledHotbarSlot = j;
                  }
               }
            }
         }
      }
   }

   /**
    * Отдать булаву назад. Тем же SWAP с теми же слотами: булава уходит в
    * инвентарь, а предмет, который там лежал, возвращается в хотбар.
    */
   public void endSession(ClientPlayerEntity var1) {
      int i = this.pulledInventorySlot;
      int j = this.pulledHotbarSlot;
      int k = this.savedHotbarSlot;
      boolean flag = var1.getMainHandStack().getItem() instanceof MaceItem;
      this.resetSwap();
      if (i == -1) {
         // Булава жила в хотбаре — только вернуть прежний слот. Но если игрок
         // сам сменил слот, булавы в руке уже нет: возврат отобрал бы его выбор.
         if (flag && k >= 0 && k < 9) {
            var1.getInventory().setSelectedSlot(k);
         }
      } else if (j >= 0
         && j < 9
         && var1.getInventory().getStack(j).getItem() instanceof MaceItem
         && var1.currentScreenHandler instanceof PlayerScreenHandler) {
         // Обратный свап только если булава действительно там, где мы её
         // оставили. Иначе (свап не дошёл, игрок переложил предмет сам) мы бы
         // отправили в инвентарь чужой предмет, а оттуда достали неизвестно что.
         Slot slot = var1.playerScreenHandler.getSlot(i);
         if (slot != null) {
            if (this.invMoveSwap.isEnabled()) {
               if (TaskScheduler.Easing(MaceHelper.class)) {
                  TaskScheduler.on23(MaceHelper.class, () -> ScreenUtils.on23(slot, j, true));
               }
            } else {
               ScreenUtils.on23(slot, j, true);
            }
         }
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
    * Скорость (блоков/тик) в конце свободного падения с заданной высоты по
    * ванильной гравитации: v ← (v - 0.08) · 0.98 за тик. Замкнутой формулы нет
    * из-за сопротивления, поэтому симуляция. На 3 блоках выходит ~0.65 бл/тик.
    */
   public static double fallSpeedFromHeight(double var0) {
      double d0 = 0.0;
      double d1 = 0.0;
      // Страховка от зацикливания: скорость упирается в терминальную ~3.92
      // бл/тик, так что любая разумная высота набирается за считанные десятки
      // тиков.
      for (int i = 0; i < 200 && d1 < var0; i++) {
         d0 = (d0 - 0.08) * 0.98;
         d1 -= d0;
      }

      return -d0;
   }

   // --- основная логика удара ----------------------------------------------
   /**
    * Приоритет 3 (LOW): Aura бьёт на приоритете по умолчанию (2), поэтому её
    * удар этого тика уже случился, и мы видим его по флагу boolean3. Дублировать
    * не нужно — свой удар мы делаем только тогда, когда Aura промолчала.
    */
   @EventTarget(3)
   public void ColorAnimator(EventTick var1) {
      ClientPlayerEntity clientplayerentity = minecraftClient3.player;
      if (clientplayerentity == null || minecraftClient3.world == null) {
         this.peakY = null;
         this.struckThisFall = false;
         return;
      }

      // считаем высоту падения: пик с момента отрыва от земли
      double d0 = clientplayerentity.getY();
      if (clientplayerentity.isOnGround()) {
         this.peakY = d0;
         this.struckThisFall = false;
      } else if (this.peakY == null || d0 > this.peakY) {
         this.peakY = d0;
      }

      double d1 = this.peakY != null ? this.peakY - d0 : 0.0;
      String s;
      if (!this.isEnabled()) {
         s = "выключен";
      } else if (!Aura.aura.isEnabled()) {
         s = "Aura выключена";
      } else if (clientplayerentity.isOnGround()) {
         s = "на земле";
      } else if (clientplayerentity.isUsingItem()) {
         s = "занят предметом";
      } else if (!this.holdingMace()) {
         s = "в руке не булава";
      } else if (this.oncePerFall.isEnabled() && this.struckThisFall) {
         s = "уже ударил в этом падении";
      } else if (d1 < this.minHeight.getCurrent()) {
         s = String.format("высота %.1f < %.1f", d1, this.minHeight.getCurrent());
      } else if (clientplayerentity.getVelocity().y >= 0.0) {
         s = "лечу вверх";
      } else if (Aura.aura.boolean3) {
         // Aura уже ударила в этом тике, и все условия смэша сошлись — значит её
         // удар и есть смэш этого падения. Флаг ставим только здесь: boolean3
         // поднимается на ЛЮБОМ ударе Ауры, и если проверять его раньше, то удар
         // на земле в начале прыжка съел бы ещё не начавшееся падение.
         this.struckThisFall = true;
         s = "ударила Aura";
      } else {
         s = this.tryStrike(clientplayerentity, d1);
      }

      if (this.debug.isEnabled() && minecraftClient3.inGameHud != null) {
         String s1 = "";
         if (this.scanGroundTop != null) {
            s1 = String.format(", до земли %.1f", d0 - this.scanGroundTop);
            if (this.scanTicksLeft != null) {
               s1 = s1 + String.format(" (%d тик.)", this.scanTicksLeft);
            }
         }

         String s2 = s == null
            ? String.format("Mace: удар! высота %.1f%s", d1, s1)
            : String.format("Mace: %s (высота %.1f%s)", s, d1, s1);
         minecraftClient3.inGameHud.setOverlayMessage(Text.of(s2), false);
      }
   }

   /**
    * Попробовать ударить. Возвращает причину отказа или null, если ударили.
    *
    * <p>Побочный эффект — scanGroundTop/scanTicksLeft, откуда отладочный
    * оверлей берёт высоту земли и тики до неё.
    */
   public String tryStrike(ClientPlayerEntity var1, double var2) {
      this.scanGroundTop = null;
      this.scanTicksLeft = null;
      String s = null;
      // Тайминг удара: 0% — бить сразу, как набрана минимальная высота, 100% —
      // максимально поздно, на последнем тике перед приземлением. Задержка
      // работает только от «Высоты для задержки»: ниже неё бьём сразу, чтобы на
      // малых высотах не терять Порыв ветра. При любом тайминге удар только в
      // воздухе: если до земли остался один тик, бьём прямо сейчас, даже если
      // прогресс не набран.
      double d0 = this.strikeTiming.getCurrent() / 100.0;
      Double odouble = this.groundTopBelow(var1);
      if (odouble != null) {
         int i = this.ticksToLand(var1, odouble);
         this.scanGroundTop = odouble;
         this.scanTicksLeft = i;
         double d1 = this.peakY != null ? this.peakY - odouble : 0.0;
         double d2 = d1 > 0.5 ? var2 / d1 : 1.0;
         boolean flag = d1 >= this.delayHeight.getCurrent();
         if (i <= 0) {
            s = "окно упущено: уже приземление";
         } else if (flag && d2 < d0 && i > 1) {
            s = String.format("рано: пройдено %.0f%% из %.0f%%", d2 * 100.0, d0 * 100.0);
         }
      }

      if (s == null) {
         float f = var1.getAttackCooldownProgress(0.0F);
         if (f <= 0.02F) {
            s = "удар был только что";
         } else {
            float f1 = this.minCharge.getCurrent() / 100.0F;
            if (!this.oncePerFall.isEnabled()) {
               f1 = Math.max(f1, 0.2F);       // повтор без спама
            }

            if (f < f1) {
               s = String.format("заряд %.0f%% < %.0f%%", f * 100.0F, f1 * 100.0F);
            }
         }
      }

      if (s != null) {
         return s;
      }

      LivingEntity livingentity = Aura.aura.zClass054();
      if (livingentity == null) {
         return "нет цели";
      }

      double d3 = var1.distanceTo(livingentity);
      if (d3 > Aura.aura.var1356() + 0.5) {
         return String.format("далеко (%.1f)", d3);
      }

      // Питоновая версия сравнивала только дистанцию: райкаста в её API не было.
      // Здесь спрашиваем саму Ауру, попадает ли её ТЕКУЩАЯ ротация по хитбоксу —
      // иначе сервер удар не зачтёт, и смэш сгорит впустую вместе с падением.
      if (!Aura.aura.var135()) {
         return "ротация мимо цели";
      }

      // Ротацию не трогаем: наводка уже сделана Аурой её текущим режимом. Флаг
      // smashNow держим только на время удара — по нему AttackEntityEvent
      // отличает наш смэш от ручного, а событие приходит синхронно внутри
      // attack, поэтому снимать его в finally безопасно.
      this.smashNow = true;

      try {
         PlayerStateService.Easing(livingentity);
      } finally {
         this.smashNow = false;
      }

      this.struckThisFall = true;
      return null;
   }

   /**
    * Y поверхности ближайшего твёрдого блока под игроком.
    *
    * <p>Нужно, чтобы знать, когда наступит приземление.
    */
   public Double groundTopBelow(ClientPlayerEntity var1) {
      int i = (int)Math.floor(var1.getX());
      int j = (int)Math.floor(var1.getY());
      int k = (int)Math.floor(var1.getZ());
      BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

      for (int l = j - 1; l > j - 65; l--) {
         blockpos$mutable.set(i, l, k);
         if (minecraftClient3.world.getBlockState(blockpos$mutable).blocksMovement()) {
            return l + 1.0;                  // твёрдый блок, не воздух и не вода
         }
      }

      return null;
   }

   /** Сколько тиков осталось до приземления (симуляция падения). */
   public int ticksToLand(ClientPlayerEntity var1, double var2) {
      double d0 = var1.getY();
      if (d0 <= var2) {
         return 0;                           // уже на уровне земли, окно упущено
      }

      double d1 = var1.getVelocity().y;

      for (int i = 1; i <= 200; i++) {
         d1 = (d1 - 0.08) * 0.98;            // гравитация и сопротивление воздуха
         d0 += d1;
         if (d0 <= var2) {
            return i;
         }
      }

      return 201;
   }

   // --- триггеры игл -------------------------------------------------------
   /**
    * Любой удар по сущности: наш смэш, удар Ауры или ручной. Хук стоит на
    * PlayerEntity.attack, поэтому смэш помощника приходит сюда сам — звать
    * вспышку из tryStrike не нужно, а smashNow отличает его от ручного.
    */
   @EventTarget
   public void ItemRegistry(AttackEntityEvent var1) {
      if (var1.ElytraTarget() == AttackEntityEvent.on23.call077) {
         Entity entity = var1.ElytraMotion();
         if (this.isEnabled() && entity != null) {
            this.map72.put(entity.getId(), System.currentTimeMillis());
            if (this.map72.size() > 32) {
               long i = System.currentTimeMillis();
               this.map72.entrySet().removeIf(var2x -> i - var2x.getValue() > 1500L);
            }

            if (this.burstAllowed(entity, false, this.smashNow)) {
               this.burst(entity);
            }
         }
      }
   }

   /** Смерть цели: вспышка, если выбран режим с киллом. */
   @EventTarget
   public void on23(EventDead var1) {
      if (this.isEnabled() && minecraftClient3.world != null) {
         LivingEntity livingentity = var1.entity();
         if (this.burstAllowed(livingentity, true, false)) {
            if (this.byMeOnly.isEnabled()) {
               // Кто добил, клиент не знает: в EventDead только сама сущность.
               // Считаем своим того, по кому мы били не дольше 1.5 с назад —
               // тем же окном, что и KillEffect.
               Long olong = this.map72.remove(livingentity.getId());
               if (olong == null || System.currentTimeMillis() - olong > 1500L) {
                  return;
               }
            }

            this.burst(livingentity);
         }
      }
   }

   /**
    * Пропускать ли вспышку по этой цели.
    *
    * @param var2 пришли по событию смерти, иначе по удару
    * @param var3 удар сделал сам помощник в падении
    */
   public boolean burstAllowed(Entity var1, boolean var2, boolean var3) {
      if (!this.needles.isEnabled() || !this.isEnabled()) {
         return false;
      } else if (var2 && this.needlesWhen.is(0)) {
         return false;
      } else if (!var2 && this.needlesWhen.is(1)) {
         return false;
      } else if (this.smashOnly.isEnabled() && !var3) {
         return false;
      } else if (var1 == null || var1 == minecraftClient3.player) {
         return false;
      } else if (this.pvpOnly.isEnabled() && !var1.isPlayer()) {
         return false;
      } else {
         // смэш уже проверил булаву в тике, ручному удару проверяем здесь
         return var3 || !this.maceOnly.isEnabled() || this.holdingMace();
      }
   }

   /**
    * False, если по этой цели уже вспыхнули только что.
    *
    * <p>Смэш и killEffect могут прийти по одной цели почти одновременно — этот
    * сторож и гасит дубль.
    */
   public boolean freshTarget(Entity var1, double var2) {
      int i = var1.getId();
      Double odouble = this.map71.get(i);
      if (odouble != null && var2 - odouble >= 0.0 && var2 - odouble < double120) {
         return false;
      } else {
         if (this.map71.size() > 32) {
            this.map71.entrySet().removeIf(var2x -> var2 - var2x.getValue() > 1.0);
         }

         this.map71.put(i, var2);
         return true;
      }
   }

   /** Точка вспышки — голова цели. */
   public Vec3d headPos(Entity var1) {
      return new Vec3d(var1.getX(), var1.getEyeY(), var1.getZ());
   }

   /** Выпустить плотную сферу игл из головы цели. */
   public void burst(Entity var1) {
      double d0 = now();
      if (this.freshTarget(var1, d0)) {
         int i = int420 - this.list200.size();
         if (i > 0) {
            int j = Math.min(Math.round(this.needleCount.getCurrent()), i);
            if (j > 0) {
               Vec3d vec3d = this.headPos(var1);
               ThreadLocalRandom threadlocalrandom = ThreadLocalRandom.current();
               double d1 = this.needleSpeed.getCurrent();
               double d2 = this.needleLife.getCurrent();
               // 0 в настройке = летят сквозь блоки; в игле -1 отключает
               // столкновения, 0 значит «отскоки кончились, но от поверхности
               // всё ещё отталкиваемся»
               int k = Math.round(this.maxBounce.getCurrent());
               // случайный поворот решётки Фибоначчи: каждая вспышка своя
               double d3 = threadlocalrandom.nextDouble() * (Math.PI * 2);
               double d4 = threadlocalrandom.nextDouble() * (Math.PI * 2);
               double d5 = Math.cos(d4);
               double d6 = Math.sin(d4);

               for (int l = 0; l < j; l++) {
                  // равномерная сфера по спирали Фибоначчи
                  double d7 = 1.0 - (2.0 * l + 1.0) / j;
                  double d8 = Math.sqrt(Math.max(0.0, 1.0 - d7 * d7));
                  double d9 = double126 * l + d3;
                  double d10 = d8 * Math.cos(d9);
                  double d11 = d8 * Math.sin(d9);
                  // наклон решётки вокруг X, чтобы вспышки не были копиями
                  double d12 = d7 * d5 - d11 * d6;
                  double d13 = d7 * d6 + d11 * d5;
                  // разброс скорости меньше 5%: сфера идёт единым фронтом
                  double d14 = d1 * (0.97 + 0.06 * threadlocalrandom.nextDouble());
                  this.list200
                     .add(
                        new MaceHelper.Needle(
                           vec3d.x,
                           vec3d.y,
                           vec3d.z,
                           d10 * d14,
                           d12 * d14,
                           d13 * d14,
                           d0,
                           d2 * (0.75 + 0.5 * threadlocalrandom.nextDouble()),
                           d14,
                           0.9 + 0.1 * threadlocalrandom.nextDouble(),
                           k > 0 ? threadlocalrandom.nextInt(1, k + 1) : -1
                        )
                     );
               }
            }
         }
      }
   }

   /** Твёрдый ли блок; ответы кешируются — за вспышку мир не меняется. */
   public boolean solidAt(int var1, int var2, int var3) {
      long i = BlockPos.asLong(var1, var2, var3);
      Boolean obool = this.map70.get(i);
      if (obool == null) {
         obool = minecraftClient3.world != null && minecraftClient3.world.getBlockState(new BlockPos(var1, var2, var3)).blocksMovement();
         if (this.map70.size() > 4096) {
            this.map70.clear();
         }

         this.map70.put(i, obool);
      }

      return obool;
   }

   // --- иглы: столкновения -------------------------------------------------
   /** Отразить иглу от блока. Возвращает исправленную позицию. */
   public Vec3d bounceOff(MaceHelper.Needle var1, double var2, double var4, double var6, double var8) {
      int i = (int)Math.floor(var2);
      int j = (int)Math.floor(var4);
      int k = (int)Math.floor(var6);
      // какая ось воткнулась: проверяем каждую отдельно от старой клетки
      boolean flag = i != var1.bx && this.solidAt(i, var1.by, var1.bz);
      boolean flag1 = j != var1.by && this.solidAt(var1.bx, j, var1.bz);
      boolean flag2 = k != var1.bz && this.solidAt(var1.bx, var1.by, k);
      if (!flag && !flag1 && !flag2) {
         flag = flag1 = flag2 = true;        // угол: ось не определилась, гасим всё
      }

      if (flag) {
         var2 = var1.x;
         var1.vx = -var1.vx * double121;
      } else {
         var1.vx *= double122;
      }

      if (flag1) {
         var4 = var1.y;
         var1.vy = -var1.vy * double121;
      } else {
         var1.vy *= double122;
      }

      if (flag2) {
         var6 = var1.z;
         var1.vz = -var1.vz * double121;
      } else {
         var1.vz *= double122;
      }

      // Тусклую иглу не подскакиваем: её всё равно не видно, а подарок жизни
      // растянул бы угасание.
      if (var1.bounces > 0 && (var8 - var1.born) / var1.life < double124) {
         var1.bounces--;
         // Длину игла считает от своей скорости относительно стартовой. К полу
         // она подходит на ~37% начальной, после удара остаётся ~23%, и вместо
         // штриха нарисовался бы огрызок. Берём скорость подскока за новую
         // точку отсчёта: игла снова уходит вверх полноценным штрихом.
         double d0 = Math.sqrt(var1.vx * var1.vx + var1.vy * var1.vy + var1.vz * var1.vz);
         if (d0 > 0.05) {
            var1.v0 = d0;
         }

         // и даём времени на сам подскок, иначе он не успеет попасть в кадр
         if (var1.remaining(var8) < double123) {
            var1.restartFade(var8, double123);
         }
      } else {
         // отскоки кончились (или игла уже погасла) — оседаем
         var1.settle(var8);
      }

      return new Vec3d(var2, var4, var6);
   }

   // --- иглы: физика и отрисовка -------------------------------------------
   @EventTarget
   public void on23(EventHookWorldRender var1) {
      if (!this.isEnabled() || !this.needles.isEnabled()) {
         if (!this.list200.isEmpty()) {
            this.list200.clear();
            this.map70.clear();
         }

         this.lastFrame = -1.0;
         return;
      }

      if (this.list200.isEmpty()) {
         if (!this.map70.isEmpty()) {
            this.map70.clear();              // мир мог измениться, кеш блоков не нужен
         }

         this.lastFrame = -1.0;
         return;
      }

      double d0 = now();
      double d1;
      if (this.lastFrame < 0.0 || d0 < this.lastFrame || d0 - this.lastFrame > 0.25) {
         d1 = 0.05;                          // первый кадр после паузы
      } else {
         d1 = d0 - this.lastFrame;
      }

      this.lastFrame = d0;
      // торможение задано как «сколько скорости остаётся за тик» -> непрерывный
      // коэффициент. Радиус вспышки одинаковый и на 30, и на 240 FPS.
      double d2 = Math.min(0.96, Math.max(0.6, this.needleDrag.getCurrent() / 100.0));
      double d3 = -Math.log(d2) * 20.0;      // 1/с
      double d4 = Math.exp(-d3 * d1);        // v(t+dt) = v(t) * decay
      double d5 = (1.0 - d4) / d3;           // точный путь за кадр = v * step
      double d6 = 0.08 * (this.needleGravity.getCurrent() / 100.0) * 400.0;   // бл/с^2
      double d7 = this.needleLength.getCurrent();
      double d8 = this.needleWidth.getCurrent() * 0.5;
      Vec3d vec3d = minecraftClient3.gameRenderer.getCamera().getCameraPos();
      float[] afloat = this.needleColor.is(1)
         ? new float[]{255.0F, 255.0F, 255.0F, 215.0F, 228.0F, 238.0F}
         : (
            this.needleColor.is(2)
               ? new float[]{228.0F, 252.0F, 255.0F, 90.0F, 210.0F, 255.0F}
               : new float[]{255.0F, 255.0F, 255.0F, 150.0F, 235.0F, 255.0F}
         );
      // Два прохода: сперва физика, потом вершины. Tessellator один на весь
      // клиент, и держать его открытым во время физики нельзя — вложенный begin
      // из чужого рисования затрёт наш буфер. К тому же end() на пустом буфере
      // бросает исключение, а после физики уже видно, есть ли что рисовать.
      boolean flag = false;
      Iterator<MaceHelper.Needle> iterator = this.list200.iterator();

      while (iterator.hasNext()) {
         MaceHelper.Needle liii1111liiii1lll1ll11il1 = iterator.next();
         double d9 = (d0 - liii1111liiii1lll1ll11il1.born) / liii1111liiii1lll1ll11il1.life;
         if (d9 >= 1.0) {
            iterator.remove();
         } else {
            liii1111liiii1lll1ll11il1.vy -= d6 * d1;
            double d10 = liii1111liiii1lll1ll11il1.x + liii1111liiii1lll1ll11il1.vx * d5;
            double d11 = liii1111liiii1lll1ll11il1.y + liii1111liiii1lll1ll11il1.vy * d5;
            double d12 = liii1111liiii1lll1ll11il1.z + liii1111liiii1lll1ll11il1.vz * d5;
            // столкновения проверяем только на переходе в новую клетку: иначе
            // это был бы запрос блока на каждую иглу каждый кадр
            if (liii1111liiii1lll1ll11il1.bounces >= 0) {
               int i = (int)Math.floor(d10);
               int j = (int)Math.floor(d11);
               int k = (int)Math.floor(d12);
               if ((i != liii1111liiii1lll1ll11il1.bx || j != liii1111liiii1lll1ll11il1.by || k != liii1111liiii1lll1ll11il1.bz)
                  && this.solidAt(i, j, k)) {
                  Vec3d vec3d1 = this.bounceOff(liii1111liiii1lll1ll11il1, d10, d11, d12, d0);
                  d10 = vec3d1.x;
                  d11 = vec3d1.y;
                  d12 = vec3d1.z;
               }

               liii1111liiii1lll1ll11il1.bx = (int)Math.floor(d10);
               liii1111liiii1lll1ll11il1.by = (int)Math.floor(d11);
               liii1111liiii1lll1ll11il1.bz = (int)Math.floor(d12);
            }

            liii1111liiii1lll1ll11il1.x = d10;
            liii1111liiii1lll1ll11il1.y = d11;
            liii1111liiii1lll1ll11il1.z = d12;
            liii1111liiii1lll1ll11il1.vx *= d4;
            liii1111liiii1lll1ll11il1.vy *= d4;
            liii1111liiii1lll1ll11il1.vz *= d4;
            // отскок мог заново пустить угасание, поэтому долю жизни берём
            // свежую: со старой множитель яркости применился бы дважды и игла
            // мигнула бы
            d9 = (d0 - liii1111liiii1lll1ll11il1.born) / liii1111liiii1lll1ll11il1.life;
            double d13 = Math.sqrt(
               liii1111liiii1lll1ll11il1.vx * liii1111liiii1lll1ll11il1.vx
                  + liii1111liiii1lll1ll11il1.vy * liii1111liiii1lll1ll11il1.vy
                  + liii1111liiii1lll1ll11il1.vz * liii1111liiii1lll1ll11il1.vz
            );
            liii1111liiii1lll1ll11il1.alpha = 0.0F;
            if (!(d13 < 1.0E-4)) {
               // держит яркость и гаснет рывком в конце: alpha = 1 - (t/T)^2
               float f = (float)(250.0 * liii1111liiii1lll1ll11il1.bright * Math.max(0.0, 1.0 - d9 * d9));
               if (!(f < 3.0F)) {
                  liii1111liiii1lll1ll11il1.dx = liii1111liiii1lll1ll11il1.vx / d13;
                  liii1111liiii1lll1ll11il1.dy = liii1111liiii1lll1ll11il1.vy / d13;
                  liii1111liiii1lll1ll11il1.dz = liii1111liiii1lll1ll11il1.vz / d13;
                  // L = L_база * (|v| / v_старта): длинная на вылете, сжимается
                  // к остановке
                  liii1111liiii1lll1ll11il1.len = Math.max(0.05, Math.min(d7, d7 * (d13 / liii1111liiii1lll1ll11il1.v0)));
                  liii1111liiii1lll1ll11il1.alpha = f;
                  flag = true;
               }
            }
         }
      }

      if (this.list200.isEmpty()) {
         this.map70.clear();
      }

      if (flag) {
         Matrix4f matrix4f = var1.ClanUpgrade().peek().getPositionMatrix();
         org.zenith.render.LegacyRenderBridge.enableBlend();
         org.zenith.render.LegacyRenderBridge.blendFunc(770, 1);   // аддитивный блендинг
         org.zenith.render.LegacyRenderBridge.disableCull();
         if (this.hideWalls.isEnabled()) {
            org.zenith.render.LegacyRenderBridge.enableDepthTest();
         } else {
            org.zenith.render.LegacyRenderBridge.disableDepthTest();
         }

         org.zenith.render.LegacyRenderBridge.depthMask(false);
         org.zenith.render.LegacyRenderBridge.usePositionColor();
         BufferBuilder bufferbuilder = Tessellator.getInstance().begin(DrawMode.QUADS, VertexFormats.POSITION_COLOR);

         for (MaceHelper.Needle liii1111liiii1lll1ll11il11 : this.list200) {
            if (liii1111liiii1lll1ll11il11.alpha >= 3.0F) {
               this.emitNeedle(bufferbuilder, matrix4f, vec3d, liii1111liiii1lll1ll11il11, d8, afloat);
            }
         }

         org.zenith.render.LegacyRenderBridge.draw(bufferbuilder.end());
         org.zenith.render.LegacyRenderBridge.depthMask(true);
         org.zenith.render.LegacyRenderBridge.enableDepthTest();
         org.zenith.render.LegacyRenderBridge.enableCull();
         org.zenith.render.LegacyRenderBridge.disableBlend();
         org.zenith.render.LegacyRenderBridge.defaultBlendFunc();
      }
   }

   /**
    * Одна игла: полоса ровной толщины лицом к камере, нарезанная поперёк на
    * ленты с мягкими краями.
    */
   public void emitNeedle(BufferBuilder var1, Matrix4f var2, Vec3d var3, MaceHelper.Needle var4, double var5, float[] var7) {
      // вершины считаем от камеры: одинарная точность далеко от нуля дрожит
      double d0 = var4.x - var3.x;
      double d1 = var4.y - var3.y;
      double d2 = var4.z - var3.z;
      // квад разворачиваем поперёк луча зрения: игла всегда смотрит плашмя
      double d3 = var4.dy * d2 - var4.dz * d1;
      double d4 = var4.dz * d0 - var4.dx * d2;
      double d5 = var4.dx * d1 - var4.dy * d0;
      double d6 = Math.sqrt(d3 * d3 + d4 * d4 + d5 * d5);
      if (d6 < 1.0E-6) {
         // игла летит точно в камеру: любая перпендикулярная сойдёт
         if (Math.abs(var4.dy) < 0.9) {
            d3 = 0.0;
            d4 = 1.0;
            d5 = 0.0;
         } else {
            d3 = 1.0;
            d4 = 0.0;
            d5 = 0.0;
         }

         d6 = 1.0;
      }

      float f = (float)(d3 / d6 * var5);
      float f1 = (float)(d4 / d6 * var5);
      float f2 = (float)(d5 / d6 * var5);
      float f3 = (float)d0;
      float f4 = (float)d1;
      float f5 = (float)d2;
      float f6 = (float)(d0 - var4.dx * var4.len);
      float f7 = (float)(d1 - var4.dy * var4.len);
      float f8 = (float)(d2 - var4.dz * var4.len);
      float f9 = var4.alpha;
      float f10 = f9 * float300;

      for (int i = 0; i < int421; i++) {
         // ленты идут от борта к борту: across = -1 .. 1, профиль 1 - across^2
         float f11 = -1.0F + 2.0F * i / int421;
         float f12 = -1.0F + 2.0F * (i + 1) / int421;
         float f13 = Math.max(0.0F, 1.0F - f11 * f11);
         float f14 = Math.max(0.0F, 1.0F - f12 * f12);
         // голова эмиссивно-белая, хвост уходит в серебристый циан и сходит на
         // нет прозрачностью, а не сужением
         this.vertex(var1, var2, f3 + f * f11, f4 + f1 * f11, f5 + f2 * f11, var7[0], var7[1], var7[2], f9 * f13);
         this.vertex(var1, var2, f3 + f * f12, f4 + f1 * f12, f5 + f2 * f12, var7[0], var7[1], var7[2], f9 * f14);
         this.vertex(var1, var2, f6 + f * f12, f7 + f1 * f12, f8 + f2 * f12, var7[3], var7[4], var7[5], f10 * f14);
         this.vertex(var1, var2, f6 + f * f11, f7 + f1 * f11, f8 + f2 * f11, var7[3], var7[4], var7[5], f10 * f13);
      }
   }

   public void vertex(
      BufferBuilder var1, Matrix4f var2, float var3, float var4, float var5, float var6, float var7, float var8, float var9
   ) {
      int i = Math.max(0, Math.min(255, Math.round(var9))) << 24
         | Math.max(0, Math.min(255, Math.round(var6))) << 16
         | Math.max(0, Math.min(255, Math.round(var7))) << 8
         | Math.max(0, Math.min(255, Math.round(var8)));
      var1.vertex(var2, var3, var4, var5).color(i);
   }


   /** Одна игла: положение, скорость, время рождения и остаток отскоков. */
   public static class Needle {
      public double x;
      public double y;
      public double z;
      public double vx;
      public double vy;
      public double vz;
      /** Момент, с которого считается угасание. */
      public double born;
      /** Сколько секунд от born игла живёт. */
      public double life;
      /** Скорость, от которой считается длина штриха. */
      public double v0;
      /** Множитель яркости. */
      public double bright;
      /** Остаток отскоков; -1 — сквозь блоки, 0 — уже осела. */
      public int bounces;
      /** Клетка прошлого кадра: столкновения ищем только на её смене. */
      public int bx;
      public int by;
      public int bz;
      /** Параметры отрисовки, посчитанные проходом физики этого кадра. */
      public double dx;
      public double dy;
      public double dz;
      public double len;
      /** Альфа головы; меньше 3 — игла в этом кадре не рисуется. */
      public float alpha;

      public Needle(
         double var1, double var3, double var5, double var7, double var9, double var11, double var13, double var15, double var17, double var19, int var21
      ) {
         this.x = var1;
         this.y = var3;
         this.z = var5;
         this.vx = var7;
         this.vy = var9;
         this.vz = var11;
         this.born = var13;
         this.life = var15;
         this.v0 = var17;
         this.bright = var19;
         this.bounces = var21;
         this.bx = (int)Math.floor(var1);
         this.by = (int)Math.floor(var3);
         this.bz = (int)Math.floor(var5);
      }

      /** Сколько секунд игле осталось жить. */
      public double remaining(double var1) {
         return this.life - (var1 - this.born);
      }

      /**
       * Заново пустить угасание: игла гаснет от ТЕКУЩЕЙ яркости за var3.
       *
       * <p>Альфа считается от доли прожитого, поэтому наивный способ продлить
       * жизнь — растянуть общий срок, сохранив долю. Так выходило боком: игла,
       * стукнувшаяся при t = 0.9, получала пятисекундную жизнь и потом висела
       * невидимой, зря молотя физику. Здесь вместо этого множитель яркости
       * домножается на уже прожитую долю, а отсчёт идёт с нуля: на стыке
       * яркость ровно та же, растягиваться нечему, и срок жизни всегда
       * ограничен var3.
       */
      public void restartFade(double var1, double var3) {
         double d0 = (var1 - this.born) / this.life;
         if (d0 > 0.0) {
            this.bright = this.bright * Math.max(0.0, 1.0 - d0 * d0);
         }

         this.born = var1;
         this.life = var3;
      }

      /**
       * Отскоки кончились: игла оседает и быстро догорает на месте.
       *
       * <p>Скорость не обнуляем: иглу рисуем вдоль неё, без направления она
       * исчезнет. Малая скорость сама сжимает иглу почти в точку, а проверка
       * столкновений остаётся включённой (bounces == 0), поэтому лежащая игла
       * не проваливается сквозь поверхность.
       */
      public void settle(double var1) {
         this.vx *= 0.15;
         this.vy *= 0.15;
         this.vz *= 0.15;
         if (this.remaining(var1) > double125) {
            this.restartFade(var1, double125);
         }
      }
   }
}
