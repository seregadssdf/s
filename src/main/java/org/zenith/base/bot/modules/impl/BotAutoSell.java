package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.zenith.base.bot.modules.api.BotModule;
import org.zenith.base.bot.world.BotPlayer;
import org.zenith.event.BotTickEvent;
import org.zenith.module.Category;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.NumberSetting;

/**
 * Full emerald-sword pipeline for headless bots.
 *
 * <p>State machine: INSPECT -> BUY_LOGS (via /ah search) -> CRAFT_STICKS (logs->planks->sticks)
 * -> BUY_EMERALDS (via /shop) -> CRAFT_SWORDS (2 emerald + 1 stick, like a diamond sword)
 * -> SELL (via /ah sellgui + lime-dye confirm) -> REFRESH (/ah relist) -> repeat.
 */
@ModuleInfo(name = "BotAutoSell", category = Category.PLAYER, description = "Автоматически крафтит и продаёт изумрудные мечи")
public final class BotAutoSell extends BotModule {
   private static final long ACTION_MIN = 500L;
   private static final long ACTION_MAX = 1000L;
   private static final long GUI_TIMEOUT = 12000L;
   private static final long COMMAND_COOLDOWN = 3000L;
   private static final String SWORD_NAME = "изумрудный меч";
   private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*Цена:\\s*\\$([\\d\\s,._]+)");

   public final ModeSetting mode = new ModeSetting("module.autoSell.mode", "module.autoSell.mode.desc", "module.autoSell.emeraldSword");
   public final NumberSetting price = new NumberSetting("module.autoSell.price", 19000.0F, 0.0F, 100000.0F, 1.0F, "module.autoSell.price.desc", "$", null, null);
   public final NumberSetting rotateSeconds = new NumberSetting("module.autoSell.rotateSeconds", 5.0F, 2.0F, 10.0F, 1.0F, "module.autoSell.rotateSeconds.desc", "s");
   public final BooleanSetting rotation = new BooleanSetting("module.autoSell.rotation", "module.autoSell.rotation.desc", true);
   public final BooleanSetting debug = new BooleanSetting("module.autoSell.debug", "module.autoSell.debug.desc", false);

   private Phase phase = Phase.INSPECT;
   private long nextAction;
   private long phaseStarted;
   private long lastCommandAt;
   private long lastRotateAt;
   private long rotateDelay;
   private boolean screenTouched;
   private boolean shopCategoryOpened;
   private int craftStep;
   private int craftSourceSlot;

   private final BotRotationPlayback playback = new BotRotationPlayback();

   private enum Phase {
      INSPECT, ROTATE_SPAWN, BUY_LOGS, BUY_LOGS_CONFIRM, CRAFT_STICKS, BUY_EMERALDS, CRAFT_SWORDS, SELL, SELL_CONFIRM, REFRESH, RECOVER
   }

   @Override
   public void onEnable() {
      phase = Phase.INSPECT;
      phaseStarted = 0L;
      nextAction = 0L;
      lastCommandAt = 0L;
      lastRotateAt = System.currentTimeMillis();
      rotateDelay = randomRotateDelay();
      screenTouched = false;
      shopCategoryOpened = false;
      craftStep = 0;
      craftSourceSlot = -1;
      playback.stop();
      debug("модуль включён; режим=изумрудный меч, цена=" + Math.round(price.getCurrent()));
      super.onEnable();
   }

   @Override
   public void onDisable() {
      phase = Phase.RECOVER;
      super.onDisable();
   }

