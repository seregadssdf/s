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
import net.minecraft.registry.Registries;
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
   private int craftStep;
   private int craftFails;
   private int lastPlanks;
   private int lastSticks;
   private int sourceSlot;
   private int sellStep;
   private int sellConfirmStep;
   private int swordStep;
   private BlockPos craftingTable;
   private boolean craftingTableClicked;
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
   private int confirmStep;

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
      craftStep = 0;
      craftFails = 0;
      lastPlanks = -1;
      lastSticks = -1;
      sourceSlot = -1;
      sellStep = 0;
      sellConfirmStep = 0;
      swordStep = 0;
      craftingTable = null;
      craftingTableClicked = false;
      lastMessage = null;
      lastMessageAt = 0L;
      refreshStep = 0;
      confirmStep = 0;
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
      message("осмотр: мечи=" + count(client, this::isSword) + ", изумруды=" + count(client, stack -> stack.isOf(Items.EMERALD))
         + ", брёвна=" + count(client, this::isLog) + ", доски=" + count(client, this::isPlanks)
         + ", палки=" + count(client, stack -> stack.isOf(Items.STICK)));
      route(client, now);
   }

   private void route(MinecraftClient client, long now) {
      if (findInventory(client, this::isSword) != null) { enter(Phase.SELL, now); return; }
      int sticks = count(client, stack -> stack.isOf(Items.STICK));
      int logs = count(client, this::isLog);
      int planks = count(client, this::isPlanks);
      if (logs > 0 || planks >= 2) { enter(Phase.CRAFT_STICKS, now); return; }
      if (sticks == 0) { enter(Phase.BUY_LOGS, now); return; }
      if (count(client, stack -> stack.isOf(Items.EMERALD)) < 2) { enter(Phase.BUY_EMERALDS, now); return; }
      enter(Phase.CRAFT_TABLE, now);
   }

   private void buyLogs(MinecraftClient client, long now) {
      if (count(client, this::isLog) > 0 || count(client, this::isPlanks) >= 2) { enter(Phase.CRAFT_STICKS, now); return; }
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
      confirmStep = 0;
      enter(Phase.BUY_LOGS_CONFIRM, now);
   }

   private void buyLogsConfirm(MinecraftClient client, long now) {
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      if (count(client, this::isLog) > 0 || count(client, this::isPlanks) > 0) { client.player.closeHandledScreen(); enter(Phase.CRAFT_STICKS, now); return; }
      switch (confirmStep) {
         case 0 -> {
            message("меню подтверждения, слоты 1-3: " + slotNames(menu, 0, 3));
            click(client, 0, 0, SlotActionType.PICKUP);
            message("клик по 1-му слоту меню подтверждения");
            confirmStep = 1;
            schedule(now, 1200L);
         }
         case 1 -> {
            click(client, 1, 0, SlotActionType.PICKUP);
            message("лот не купился, пробую 2-й слот");
            confirmStep = 2;
            schedule(now, 1200L);
         }
         default -> {
            message("покупка не подтвердилась, начинаю заново");
            client.player.closeHandledScreen();
            enter(Phase.INSPECT, now);
         }
      }
   }

   private void craftSticks(MinecraftClient client, long now) {
      if (client.player.currentScreenHandler != client.player.playerScreenHandler) { client.player.closeHandledScreen(); schedule(now); return; }
      switch (craftStep) {
         case 0 -> { // брёвна -> доски: весь стак в сетку, Shift по результату
            Slot log = findInventory(client, this::isLog);
            if (log == null) { craftStep = 10; lastSticks = -1; schedule(now); return; }
            sourceSlot = log.id;
            click(client, sourceSlot, 0, SlotActionType.PICKUP);
            craftStep = 1;
         }
         case 1 -> { click(client, 1, 0, SlotActionType.PICKUP); craftStep = 2; }
         case 2 -> { click(client, 0, 0, SlotActionType.QUICK_MOVE); message("перекрафчиваю брёвна в доски"); craftStep = 3; }
         case 3 -> { click(client, 1, 0, SlotActionType.PICKUP); craftStep = 4; }
         case 4 -> {
            depositCursor(client);
            int planks = count(client, this::isPlanks);
            if (planks == lastPlanks) craftFails++; else { craftFails = 0; lastPlanks = planks; }
            if (craftFails >= 2) { message("крафт досок не двигается (инвентарь полон?); перехожу к палкам"); craftFails = 0; craftStep = 10; lastSticks = -1; }
            else craftStep = 0;
         }
         case 10 -> { // доски -> палки: стак вверх, полстака вниз, Shift по результату
            if (count(client, this::isPlanks) < 2) { craftStep = 0; lastPlanks = -1; message("всё дерево перекрафчено в палки"); enter(Phase.INSPECT, now); return; }
            Slot planks = findInventory(client, this::isPlanks);
            sourceSlot = planks.id;
            click(client, sourceSlot, 0, SlotActionType.PICKUP);
            craftStep = 11;
         }
         case 11 -> { click(client, 1, 0, SlotActionType.PICKUP); craftStep = 12; }
         case 12 -> { click(client, 1, 1, SlotActionType.PICKUP); craftStep = 13; }
         case 13 -> { click(client, 3, 0, SlotActionType.PICKUP); craftStep = 14; }
         case 14 -> { click(client, 0, 0, SlotActionType.QUICK_MOVE); message("перекрафчиваю доски в палки"); craftStep = 15; }
         case 15 -> { click(client, 1, 0, SlotActionType.PICKUP); craftStep = 16; }
         case 16 -> { depositCursor(client); craftStep = 17; }
         case 17 -> { click(client, 3, 0, SlotActionType.PICKUP); craftStep = 18; }
         case 18 -> {
            depositCursor(client);
            int sticks = count(client, stack -> stack.isOf(Items.STICK));
            if (sticks == lastSticks) craftFails++; else { craftFails = 0; lastSticks = sticks; }
            if (craftFails >= 2) { message("крафт палок не двигается (инвентарь полон?); возвращаюсь к проверке"); craftFails = 0; craftStep = 0; enter(Phase.INSPECT, now); return; }
            craftStep = 10;
         }
         default -> { craftStep = 0; enter(Phase.INSPECT, now); return; }
      }
      schedule(now);
   }

   /** Возвращает предмет с курсора в инвентарь: в такой же стак или в первую пустую ячейку. */
   private void depositCursor(MinecraftClient client) {
      ItemStack cursor = client.player.currentScreenHandler.getCursorStack();
      if (cursor.isEmpty()) return;
      Slot merge = null, empty = null;
      for (Slot slot : client.player.currentScreenHandler.slots) {
         if (slot.inventory != client.player.getInventory()) continue;
         ItemStack stack = slot.getStack();
         if (stack.isEmpty()) { if (empty == null) empty = slot; continue; }
         if (merge == null && stack.getItem() == cursor.getItem() && stack.getCount() < stack.getMaxCount()) merge = slot;
      }
      Slot target = merge != null ? merge : empty;
      if (target != null) click(client, target.id, 0, SlotActionType.PICKUP);
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
         click(client, i, 1, SlotActionType.QUICK_MOVE);
         message("Shift+ПКМ по изумруду, жду покупку стака");
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
      switch (sellStep) {
         case 0 -> {
            if (client.player.currentScreenHandler != client.player.playerScreenHandler) { client.player.closeHandledScreen(); schedule(now); return; }
            if (isSword(client.player.getMainHandStack())) { sellStep = 1; message("меч уже в руке"); schedule(now); return; }
            Slot sword = findInventory(client, this::isSword);
            if (sword == null) { message("меч не распознан в инвентаре: " + inventorySwords(client)); enter(Phase.INSPECT, now); return; }
            click(client, sword.id, client.player.getInventory().selectedSlot, SlotActionType.SWAP);
            sellStep = 1;
            message("меч перенесён в руку из слота " + (sword.id + 1));
            schedule(now);
         }
         case 1 -> {
            if (!isSword(client.player.getMainHandStack())) { sellStep = 0; message("меча нет в руке после переноса, повторяю"); schedule(now); return; }
            if (!commandReady(now)) { schedule(now); return; }
            client.player.networkHandler.sendChatCommand("ah sellgui " + Math.round(price.getCurrent()));
            lastCommandAt = now;
            sellStep = 2;
            message("отправлена команда /ah sellgui " + Math.round(price.getCurrent()) + ", жду окно");
            schedule(now, 2000L);
         }
         case 2 -> {
            if (client.player.currentScreenHandler == client.player.playerScreenHandler) { schedule(now); return; }
            if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler)) { schedule(now); return; }
            Slot sword = findInventory(client, this::isSword);
            if (sword == null) { message("меча нет в инвентаре при открытом окне продажи"); enter(Phase.SELL_CONFIRM, now); return; }
            click(client, sword.id, 0, SlotActionType.QUICK_MOVE);
            message("перекладываю меч в окно продажи (Shift+ЛКМ)");
            sellStep = 3;
            schedule(now, 1500L);
         }
         case 3 -> {
            if (client.player.currentScreenHandler == client.player.playerScreenHandler) { message("окно продажи закрылось, проверяю подтверждение"); enter(Phase.SELL_CONFIRM, now); return; }
            Slot sword = findInventory(client, this::isSword);
            if (sword != null) {
               click(client, sword.id, 0, SlotActionType.PICKUP);
               message("Shift не сработал, беру меч на курсор");
               sellStep = 4;
               schedule(now, 700L);
               return;
            }
            message("меч в окне продажи, ищу кнопку подтверждения");
            enter(Phase.SELL_CONFIRM, now);
         }
         case 4 -> {
            if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { sellStep = 0; enter(Phase.INSPECT, now); return; }
            int target = -1;
            for (int i = 0; i < containerSlots(menu); i++) if (menu.getSlot(i).getStack().isEmpty()) { target = i; break; }
            if (target < 0) target = 0;
            click(client, target, 0, SlotActionType.PICKUP);
            message("кладу меч с курсора в слот " + (target + 1) + " окна продажи");
            sellStep = 5;
            schedule(now, 1200L);
         }
         default -> {
            if (findInventory(client, this::isSword) == null) { message("меч в окне продажи"); enter(Phase.SELL_CONFIRM, now); return; }
            message("меч не лёг в окно продажи, возвращаюсь к проверке");
            sellStep = 0;
            enter(Phase.INSPECT, now);
         }
      }
   }

   private void sellConfirm(MinecraftClient client, long now) {
      if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) {
         sellConfirmStep++;
         if (sellConfirmStep > 3) { message("окно продажи так и не открылось, начинаю заново"); enter(Phase.INSPECT, now); return; }
         message("стадия подтверждения, но окна продажи нет, жду (" + sellConfirmStep + ")");
         schedule(now, 1000L);
         return;
      }
      if (sellConfirmStep == 0) { message("окно продажи: " + slotNames(menu, 0, Math.min(18, containerSlots(menu)))); sellConfirmStep = 1; schedule(now); return; }
      int target = -1;
      for (int i = 0; i < containerSlots(menu); i++) if (menu.getSlot(i).getStack().isOf(Items.LIME_DYE)) { target = i; break; }
      boolean dye = target >= 0;
      if (!dye && containerSlots(menu) >= 15) target = 14;
      if (target < 0) { message("в окне продажи нет ни красителя, ни 15-го слота"); schedule(now, 1000L); return; }
      click(client, target, 0, SlotActionType.PICKUP);
      message("подтверждение продажи: слот " + (target + 1) + (dye ? " (лаймовый краситель)" : " (красителя нет, нажал 15-й слот: " + menu.getSlot(target).getStack().getName().getString() + ")"));
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
      swordStep = next;
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

   private String slotNames(GenericContainerScreenHandler menu, int from, int to) {
      StringBuilder names = new StringBuilder();
      for (int i = from; i < Math.min(to, containerSlots(menu)); i++) {
         ItemStack stack = menu.getSlot(i).getStack();
         if (stack.isEmpty()) continue;
         String label = stack.getName().getString();
         names.append(i + 1).append("='").append(label, 0, Math.min(20, label.length())).append("' ");
      }
      return names.length() == 0 ? "пусто" : names.toString();
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

   /** Сначала цифры после слова «цена», затем запасной вариант — после последнего «$». */
   private long priceFromText(String raw) {
      String text = normalize(raw);
      int at = text.indexOf("цена");
      if (at >= 0) {
         long price = digitsAfter(text, at + 4);
         if (price >= 0) return price;
      }
      int dollar = text.lastIndexOf('$');
      return dollar < 0 ? -1 : digitsAfter(text, dollar + 1);
   }

   private long digitsAfter(String text, int from) {
      StringBuilder digits = new StringBuilder();
      for (int i = from; i < text.length(); i++) {
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
      return joined.substring(0, Math.min(120, joined.length())) + " ‖ парс=" + priceFromText(target) + " ‖ коды: " + codes;
   }

   /** Диагностика isSword: какие алмазные мечи реально лежат в инвентаре. */
   private String inventorySwords(MinecraftClient client) {
      StringBuilder result = new StringBuilder();
      for (Slot slot : client.player.currentScreenHandler.slots) {
         if (slot.inventory != client.player.getInventory()) continue;
         ItemStack stack = slot.getStack();
         if (!stack.isOf(Items.DIAMOND_SWORD)) continue;
         String name = stack.getName().getString();
         StringBuilder ench = new StringBuilder();
         ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
         if (enchantments != null) for (var entry : enchantments.getEnchantmentEntries()) ench.append(entry.getKey().getKey().toString()).append(':').append(entry.getIntValue()).append(' ');
         result.append("сл").append(slot.id + 1).append(" '").append(name, 0, Math.min(30, name.length())).append("' ").append(ench).append("; ");
         if (result.length() > 200) break;
      }
      return result.length() == 0 ? "алмазных мечей в инвентаре нет" : result.toString();
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
      String path = Registries.ITEM.getId(stack.getItem()).getPath();
      return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae");
   }

   private boolean isPlanks(ItemStack stack) {
      return Registries.ITEM.getId(stack.getItem()).getPath().endsWith("_planks");
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
   private void enter(Phase next, long now) {
      phase = next;
      phaseStarted = now;
      if (next == Phase.REFRESH) refreshStep = 0;
      if (next == Phase.SELL) sellStep = 0;
      if (next == Phase.SELL_CONFIRM) sellConfirmStep = 0;
      schedule(now);
      message("переход на стадию " + next);
   }

   private void message(String text) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player == null) return;
      long now = System.currentTimeMillis();
      if (text.equals(lastMessage) && now - lastMessageAt < 1800L) return;
      lastMessage = text;
      lastMessageAt = now;
      client.player.sendMessage(Text.literal("§eAutoSell.v3 [" + phase + "]: §f" + text), false);
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
