package org.zenith.module.player.autosell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Цикл «изумрудный меч»: закупка дерева и изумрудов, крафт, продажа через /ah sellgui и перевыставление.
 * Общий для ботов и локального игрока.
 *
 * <p>Каждый шаг опирается только на то, что прислал сервер: меню ждём по новому syncId, кнопки ищем по
 * предметам, покупку и продажу подтверждаем по инвентарю после синхронизации. Клиентское предсказание
 * клика (предмет «переехал» локально, а сервер клик отменил) само по себе успехом не считается.
 */
public final class AutoSellEngine {
   private static final long ACTION_MIN_MS = 500L;
   private static final long ACTION_MAX_MS = 1000L;
   private static final long SERVER_TIMEOUT_MS = 6000L;
   private static final long SHORT_TIMEOUT_MS = 4000L;
   /** Сколько результат должен продержаться, прежде чем ему верить: откат отменённого клика приходит за RTT. */
   private static final long SETTLE_MS = 400L;
   /** Раньше этого меню после клика не считаем сменившимся — сначала должен прийти ответ сервера. */
   private static final long MENU_SWITCH_MS = 900L;
   private static final long IDLE_TURN_MIN_MS = 2000L;
   private static final long IDLE_TURN_MAX_MS = 10000L;
   private static final float IDLE_TURN_MIN_DEG = 0.00001F;
   private static final float IDLE_TURN_MAX_DEG = 5.0F;
   /** Фоновый поворот не должен надолго задерживать работу с меню — точное попадание тут не важно. */
   private static final int IDLE_TURN_TICKS = 30;
   private static final long MAX_BACKOFF_MS = 60000L;
   private static final long SELL_RETRY_MS = 90000L;
   private static final int SELL_FAILS_BEFORE_PAUSE = 2;
   private static final int MAX_ATTEMPTS = 6;
   private static final int CLEANUP_ATTEMPTS = 3;
   /** Свободные слоты под возврат остатков из верстака: при закрытии без места сервер выбросит их на землю. */
   private static final int CRAFT_RESERVE = 3;
   private static final double TABLE_REACH = 4.5;
   /** 47-й слот по счёту в двойном сундуке /ah. */
   private static final int AH_ITEMS_SLOT = 46;
   /** «1 слот» меню подтверждения покупки на аукционе. */
   private static final int AH_CONFIRM_SLOT = 0;
   /** Центральная клетка меню /ah sellgui — туда кладём меч, если шифт-клик его не перенёс. */
   private static final int SELL_ITEM_SLOT = 13;
   /** Кнопка подтверждения продажи, если лаймового красителя в меню не нашлось. */
   private static final int SELL_CONFIRM_FALLBACK_SLOT = 15;
   private static final int RESULT_SLOT = 0;
   private static final int GRID2_FIRST = 1;
   private static final int GRID2_LAST = 4;
   private static final int GRID2_TOP_LEFT = 1;
   private static final int GRID2_BOTTOM_LEFT = 3;
   private static final int GRID3_FIRST = 1;
   private static final int GRID3_LAST = 9;
   private static final int GRID3_TOP = 2;
   private static final int GRID3_MIDDLE = 5;
   private static final int GRID3_BOTTOM = 8;

   private final AutoSellSettings settings;
   private final AutoSellHost host;
   private final AutoSellMotorAim aim = new AutoSellMotorAim();
   private final Random random = new Random();
   private Phase phase = Phase.STOPPED;
   private int step;
   private int attempts;
   private int cleanupAttempts;
   private long nextActionAt;
   private long deadline;
   private long actionAt;
   private long settleSince;
   private long nextIdleTurnAt;
   private long sellRetryAt;
   private int failures;
   private int sellFailures;
   private boolean relistPending;
   private boolean craftCommandTried;
   private boolean progress;
   private int stickPlan;
   private int watchSyncId;
   private int watchHash;
   private int countBefore;
   private BlockPos table;
   private String learnedSword;
   private String lastStatus;
   private long lastStatusAt;

   private enum Phase {
      STOPPED,
      TURN,
      INSPECT,
      SELL,
      RELIST,
      CRAFT_PLANKS,
      CRAFT_STICKS,
      CRAFT_SWORDS,
      BUY_EMERALDS,
      BUY_WOOD
   }

   private record Listing(int slot, long price) {
   }

   public AutoSellEngine(AutoSellSettings settings, AutoSellHost host) {
      this.settings = settings;
      this.host = host;
   }

   public void start() {
      this.phase = Phase.TURN;
      this.step = 0;
      this.attempts = 0;
      this.cleanupAttempts = 0;
      this.failures = 0;
      this.sellFailures = 0;
      this.relistPending = false;
      this.craftCommandTried = false;
      this.sellRetryAt = 0L;
      this.nextActionAt = 0L;
      this.nextIdleTurnAt = 0L;
      this.lastStatus = null;
      this.aim.cancel();
      this.debug("включён; режим: изумрудный меч, цена: " + this.price());
   }

   /** @param cleanup закрыть меню и вернуть курсор — только пока соединение живо. */
   public void stop(boolean cleanup) {
      Phase previous = this.phase;
      this.phase = Phase.STOPPED;
      this.aim.cancel();
      if (!cleanup || previous == Phase.STOPPED) {
         return;
      }

      try {
         PlayerEntity player = this.host.player();
         if (player != null && this.needsCleanup(player)) {
            this.host.closeContainer();
         }
      } catch (RuntimeException ignored) {
      }
   }

   public void tick() {
      if (this.phase == Phase.STOPPED) {
         return;
      }

      PlayerEntity player = this.host.player();
      if (player == null || !player.isAlive()) {
         return;
      }

      long now = this.host.now();
      if (this.aim.isActive()) {
         if (containerOpen(player)) {
            this.aim.cancel();
         } else {
            float[] rotation = this.aim.tick(player.getYaw(), player.getPitch(), player.getVelocity());
            if (rotation != null) {
               this.host.setRotation(rotation[0], rotation[1]);
            }

            if (this.aim.isActive()) {
               return;
            }
         }
      }

      if (this.idleTurn(player, now) || now < this.nextActionAt) {
         return;
      }

      try {
         switch (this.phase) {
            case TURN -> this.turn(player, now);
            case INSPECT -> this.inspect(player, now);
            case SELL -> this.sell(player, now);
            case RELIST -> this.relist(player, now);
            case CRAFT_PLANKS -> this.craftPlanks(player, now);
            case CRAFT_STICKS -> this.craftSticks(player, now);
            case CRAFT_SWORDS -> this.craftSwords(player, this.host.world(), now);
            case BUY_EMERALDS -> this.buyEmeralds(player, now);
            case BUY_WOOD -> this.buyWood(player, now);
            default -> {
            }
         }
      } catch (RuntimeException exception) {
         this.fail(player, "внутренняя ошибка " + exception.getClass().getSimpleName(), now);
      }
   }