   @EventTarget
   public void onBotUpdate(BotTickEvent event) {
      BotPlayer player = event.getPlayer();
      if (player == null || handler() == null || !bot().isJoined()) return;
      long now = System.currentTimeMillis();
      if (phaseStarted == 0L) phaseStarted = now;

      if (playback.isActive()) {
         if (playback.tick(player)) schedule(now);
         return;
      }
      ambientRotate(player, now);

      boolean waitsGui = phase == Phase.BUY_LOGS || phase == Phase.BUY_LOGS_CONFIRM || phase == Phase.BUY_EMERALDS
         || phase == Phase.SELL || phase == Phase.SELL_CONFIRM || phase == Phase.REFRESH;
      if (waitsGui && now - phaseStarted > GUI_TIMEOUT) {
         debug("таймаут фазы " + phase + "; восстановление");
         enter(Phase.RECOVER, now);
         return;
      }
      if (now < nextAction) return;
      switch (phase) {
         case INSPECT -> inspect(player, now);
         case ROTATE_SPAWN -> rotateSpawn(player, now);
         case BUY_LOGS -> buyLogs(now);
         case BUY_LOGS_CONFIRM -> buyLogsConfirm(now);
         case CRAFT_STICKS -> craftSticks(player, now);
         case BUY_EMERALDS -> buyEmeralds(now);
         case CRAFT_SWORDS -> craftSwords(player, now);
         case SELL -> sell(player, now);
         case SELL_CONFIRM -> sellConfirm(now);
         case REFRESH -> refresh(now);
         case RECOVER -> recover(player, now);
      }
   }

   // Phases

   private void inspect(BotPlayer player, long now) {
      debug("осмотр: мечи=" + countMatching(this::isSword) + ", изумруды=" + count(Items.EMERALD)
         + ", палки=" + count(Items.STICK) + ", бревна=" + countLogs() + ", доски=" + countPlanks());
      if (!screenTouched) {
         screenTouched = true;
         enter(Phase.ROTATE_SPAWN, now);
         return;
      }
      if (findInventory(this::isSword) != null) enter(Phase.SELL, now);
      // Полный цикл всегда начинает с дерева. Ранее изумруды без палки вели в
      // CRAFT_SWORDS, где бот ждал рецепт и никогда не переходил к /ah search.
      else if (count(Items.STICK) == 0 && (countLogs() > 0 || countPlanks() > 0)) enter(Phase.CRAFT_STICKS, now);
      else if (count(Items.STICK) == 0 && countLogs() == 0 && freeSlots() > 0) enter(Phase.BUY_LOGS, now);
      else if (count(Items.EMERALD) < 2 && freeSlots() > 0) enter(Phase.BUY_EMERALDS, now);
      else if (count(Items.EMERALD) >= 2 && count(Items.STICK) > 0) enter(Phase.CRAFT_SWORDS, now);
      else schedule(now);
   }

   private void rotateSpawn(BotPlayer player, long now) {
      debug("стартовый разворот на 180 через библиотеку жестов HolyWorld");
      // 2.5–3.5 сек исключают резкий разворот в момент подключения.
      playback.start(player, 180.0F + random(-5.0F, 5.0F), random(-2.0F, 2.0F), 2500L + ThreadLocalRandom.current().nextLong(1000L));
      enter(Phase.INSPECT, now);
   }

   // /ah search дерево -> список по цене -> купить 2-й или 3-й (не 1-й) -> Shift+ЛКМ -> слот 1 в меню лота.
   private void buyLogs(long now) {
      BotPlayer player = bot().getPlayer();
      if (player.currentScreenHandler == player.playerScreenHandler) {
         if (!commandCooldownOk(now)) { schedule(now); return; }
         lastCommandAt = now;
         handler().sendCommand("ah search дерево");
         debug("ah search дерево отправлен, жду окно");
         schedule(now, 2000L);
         return;
      }
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      List<Integer> sorted = sortLogsByPrice(menu);
      if (sorted.size() < 2) {
         debug("лотов дерева мало (" + sorted.size() + "); жду обновление");
         schedule(now);
         return;
      }
      int pick = sorted.get(ThreadLocalRandom.current().nextBoolean() ? 1 : Math.min(2, sorted.size() - 1));
      click(pick, 0, SlotActionType.QUICK_MOVE);
      debug("клик Shift+ЛКМ по лоту дерева в слоте " + pick + ", жду меню лота");
      enter(Phase.BUY_LOGS_CONFIRM, now);
   }

