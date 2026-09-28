package org.zenith.module.player;

import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.zenith.event.EventTick;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.NumberSetting;

/** Same sale pipeline as BotAutoSell, executed by the local player for testing. */
@ModuleInfo(name = "AutoSell", category = Category.PLAYER, description = "Полный цикл покупки, крафта и продажи изумрудных мечей")
public final class AutoSell extends Module {
   public static final AutoSell autoSell = new AutoSell();
   private static final long ACTION_MIN = 500L;
   private static final long ACTION_MAX = 1000L;
   private static final long GUI_TIMEOUT = 12000L;
   private static final long COMMAND_COOLDOWN = 3000L;
   private static final long MAX_LOG_STACK_PRICE = 250_000L;
   public final NumberSetting price = new NumberSetting("module.autoSell.price", 19000.0F, 0.0F, 100000.0F, 1.0F, "module.autoSell.price.desc", "$", null, null);

   private Phase phase;
   private long nextAction;
   private long phaseStarted;
   private long lastCommandAt;
   private boolean shopCategoryOpened;
   private int stickStep;
   private int sourceSlot;
   private int swordStep;
   private BlockPos craftingTable;
   private boolean craftingTableClicked;
   private boolean sellCommandSent;
   private String lastMessage;
   private long lastMessageAt;
   private int turnTotal;
   private int turnTicksLeft;
   private float turnStartYaw;
   private float turnStartPitch;
   private float turnTargetYaw;
   private float turnTargetPitch;
   private long nextSwayAt;
   private float swayYawLeft;
   private float swayPitchLeft;
   private int refreshStep;

   private enum Phase {
      TURN, INSPECT, BUY_LOGS, BUY_LOGS_CONFIRM, CRAFT_STICKS, BUY_EMERALDS, CRAFT_TABLE, CRAFT_SWORDS, SELL, SELL_CONFIRM, REFRESH, RECOVER
   }

   @Override
   public void onEnable() {
      phase = Phase.INSPECT;
      nextAction = 0L;
      phaseStarted = 0L;
      lastCommandAt = 0L;
      shopCategoryOpened = false;
      stickStep = 0;
      sourceSlot = -1;
      swordStep = 0;
      craftingTable = null;
      craftingTableClicked = false;
      sellCommandSent = false;
      lastMessage = null;
      lastMessageAt = 0L;
      refreshStep = 0;
      swayYawLeft = 0.0F;
      swayPitchLeft = 0.0F;
      nextSwayAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(2000L, 10001L);
      MinecraftClient startClient = MinecraftClient.getInstance();
      if (startClient.player != null) {
         startTurn(startClient);
         message("модуль включён: разворот и запуск цикла AutoSell игрока");
      } else {
         phase = Phase.INSPECT;
         message("модуль включён: использую цикл AutoSell игрока");
      }
      super.onEnable();
   }

