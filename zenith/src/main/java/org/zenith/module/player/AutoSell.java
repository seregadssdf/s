package org.zenith.module.player;

import com.darkmagician6.eventapi.EventTarget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResult.Success;
import net.minecraft.util.ActionResult.SwingSource;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.zenith.event.EventTick;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.module.player.autosell.AutoSellEngine;
import org.zenith.module.player.autosell.AutoSellHost;
import org.zenith.module.player.autosell.AutoSellSettings;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.TextSetting;

/** Автопродажа изумрудных мечей. Настройки общие: BotAutoSell у ботов читает их отсюда. */
@ModuleInfo(name = "AutoSell", category = Category.PLAYER, description = "module.autoSell.desc")
public final class AutoSell extends Module implements AutoSellSettings {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final AutoSell autoSell = new AutoSell();
   public final ModeSetting mode = new ModeSetting("module.autoSell.mode", "module.autoSell.mode.desc", "module.autoSell.emeraldSword");
   public final TextSetting price = new TextSetting(
      "module.autoSell.price",
      "module.autoSell.price.desc",
      "19000",
      "module.autoSell.price.empty",
      TextSetting.Validator.on23(12, value -> value.isEmpty() || value.chars().allMatch(Character::isDigit))
   );
   public final BooleanSetting debug = new BooleanSetting("module.autoSell.debug", "module.autoSell.debug.desc", false);
   private final AutoSellEngine engine = new AutoSellEngine(this, new LocalHost());

   @Override
   public long priceValue() {
      String value = this.price.getValue();
      if (value == null || value.isBlank()) {
         return 0L;
      }

      try {
         return Long.parseLong(value.trim());
      } catch (NumberFormatException exception) {
         return 0L;
      }
   }

   @Override
   public boolean debugEnabled() {
      return this.debug.isEnabled();
   }

   @Override
   public void onEnable() {
      super.onEnable();
      if (this.isEnabled()) {
         this.engine.start();
      }
   }

   @Override
   public void onDisable() {
      this.engine.stop(minecraftClient3.player != null && minecraftClient3.getNetworkHandler() != null);
      super.onDisable();
   }

   @EventTarget
   public void onUpdate(EventTick event) {
      if (minecraftClient3.player != null && minecraftClient3.world != null && minecraftClient3.interactionManager != null) {
         this.engine.tick();
      }
   }

   private static final class LocalHost implements AutoSellHost {
      @Override
      public PlayerEntity player() {
         return minecraftClient3.player;
      }

      @Override
      public World world() {
         return minecraftClient3.world;
      }

      @Override
      public void clickSlot(int slot, int button, SlotActionType action) {
         if (minecraftClient3.player != null && minecraftClient3.interactionManager != null) {
            minecraftClient3.interactionManager.clickSlot(minecraftClient3.player.currentScreenHandler.syncId, slot, button, action, minecraftClient3.player);
         }
      }

      @Override
      public void sendCommand(String command) {
         if (minecraftClient3.player != null) {
            minecraftClient3.player.networkHandler.sendChatCommand(command);
         }
      }

      @Override
      public void closeContainer() {
         if (minecraftClient3.player != null) {
            minecraftClient3.player.closeHandledScreen();
         }
      }

      @Override
      public void selectHotbarSlot(int slot) {
         if (minecraftClient3.player != null) {
            minecraftClient3.player.getInventory().setSelectedSlot(slot);
         }
      }

      @Override
      public void setRotation(float yaw, float pitch) {
         if (minecraftClient3.player != null) {
            minecraftClient3.player.setYaw(yaw);
            minecraftClient3.player.setPitch(pitch);
         }
      }

      @Override
      public void interactBlock(BlockHitResult hit) {
         if (minecraftClient3.player != null && minecraftClient3.interactionManager != null) {
            ActionResult result = minecraftClient3.interactionManager.interactBlock(minecraftClient3.player, Hand.MAIN_HAND, hit);
            if (result instanceof Success success && success.swingSource() == SwingSource.CLIENT) {
               minecraftClient3.player.swingHand(Hand.MAIN_HAND);
            }
         }
      }

      @Override
      public void message(String text) {
         if (minecraftClient3.player != null) {
            minecraftClient3.player.sendMessage(Text.literal("§7[§fAutoSell§7] §f" + text), false);
         }
      }
   }
}