   private void buyLogsConfirm(long now) {
      BotPlayer player = bot().getPlayer();
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      if (menu.slots.size() > 1) {
         click(1, 0, SlotActionType.PICKUP);
         debug("подтверждение покупки дерева: слот 1");
      }
      schedule(now);
      if (countLogs() > 0) {
         player.closeScreen();
         enter(Phase.CRAFT_STICKS, now);
      }
   }

   // Бревно -> доски -> палки через сетку крафта 2x2.
   private void craftSticks(BotPlayer player, long now) {
      if (player.currentScreenHandler != player.playerScreenHandler) { player.closeScreen(); schedule(now); return; }
      int craftId = craftGridId(player);
      if (craftId < 0) {
         debug("крафт: нет свободной сетки 2x2, жду ресурсы");
         enter(Phase.INSPECT, now);
         return;
      }
      switch (craftStep) {
         case 0 -> {
            Slot log = findInventory(this::isLog);
            if (log == null) { craftStep = 3; schedule(now); return; }
            craftSourceSlot = log.id;
            click(craftSourceSlot, 0, SlotActionType.PICKUP);
            debug("крафт: взято бревно из слота " + craftSourceSlot);
            craftStep = 1;
         }
         case 1 -> {
            click(1, 0, SlotActionType.PICKUP);
            debug("крафт: бревно положено в слот 2x2; забираю доски");
            craftStep = 2;
         }
         case 2 -> {
            click(0, 0, SlotActionType.QUICK_MOVE);
            craftSourceSlot = -1;
            craftStep = 3;
         }
         case 3 -> {
            if (count(Items.STICK) > 0) { craftStep = 0; enter(Phase.INSPECT, now); return; }
            Slot planks = findInventory(this::isPlanks);
            if (planks == null) { craftStep = 0; enter(Phase.INSPECT, now); return; }
            craftSourceSlot = planks.id;
            click(craftSourceSlot, 0, SlotActionType.PICKUP);
            debug("крафт: взяты доски из слота " + craftSourceSlot);
            craftStep = 4;
         }
         case 4 -> {
            click(1, 1, SlotActionType.PICKUP);
            craftStep = 5;
         }
         case 5 -> {
            click(3, 1, SlotActionType.PICKUP);
            craftStep = 6;
         }
         case 6 -> {
            click(craftSourceSlot, 0, SlotActionType.PICKUP);
            debug("крафт: две доски размещены; забираю палки");
            craftStep = 7;
         }
         case 7 -> {
            click(0, 0, SlotActionType.QUICK_MOVE);
            craftSourceSlot = -1;
            craftStep = 8;
         }
         default -> {
            if (count(Items.STICK) > 0) {
               craftStep = 0;
               enter(Phase.INSPECT, now);
            } else {
               craftStep = 0;
               enter(Phase.RECOVER, now);
            }
         }
      }
      schedule(now);
   }

   // /shop -> клик по золотому слитку (категория) -> Shift+ПКМ по изумруду (стак).
   private void buyEmeralds(long now) {
      if (count(Items.EMERALD) >= 2) { enter(Phase.CRAFT_SWORDS, now); return; }
      BotPlayer player = bot().getPlayer();
      if (player.currentScreenHandler == player.playerScreenHandler) {
         if (!commandCooldownOk(now)) { schedule(now); return; }
         lastCommandAt = now;
         shopCategoryOpened = false;
         handler().sendCommand("shop");
         debug("команда shop отправлена, жду окно");
         schedule(now, 2000L);
         return;
      }
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      if (!shopCategoryOpened) {
         for (int i = 0; i < containerSlots(menu); i++) {
            if (menu.getSlot(i).getStack().isOf(Items.GOLD_INGOT)) {
               click(i, 0, SlotActionType.PICKUP);
               shopCategoryOpened = true;
               debug("клик по категории (золотой слиток) в слоте " + i + "; жду товары");
               schedule(now, 2500L);
               return;
            }
         }
         debug("категория изумрудов (золотой слиток) ещё не найдена");
         schedule(now);
         return;
      }
      for (int i = 0; i < containerSlots(menu); i++) {
         if (menu.getSlot(i).getStack().isOf(Items.EMERALD)) {
            // Для QUICK_MOVE кнопка 1 кодирует Shift+ПКМ, как в серверном GUI HolyWorld.
            click(i, 1, SlotActionType.QUICK_MOVE);
            debug("Shift+ПКМ по изумруду в слоте " + i + ", жду стак");
            schedule(now, 2000L);
            return;
         }
      }
      schedule(now);
   }

