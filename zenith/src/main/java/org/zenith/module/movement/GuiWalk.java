package org.zenith.module.movement;

import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.module.ModuleManager;
import org.zenith.module.combat.*;
import org.zenith.module.movement.*;
import org.zenith.module.player.*;
import org.zenith.module.render.*;
import org.zenith.module.misc.*;

import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.zenith.ZenithClient;
import org.zenith.core.EffectEngine;
import org.zenith.core.FocusedSlotSink;
import org.zenith.core.TaskQueueWorker;
import org.zenith.event.CloseScreenEvent;
import org.zenith.event.RotationUpdateStartEvent;
import org.zenith.event.GuiWalkEvent;
import org.zenith.event.EventClick;
import org.zenith.event.EventTick;
import org.zenith.event.EventTriggerKeyEvent;
import org.zenith.event.MovementInputEvent;
import org.zenith.event.PacketEvent;
import org.zenith.rotation.RotationTask;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.KeySetting;
import org.zenith.util.MovementUtils;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;

@ModuleInfo(name = "Gui Walk", category = Category.MOVEMENT, description = "Можно ходить в инвентаре или контейнере")
public final class GuiWalk extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final GuiWalk guiWalk = new GuiWalk();
   public final ModeSetting mode9 = new ModeSetting("module.guiWalk.mode", "module.guiWalk.mode.desc");
   public final ModeSetting.Option modeSetting3Var15936 = new ModeSetting.Option(this.mode9, "module.guiWalk.mode.visual").int210();
   public final ModeSetting.Option modeSetting3Var15937 = new ModeSetting.Option(this.mode9, "module.guiWalk.mode.grim");
   public final ModeSetting.Option modeSetting3Var15938 = new ModeSetting.Option(this.mode9, "HolyWorld");
   public final BooleanSetting cameraLock = new BooleanSetting(
      "module.guiWalk.cameraLock", "module.guiWalk.cameraLock.desc", true, this.modeSetting3Var15937::isSelected
   );
   /**
    * Свап по бинду, как в ClickAction: предмет под курсором в открытом инвентаре
    * уезжает в основную руку теми же вызовами ScreenUtils.
    */
   public final KeySetting swapBind = new KeySetting("module.guiWalk.swapBind", "module.guiWalk.swapBind.desc");
   public SlotActionType slotActionType = null;
   public final List<Packet<?>> list16 = new ArrayList<>();
   /**
    * Slot clicks awaiting their packet. A counter rather than a flag: several
    * clicks can be registered before their packets are sent, and a single flag
    * only ever buffered the first of them, letting the rest through unbuffered.
    */
   int pendingClicks = 0;
   boolean val122 = false;
   boolean val092 = false;

   @EventTarget
   public void onPacket(PacketEvent var1) {
      if (!this.modeSetting3Var15938.isSelected()) {
         try {
            if (EffectEngine.double69()) {
               return;
            }

            if (minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler) {
               // Only slot clicks may consume a pending buffer permit. This fires for
               // every packet in both directions, so movement and keep-alive traffic
               // used to eat the permit before the click packet arrived: the click was
               // then sent straight through instead of being buffered, which desynced
               // the buffer from the server. Fast number-key swaps hit this constantly,
               // since GuiWalk means movement packets flow every tick.
               if (!(var1.ItemScroller() instanceof ClickSlotC2SPacket clickslotc2spacket)) {
                  return;
               }

               if (this.pendingClicks <= 0) {
                  return;
               }

               this.pendingClicks--;
               if ((!this.list16.isEmpty() || MovementUtils.double64()) && TaskScheduler.call203()) {
                  this.list16.add(clickslotc2spacket);
                  this.slotActionType = clickslotc2spacket.actionType();
                  var1.setCancelled(true);
               }
            }
         } catch (Exception exception) {
            exception.printStackTrace();
         }
      }
   }

   @EventTarget
   public void UiAnimation(EventTick var1) {
      if (minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler) {
         if (TaskScheduler.call203()
            && (!this.modeSetting3Var15936.isSelected() || !this.val122)
            && !this.val092
            && (!this.list16.isEmpty() || minecraftClient3.player.currentScreenHandler.getCursorStack().isEmpty())) {
            TaskScheduler.call204();
         }
      } else {
         this.val122 = false;
         this.val092 = false;
      }
   }

   @EventTarget
   public void on23(EventClick var1) {
      if (var1.PricedItem() >= 0) {
         Slot slot = minecraftClient3.player.currentScreenHandler.getSlot(var1.PricedItem());
         ItemStack itemstack = slot.getStack();
         if (itemstack.get(DataComponentTypes.CUSTOM_DATA) != null) {
         }
      }

      if (!this.modeSetting3Var15938.isSelected() && minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler) {
         SlotActionType slotactiontype = var1.HeldItemWatcher();
         if (this.modeSetting3Var15936.isSelected()) {
            if (MovementUtils.double64()) {
               var1.setCancelled(true);
            } else {
               this.val122 = true;
            }
         }

         if (this.list16.isEmpty() && !MovementUtils.double64()) {
            this.val092 = true;
         } else if (var1.ContainerScanner() == 1 && slotactiontype.equals(SlotActionType.PICKUP)
            || this.slotActionType == SlotActionType.PICKUP && var1.HeldItemWatcher() == SlotActionType.QUICK_MOVE
            || this.slotActionType == SlotActionType.QUICK_MOVE && var1.HeldItemWatcher() == SlotActionType.PICKUP
            || this.slotActionType == SlotActionType.QUICK_MOVE && var1.HeldItemWatcher() == SlotActionType.PICKUP_ALL
            || this.slotActionType == SlotActionType.PICKUP_ALL && var1.HeldItemWatcher() == SlotActionType.QUICK_MOVE) {
            var1.setCancelled(true);
         } else {
            this.pendingClicks++;
         }
      }
   }

   @EventTarget
   public void on23(CloseScreenEvent var1) {
      if (!this.modeSetting3Var15938.isSelected() && minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler) {
         if (this.modeSetting3Var15936.isSelected()) {
            if (!this.val122) {
               var1.setCancelled(true);
            }

            this.val122 = false;
         } else if (this.val092 && this.list16.isEmpty()) {
            this.val092 = false;
         } else {
            this.val092 = false;
            if (MovementUtils.double64() || !this.list16.isEmpty()) {
               var1.setCancelled(true);
            }

            if (!this.list16.isEmpty()) {
               this.slotActionType = null;
               this.pendingClicks = 0;
               List<Packet<?>> arraylist = new ArrayList<>(this.list16);
               this.list16.clear();
               TaskQueueWorker ll1ill11111i_l1i1illlili = new TaskQueueWorker(GuiWalk.class);
               if (InventorySetting.inventorySetting.call099()) {
                  ll1ill11111i_l1i1illlili.on23(
                     MovementInputEvent.class,
                     var1x -> {
                        var1x.NoSlow();
                        if (!minecraftClient3.player.lastPlayerInput.jump()
                           && !minecraftClient3.player.isSprinting()
                           && !minecraftClient3.player.lastPlayerInput.forward()
                           && !minecraftClient3.player.lastPlayerInput.backward()
                           && !minecraftClient3.player.lastPlayerInput.left()
                           && !minecraftClient3.player.lastPlayerInput.right()) {
                           arraylist.forEach(var0x -> minecraftClient3.getNetworkHandler().sendPacket(var0x));
                           ScreenUtils.closeScreen();
                           return true;
                        } else {
                           return false;
                        }
                     }
                  );
               } else {
                  for (int i = 0; i < arraylist.size(); i++) {
                     Packet<?> packet = arraylist.get(i);
                     ll1ill11111i_l1i1illlili.on23(
                        GuiWalkEvent.class,
                        var1x -> {
                           if (!(minecraftClient3.player.currentScreenHandler instanceof PlayerScreenHandler)) {
                              ScreenUtils.closeScreen();
                           }

                           if (!minecraftClient3.player.lastPlayerInput.jump()
                              && !minecraftClient3.player.isSprinting()
                              && !minecraftClient3.player.lastPlayerInput.forward()
                              && !minecraftClient3.player.lastPlayerInput.backward()
                              && !minecraftClient3.player.lastPlayerInput.left()
                              && !minecraftClient3.player.lastPlayerInput.right()) {
                              minecraftClient3.getNetworkHandler().sendPacket(packet);
                              return true;
                           } else {
                              return false;
                           }
                        }
                     );
                  }

                  ll1ill11111i_l1i1illlili.on23(
                     MovementInputEvent.class,
                     var0 -> {
                        if (!minecraftClient3.player.lastPlayerInput.jump()
                           && !minecraftClient3.player.isSprinting()
                           && !minecraftClient3.player.lastPlayerInput.forward()
                           && !minecraftClient3.player.lastPlayerInput.backward()
                           && !minecraftClient3.player.lastPlayerInput.left()
                           && !minecraftClient3.player.lastPlayerInput.right()) {
                           ScreenUtils.closeScreen();
                           return true;
                        } else {
                           return false;
                        }
                     }
                  );
                  ll1ill11111i_l1i1illlili.UiAnimation(MovementInputEvent.class, var0 -> {
                     var0.NoSlow();
                     return true;
                  });
               }

               // Registered for both flush paths. It used to sit inside the branch
               // above, so turning off delayMoveItem silently disabled the camera
               // lock even though the setting stayed on.
               if (this.cameraLock.isEnabled()) {
                  ll1ill11111i_l1i1illlili.UiAnimation(RotationUpdateStartEvent.class, var1x -> {
                     val002.on23(new RotationTask(val002.LineShader(), val002::LineShader, val002.int150().HudPreviewItem()), 999, this, 1);
                     return true;
                  });
               }

               ZenithClient.on23().FileLogger().on23(ll1ill11111i_l1i1illlili);
            }
         }
      }
   }

   /**
    * Свап предмета под курсором в основную руку по бинду — те же вызовы ScreenUtils,
    * что и у ClickAction: ванильный SWAP-клик слота хотбара, плюс платёжный пакет
    * call216 из InventorySetting. В отличие от воронки ItemServiceBase экран не
    * закрывается и юз не делается: свап должен оставаться внутри открытого инвентаря.
    *
    * <p>Проверка нажатия сырым on23(keyCode, true), а не ItemRegistry(): тот требует
    * закрытый экран, а здесь экран как раз открыт. Слот под курсором — защищённое
    * ванильное поле HandledScreen, наружу выходит через duck-интерфейс FocusedSlotSink.
    *
    * <p>Свап идёт мимо буфера on23(EventClick): это прямой clickSlot, а не клик по
    * экрану. Но если у буфера есть свободные разрешения (pendingClicks > 0), onPacket
    * упакует его пакет вместе с остальными — порядок пакетов не нарушится.
    */
   @EventTarget
   public void on23(EventTriggerKeyEvent var1) {
      if (var1.on23(this.swapBind.getKeyCode(), true)
         && minecraftClient3.player != null
         && minecraftClient3.currentScreen instanceof FocusedSlotSink focusedslotsink) {
         Slot slot = focusedslotsink.zenith_guiWalk_focusedSlot();
         if (slot != null && slot.hasStack()) {
            ScreenUtils.on23(slot, Hand.MAIN_HAND, true);
         }
      }
   }
}
