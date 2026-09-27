package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
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
import org.zenith.rotation.Rotation;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.NumberSetting;

/** Buys materials, crafts emerald swords and sells them through the HolyWorld AH. */
@ModuleInfo(name = "BotAutoSell", category = Category.PLAYER, description = "Автоматически крафтит и продаёт изумрудные мечи")
public final class BotAutoSell extends BotModule {
   private static final long ACTION_MIN = 500L;
   private static final long ACTION_MAX = 1000L;
   private static final long GUI_TIMEOUT = 8000L;
   private static final String SWORD_NAME = "изумрудный меч";

   public final ModeSetting mode = new ModeSetting("module.autoSell.mode", "module.autoSell.mode.desc", "module.autoSell.emeraldSword");
   public final NumberSetting price = new NumberSetting("module.autoSell.price", 19000.0F, 0.0F, 100000.0F, 1.0F, "module.autoSell.price.desc", "$", null, null);
   public final NumberSetting rotateSeconds = new NumberSetting("module.autoSell.rotateSeconds", 5.0F, 2.0F, 10.0F, 1.0F, "module.autoSell.rotateSeconds.desc", "s");
   public final BooleanSetting debug = new BooleanSetting("module.autoSell.debug", "module.autoSell.debug.desc", false);

   private Phase phase = Phase.INSPECT;
   private long nextAction;
   private long phaseStarted;
   private float targetYaw;
   private boolean started;

   private enum Phase { INSPECT, ROTATE, SELL, CRAFT, BUY_EMERALDS, BUY_WOOD, REFRESH, RECOVER }

   @Override
   public void onEnable() {
      this.phase = Phase.INSPECT;
      this.phaseStarted = 0L;
      this.nextAction = 0L;
      this.started = false;
      this.debug("модуль включён; режим=" + this.mode.get() + ", цена=" + Math.round(this.price.getCurrent()));
      super.onEnable();
   }

   @Override
   public void onDisable() {
      this.phase = Phase.RECOVER;
      super.onDisable();
   }

   @EventTarget
   public void onBotUpdate(BotTickEvent event) {
      BotPlayer player = event.getPlayer();
      if (player == null || handler() == null || !bot().isJoined()) return;
      long now = System.currentTimeMillis();
      if (phaseStarted == 0L) {
         phaseStarted = now;
         this.debug("старт; фаза=" + phase);
      }
      if (now - phaseStarted > GUI_TIMEOUT && phase != Phase.INSPECT && phase != Phase.ROTATE) {
         this.debug("таймаут фазы " + phase + "; восстановление");
         enter(Phase.RECOVER, now);
         phaseStarted = now;
      }
      if (phase == Phase.ROTATE) {
         rotateToTarget(player);
         if (Math.abs(MathHelper.wrapDegrees(targetYaw - player.getYaw())) < 1.0F) {
            this.debug("поворот завершён; yaw=" + player.getYaw() + ", pitch=" + player.getPitch());
            enter(Phase.INSPECT, now);
         }
         return;
      }
      if (now < nextAction) return;
      switch (phase) {
         case INSPECT -> inspect(player, now);
         case SELL -> sell(player, now);
         case CRAFT -> craft(player, now);
         case BUY_EMERALDS -> buyEmeralds(now);
         case BUY_WOOD -> buyWood(now);
         case REFRESH -> refresh(player, now);
         case RECOVER -> recover(player, now);
         default -> { }
      }
   }

   private void inspect(BotPlayer player, long now) {
      this.debug("осмотр: мечи=" + countMatching(this::isSword) + ", изумруды=" + count(Items.EMERALD) + ", палки=" + count(Items.STICK)
         + ", GUI=" + player.currentScreenHandler.getClass().getSimpleName());
      if (!started) {
         started = true;
         targetYaw = player.getYaw() + 180.0F + random(-5.0F, 5.0F);
         this.debug("проверка инвентаря; поворот на 180 градусов");
         enter(Phase.ROTATE, now);
      } else if (findInventory(this::isSword) != null) enter(Phase.SELL, now);
      else if (count(Items.EMERALD) >= 2 && count(Items.STICK) > 0) enter(Phase.CRAFT, now);
      else if (count(Items.EMERALD) == 0) enter(Phase.BUY_EMERALDS, now);
      else enter(Phase.BUY_WOOD, now);
   }

   private void sell(BotPlayer player, long now) {
      Slot sword = findInventory(this::isSword);
      if (sword == null) { this.debug("мечи закончились; обновляю AH"); enter(Phase.REFRESH, now); return; }
      if (player.currentScreenHandler != player.playerScreenHandler) { this.debug("продажа: закрываю GUI"); player.closeScreen(); schedule(now); return; }
      click(sword.id, 40, SlotActionType.SWAP);
      this.debug("найден меч в слоте " + sword.id + "; открываю продажу");
      handler().sendCommand("ah sellgui " + Math.round(price.getCurrent()));
      enter(Phase.CRAFT, now);
   }