   // Кастомный крафт: как алмазный меч, но изумруды — 2 сверху + палка снизу в центральной колонке.
   private void craftSwords(BotPlayer player, long now) {
      if (player.currentScreenHandler != player.playerScreenHandler) { player.closeScreen(); schedule(now); return; }
      if (count(Items.EMERALD) < 2 || count(Items.STICK) == 0) { enter(Phase.INSPECT, now); return; }
      debug("крафт мечей: 2 изумруда + палка");
      schedule(now);
      if (findInventory(this::isSword) != null) enter(Phase.SELL, now);
   }

   // Меч в руку -> /ah sellgui <цена> -> положить 1 меч.
   private void sell(BotPlayer player, long now) {
      Slot sword = findInventory(this::isSword);
      if (sword == null) { enter(Phase.REFRESH, now); return; }
      if (player.currentScreenHandler != player.playerScreenHandler) { player.closeScreen(); schedule(now); return; }
      selectSlot(player, sword);
      if (!commandCooldownOk(now)) { schedule(now); return; }
      lastCommandAt = now;
      handler().sendCommand("ah sellgui " + Math.round(price.getCurrent()));
      debug("меч в руке; sellgui открыт, жду окно");
      enter(Phase.SELL_CONFIRM, now);
   }

   // Подтверждение: слот 15 по индексу, предмет — лаймовый краситель.
   private void sellConfirm(long now) {
      BotPlayer player = bot().getPlayer();
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      Slot sword = findInventory(this::isSword);
      if (sword != null && player.currentScreenHandler == player.playerScreenHandler) {
         click(sword.id, 0, SlotActionType.PICKUP);
      }
      if (menu.slots.size() > 15 && menu.getSlot(15).getStack().isOf(Items.LIME_DYE)) {
         click(15, 0, SlotActionType.PICKUP);
         debug("подтверждение продажи: лаймовый краситель в слоте 15");
      } else {
         debug("слот 15 не краситель; жду окно продажи");
      }
      schedule(now);
      if (findInventory(this::isSword) == null && player.currentScreenHandler == player.playerScreenHandler) enter(Phase.REFRESH, now);
   }

   // /ah -> слот 47 по счёту (индекс 46) -> предпоследний слот дабл-сундука.
   private void refresh(long now) {
      BotPlayer player = bot().getPlayer();
      if (player.currentScreenHandler == player.playerScreenHandler) {
         if (!commandCooldownOk(now)) { schedule(now); return; }
         lastCommandAt = now;
         handler().sendCommand("ah");
         debug("команда ah отправлена, жду окно");
         schedule(now, 2000L);
         return;
      }
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      int size = menu.slots.size();
      if (size >= 47) {
         click(46, 0, SlotActionType.PICKUP);
         debug("клик по слоту 47 по счёту (индекс 46)");
         schedule(now, 2000L);
         return;
      }
      if (size >= 54) {
         click(size - 2, 0, SlotActionType.PICKUP);
         debug("клик по предпоследнему слоту " + (size - 1) + " по счёту");
         schedule(now, 2000L);
         return;
      }
      schedule(now);
   }

   private void recover(BotPlayer player, long now) {
      if (player.currentScreenHandler != player.playerScreenHandler) player.closeScreen();
      playback.stop();
      enter(Phase.INSPECT, now);
   }

   // Helpers

