package org.zenith.core;

import net.minecraft.screen.slot.Slot;

/**
 * Duck-интерфейс миксина {@link org.zenith.utility.mixin.screen.MixinHandledScreen}:
 * наружу защищённое ванильное поле focusedSlot — слот под курсором открытого экрана.
 *
 * <p>Нужен GuiWalk для свапа предмета под курсором в руку по бинду: у HandledScreen
 * нет публичного доступа к этому полю, а свой хит-тест по координатам дублировал бы
 * ванильный. Тот же приём, что и у DrawContextSink.
 */
public interface FocusedSlotSink {
   Slot zenith_guiWalk_focusedSlot();
}