   private void craft(BotPlayer player, long now) {
      if (player.currentScreenHandler != player.playerScreenHandler) { this.debug("крафт: закрываю GUI"); player.closeScreen(); schedule(now); return; }
      // Vanilla 2x2 recipe: emerald, emerald, stick in the center column.
      int emerald = inventorySlot(Items.EMERALD), stick = inventorySlot(Items.STICK);
      this.debug("крафт: emeraldSlot=" + emerald + ", stickSlot=" + stick);
      if (emerald < 0 || stick < 0) { this.debug("крафт: ресурсов недостаточно"); enter(Phase.INSPECT, now); return; }
      click(emerald, 0, SlotActionType.QUICK_MOVE);
      click(stick, 0, SlotActionType.QUICK_MOVE);
      schedule(now);
      if (count(Items.EMERALD) < 2 || count(Items.STICK) == 0) enter(Phase.INSPECT, now + 1);
   }

   private void buyEmeralds(long now) { this.debug("команда: /shop"); handler().sendCommand("shop"); schedule(now); enter(Phase.INSPECT, now + 1); }
   private void buyWood(long now) { this.debug("команда: /ah search дерево"); handler().sendCommand("ah search дерево"); schedule(now); enter(Phase.INSPECT, now + 1); }

   private void refresh(BotPlayer player, long now) {
      this.debug("обновление AH; GUI=" + player.currentScreenHandler.getClass().getSimpleName());
      if (player.currentScreenHandler instanceof GenericContainerScreenHandler menu) {
         int container = containerSlots(menu);
         int slot = Math.min(53, container - 1);
         this.debug("AH: containerSlots=" + container + ", slot=" + slot);
         if (slot >= 0 && slot < menu.slots.size()) click(slot, 0, SlotActionType.PICKUP);
         schedule(now);
      } else { handler().sendCommand("ah"); schedule(now); }
      enter(Phase.INSPECT, now + 1);
   }

   private void recover(BotPlayer player, long now) { this.debug("восстановление; GUI=" + player.currentScreenHandler.getClass().getSimpleName()); if (player.currentScreenHandler != player.playerScreenHandler) player.closeScreen(); enter(Phase.INSPECT, now); }

   private void rotateToTarget(BotPlayer player) {
      Rotation current = new Rotation(player.getYaw(), player.getPitch());
      Rotation target = new Rotation(targetYaw, player.getPitch() + random(-2.0F, 2.0F));
      Rotation smooth = current.on23(current.EmoteManager(target));
      player.setYaw(player.getYaw() + MathHelper.wrapDegrees(smooth.GrimGlide() - player.getYaw()));
      player.setPitch(MathHelper.clamp(smooth.GuiWalk(), -90.0F, 90.0F));
   }

   private void enter(Phase next, long now) { Phase previous = phase; phase = next; phaseStarted = now; nextAction = now + randomDelay(); this.debug("переход: " + previous + " -> " + next + "; delay=" + (nextAction - now) + "ms"); }
   private void schedule(long now) { nextAction = now + randomDelay(); this.debug("задержка=" + (nextAction - now) + "ms"); }
   private long randomDelay() { return ThreadLocalRandom.current().nextLong(ACTION_MIN, ACTION_MAX + 1); }
   private float random(float min, float max) { return (float)(min + ThreadLocalRandom.current().nextDouble() * (max - min)); }

   private void click(int slot, int button, SlotActionType action) {
      this.debug("клик: слот=" + slot + ", кнопка=" + button + ", действие=" + action);
      if (interaction() != null) interaction().clickSlot(bot().getPlayer().currentScreenHandler.syncId, slot, button, action, bot().getPlayer());
   }

   private void debug(String message) {
      if (this.debug != null && this.debug.isEnabled() && this.bot() != null) {
         this.bot().systemMessage("AutoSell: " + message);
      }
   }
   private int containerSlots(GenericContainerScreenHandler menu) { return Math.min(menu.getInventory().size(), Math.max(0, menu.slots.size() - 36)); }
   private int inventorySlot(Item item) { Slot slot = findInventory(stack -> !stack.isEmpty() && stack.isOf(item)); return slot == null ? -1 : slot.id; }
   private int countMatching(java.util.function.Predicate<ItemStack> test) { int total = 0; for (int i = 0; i < bot().getPlayer().getInventory().size(); i++) { ItemStack stack = bot().getPlayer().getInventory().getStack(i); if (test.test(stack)) total += stack.getCount(); } return total; }
   private Slot findInventory(java.util.function.Predicate<ItemStack> test) { for (Slot slot : bot().getPlayer().currentScreenHandler.slots) if (slot.inventory == bot().getPlayer().getInventory() && test.test(slot.getStack())) return slot; return null; }
   private int count(Item item) { int total = 0; for (int i = 0; i < bot().getPlayer().getInventory().size(); i++) { ItemStack stack = bot().getPlayer().getInventory().getStack(i); if (stack.isOf(item)) total += stack.getCount(); } return total; }
   private boolean isSword(ItemStack stack) {
      if (stack.isEmpty() || !stack.isOf(Items.DIAMOND_SWORD)) return false;
      String name = stack.getName().getString().toLowerCase(Locale.ROOT);
      if (!name.contains(SWORD_NAME)) return false;
      ItemEnchantmentsComponent ench = stack.get(DataComponentTypes.ENCHANTMENTS);
      if (ench == null) return false;
      for (var entry : ench.getEnchantmentEntries()) if (entry.getKey().getKey().toString().toLowerCase(Locale.ROOT).contains("sharpness") && entry.getIntValue() >= 3) return true;
      return false;
   }
}