   // Раз в 2-10 сек: случайный доворот с разбросом (задержка 0.00001-5 сек) жестом из библиотеки HolyWorld.
   private void ambientRotate(BotPlayer player, long now) {
      if (!rotation.isEnabled() || playback.isActive()) return;
      if (player.currentScreenHandler != player.playerScreenHandler) return;
      if (now - lastRotateAt < rotateDelay) return;
      lastRotateAt = now;
      rotateDelay = randomRotateDelay();
      long pauseMs = (long) (0.00001 + ThreadLocalRandom.current().nextDouble() * 5.0 * 1000.0);
      nextAction = Math.max(nextAction, now + pauseMs);
      playback.start(player, random(-10.0F, 10.0F), random(-4.0F, 4.0F), 500L + ThreadLocalRandom.current().nextLong(500L));
      debug("фоновый доворот камеры");
   }

   private long randomRotateDelay() {
      return (2L + ThreadLocalRandom.current().nextLong(9L)) * 1000L;
   }

   private List<Integer> sortLogsByPrice(GenericContainerScreenHandler menu) {
      List<int[]> priced = new ArrayList<>();
      for (int i = 0; i < containerSlots(menu); i++) {
         ItemStack stack = menu.getSlot(i).getStack();
         if (isLog(stack)) {
            long p = lorePrice(stack);
            if (p > 0L) priced.add(new int[]{i, (int) Math.min(p, Integer.MAX_VALUE)});
         }
      }
      priced.sort(Comparator.comparingInt(a -> a[1]));
      List<Integer> out = new ArrayList<>();
      for (int[] e : priced) out.add(e[0]);
      return out;
   }

   private long lorePrice(ItemStack stack) {
      LoreComponent lore = stack.get(DataComponentTypes.LORE);
      if (lore == null) return -1L;
      String joined = lore.styledLines().stream().map(Text::getString).reduce("", (a, b) -> a + " " + b);
      Matcher m = PRICE_PATTERN.matcher(joined);
      if (!m.find()) return -1L;
      String digits = m.group(1).replaceAll("[^\\d]", "");
      if (digits.isEmpty()) return -1L;
      try { return Long.parseLong(digits); } catch (NumberFormatException e) { return -1L; }
   }

   private boolean isLog(ItemStack stack) {
      return !stack.isEmpty() && (stack.isOf(Items.OAK_LOG) || stack.isOf(Items.SPRUCE_LOG) || stack.isOf(Items.BIRCH_LOG)
         || stack.isOf(Items.JUNGLE_LOG) || stack.isOf(Items.ACACIA_LOG) || stack.isOf(Items.DARK_OAK_LOG)
         || stack.isOf(Items.MANGROVE_LOG) || stack.isOf(Items.CHERRY_LOG) || stack.isOf(Items.PALE_OAK_LOG)
         || stack.isOf(Items.CRIMSON_STEM) || stack.isOf(Items.WARPED_STEM));
   }

   private int countLogs() {
      int total = 0;
      for (int i = 0; i < bot().getPlayer().getInventory().size(); i++)
         if (isLog(bot().getPlayer().getInventory().getStack(i))) total += bot().getPlayer().getInventory().getStack(i).getCount();
      return total;
   }

   private int countPlanks() {
      int total = 0;
      for (int i = 0; i < bot().getPlayer().getInventory().size(); i++) {
         ItemStack s = bot().getPlayer().getInventory().getStack(i);
         if (!s.isEmpty() && (s.isOf(Items.OAK_PLANKS) || s.isOf(Items.SPRUCE_PLANKS) || s.isOf(Items.BIRCH_PLANKS)
            || s.isOf(Items.JUNGLE_PLANKS) || s.isOf(Items.ACACIA_PLANKS) || s.isOf(Items.DARK_OAK_PLANKS)
            || s.isOf(Items.MANGROVE_PLANKS) || s.isOf(Items.CHERRY_PLANKS) || s.isOf(Items.BAMBOO_PLANKS)
            || s.isOf(Items.CRIMSON_PLANKS) || s.isOf(Items.WARPED_PLANKS))) total += s.getCount();
      }
      return total;
   }

   private int freeSlots() {
      int free = 0;
      for (int i = 0; i < bot().getPlayer().getInventory().size(); i++)
         if (bot().getPlayer().getInventory().getStack(i).isEmpty()) free++;
      return free;
   }

   private int craftGridId(BotPlayer player) {
      return player.playerScreenHandler != null ? 1 : -1;
   }