   private void turn(PlayerEntity player, long now) {
      if (this.step == 0) {
         if (this.needsCleanup(player)) {
            this.cleanup(player, now);
            return;
         }

         float yaw = player.getYaw() + 180.0F + this.spread(5.0F);
         float pitch = MathHelper.clamp(player.getPitch() + this.spread(2.0F), -60.0F, 60.0F);
         this.aim.aimAt(yaw, pitch, player.getYaw(), player.getPitch(), AutoSellMotorAim.GIVE_UP_TICKS);
         this.step = 1;
         return;
      }

      this.enter(Phase.INSPECT, now);
   }

   /** Раз в 2–10 с сдвигает камеру на 0.00001–5° — только когда не открыто меню, как у живого игрока. */
   private boolean idleTurn(PlayerEntity player, long now) {
      if (this.nextIdleTurnAt == 0L) {
         this.nextIdleTurnAt = now + this.randomMs(IDLE_TURN_MIN_MS, IDLE_TURN_MAX_MS);
         return false;
      }

      if (now < this.nextIdleTurnAt || this.phase == Phase.TURN || this.inMenu(player)) {
         return false;
      }

      this.nextIdleTurnAt = now + this.randomMs(IDLE_TURN_MIN_MS, IDLE_TURN_MAX_MS);
      float yaw = player.getYaw() + this.idleSpread();
      float pitch = MathHelper.clamp(player.getPitch() + this.idleSpread(), -70.0F, 70.0F);
      this.aim.aimAt(yaw, pitch, player.getYaw(), player.getPitch(), IDLE_TURN_TICKS);
      return this.aim.isActive();
   }

   private boolean inMenu(PlayerEntity player) {
      return this.needsCleanup(player) || this.phase == Phase.CRAFT_PLANKS || this.phase == Phase.CRAFT_STICKS;
   }

   private void inspect(PlayerEntity player, long now) {
      if (this.needsCleanup(player)) {
         this.cleanup(player, now);
         return;
      }

      this.cleanupAttempts = 0;
      int swords = this.countSwords(player);
      long price = this.price();
      if (swords > 0 && price > 0L && now >= this.sellRetryAt) {
         this.enter(Phase.SELL, now);
         return;
      }

      if (this.relistPending) {
         this.enter(Phase.RELIST, now);
         return;
      }

      if (this.plankButton(player) >= 0) {
         this.enter(Phase.CRAFT_PLANKS, now);
         return;
      }

      if (this.stickMode(player) != 0) {
         this.enter(Phase.CRAFT_STICKS, now);
         return;
      }

      int emeralds = count(player, AutoSellEngine::isEmerald);
      int sticks = count(player, AutoSellEngine::isStick);
      int free = freeSlots(player);
      if (emeralds >= 2 && sticks >= 1 && free >= 2) {
         this.enter(Phase.CRAFT_SWORDS, now);
         return;
      }

      if (swords > 0) {
         this.status(price <= 0L ? "не задана цена продажи (модуль AutoSell во вкладке PvE)" : "продажа на паузе — жду свободных лотов");
         this.nextActionAt = now + 5000L;
         return;
      }

      boolean hasWood = count(player, AutoSellEngine::isLog) > 0 || count(player, AutoSellEngine::isPlank) >= 2;
      if (free > 0 && emeralds < 2) {
         this.enter(Phase.BUY_EMERALDS, now);
      } else if (free > 0 && sticks < 1 && !hasWood) {
         this.enter(Phase.BUY_WOOD, now);
      } else {
         this.status("не хватает места в инвентаре для крафта — освободите слоты");
         this.nextActionAt = now + 15000L;
      }
   }

