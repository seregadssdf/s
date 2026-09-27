package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.zenith.base.bot.modules.api.BotModule;
import org.zenith.base.bot.world.BotPlayer;
import org.zenith.event.BotTickEvent;
import org.zenith.module.Category;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.NumberSetting;

/** Buys materials, crafts emerald swords and sells them through the HolyWorld AH. */
@ModuleInfo(name = "BotAutoSell", category = Category.PLAYER, description = "Автоматически крафтит и продаёт изумрудные мечи")
public final class BotAutoSell extends BotModule {
   private static final long ACTION_MIN = 800L;
   private static final long ACTION_MAX = 1500L;
   private static final long GUI_TIMEOUT = 10000L;
   private static final long COMMAND_COOLDOWN = 3000L;
   private static final String SWORD_NAME = "изумрудный меч";

   public final ModeSetting mode = new ModeSetting("module.autoSell.mode", "module.autoSell.mode.desc", "module.autoSell.emeraldSword");
   public final NumberSetting price = new NumberSetting("module.autoSell.price", 19000.0F, 0.0F, 100000.0F, 1.0F, "module.autoSell.price.desc", "$", null, null);
   public final NumberSetting rotateSeconds = new NumberSetting("module.autoSell.rotateSeconds", 5.0F, 2.0F, 10.0F, 1.0F, "module.autoSell.rotateSeconds.desc", "s");
   public final BooleanSetting debug = new BooleanSetting("module.autoSell.debug", "module.autoSell.debug.desc", false);

   private Phase phase = Phase.INSPECT;
   private long nextAction;
   private long phaseStarted;
   private long lastCommandAt;
   private boolean shopOpened;
   private boolean emeraldClicked;
   private boolean categoryClicked;

   private enum Phase { INSPECT, SELL, SELL_WAIT, BUY_EMERALDS, WAIT_RESOURCES, RECOVER }

   @Override
   public void onEnable() {
      phase = Phase.INSPECT;
      phaseStarted = 0L;
      nextAction = 0L;
      lastCommandAt = 0L;
      shopOpened = false;
      emeraldClicked = false;
      categoryClicked = false;
      debug("модуль включён; цена=" + Math.round(price.getCurrent()));
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
      if ((phase == Phase.SELL_WAIT || phase == Phase.BUY_EMERALDS) && now - phaseStarted > GUI_TIMEOUT) {
         debug("таймаут фазы " + phase + "; восстановление");
         enter(Phase.RECOVER, now);
      }
      if (now < nextAction) return;
      switch (phase) {
         case INSPECT -> inspect(player, now);
         case SELL -> sell(player, now);
         case SELL_WAIT -> sellWait(player, now);
         case BUY_EMERALDS -> buyEmeralds(now);
         case WAIT_RESOURCES -> schedule(now);
         case RECOVER -> recover(player, now);
      }
   }

   private void inspect(BotPlayer player, long now) {
      debug("осмотр: мечи=" + countMatching(this::isSword) + ", изумруды=" + count(Items.EMERALD) + ", палки=" + count(Items.STICK));
      if (findInventory(this::isSword) != null) enter(Phase.SELL, now);
      else if (count(Items.EMERALD) < 2) enter(Phase.BUY_EMERALDS, now);
      else enter(Phase.WAIT_RESOURCES, now);
   }

   private void sell(BotPlayer player, long now) {
      Slot sword = findInventory(this::isSword);
      if (sword == null) { enter(Phase.INSPECT, now); return; }
      if (player.currentScreenHandler != player.playerScreenHandler) { player.closeScreen(); schedule(now); return; }
      if (!player.currentScreenHandler.getSlot(45).getStack().isEmpty()) { schedule(now); return; }
      click(sword.id, 40, SlotActionType.SWAP);
      if (!commandCooldownOk(now)) { schedule(now); return; }
      lastCommandAt = now;
      handler().sendCommand("ah sellgui " + Math.round(price.getCurrent()));
      debug("меч в слоте " + sword.id + "; sellgui открыт, жду окно");
      enter(Phase.SELL_WAIT, now);
   }

   private void sellWait(BotPlayer player, long now) {
      if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
         schedule(now);
      } else {
         enter(Phase.INSPECT, now);
      }
   }

   private void buyEmeralds(long now) {
      if (count(Items.EMERALD) >= 2) { enter(Phase.INSPECT, now); return; }
      BotPlayer player = bot().getPlayer();
      if (player.currentScreenHandler == player.playerScreenHandler) {
         if (!shopOpened && commandCooldownOk(now)) {
            shopOpened = true;
            lastCommandAt = now;
            handler().sendCommand("shop");
            debug("команда shop отправлена, жду окно");
            schedule(now, 2000L);
         } else {
            schedule(now);
         }
         return;
      }
      if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler menu)) { schedule(now); return; }
      if (!emeraldClicked) {
         for (int i = 0; i < containerSlots(menu); i++) {
            if (menu.getSlot(i).getStack().isOf(Items.EMERALD)) {
               emeraldClicked = true;
               click(i, 0, SlotActionType.PICKUP);
               debug("клик по изумруду в слоте " + i + ", жду предмет");
               schedule(now, 2000L);
               return;
            }
         }
      } else if (count(Items.EMERALD) >= 2) {
         debug("изумруды куплены");
         enter(Phase.INSPECT, now);
         return;
      }
      if (!categoryClicked) {
         for (int i = 0; i < containerSlots(menu); i++) {
            if (menu.getSlot(i).getStack().isOf(Items.GOLD_INGOT)) {
               categoryClicked = true;
               click(i, 0, SlotActionType.PICKUP);
               debug("клик по категории в слоте " + i + ", жду обновление меню");
               schedule(now, 2500L);
               return;
            }
         }
      }
      schedule(now);
   }

   private void recover(BotPlayer player, long now) {
      if (player.currentScreenHandler != player.playerScreenHandler) player.closeScreen();
      enter(Phase.INSPECT, now);
   }

   private void enter(Phase next, long now) {
      Phase previous = phase;
      phase = next;
      phaseStarted = now;
      nextAction = now + randomDelay();
      if (next == Phase.BUY_EMERALDS) { shopOpened = false; emeraldClicked = false; categoryClicked = false; }
      debug("переход: " + previous + " -> " + next);
   }

   private boolean commandCooldownOk(long now) { return now - lastCommandAt >= COMMAND_COOLDOWN; }
   private void schedule(long now) { nextAction = now + randomDelay(); }
   private void schedule(long now, long extra) { nextAction = now + extra + randomDelay(); }
   private long randomDelay() { return ThreadLocalRandom.current().nextLong(ACTION_MIN, ACTION_MAX + 1); }

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
      var ench = stack.get(DataComponentTypes.ENCHANTMENTS);
      if (ench == null) return false;
      for (var entry : ench.getEnchantmentEntries())
         if (entry.getKey().getKey().toString().toLowerCase(Locale.ROOT).contains("sharpness") && entry.getIntValue() >= 3) return true;
      return false;
   }
}