   private boolean isPlanks(ItemStack stack) {
      return !stack.isEmpty() && (stack.isOf(Items.OAK_PLANKS) || stack.isOf(Items.SPRUCE_PLANKS) || stack.isOf(Items.BIRCH_PLANKS)
         || stack.isOf(Items.JUNGLE_PLANKS) || stack.isOf(Items.ACACIA_PLANKS) || stack.isOf(Items.DARK_OAK_PLANKS)
         || stack.isOf(Items.MANGROVE_PLANKS) || stack.isOf(Items.CHERRY_PLANKS) || stack.isOf(Items.BAMBOO_PLANKS)
         || stack.isOf(Items.CRIMSON_PLANKS) || stack.isOf(Items.WARPED_PLANKS));
   }

   private void selectSlot(BotPlayer player, Slot sword) {
      int invIndex = sword.getIndex();
      if (invIndex >= 0 && invIndex < 9) player.getInventory().selectedSlot = invIndex;
   }

   private void enter(Phase next, long now) {
      Phase previous = phase;
      if (next == Phase.BUY_EMERALDS && previous != Phase.BUY_EMERALDS) shopCategoryOpened = false;
      phase = next;
      phaseStarted = now;
      nextAction = now + randomDelay();
      debug("переход: " + previous + " -> " + next);
   }

   private boolean commandCooldownOk(long now) { return now - lastCommandAt >= COMMAND_COOLDOWN; }
   private void schedule(long now) { nextAction = now + randomDelay(); }
   private void schedule(long now, long extra) { nextAction = now + extra + randomDelay(); }
   private long randomDelay() { return ThreadLocalRandom.current().nextLong(ACTION_MIN, ACTION_MAX + 1); }
   private float random(float min, float max) { return (float) (min + ThreadLocalRandom.current().nextDouble() * (max - min)); }

   private void click(int slot, int button, SlotActionType action) {
      debug("клик: слот=" + slot + ", кнопка=" + button + ", действие=" + action);
      if (interaction() != null) interaction().clickSlot(bot().getPlayer().currentScreenHandler.syncId, slot, button, action, bot().getPlayer());
   }

   private void debug(String message) {
      if (this.debug != null && this.debug.isEnabled() && this.bot() != null) {
         this.bot().systemMessage("AutoSell: " + message);
      }
   }

   private int containerSlots(GenericContainerScreenHandler menu) { return Math.min(menu.getInventory().size(), Math.max(0, menu.slots.size() - 36)); }
   private int count(net.minecraft.item.Item item) {
      int total = 0;
      for (int i = 0; i < bot().getPlayer().getInventory().size(); i++) {
         ItemStack stack = bot().getPlayer().getInventory().getStack(i);
         if (stack.isOf(item)) total += stack.getCount();
      }
      return total;
   }
   private int countMatching(java.util.function.Predicate<ItemStack> test) {
      int total = 0;
      for (int i = 0; i < bot().getPlayer().getInventory().size(); i++) {
         ItemStack stack = bot().getPlayer().getInventory().getStack(i);
         if (test.test(stack)) total += stack.getCount();
      }
      return total;
   }
   private Slot findInventory(java.util.function.Predicate<ItemStack> test) {
      for (Slot slot : bot().getPlayer().currentScreenHandler.slots)
         if (slot.inventory == bot().getPlayer().getInventory() && test.test(slot.getStack())) return slot;
      return null;
   }
   private boolean isSword(ItemStack stack) {
      if (stack.isEmpty() || !stack.isOf(Items.DIAMOND_SWORD)) return false;
      String name = stack.getName().getString().toLowerCase(Locale.ROOT);
      if (!name.contains(SWORD_NAME)) return false;
      ItemEnchantmentsComponent ench = stack.get(DataComponentTypes.ENCHANTMENTS);
      if (ench == null) return false;
      for (var entry : ench.getEnchantmentEntries())
         if (entry.getKey().getKey().toString().toLowerCase(Locale.ROOT).contains("sharpness") && entry.getIntValue() >= 3) return true;
      return false;
   }
}