   @Override
   public void onDisable() {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) client.player.closeHandledScreen();
      super.onDisable();
   }

   @EventTarget
   public void onTick(EventTick event) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player == null || client.world == null || client.interactionManager == null || client.player.networkHandler == null) return;
      long now = System.currentTimeMillis();
      if (phaseStarted == 0L) phaseStarted = now;
      boolean guiPhase = phase == Phase.BUY_LOGS || phase == Phase.BUY_LOGS_CONFIRM || phase == Phase.BUY_EMERALDS
         || phase == Phase.SELL || phase == Phase.SELL_CONFIRM || phase == Phase.REFRESH || phase == Phase.CRAFT_TABLE || phase == Phase.CRAFT_SWORDS;
      if (guiPhase && now - phaseStarted > GUI_TIMEOUT) {
         message("таймаут; закрываю окно и начинаю проверку заново");
         client.player.closeHandledScreen();
         enter(Phase.RECOVER, now);
         return;
      }
      if (phase == Phase.TURN) { updateTurn(client); return; }
      updateSway(client, now);
      if (now < nextAction) return;
      switch (phase) {
         case INSPECT -> inspect(client, now);
         case BUY_LOGS -> buyLogs(client, now);
         case BUY_LOGS_CONFIRM -> buyLogsConfirm(client, now);
         case CRAFT_STICKS -> craftSticks(client, now);
         case BUY_EMERALDS -> buyEmeralds(client, now);
         case CRAFT_TABLE -> openCraftingTable(client, now);
         case CRAFT_SWORDS -> craftSword(client, now);
         case SELL -> sell(client, now);
         case SELL_CONFIRM -> sellConfirm(client, now);
         case REFRESH -> refresh(client, now);
         case RECOVER -> { client.player.closeHandledScreen(); enter(Phase.INSPECT, now); }
      }
   }

   private void inspect(MinecraftClient client, long now) {
      message("осмотр: мечи=" + count(client, this::isSword) + ", изумруды=" + count(client, stack -> stack.isOf(Items.EMERALD)) + ", палки=" + count(client, stack -> stack.isOf(Items.STICK)));
      if (findInventory(client, this::isSword) != null) enter(Phase.SELL, now);
      else if (count(client, stack -> stack.isOf(Items.STICK)) == 0 && (count(client, this::isLog) > 0 || count(client, this::isPlanks) > 0)) enter(Phase.CRAFT_STICKS, now);
      else if (count(client, stack -> stack.isOf(Items.STICK)) == 0 && count(client, this::isLog) == 0) enter(Phase.BUY_LOGS, now);
      else if (count(client, stack -> stack.isOf(Items.EMERALD)) < 2) enter(Phase.BUY_EMERALDS, now);
      else enter(Phase.CRAFT_TABLE, now);
   }

   private void buyLogs(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler == client.player.playerScreenHandler) {
         if (!commandReady(now)) { schedule(now); return; }
         client.player.networkHandler.sendChatCommand("ah search дерево");
         lastCommandAt = now;
         message("отправлена команда /ah search дерево");
         schedule(now, 2000L);
         return;
      }
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      List<Integer> logs = sortAuctionLotsByPrice(menu);
      if (logs.size() < 2) { schedule(now); return; }
      int slot = logs.get(ThreadLocalRandom.current().nextBoolean() ? 1 : Math.min(2, logs.size() - 1));
      click(client, slot, 0, SlotActionType.QUICK_MOVE);
      message("Shift+ЛКМ по " + (slot + 1) + "-му слоту списка дерева");
      enter(Phase.BUY_LOGS_CONFIRM, now);
   }

   private void buyLogsConfirm(MinecraftClient client, long now) {
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler)) { schedule(now); return; }
      click(client, 1, 0, SlotActionType.PICKUP);
      message("подтверждение покупки дерева: слот 1");
      if (count(client, this::isLog) > 0) { client.player.closeHandledScreen(); enter(Phase.CRAFT_STICKS, now); } else schedule(now, 1200L);
   }

   private void craftSticks(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler != client.player.playerScreenHandler) { client.player.closeHandledScreen(); schedule(now); return; }
      switch (stickStep) {
         case 0 -> pickFromInventory(client, this::isLog, "взято бревно для досок", 1);
         case 1 -> { click(client, 1, 0, SlotActionType.PICKUP); message("бревно в сетке 2x2"); stickStep = 2; }
         case 2 -> { click(client, 0, 0, SlotActionType.QUICK_MOVE); message("забираю доски"); stickStep = 3; }
         case 3 -> pickFromInventory(client, this::isPlanks, "взяты доски для палок", 4);
         case 4 -> { click(client, 1, 1, SlotActionType.PICKUP); stickStep = 5; }
         case 5 -> { click(client, 3, 1, SlotActionType.PICKUP); stickStep = 6; }
         case 6 -> { click(client, sourceSlot, 0, SlotActionType.PICKUP); message("доски в сетке 2x2"); stickStep = 7; }
         case 7 -> { click(client, 0, 0, SlotActionType.QUICK_MOVE); message("забираю палки"); stickStep = 8; }
         default -> {
            if (count(client, stack -> stack.isOf(Items.STICK)) > 0) { stickStep = 0; enter(Phase.INSPECT, now); return; }
            message("крафт палок не дал результата, повторяю проверку");
            stickStep = 0;
            enter(Phase.INSPECT, now);
            return;
         }
      }
      schedule(now);
   }

   private void buyEmeralds(MinecraftClient client, long now) {
      if (count(client, stack -> stack.isOf(Items.EMERALD)) >= 2) { enter(Phase.INSPECT, now); return; }
      if (client.player.currentScreenHandler == client.player.playerScreenHandler) {
         if (!commandReady(now)) { schedule(now); return; }
         shopCategoryOpened = false;
         client.player.networkHandler.sendChatCommand("shop");
         lastCommandAt = now;
         message("отправлена команда /shop");
         schedule(now, 2000L);
         return;
      }
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      int size = containerSlots(menu);
      if (!shopCategoryOpened) {
         for (int i = 0; i < size; i++) if (menu.getSlot(i).getStack().isOf(Items.GOLD_INGOT)) {
            click(client, i, 0, SlotActionType.PICKUP);
            shopCategoryOpened = true;
            message("нажат золотой слиток: жду товары");
            schedule(now, 2500L);
            return;
         }
         message("золотой слиток не найден, жду окно магазина");
         schedule(now);
         return;
      }
      for (int i = 0; i < size; i++) if (menu.getSlot(i).getStack().isOf(Items.EMERALD)) {
         click(client, i, 0, SlotActionType.QUICK_MOVE);
         message("Shift+ЛКМ по изумруду, жду покупку стака");
         schedule(now, 2000L);
         return;
      }
      message("изумруд в категории пока не найден");
      schedule(now);
   }

   private void openCraftingTable(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler instanceof CraftingScreenHandler) { enter(Phase.CRAFT_SWORDS, now); return; }
      if (client.player.currentScreenHandler != client.player.playerScreenHandler) { client.player.closeHandledScreen(); schedule(now); return; }
      if (craftingTable == null || !client.world.getBlockState(craftingTable).isOf(Blocks.CRAFTING_TABLE)) {
         craftingTable = findCraftingTable(client);
         craftingTableClicked = false;
         if (craftingTable == null) { message("верстак не найден в радиусе 4.5 блоков"); schedule(now, 2000L); return; }
         message("верстак найден: " + craftingTable.toShortString());
      }
      client.player.setYaw((float)(Math.toDegrees(Math.atan2(craftingTable.getZ() + 0.5 - client.player.getZ(), craftingTable.getX() + 0.5 - client.player.getX())) - 90.0));
      if (!craftingTableClicked) {
         client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(craftingTable.toCenterPos(), Direction.UP, craftingTable, false));
         craftingTableClicked = true;
         message("повернулся и нажал ПКМ по верстаку; жду сетку 3x3");
         schedule(now, 1500L);
      } else {
         craftingTableClicked = false;
         message("окно верстака не открылось, пробую ещё раз");
         schedule(now);
      }
   }

   private void craftSword(MinecraftClient client, long now) {
      if (!(client.player.currentScreenHandler instanceof CraftingScreenHandler)) { enter(Phase.CRAFT_TABLE, now); return; }
      switch (swordStep) {
         case 0 -> pickFromInventory(client, stack -> stack.isOf(Items.EMERALD), "взяты изумруды для меча", 1);
         case 1 -> { click(client, 2, 1, SlotActionType.PICKUP); message("1-й изумруд в верхнем центре"); swordStep = 2; }
         case 2 -> { click(client, 5, 1, SlotActionType.PICKUP); message("2-й изумруд в центре"); swordStep = 3; }
         case 3 -> { click(client, sourceSlot, 0, SlotActionType.PICKUP); swordStep = 4; }
         case 4 -> pickFromInventory(client, stack -> stack.isOf(Items.STICK), "взята палка для меча", 5);
         case 5 -> { click(client, 8, 1, SlotActionType.PICKUP); message("палка в нижнем центре"); swordStep = 6; }
         case 6 -> { click(client, sourceSlot, 0, SlotActionType.PICKUP); swordStep = 7; }
         case 7 -> { click(client, 0, 0, SlotActionType.QUICK_MOVE); message("забираю результат крафта"); swordStep = 8; }
         default -> {
            if (findInventory(client, this::isSword) != null) { swordStep = 0; client.player.closeHandledScreen(); enter(Phase.SELL, now); return; }
            message("сервер не выдал изумрудный меч; возвращаюсь к проверке");
            swordStep = 0;
            enter(Phase.INSPECT, now);
            return;
         }
      }
      schedule(now);
   }

   private void sell(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler != client.player.playerScreenHandler) { client.player.closeHandledScreen(); schedule(now); return; }
      Slot sword = findInventory(client, this::isSword);
      if (sword == null) { enter(Phase.REFRESH, now); return; }
      if (!sellCommandSent) {
         click(client, sword.id, client.player.getInventory().selectedSlot, SlotActionType.SWAP);
         sellCommandSent = true;
         message("меч перенесён в руку");
         schedule(now);
         return;
      }
      if (!commandReady(now)) { schedule(now); return; }
      client.player.networkHandler.sendChatCommand("ah sellgui " + Math.round(price.getCurrent()));
      lastCommandAt = now;
      sellCommandSent = false;
      message("отправлена команда /ah sellgui " + Math.round(price.getCurrent()));
      enter(Phase.SELL_CONFIRM, now);
   }

   private void sellConfirm(MinecraftClient client, long now) {
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      int target = -1;
      for (int i = 0; i < containerSlots(menu); i++) if (menu.getSlot(i).getStack().isOf(Items.LIME_DYE)) { target = i; break; }
      if (target < 0 && menu.slots.size() > 15) target = 15;
      if (target < 0) { message("слот подтверждения не найден, жду окно"); schedule(now); return; }
      click(client, target, 0, SlotActionType.PICKUP);
      message("подтверждение продажи: слот " + (target + 1) + (menu.getSlot(target).getStack().isOf(Items.LIME_DYE) ? " (лаймовый краситель)" : " (лаймовый краситель не найден)"));
      enter(Phase.INSPECT, now);
   }

   private void refresh(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler == client.player.playerScreenHandler) {
         if (!commandReady(now)) { schedule(now); return; }
         client.player.networkHandler.sendChatCommand("ah");
         lastCommandAt = now;
         message("отправлена команда /ah для обновления лота");
         schedule(now, 2000L);
         return;
      }
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      int container = containerSlots(menu);
      if (refreshStep == 0) {
         if (container < 47) { enter(Phase.INSPECT, now); return; }
         click(client, 46, 0, SlotActionType.PICKUP);
         message("клик 47-го по счёту слота /ah");
         refreshStep = 1;
         schedule(now, 1500L);
         return;
      }
      click(client, container - 2, 0, SlotActionType.PICKUP);
      message("клик предпоследнего слота дабл-сундука");
      enter(Phase.INSPECT, now);
   }

   private void pickFromInventory(MinecraftClient client, java.util.function.Predicate<ItemStack> test, String text, int next) {
      Slot slot = findInventory(client, test);
      if (slot == null) { message("нет предмета: " + text); enter(Phase.INSPECT, System.currentTimeMillis()); return; }
      sourceSlot = slot.id;
      click(client, sourceSlot, 0, SlotActionType.PICKUP);
      message(text + " из слота " + sourceSlot);
      if (phase == Phase.CRAFT_STICKS) stickStep = next; else swordStep = next;
   }

   private BlockPos findCraftingTable(MinecraftClient client) {
      BlockPos origin = client.player.getBlockPos();
      BlockPos nearest = null;
      double best = 4.5 * 4.5;
      for (BlockPos pos : BlockPos.iterate(origin.add(-5, -3, -5), origin.add(5, 3, 5))) {
         if (!client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) continue;
         double distance = client.player.getEyePos().squaredDistanceTo(pos.toCenterPos());
         if (distance <= best) { nearest = pos.toImmutable(); best = distance; }
      }
      return nearest;
   }

   private List<Integer> sortAuctionLotsByPrice(GenericContainerScreenHandler menu) {
      List<int[]> prices = new ArrayList<>();
      // Последняя строка из 9 слотов — серверные кнопки /ah, это не лоты.
      int lotSlots = Math.max(0, containerSlots(menu) - 9);
      int enough = 0, withPrice = 0, affordable = 0;
      ItemStack sample = ItemStack.EMPTY;
      for (int i = 0; i < lotSlots; i++) {
         ItemStack stack = menu.getSlot(i).getStack();
         if (stack.isEmpty() || stack.getCount() < 32) continue;
         enough++;
         long price = lorePrice(stack);
         if (price < 0 && sample.isEmpty()) sample = stack;
         if (price > 0) withPrice++;
         if (price > 0 && price * 64L <= MAX_LOG_STACK_PRICE * stack.getCount()) {
            affordable++;
            prices.add(new int[]{i, (int)Math.min(price, Integer.MAX_VALUE)});
         }
      }
      message("аукцион: слотов=" + containerSlots(menu) + ", лотов 32+=" + enough + ", с ценой=" + withPrice + ", до лимита=" + affordable);
      if (withPrice == 0 && !sample.isEmpty()) message("пример lore лота: " + sampleLore(sample));
      prices.sort(Comparator.comparingInt(value -> value[1]));
      return prices.stream().map(value -> value[0]).toList();
   }

   private long lorePrice(ItemStack stack) {
      LoreComponent lore = stack.get(DataComponentTypes.LORE);
      if (lore == null) return -1;
      for (Text line : lore.lines()) {
         long price = priceFromText(line.getString());
         if (price >= 0) return price;
      }
      return -1;
   }

   /** Сканируем после слова «Цена» до первой цифры: оформление вокруг цены нестандартное. */
   private long priceFromText(String raw) {
      String text = normalize(raw);
      int at = text.indexOf("цена");
      if (at < 0) return -1;
      StringBuilder digits = new StringBuilder();
      for (int i = at + 4; i < text.length(); i++) {
         char c = text.charAt(i);
         if (Character.isDigit(c)) { digits.append(Character.getNumericValue(c)); continue; }
         boolean separator = digits.length() > 0
            && (c == ',' || c == '.' || c == ' ' || c == '_' || c == '\'' || c == '\u00a0' || c == '\u202f');
         if (separator) continue;
         if (digits.length() > 0) break;
      }
      if (digits.isEmpty()) return -1;
      try { return Long.parseLong(digits.toString()); } catch (NumberFormatException ignored) { return -1; }
   }

   /** Убирает невидимые символы оформления, ломающие поиск слова и числа. */
   private String normalize(String value) {
      StringBuilder result = new StringBuilder(value.length());
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         int type = Character.getType(c);
         if (type == Character.FORMAT || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
         result.append(c);
      }
      return result.toString().toLowerCase(Locale.ROOT);
   }

   private String sampleLore(ItemStack stack) {
      LoreComponent lore = stack.get(DataComponentTypes.LORE);
      if (lore == null) return "компонент lore отсутствует";
      List<String> lines = lore.lines().stream().map(Text::getString).filter(s -> !s.isBlank()).toList();
      String joined = lines.stream().limit(6).reduce("", (a, b) -> a + " | " + b);
      String target = lines.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("ена")).findFirst()
         .orElse(lines.size() > 1 ? lines.get(1) : "");
      StringBuilder codes = new StringBuilder();
      for (int i = 0; i < target.length() && codes.length() < 280; i++) codes.append(String.format("U+%04X ", (int)target.charAt(i)));
      return joined.substring(0, Math.min(160, joined.length())) + " ‖ коды строки цены: " + codes;
   }

   private int count(MinecraftClient client, java.util.function.Predicate<ItemStack> test) {
      int total = 0;
      for (int i = 0; i < client.player.getInventory().size(); i++) if (test.test(client.player.getInventory().getStack(i))) total += client.player.getInventory().getStack(i).getCount();
      return total;
   }

   private Slot findInventory(MinecraftClient client, java.util.function.Predicate<ItemStack> test) {
      for (Slot slot : client.player.currentScreenHandler.slots) if (slot.inventory == client.player.getInventory() && test.test(slot.getStack())) return slot;
      return null;
   }

   private boolean isLog(ItemStack stack) {
      return stack.isOf(Items.OAK_LOG) || stack.isOf(Items.SPRUCE_LOG) || stack.isOf(Items.BIRCH_LOG) || stack.isOf(Items.JUNGLE_LOG) || stack.isOf(Items.ACACIA_LOG) || stack.isOf(Items.DARK_OAK_LOG) || stack.isOf(Items.MANGROVE_LOG) || stack.isOf(Items.CHERRY_LOG) || stack.isOf(Items.PALE_OAK_LOG) || stack.isOf(Items.CRIMSON_STEM) || stack.isOf(Items.WARPED_STEM);
   }

   private boolean isPlanks(ItemStack stack) {
      Item item = stack.getItem();
      return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS || item == Items.BAMBOO_PLANKS || item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS;
   }

   private boolean isSword(ItemStack stack) {
      if (!stack.isOf(Items.DIAMOND_SWORD) || !stack.getName().getString().toLowerCase(Locale.ROOT).contains("изумрудный меч")) return false;
      ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
      if (enchantments == null) return false;
      for (var entry : enchantments.getEnchantmentEntries()) if (entry.getKey().getKey().toString().contains("sharpness") && entry.getIntValue() >= 3) return true;
      return false;
   }

   private int containerSlots(GenericContainerScreenHandler menu) { return Math.min(menu.getInventory().size(), Math.max(0, menu.slots.size() - 36)); }
   private void click(MinecraftClient client, int slot, int button, SlotActionType action) { client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slot, button, action, client.player); }
   private boolean commandReady(long now) { return now - lastCommandAt >= COMMAND_COOLDOWN; }
   private void schedule(long now) { nextAction = now + ThreadLocalRandom.current().nextLong(ACTION_MIN, ACTION_MAX + 1); }
   private void schedule(long now, long extra) { nextAction = now + extra + ThreadLocalRandom.current().nextLong(ACTION_MIN, ACTION_MAX + 1); }
   private void enter(Phase next, long now) { phase = next; phaseStarted = now; if (next == Phase.REFRESH) refreshStep = 0; schedule(now); message("переход на стадию " + next); }

   private void message(String text) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player == null) return;
      long now = System.currentTimeMillis();
      if (text.equals(lastMessage) && now - lastMessageAt < 1800L) return;
      lastMessage = text;
      lastMessageAt = now;
      client.player.sendMessage(Text.literal("§eAutoSell [" + phase + "]: §f" + text), false);
   }

   /** Разворот камеры на ~180 при включении: прямое плавное ведение, видно игроку и серверу. */
   private void startTurn(MinecraftClient client) {
      turnStartYaw = client.player.getYaw();
      turnStartPitch = client.player.getPitch();
      turnTargetYaw = turnStartYaw + 180.0F + (ThreadLocalRandom.current().nextFloat() * 10.0F - 5.0F);
      turnTargetPitch = Math.max(-90.0F, Math.min(90.0F, turnStartPitch + (ThreadLocalRandom.current().nextFloat() * 4.0F - 2.0F)));
      turnTotal = 50 + ThreadLocalRandom.current().nextInt(25);
      turnTicksLeft = turnTotal;
      phase = Phase.TURN;
   }

   private void updateTurn(MinecraftClient client) {
      if (turnTicksLeft <= 0) { enter(Phase.INSPECT, System.currentTimeMillis()); return; }
      turnTicksLeft--;
      float progress = 1.0F - turnTicksLeft / (float)Math.max(1, turnTotal);
      float eased = progress * progress * (3.0F - 2.0F * progress);
      client.player.setYaw(turnStartYaw + (turnTargetYaw - turnStartYaw) * eased);
      client.player.setPitch(turnStartPitch + (turnTargetPitch - turnStartPitch) * eased);
      if (turnTicksLeft == 0) enter(Phase.INSPECT, System.currentTimeMillis());
   }

   /** Лёгкое шевеление камеры раз в 2–10 секунд, амплитуда 0.00001–5 градусов. */
   private void updateSway(MinecraftClient client, long now) {
      if (now >= nextSwayAt) {
         nextSwayAt = now + ThreadLocalRandom.current().nextLong(2000L, 10001L);
         swayYawLeft = randomOffset();
         swayPitchLeft = randomOffset() * 0.4F;
      }
      if (Math.abs(swayYawLeft) > 0.00001F) {
         float step = swayYawLeft * 0.2F;
         client.player.setYaw(client.player.getYaw() + step);
         swayYawLeft -= step;
      }
      if (Math.abs(swayPitchLeft) > 0.00001F) {
         float step = swayPitchLeft * 0.2F;
         client.player.setPitch(Math.max(-90.0F, Math.min(90.0F, client.player.getPitch() + step)));
         swayPitchLeft -= step;
      }
   }

   private float randomOffset() {
      double magnitude = 0.00001 + ThreadLocalRandom.current().nextDouble() * (5.0 - 0.00001);
      return (float)(ThreadLocalRandom.current().nextBoolean() ? magnitude : -magnitude);
   }
}
