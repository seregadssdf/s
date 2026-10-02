package org.zenith.module.player.autosell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

/** Мост к платформе: один и тот же цикл автопродажи ведёт и headless-бота, и локального игрока. */
public interface AutoSellHost {
   PlayerEntity player();

   World world();

   void clickSlot(int slot, int button, SlotActionType action);

   /** Команда без ведущего слэша. */
   void sendCommand(String command);

   /** Закрывает текущее меню; для инвентаря игрока сервер при этом возвращает курсор и сетку 2x2. */
   void closeContainer();

   void selectHotbarSlot(int slot);

   void setRotation(float yaw, float pitch);

   void interactBlock(BlockHitResult hit);

   void message(String text);

   default long now() {
      return System.currentTimeMillis();
   }
}