   private void sell(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            int index = this.findSwordIndex(player);
            if (index < 0) {
               this.succeed();
               this.enter(Phase.INSPECT, now);
               return;
            }

            if (this.attempts++ > MAX_ATTEMPTS) {
               this.fail(player, "не получается взять меч в руку", now);
               return;
            }

            if (index >= PlayerInventory.HOTBAR_SIZE) {
               // В инвентаре игрока слоты 9–35 совпадают с индексами инвентаря.
               this.host.clickSlot(index, hotbarTarget(player), SlotActionType.SWAP);
               this.delay(now);
               return;
            }

            if (player.getInventory().getSelectedSlot() != index) {
               this.host.selectHotbarSlot(index);
               this.delay(now);
               return;
            }

            this.countBefore = this.countSwords(player);
            this.watchSyncId = handler.syncId;
            this.host.sendCommand("ah sellgui " + this.price());
            this.settleSince = 0L;
            this.advance(1, now, SERVER_TIMEOUT_MS);
         }
         case 1 -> {
            if (this.newContainerReady(player)) {
               this.advance(2, now, SERVER_TIMEOUT_MS);
               return;
            }

            // Некоторые аукционы выставляют предмет из руки сразу, без меню.
            if (!containerOpen(player) && this.countSwords(player) < this.countBefore) {
               if (this.settled(now)) {
                  this.onSold();
                  this.advance(8, now, SERVER_TIMEOUT_MS);
               }

               return;
            }

            this.settleSince = 0L;
            if (now > this.deadline) {
               this.fail(player, "меню /ah sellgui не открылось", now);
            }
         }
         case 2 -> {
            if (!containerOpen(player)) {
               this.fail(player, "меню продажи закрылось", now);
               return;
            }

            if (this.findContainerSlot(player, handler, this::isSword) != null) {
               this.advance(6, now, SERVER_TIMEOUT_MS);
               return;
            }

            Slot sword = findPlayerSlot(player, handler, this::isSword, player.getInventory().getSelectedSlot());
            if (sword == null) {
               this.fail(player, "меч пропал из инвентаря", now);
               return;
            }

            this.host.clickSlot(sword.id, 0, SlotActionType.QUICK_MOVE);
            this.advance(3, now, SERVER_TIMEOUT_MS);
         }
         case 3 -> {
            if (!containerOpen(player)) {
               this.fail(player, "меню продажи закрылось", now);
               return;
            }

            if (this.findContainerSlot(player, handler, this::isSword) != null && handler.getCursorStack().isEmpty()) {
               this.advance(6, now, SERVER_TIMEOUT_MS);
               return;
            }

            // Шифт-клик меню не принял — переносим меч курсором, как руками.
            Slot sword = findPlayerSlot(player, handler, this::isSword, player.getInventory().getSelectedSlot());
            if (sword == null || !handler.getCursorStack().isEmpty()) {
               this.fail(player, "не удалось положить меч в меню продажи", now);
               return;
            }

            this.host.clickSlot(sword.id, 0, SlotActionType.PICKUP);
            this.advance(4, now, SERVER_TIMEOUT_MS);
         }
         case 4 -> {
            Slot target = containerOpen(player) && this.isSword(handler.getCursorStack()) ? this.sellTargetSlot(player, handler) : null;
            if (target == null) {
               this.fail(player, "не удалось положить меч в меню продажи", now);
               return;
            }

            this.host.clickSlot(target.id, 0, SlotActionType.PICKUP);
            this.advance(5, now, SERVER_TIMEOUT_MS);
         }
         case 5 -> {
            if (containerOpen(player) && this.findContainerSlot(player, handler, this::isSword) != null && handler.getCursorStack().isEmpty()) {
               this.advance(6, now, SERVER_TIMEOUT_MS);
            } else {
               this.fail(player, "меню продажи не принимает меч", now);
            }
         }
         case 6 -> {
            if (!containerOpen(player)) {
               this.fail(player, "меню продажи закрылось", now);
               return;
            }

            Slot confirm = this.findContainerSlot(player, handler, stack -> stack.isOf(Items.LIME_DYE));
            int slot = confirm != null ? confirm.id : SELL_CONFIRM_FALLBACK_SLOT;
            if (slot >= containerSize(player, handler)) {
               this.fail(player, "в меню продажи нет кнопки подтверждения", now);
               return;
            }

            this.host.clickSlot(slot, 0, SlotActionType.PICKUP);
            this.settleSince = 0L;
            this.advance(7, now, SERVER_TIMEOUT_MS);
         }
         case 7 -> {
            boolean placed = containerOpen(player) && this.findContainerSlot(player, handler, this::isSword) != null;
            if (!placed && handler.getCursorStack().isEmpty()) {
               if (!this.settled(now)) {
                  return;
               }

               if (this.countSwords(player) < this.countBefore) {
                  this.onSold();
                  this.advance(8, now, SERVER_TIMEOUT_MS);
               } else {
                  this.fail(player, "меч вернулся в инвентарь — продажа не прошла", now);
               }

               return;
            }

            this.settleSince = 0L;
            if (now > this.deadline) {
               this.fail(player, "продажа не подтвердилась", now);
            }
         }
         case 8 -> {
            if (containerOpen(player)) {
               this.host.closeContainer();
            }

            this.step = 0;
            this.attempts = 0;
            this.delay(now);
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void onSold() {
      this.succeed();
      this.sellFailures = 0;
      this.relistPending = true;
      this.debug("меч выставлен за " + this.price());
   }

   private void relist(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            this.watchSyncId = handler.syncId;
            this.host.sendCommand("ah");
            this.advance(1, now, SERVER_TIMEOUT_MS);
         }
         case 1 -> {
            if (this.newContainerReady(player)) {
               if (AH_ITEMS_SLOT >= containerSize(player, handler)) {
                  this.fail(player, "в меню /ah нет 47-го слота", now);
                  return;
               }

               this.watchSyncId = handler.syncId;
               this.watchHash = containerHash(player, handler);
               this.host.clickSlot(AH_ITEMS_SLOT, 0, SlotActionType.PICKUP);
               this.advance(2, now, SHORT_TIMEOUT_MS);
               return;
            }

            if (now > this.deadline) {
               this.fail(player, "/ah не открылся", now);
            }
         }
         case 2 -> {
            if (!containerOpen(player)) {
               this.fail(player, "меню /ah закрылось", now);
               return;
            }

            boolean switched = now - this.actionAt >= MENU_SWITCH_MS
               && handler.getCursorStack().isEmpty()
               && (handler.syncId != this.watchSyncId || containerHash(player, handler) != this.watchHash);
            if (!switched && now <= this.deadline) {
               return;
            }

            int size = containerSize(player, handler);
            if (size < 2) {
               this.fail(player, "меню /ah слишком маленькое", now);
               return;
            }

            // Предпоследний слот: 53-й по счёту в двойном сундуке.
            this.host.clickSlot(size - 2, 0, SlotActionType.PICKUP);
            this.advance(3, now, SERVER_TIMEOUT_MS);
         }
         case 3 -> {
            if (containerOpen(player)) {
               this.host.closeContainer();
            }

            this.relistPending = false;
            this.succeed();
            this.debug("мечи перевыставлены");
            this.enter(Phase.INSPECT, now);
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void craftPlanks(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            int button = this.plankButton(player);
            Slot log = largestPlayerSlot(player, handler, AutoSellEngine::isLog);
            if (button < 0 || log == null) {
               this.finishCraft(player, "доски", now);
               return;
            }

            this.countBefore = count(player, AutoSellEngine::isLog);
            this.host.clickSlot(log.id, button, SlotActionType.PICKUP);
            this.advance(1, now, SHORT_TIMEOUT_MS);
         }
         case 1 -> {
            if (!isLog(handler.getCursorStack())) {
               this.fail(player, "не удалось взять дерево", now);
               return;
            }

            this.host.clickSlot(GRID2_TOP_LEFT, 0, SlotActionType.PICKUP);
            this.advance(2, now, SHORT_TIMEOUT_MS);
         }
         case 2 -> {
            if (handler.getSlot(RESULT_SLOT).getStack().isEmpty()) {
               if (now > this.deadline) {
                  this.fail(player, "сервер не дал доски из дерева", now);
               }

               return;
            }

            this.host.clickSlot(RESULT_SLOT, 0, SlotActionType.QUICK_MOVE);
            this.advance(3, now, SHORT_TIMEOUT_MS);
         }
         case 3 -> {
            int left = count(player, AutoSellEngine::isLog) + stackCount(handler, GRID2_TOP_LEFT);
            if (left < this.countBefore) {
               this.progress = true;
               this.advance(0, now, SERVER_TIMEOUT_MS);
            } else if (now > this.deadline) {
               this.fail(player, "доски не скрафтились", now);
            }
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void craftSticks(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            this.stickPlan = this.stickMode(player);
            Slot planks = largestPlayerSlot(player, handler, AutoSellEngine::isPlank);
            if (this.stickPlan == 0 || planks == null) {
               this.finishCraft(player, "палки", now);
               return;
            }

            this.countBefore = count(player, AutoSellEngine::isPlank);
            this.host.clickSlot(planks.id, 0, SlotActionType.PICKUP);
            this.advance(1, now, SHORT_TIMEOUT_MS);
         }
         case 1 -> {
            if (!isPlank(handler.getCursorStack())) {
               this.fail(player, "не удалось взять доски", now);
               return;
            }

            this.host.clickSlot(GRID2_TOP_LEFT, 0, SlotActionType.PICKUP);
            this.advance(2, now, SHORT_TIMEOUT_MS);
         }
         case 2 -> {
            Slot second = this.stickPlan == 2 ? largestPlayerSlot(player, handler, AutoSellEngine::isPlank) : null;
            if (second != null) {
               this.host.clickSlot(second.id, 0, SlotActionType.PICKUP);
            } else if (stackCount(handler, GRID2_TOP_LEFT) >= 2) {
               // ПКМ по стаку забирает половину — вторая половина пойдёт в нижнюю клетку.
               this.host.clickSlot(GRID2_TOP_LEFT, 1, SlotActionType.PICKUP);
            } else {
               this.fail(player, "не хватает досок на палки", now);
               return;
            }

            this.advance(3, now, SHORT_TIMEOUT_MS);
         }
         case 3 -> {
            if (!isPlank(handler.getCursorStack())) {
               this.fail(player, "не удалось взять доски", now);
               return;
            }

            this.host.clickSlot(GRID2_BOTTOM_LEFT, 0, SlotActionType.PICKUP);
            this.advance(4, now, SHORT_TIMEOUT_MS);
         }
         case 4 -> {
            if (handler.getSlot(RESULT_SLOT).getStack().isEmpty()) {
               if (now > this.deadline) {
                  this.fail(player, "сервер не дал палки из досок", now);
               }

               return;
            }

            this.host.clickSlot(RESULT_SLOT, 0, SlotActionType.QUICK_MOVE);
            this.advance(5, now, SHORT_TIMEOUT_MS);
         }
         case 5 -> {
            int left = count(player, AutoSellEngine::isPlank) + stackCount(handler, GRID2_TOP_LEFT) + stackCount(handler, GRID2_BOTTOM_LEFT);
            if (left < this.countBefore) {
               this.progress = true;
               this.advance(0, now, SERVER_TIMEOUT_MS);
            } else if (now > this.deadline) {
               this.fail(player, "палки не скрафтились", now);
            }
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void craftSwords(PlayerEntity player, World world, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (handler instanceof CraftingScreenHandler) {
               // Сетка должна быть пустой: клик стаком по занятой клетке поменял бы предметы местами.
               if (!this.clearCraftingGrid(player, handler, now)) {
                  this.advance(3, now, SERVER_TIMEOUT_MS);
               }

               return;
            }

            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            this.table = findCraftingTable(player, world);
            if (this.table != null) {
               float[] look = lookAt(player.getEyePos(), Vec3d.ofCenter(this.table));
               this.aim.aimAt(look[0], look[1], player.getYaw(), player.getPitch(), AutoSellMotorAim.GIVE_UP_TICKS);
               this.step = 1;
               this.attempts = 0;
               return;
            }

            if (!this.craftCommandTried) {
               this.craftCommandTried = true;
               this.watchSyncId = handler.syncId;
               this.host.sendCommand("craft");
               this.advance(2, now, SERVER_TIMEOUT_MS);
               return;
            }

            this.fail(player, "нет верстака в радиусе 4 блоков (и /craft недоступен)", now);
         }
         case 1 -> {
            if (this.table == null || world == null || !world.getBlockState(this.table).isOf(Blocks.CRAFTING_TABLE)) {
               this.advance(0, now, SERVER_TIMEOUT_MS);
               return;
            }

            // Доворот закончился — пауза перед кликом, как перед любым действием с экраном.
            if (this.attempts++ == 0) {
               this.delay(now);
               return;
            }

            this.watchSyncId = handler.syncId;
            this.host.interactBlock(tableHit(player, world, this.table));
            this.advance(2, now, SERVER_TIMEOUT_MS);
         }
         case 2 -> {
            if (handler instanceof CraftingScreenHandler) {
               this.advance(0, now, SERVER_TIMEOUT_MS);
            } else if (now > this.deadline) {
               this.fail(player, "верстак не открылся", now);
            }
         }
         case 3 -> {
            if (!(handler instanceof CraftingScreenHandler)) {
               this.fail(player, "верстак закрылся", now);
               return;
            }

            Slot emeralds = largestPlayerSlot(player, handler, AutoSellEngine::isEmerald);
            if (emeralds == null || !handler.getCursorStack().isEmpty()) {
               this.advance(12, now, SERVER_TIMEOUT_MS);
               return;
            }

            this.host.clickSlot(emeralds.id, 0, SlotActionType.PICKUP);
            this.advance(4, now, SHORT_TIMEOUT_MS);
         }
         case 4 -> {
            if (!isEmerald(handler.getCursorStack())) {
               this.fail(player, "не удалось взять изумруды", now);
               return;
            }

            this.host.clickSlot(GRID3_TOP, 0, SlotActionType.PICKUP);
            this.advance(5, now, SHORT_TIMEOUT_MS);
         }
         case 5 -> {
            Slot more = largestPlayerSlot(player, handler, AutoSellEngine::isEmerald);
            if (more != null) {
               this.host.clickSlot(more.id, 0, SlotActionType.PICKUP);
            } else if (stackCount(handler, GRID3_TOP) >= 2) {
               this.host.clickSlot(GRID3_TOP, 1, SlotActionType.PICKUP);
            } else {
               this.advance(12, now, SERVER_TIMEOUT_MS);
               return;
            }

            this.advance(6, now, SHORT_TIMEOUT_MS);
         }
         case 6 -> {
            if (!isEmerald(handler.getCursorStack())) {
               this.fail(player, "не удалось взять изумруды", now);
               return;
            }

            this.host.clickSlot(GRID3_MIDDLE, 0, SlotActionType.PICKUP);
            this.advance(7, now, SHORT_TIMEOUT_MS);
         }
         case 7 -> {
            Slot sticks = largestPlayerSlot(player, handler, AutoSellEngine::isStick);
            if (sticks == null || !handler.getCursorStack().isEmpty()) {
               this.advance(12, now, SERVER_TIMEOUT_MS);
               return;
            }

            this.host.clickSlot(sticks.id, 0, SlotActionType.PICKUP);
            this.advance(8, now, SHORT_TIMEOUT_MS);
         }
         case 8 -> {
            if (!isStick(handler.getCursorStack())) {
               this.fail(player, "не удалось взять палки", now);
               return;
            }

            this.host.clickSlot(GRID3_BOTTOM, 0, SlotActionType.PICKUP);
            this.advance(9, now, SHORT_TIMEOUT_MS);
         }
         case 9 -> {
            if (!(handler instanceof CraftingScreenHandler)) {
               this.fail(player, "верстак закрылся", now);
               return;
            }

            ItemStack result = handler.getSlot(RESULT_SLOT).getStack();
            if (result.isEmpty()) {
               if (now > this.deadline) {
                  this.fail(player, "сервер не дал изумрудный меч по рецепту", now);
               }

               return;
            }

            this.learnedSword = signature(result);
            int expected = Math.min(Math.min(stackCount(handler, GRID3_TOP), stackCount(handler, GRID3_MIDDLE)), stackCount(handler, GRID3_BOTTOM));
            int room = freeSlots(player) - CRAFT_RESERVE;
            if (room <= 0) {
               this.advance(12, now, SERVER_TIMEOUT_MS);
            } else if (expected <= room) {
               this.host.clickSlot(RESULT_SLOT, 0, SlotActionType.QUICK_MOVE);
               this.progress = true;
               this.advance(12, now, SERVER_TIMEOUT_MS);
            } else {
               // Места на все мечи нет: крафтим поштучно, чтобы остатки было куда вернуть.
               this.host.clickSlot(RESULT_SLOT, 0, SlotActionType.PICKUP);
               this.advance(10, now, SHORT_TIMEOUT_MS);
            }
         }
         case 10 -> {
            Slot empty = handler instanceof CraftingScreenHandler && !handler.getCursorStack().isEmpty() ? emptyPlayerSlot(player, handler) : null;
            if (empty == null) {
               this.fail(player, "не удалось забрать меч из верстака", now);
               return;
            }

            this.host.clickSlot(empty.id, 0, SlotActionType.PICKUP);
            this.progress = true;
            this.advance(11, now, SHORT_TIMEOUT_MS);
         }
         case 11 -> {
            if (!(handler instanceof CraftingScreenHandler) || !handler.getCursorStack().isEmpty()) {
               this.fail(player, "меч остался на курсоре", now);
               return;
            }

            if (!handler.getSlot(RESULT_SLOT).getStack().isEmpty() && freeSlots(player) - CRAFT_RESERVE > 0) {
               this.host.clickSlot(RESULT_SLOT, 0, SlotActionType.PICKUP);
               this.advance(10, now, SHORT_TIMEOUT_MS);
            } else {
               this.advance(12, now, SERVER_TIMEOUT_MS);
            }
         }
         case 12 -> {
            if (handler instanceof CraftingScreenHandler && this.attempts++ < 12 && this.clearCraftingGrid(player, handler, now)) {
               return;
            }

            if (containerOpen(player)) {
               this.host.closeContainer();
            }

            this.craftCommandTried = false;
            this.finishCraft(player, "изумрудный меч", now);
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   /** Одно действие по уборке верстака (курсор, затем клетки сетки); false — убирать уже нечего. */
   private boolean clearCraftingGrid(PlayerEntity player, ScreenHandler handler, long now) {
      if (!handler.getCursorStack().isEmpty()) {
         Slot empty = emptyPlayerSlot(player, handler);
         if (empty != null) {
            this.host.clickSlot(empty.id, 0, SlotActionType.PICKUP);
            this.delay(now);
            return true;
         }
      }

      for (int slot = GRID3_FIRST; slot <= GRID3_LAST; slot++) {
         if (!handler.getSlot(slot).getStack().isEmpty()) {
            this.host.clickSlot(slot, 0, SlotActionType.QUICK_MOVE);
            this.delay(now);
            return true;
         }
      }

      return false;
   }

   private void finishCraft(PlayerEntity player, String what, long now) {
      if (this.progress) {
         this.succeed();
         this.enter(Phase.INSPECT, now);
      } else {
         this.fail(player, "не получилось скрафтить: " + what, now);
      }
   }

   private void buyEmeralds(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            this.countBefore = count(player, AutoSellEngine::isEmerald);
            this.watchSyncId = handler.syncId;
            this.host.sendCommand("shop");
            this.advance(1, now, SERVER_TIMEOUT_MS);
         }
         case 1 -> {
            if (this.newContainerReady(player)) {
               if (this.findContainerSlot(player, handler, AutoSellEngine::isEmerald) != null) {
                  this.advance(3, now, SERVER_TIMEOUT_MS);
                  return;
               }

               Slot gold = this.findContainerSlot(player, handler, stack -> stack.isOf(Items.GOLD_INGOT));
               if (gold != null) {
                  this.host.clickSlot(gold.id, 0, SlotActionType.PICKUP);
                  this.advance(2, now, SERVER_TIMEOUT_MS);
                  return;
               }
            }

            if (now > this.deadline) {
               this.fail(player, containerOpen(player) ? "в меню /shop нет золотого слитка" : "/shop не открылся", now);
            }
         }
         case 2 -> {
            if (containerOpen(player) && handler.getCursorStack().isEmpty() && this.findContainerSlot(player, handler, AutoSellEngine::isEmerald) != null) {
               this.advance(3, now, SERVER_TIMEOUT_MS);
            } else if (now > this.deadline) {
               this.fail(player, "в меню /shop не найден изумруд", now);
            }
         }
         case 3 -> {
            Slot emerald = containerOpen(player) ? this.findContainerSlot(player, handler, AutoSellEngine::isEmerald) : null;
            if (emerald == null) {
               this.fail(player, "изумруд пропал из меню /shop", now);
               return;
            }

            // Шифт + ПКМ по изумруду — покупка стака.
            this.host.clickSlot(emerald.id, 1, SlotActionType.QUICK_MOVE);
            this.settleSince = 0L;
            this.advance(4, now, SERVER_TIMEOUT_MS);
         }
         case 4 -> {
            if (count(player, AutoSellEngine::isEmerald) > this.countBefore && handler.getCursorStack().isEmpty()) {
               if (this.settled(now)) {
                  this.finishPurchase(player, "изумруды куплены", now);
               }

               return;
            }

            this.settleSince = 0L;
            if (now > this.deadline) {
               this.fail(player, "покупка изумрудов не прошла", now);
            }
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void buyWood(PlayerEntity player, long now) {
      ScreenHandler handler = player.currentScreenHandler;
      switch (this.step) {
         case 0 -> {
            if (this.needsCleanup(player)) {
               this.cleanup(player, now);
               return;
            }

            this.countBefore = count(player, AutoSellEngine::isLog);
            this.watchSyncId = handler.syncId;
            this.host.sendCommand("ah search дерево");
            this.advance(1, now, SERVER_TIMEOUT_MS);
         }
         case 1 -> {
            List<Listing> listings = this.newContainerReady(player) ? woodListings(player, handler) : List.of();
            if (listings.size() >= 2) {
               List<Listing> sorted = new ArrayList<>(listings);
               sorted.sort(Comparator.comparingLong(Listing::price).thenComparingInt(Listing::slot));
               int pick = AutoSellText.pickListingIndex(sorted.size(), this.random);
               Listing chosen = sorted.get(pick);
               this.debug("лотов дерева: " + sorted.size() + ", беру " + (pick + 1) + "-й по цене за " + chosen.price());
               this.watchSyncId = handler.syncId;
               this.watchHash = containerHash(player, handler);
               // Шифт + ЛКМ по лоту.
               this.host.clickSlot(chosen.slot(), 0, SlotActionType.QUICK_MOVE);
               this.settleSince = 0L;
               this.advance(2, now, SERVER_TIMEOUT_MS);
               return;
            }

            if (now > this.deadline) {
               this.fail(player, !containerOpen(player) ? "/ah search не открылся"
                  : listings.size() == 1 ? "на аукционе один лот дерева, а самый дешёвый не берём" : "на аукционе нет дерева с ценой", now);
            }
         }
         case 2 -> {
            if (now - this.actionAt < MENU_SWITCH_MS) {
               return;
            }

            if (count(player, AutoSellEngine::isLog) > this.countBefore && handler.getCursorStack().isEmpty()) {
               if (this.settled(now)) {
                  this.finishPurchase(player, "дерево куплено без подтверждения", now);
               }

               return;
            }

            this.settleSince = 0L;
            boolean confirmMenu = containerOpen(player)
               && handler.getCursorStack().isEmpty()
               && (handler.syncId != this.watchSyncId || containerHash(player, handler) != this.watchHash);
            if (confirmMenu && AH_CONFIRM_SLOT < containerSize(player, handler)) {
               this.host.clickSlot(AH_CONFIRM_SLOT, 0, SlotActionType.PICKUP);
               this.advance(3, now, SERVER_TIMEOUT_MS);
            } else if (now > this.deadline) {
               this.fail(player, "меню подтверждения покупки не открылось", now);
            }
         }
         case 3 -> {
            if (count(player, AutoSellEngine::isLog) > this.countBefore && handler.getCursorStack().isEmpty()) {
               if (this.settled(now)) {
                  this.finishPurchase(player, "дерево куплено", now);
               }

               return;
            }

            this.settleSince = 0L;
            if (now > this.deadline) {
               this.fail(player, "покупка дерева не прошла", now);
            }
         }
         default -> this.enter(Phase.INSPECT, now);
      }
   }

   private void finishPurchase(PlayerEntity player, String message, long now) {
      this.debug(message);
      this.succeed();
      if (containerOpen(player)) {
         this.host.closeContainer();
      }

      this.enter(Phase.INSPECT, now);
   }

   /** Кнопка ПКМ/ЛКМ для взятия дерева на доски: 0 — весь стак, 1 — половина, -1 — некуда складывать доски. */
   private int plankButton(PlayerEntity player) {
      Slot log = largestPlayerSlot(player, player.playerScreenHandler, AutoSellEngine::isLog);
      if (log == null) {
         return -1;
      }

      int logs = log.getStack().getCount();
      int free = freeSlots(player);
      if (free + 1 >= slotsFor(logs * 4)) {
         return 0;
      }

      return free >= slotsFor((logs + 1) / 2 * 4) ? 1 : -1;
   }

   /** 2 — два стака досок в сетку, 1 — один стак делится пополам, 0 — палкам не хватит места. */
   private int stickMode(PlayerEntity player) {
      List<Integer> stacks = new ArrayList<>();
      for (Slot slot : player.playerScreenHandler.slots) {
         if (isPlayerStorage(player, slot) && isPlank(slot.getStack())) {
            stacks.add(slot.getStack().getCount());
         }
      }

      stacks.sort(Comparator.reverseOrder());
      int free = freeSlots(player);
      if (stacks.size() >= 2) {
         int a = stacks.get(0);
         int b = stacks.get(1);
         if (free + 2 >= slotsFor(Math.min(a, b) * 4) + (a != b ? 1 : 0)) {
            return 2;
         }
      }

      if (!stacks.isEmpty()) {
         int total = stacks.get(0);
         if (total >= 2 && free + 1 >= slotsFor(total / 2 * 4) + total % 2) {
            return 1;
         }
      }

      return 0;
   }

   private static int slotsFor(int items) {
      return (items + 63) / 64;
   }

   private void enter(Phase next, long now) {
      if (next != this.phase) {
         this.debug("фаза: " + this.phase + " -> " + next);
      }

      this.phase = next;
      this.step = 0;
      this.attempts = 0;
      this.progress = false;
      this.deadline = now + SERVER_TIMEOUT_MS;
      this.delay(now);
   }

   /** Переход к следующему шагу после действия: пауза 500–1000 мс и новый срок ожидания ответа сервера. */
   private void advance(int next, long now, long timeout) {
      this.step = next;
      this.attempts = 0;
      this.actionAt = now;
      this.deadline = now + timeout;
      this.delay(now);
   }

   private void delay(long now) {
      this.nextActionAt = now + this.randomMs(ACTION_MIN_MS, ACTION_MAX_MS);
   }

   private long randomMs(long min, long max) {
      return min + this.random.nextInt((int)(max - min) + 1);
   }

   private float spread(float max) {
      return (this.random.nextFloat() * 2.0F - 1.0F) * max;
   }

   private float idleSpread() {
      float amount = IDLE_TURN_MIN_DEG + this.random.nextFloat() * (IDLE_TURN_MAX_DEG - IDLE_TURN_MIN_DEG);
      return this.random.nextBoolean() ? amount : -amount;
   }

   private boolean settled(long now) {
      if (this.settleSince == 0L) {
         this.settleSince = now;
         return false;
      }

      return now - this.settleSince >= SETTLE_MS;
   }

   private void succeed() {
      this.failures = 0;
   }

   private void fail(PlayerEntity player, String reason, long now) {
      this.failures++;
      long pause = Math.min(MAX_BACKOFF_MS, 2000L << Math.min(this.failures - 1, 5));
      String message = reason;
      if (this.phase == Phase.SELL && ++this.sellFailures >= SELL_FAILS_BEFORE_PAUSE) {
         this.sellFailures = 0;
         this.sellRetryAt = now + SELL_RETRY_MS;
         this.relistPending = true;
         message = message + "; продажа на паузе " + SELL_RETRY_MS / 1000L + " с";
      }

      // Сломанное перевыставление не должно стопорить крафт и закупку — попробуем после следующих продаж.
      if (this.phase == Phase.RELIST) {
         this.relistPending = false;
      }

      this.status(message + " — повтор через " + pause / 1000L + " с");
      this.aim.cancel();
      if (this.needsCleanup(player)) {
         this.host.closeContainer();
      }

      this.phase = Phase.INSPECT;
      this.step = 0;
      this.attempts = 0;
      this.nextActionAt = now + pause;
   }

   private boolean needsCleanup(PlayerEntity player) {
      return containerOpen(player) || !player.currentScreenHandler.getCursorStack().isEmpty() || gridHasItems(player);
   }

   /** Закрытие меню: сервер сам вернёт курсор и сетку 2x2 в инвентарь. */
   private void cleanup(PlayerEntity player, long now) {
      this.host.closeContainer();
      if (++this.cleanupAttempts >= CLEANUP_ATTEMPTS) {
         // Синхронизация так и не пришла — это остаток клиентского предсказания, серверу он не нужен.
         this.cleanupAttempts = 0;
         player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
         for (int slot = GRID2_FIRST; slot <= GRID2_LAST; slot++) {
            player.playerScreenHandler.getSlot(slot).setStackNoCallbacks(ItemStack.EMPTY);
         }
      }

      this.delay(now);
   }

   private boolean newContainerReady(PlayerEntity player) {
      ScreenHandler handler = player.currentScreenHandler;
      return containerOpen(player) && handler.syncId != this.watchSyncId && containerHasItems(player, handler);
   }

   private long price() {
      return this.settings.priceValue();
   }

   private void debug(String text) {
      if (this.settings.debugEnabled()) {
         this.host.message(text);
      }
   }

   /** Важные сообщения видны всегда, но одно и то же не чаще раза в 30 с. */
   private void status(String text) {
      long now = this.host.now();
      if (!text.equals(this.lastStatus) || now - this.lastStatusAt >= 30000L) {
         this.lastStatus = text;
         this.lastStatusAt = now;
         this.host.message(text);
      }
   }

   private int countSwords(PlayerEntity player) {
      return count(player, this::isSword);
   }

   private int findSwordIndex(PlayerEntity player) {
      PlayerInventory inventory = player.getInventory();
      int selected = inventory.getSelectedSlot();
      if (this.isSword(inventory.getStack(selected))) {
         return selected;
      }

      for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
         if (this.isSword(inventory.getStack(i))) {
            return i;
         }
      }

      return -1;
   }

   private boolean isSword(ItemStack stack) {
      if (stack.isEmpty()) {
         return false;
      }

      if (this.learnedSword != null && this.learnedSword.equals(signature(stack))) {
         return true;
      }

      return stack.isIn(ItemTags.SWORDS) && AutoSellText.isEmeraldSwordName(stack.getName().getString());
   }

   private Slot findContainerSlot(PlayerEntity player, ScreenHandler handler, Predicate<ItemStack> test) {
      int size = containerSize(player, handler);
      for (int i = 0; i < size; i++) {
         Slot slot = handler.getSlot(i);
         if (!slot.getStack().isEmpty() && test.test(slot.getStack())) {
            return slot;
         }
      }

      return null;
   }

   private Slot sellTargetSlot(PlayerEntity player, ScreenHandler handler) {
      int size = containerSize(player, handler);
      if (SELL_ITEM_SLOT < size && handler.getSlot(SELL_ITEM_SLOT).getStack().isEmpty()) {
         return handler.getSlot(SELL_ITEM_SLOT);
      }

      for (int i = 0; i < size; i++) {
         if (i != SELL_CONFIRM_FALLBACK_SLOT && handler.getSlot(i).getStack().isEmpty()) {
            return handler.getSlot(i);
         }
      }

      return null;
   }

   private static boolean containerOpen(PlayerEntity player) {
      return player.currentScreenHandler != player.playerScreenHandler;
   }

   /** Слоты самого меню идут до первого слота инвентаря игрока — так для сундуков, верстака и любых GUI. */
   private static int containerSize(PlayerEntity player, ScreenHandler handler) {
      if (handler == player.playerScreenHandler) {
         return 0;
      }

      int size = 0;
      for (Slot slot : handler.slots) {
         if (slot.inventory == player.getInventory()) {
            break;
         }

         size++;
      }

      return size;
   }

   private static boolean containerHasItems(PlayerEntity player, ScreenHandler handler) {
      int size = containerSize(player, handler);
      for (int i = 0; i < size; i++) {
         if (!handler.getSlot(i).getStack().isEmpty()) {
            return true;
         }
      }

      return false;
   }

   private static int containerHash(PlayerEntity player, ScreenHandler handler) {
      int size = containerSize(player, handler);
      int hash = size;
      for (int i = 0; i < size; i++) {
         ItemStack stack = handler.getSlot(i).getStack();
         hash = hash * 31 + (stack.isEmpty() ? 0 : Item.getRawId(stack.getItem()) * 97 + stack.getCount());
         hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getName().getString().hashCode());
      }

      return hash;
   }

   /** Основной инвентарь и хотбар (без брони и второй руки). */
   private static boolean isPlayerStorage(PlayerEntity player, Slot slot) {
      return slot.inventory == player.getInventory() && slot.getIndex() < PlayerInventory.MAIN_SIZE;
   }

   private static Slot findPlayerSlot(PlayerEntity player, ScreenHandler handler, Predicate<ItemStack> test, int preferredIndex) {
      Slot found = null;
      for (Slot slot : handler.slots) {
         if (isPlayerStorage(player, slot) && !slot.getStack().isEmpty() && test.test(slot.getStack())) {
            if (slot.getIndex() == preferredIndex) {
               return slot;
            }

            if (found == null) {
               found = slot;
            }
         }
      }

      return found;
   }

   private static Slot largestPlayerSlot(PlayerEntity player, ScreenHandler handler, Predicate<ItemStack> test) {
      Slot best = null;
      for (Slot slot : handler.slots) {
         if (isPlayerStorage(player, slot) && !slot.getStack().isEmpty() && test.test(slot.getStack())
            && (best == null || slot.getStack().getCount() > best.getStack().getCount())) {
            best = slot;
         }
      }

      return best;
   }

   private static Slot emptyPlayerSlot(PlayerEntity player, ScreenHandler handler) {
      for (Slot slot : handler.slots) {
         if (isPlayerStorage(player, slot) && slot.getStack().isEmpty()) {
            return slot;
         }
      }

      return null;
   }

   private static int hotbarTarget(PlayerEntity player) {
      PlayerInventory inventory = player.getInventory();
      for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
         if (inventory.getStack(i).isEmpty()) {
            return i;
         }
      }

      return inventory.getSelectedSlot();
   }

   private static int count(PlayerEntity player, Predicate<ItemStack> test) {
      PlayerInventory inventory = player.getInventory();
      int total = 0;
      for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
         ItemStack stack = inventory.getStack(i);
         if (!stack.isEmpty() && test.test(stack)) {
            total += stack.getCount();
         }
      }

      return total;
   }

   private static int freeSlots(PlayerEntity player) {
      PlayerInventory inventory = player.getInventory();
      int free = 0;
      for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
         if (inventory.getStack(i).isEmpty()) {
            free++;
         }
      }

      return free;
   }

   private static boolean gridHasItems(PlayerEntity player) {
      for (int slot = GRID2_FIRST; slot <= GRID2_LAST; slot++) {
         if (!player.playerScreenHandler.getSlot(slot).getStack().isEmpty()) {
            return true;
         }
      }

      return false;
   }

   private static int stackCount(ScreenHandler handler, int slot) {
      return handler.getSlot(slot).getStack().getCount();
   }

   private static boolean isLog(ItemStack stack) {
      return stack.isIn(ItemTags.LOGS);
   }

   private static boolean isPlank(ItemStack stack) {
      return stack.isIn(ItemTags.PLANKS);
   }

   private static boolean isEmerald(ItemStack stack) {
      return stack.isOf(Items.EMERALD);
   }

   private static boolean isStick(ItemStack stack) {
      return stack.isOf(Items.STICK);
   }

   private static String signature(ItemStack stack) {
      return Registries.ITEM.getId(stack.getItem()) + "|" + AutoSellText.plain(stack.getName().getString());
   }

   private static List<Listing> woodListings(PlayerEntity player, ScreenHandler handler) {
      List<Listing> listings = new ArrayList<>();
      int size = containerSize(player, handler);
      for (int i = 0; i < size; i++) {
         ItemStack stack = handler.getSlot(i).getStack();
         if (!stack.isEmpty() && isLog(stack)) {
            long price = AutoSellText.parsePrice(loreLines(stack));
            if (price > 0L) {
               listings.add(new Listing(i, price));
            }
         }
      }

      return listings;
   }

   private static List<String> loreLines(ItemStack stack) {
      LoreComponent lore = stack.get(DataComponentTypes.LORE);
      if (lore == null) {
         return List.of();
      }

      List<String> lines = new ArrayList<>(lore.lines().size());
      for (Text line : lore.lines()) {
         lines.add(line.getString());
      }

      return lines;
   }

   private static BlockPos findCraftingTable(PlayerEntity player, World world) {
      if (world == null) {
         return null;
      }

      Vec3d eye = player.getEyePos();
      BlockPos origin = BlockPos.ofFloored(eye);
      BlockPos best = null;
      double bestDistance = TABLE_REACH * TABLE_REACH;
      for (BlockPos pos : BlockPos.iterate(origin.add(-5, -5, -5), origin.add(5, 5, 5))) {
         if (world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance <= bestDistance) {
               bestDistance = distance;
               best = pos.toImmutable();
            }
         }
      }

      return best;
   }

   /** Точка клика по верстаку: куда реально смотрит игрок, иначе центр ближайшей к нему грани. */
   private static BlockHitResult tableHit(PlayerEntity player, World world, BlockPos table) {
      Vec3d eye = player.getEyePos();
      Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(TABLE_REACH + 1.0));
      BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
      if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(table)) {
         return hit;
      }

      Vec3d center = Vec3d.ofCenter(table);
      Direction side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
      Vec3d point = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
      return new BlockHitResult(point, side, table, false);
   }

   private static float[] lookAt(Vec3d eye, Vec3d target) {
      double dx = target.x - eye.x;
      double dy = target.y - eye.y;
      double dz = target.z - eye.z;
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
      return new float[]{yaw, pitch};
   }
}
