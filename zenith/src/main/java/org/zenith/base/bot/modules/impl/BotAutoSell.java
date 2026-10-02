package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import java.util.List;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResult.Success;
import net.minecraft.util.ActionResult.SwingSource;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.zenith.base.bot.modules.api.BotModule;
import org.zenith.base.bot.net.BotPlayHandler;
import org.zenith.base.bot.world.BotInteractionManager;
import org.zenith.base.bot.world.BotPlayer;
import org.zenith.event.BotTickEvent;
import org.zenith.module.Category;
import org.zenith.module.ModuleInfo;
import org.zenith.module.player.AutoSell;
import org.zenith.module.player.autosell.AutoSellEngine;
import org.zenith.module.player.autosell.AutoSellHost;
import org.zenith.setting.Setting;

/** Автопродажа изумрудных мечей у бота. Цена и прочие настройки — из модуля AutoSell во вкладке PvE. */
@ModuleInfo(name = "BotAutoSell", category = Category.PLAYER, description = "Крафтит и продаёт изумрудные мечи")
public final class BotAutoSell extends BotModule {
   private final AutoSellEngine engine = new AutoSellEngine(AutoSell.autoSell, new BotHost());

   @Override
   public void onEnable() {
      super.onEnable();
      this.engine.start();
   }

   @Override
   public void onDisable() {
      // При обрыве связи модуль гасится уже после смены фазы — пакеты в закрытое соединение не шлём.
      this.engine.stop(this.bot() != null && this.bot().isJoined());
      super.onDisable();
   }

   @EventTarget
   public void onBotUpdate(BotTickEvent event) {
      if (event.getPlayer() != null && this.bot().isJoined() && this.handler() != null) {
         this.engine.tick();
      }
   }

   /** В меню бота видны общие настройки AutoSell; в профиль бота они не сохраняются. */
   @Override
   public List<Setting> getUiSettings() {
      return AutoSell.autoSell.getSettings();
   }

   private final class BotHost implements AutoSellHost {
      @Override
      public PlayerEntity player() {
         return BotAutoSell.this.player();
      }

      @Override
      public World world() {
         return BotAutoSell.this.world();
      }

      @Override
      public void clickSlot(int slot, int button, SlotActionType action) {
         BotPlayer player = BotAutoSell.this.player();
         BotInteractionManager interaction = BotAutoSell.this.interaction();
         if (player != null && interaction != null) {
            interaction.clickSlot(player.currentScreenHandler.syncId, slot, button, action, player);
         }
      }

      @Override
      public void sendCommand(String command) {
         BotPlayHandler handler = BotAutoSell.this.handler();
         if (handler != null) {
            handler.sendCommand(command);
         }
      }

      @Override
      public void closeContainer() {
         BotPlayer player = BotAutoSell.this.player();
         if (player != null) {
            player.closeHandledScreen();
         }
      }

      @Override
      public void selectHotbarSlot(int slot) {
         BotPlayer player = BotAutoSell.this.player();
         if (player != null) {
            player.getInventory().setSelectedSlot(slot);
         }
      }

      @Override
      public void setRotation(float yaw, float pitch) {
         BotPlayer player = BotAutoSell.this.player();
         if (player != null) {
            player.setYaw(yaw);
            player.setPitch(pitch);
         }
      }

      @Override
      public void interactBlock(BlockHitResult hit) {
         BotPlayer player = BotAutoSell.this.player();
         BotInteractionManager interaction = BotAutoSell.this.interaction();
         if (player != null && interaction != null) {
            ActionResult result = interaction.interactBlock(player, Hand.MAIN_HAND, hit);
            if (result instanceof Success success && success.swingSource() == SwingSource.CLIENT) {
               player.swingHand(Hand.MAIN_HAND);
            }
         }
      }

      @Override
      public void message(String text) {
         BotAutoSell.this.bot().systemMessage("AutoSell: " + text);
      }
   }
}
