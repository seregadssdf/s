package org.zenith.module.player;

import com.darkmagician6.eventapi.EventTarget;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.zenith.event.EventTick;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.NumberSetting;

@ModuleInfo(name = "AutoSell", category = Category.PLAYER, description = "Помощник покупки изумрудов и продажи мечей")
public final class AutoSell extends Module {
   public static final AutoSell autoSell = new AutoSell();
   public final NumberSetting price = new NumberSetting("module.autoSell.price", 19000.0F, 0.0F, 100000.0F, 1.0F, "module.autoSell.price.desc", "$", null, null);
   private long nextAction;
   private boolean shopOpened;
   private boolean emeraldSelected;
   private boolean selling;

   @Override
   public void onEnable() {
      nextAction = 0;
      shopOpened = false;
      emeraldSelected = false;
      selling = false;
      super.onEnable();
   }

   @EventTarget
   public void onTick(EventTick event) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player == null || client.interactionManager == null || client.player.networkHandler == null) return;
      long now = System.currentTimeMillis();
      if (now < nextAction) return;
      nextAction = now + 800L;

      if (selling) {
         if (client.player.currentScreenHandler instanceof GenericContainerScreenHandler) return;
         selling = false;
      }
      if (client.player.currentScreenHandler == client.player.playerScreenHandler) {
         for (Slot slot : client.player.currentScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory() && isSword(slot.getStack())) {
               if (!client.player.currentScreenHandler.getSlot(45).getStack().isEmpty()) return;
               client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slot.id, 40, SlotActionType.SWAP, client.player);
               client.player.networkHandler.sendChatCommand("ah sellgui " + Math.round(price.getCurrent()));
               selling = true;
               return;
            }
         }
         if (countEmeralds(client) >= 2) return;
         if (!shopOpened) {
            shopOpened = true;
            client.player.networkHandler.sendChatCommand("shop");
         }
      } else if (shopOpened && !emeraldSelected && client.player.currentScreenHandler instanceof GenericContainerScreenHandler menu) {
         int containerSlots = Math.min(menu.getInventory().size(), Math.max(0, menu.slots.size() - 36));
         for (int i = 0; i < containerSlots; i++) {
            if (menu.getSlot(i).getStack().isOf(Items.EMERALD)) {
               emeraldSelected = true;
               client.interactionManager.clickSlot(menu.syncId, i, 0, SlotActionType.PICKUP, client.player);
               return;
            }
         }
      }
   }

   private int countEmeralds(MinecraftClient client) {
      int total = 0;
      for (int i = 0; i < client.player.getInventory().size(); i++) {
         ItemStack stack = client.player.getInventory().getStack(i);
         if (stack.isOf(Items.EMERALD)) total += stack.getCount();
      }
      return total;
   }

   private boolean isSword(ItemStack stack) {
      if (!stack.isOf(Items.DIAMOND_SWORD) || !stack.getName().getString().toLowerCase(Locale.ROOT).contains("изумрудный меч")) return false;
      ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
      if (enchantments == null) return false;
      for (var entry : enchantments.getEnchantmentEntries()) {
         if (entry.getKey().getKey().toString().contains("sharpness") && entry.getIntValue() >= 3) return true;
      }
      return false;
   }
}
