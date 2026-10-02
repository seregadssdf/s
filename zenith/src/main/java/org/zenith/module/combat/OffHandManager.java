package org.zenith.module.combat;

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
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import org.zenith.event.MovementInputEvent;
import org.zenith.setting.ModeSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.util.ColorUtils;
import org.zenith.util.CooldownTimer;
import org.zenith.util.ScreenUtils;
import org.zenith.util.TaskScheduler;

@ModuleInfo(name = "OffHandManager", category = Category.COMBAT, description = "")
public final class OffHandManager extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final OffHandManager offHandManager = new OffHandManager();
   public static final String MODE_NONE = "module.offhand.mode.none";
   public static final String MODE_CRUSHER_TALISMAN = "module.offhand.mode.crusherTalisman";
   public static final String MODE_FURY_TALISMAN = "module.offhand.mode.furyTalisman";
   public static final String CRUSHER_TALISMAN_NAME = "Талисман крушителя";
   public static final String FURY_TALISMAN_NAME = "Талисман ярости";
   public final ModeSetting swapHealMode = new ModeSetting(
      "module.offhand.swapHealMode",
      "module.offhand.swapHealMode.desc",
      "module.offhand.mode.mythic",
      "module.offhand.mode.legendary",
      "module.offhand.mode.talisman",
      MODE_CRUSHER_TALISMAN,
      MODE_FURY_TALISMAN,
      MODE_NONE
   );
   public final ModeSetting swapEnemyMode = new ModeSetting(
      "module.offhand.swapEnemyMode",
      "module.offhand.swapEnemyMode.desc",
      "module.offhand.mode.cerberus",
      "module.offhand.mode.mythic",
      "module.offhand.mode.legendary",
      "module.offhand.mode.talisman",
      MODE_CRUSHER_TALISMAN,
      MODE_FURY_TALISMAN,
      MODE_NONE
   );
   /**
    * Порог здоровья для свапа на талисман ярости, когда враг ест. Пока хп выше порога, свап не
    * делается вовсе: в оффхенде остаётся то, что там было (тотем, другой талисман), и модуль
    * ведёт себя так, будто условие по врагу не выполнено.
    *
    * Ровно на пороге свап уже идёт: сравнение нестрогое, поэтому на 16 хп талисман встаёт, на 17 —
    * нет. Шаг 1 и целая подпись: хп на сервере целое, а дробный порог давал бы вид «свап на 16.5»,
    * которого игрок в интерфейсе не увидит.
    *
    * Порог смотрит только на красное хп ({@code getHealth()}), без абсорбции: игрок читает своё
    * состояние по красной полосе, и «16 хп» — это она. Отдельно от {@code swapHealMode}: там условие
    * по собственной еде, и порог его не касается.
    */
   public final NumberSetting furyHealthThreshold = new NumberSetting(
      "module.offhand.furyHealth",
      16.0F,
      1.0F,
      40.0F,
      1.0F,
      "module.offhand.furyHealth.desc",
      "hp",
      () -> this.swapEnemyMode.is(MODE_FURY_TALISMAN),
      null
   );
   public final CooldownTimer zClass06730 = new CooldownTimer();
   /** Inventory index the talisman was taken from, or -1 when nothing is tracked. */
   public int swappedSlotIndex = -1;
   /** Display name of the talisman currently in the off hand, or null. */
   public String swappedName = null;

   public void reset() {
      this.zClass06730.reset();
   }

   @Override
   public void onDisable() {
      super.onDisable();
      this.swappedSlotIndex = -1;
      this.swappedName = null;
   }

   @EventTarget
   public void ItemRegistry(MovementInputEvent var1) {
      LivingEntity livingentity = Aura.aura.zClass054();
      if (this.int393()) {
         Slot slot;
         if (!this.swapEnemyMode.is(MODE_NONE) && livingentity != null && this.enemyConditionHolds(livingentity)) {
            slot = this.UiAnimation(this.swapEnemyMode);
            this.rememberSwap(this.swapEnemyMode, slot);
         } else if (minecraftClient3.player.isUsingItem() && !this.swapHealMode.is(MODE_NONE)) {
            slot = this.UiAnimation(this.swapHealMode);
            this.rememberSwap(this.swapHealMode, slot);
            if (slot == null) {
               return;
            }

            if (!minecraftClient3.player.getOffHandStack().equals(slot.getStack())
               && TaskScheduler.Easing(AutoTotem.class)
               && TaskScheduler.Easing(AutoSwap.class)
               && TaskScheduler.Easing(OffHandManager.class)) {
               TaskScheduler.on23(OffHandManager.class, () -> {
                  if (TaskScheduler.Easing(AutoTotem.class)) {
                     ScreenUtils.on23(slot, Hand.OFF_HAND, true);
                  }
               });
            }
         } else {
            slot = null;
            // No condition holds any more, so a talisman this module put into the
            // off hand has to go back: nothing else returns it. AutoTotem only
            // restores the totem on its own low-health trigger.
            this.restoreSwap();
         }

         if (slot != null && TaskScheduler.Easing(AutoTotem.class) && TaskScheduler.Easing(AutoSwap.class) && TaskScheduler.Easing(OffHandManager.class)) {
            TaskScheduler.on23(OffHandManager.class, () -> {
               if (TaskScheduler.Easing(AutoTotem.class)) {
                  ScreenUtils.on23(slot, Hand.OFF_HAND, true);
               }
            });
         }
      }
   }

   public boolean int393() {
      return this.zClass06730.EventModifyMouseRotationInput(1000L);
   }

   public Slot UiAnimation(ModeSetting var1) {
      try {
         if (var1.get().equals(MODE_CRUSHER_TALISMAN)) {
            return this.findSlotByDisplayName(CRUSHER_TALISMAN_NAME);
         }

         if (var1.get().equals(MODE_FURY_TALISMAN)) {
            return this.findSlotByDisplayName(FURY_TALISMAN_NAME);
         }

         if (var1.get().equals("module.offhand.mode.talisman")) {
            return ScreenUtils.on23(
               minecraftClient3.player.playerScreenHandler,
               Items.TOTEM_OF_UNDYING,
               Comparator.<Slot, Boolean>comparing(var0 -> !var0.getStack().hasEnchantments()).thenComparing((var0, var1x) -> {
                  NbtComponent nbtcomponent = (NbtComponent)var0.getStack().get(DataComponentTypes.CUSTOM_DATA);
                  NbtComponent nbtcomponent1 = (NbtComponent)var1x.getStack().get(DataComponentTypes.CUSTOM_DATA);
                  if (nbtcomponent == null && nbtcomponent1 != null) {
                     return -1;
                  }

                  if (nbtcomponent != null && nbtcomponent1 == null) {
                     return 1;
                  }

                  if (nbtcomponent == null) {
                     return 0;
                  }

                  boolean flag = nbtcomponent.copyNbt().contains("sphereEffect");
                  boolean flag1 = nbtcomponent1.copyNbt().contains("sphereEffect");
                  if (flag && !flag1) {
                     return 1;
                  }

                  if (!flag && flag1) {
                     return -1;
                  }

                  if (!flag) {
                     return 0;
                  }

                  String s1 = nbtcomponent.copyNbt().get("sphereEffect").toString();
                  String s2 = nbtcomponent1.copyNbt().get("sphereEffect").toString();
                  return Integer.compare(s2.length(), s1.length());
               }).thenComparingInt(var0 -> var0.id).reversed(),
               var0 -> true
            );
         }

         String s = this.EventTick(var1.get());
         return ScreenUtils.on23(
            Items.PLAYER_HEAD,
            var1x -> {
               if (!var1x.getStack().isEmpty()) {
                  NbtComponent nbtcomponent = (NbtComponent)var1x.getStack().get(DataComponentTypes.CUSTOM_DATA);
                  if (nbtcomponent != null
                     && nbtcomponent.copyNbt().contains("SkullOwner")
                     && nbtcomponent.copyNbt().get("SkullOwner").toString().contains(s)) {
                     return true;
                  }
               }

               return false;
            }
         );
      } catch (Exception exception) {
         exception.printStackTrace();
         return null;
      }
   }

   /**
    * Finds an inventory slot whose visible item name contains the given text.
    * Server-side talismans carry no distinguishing item type or custom data, so
    * the rendered name is the only thing left to match on. Formatting codes are
    * stripped and the comparison ignores case, since the colouring and casing of
    * these names are not stable across servers.
    */
   public Slot findSlotByDisplayName(String var1) {
      String s = var1.toLowerCase(Locale.ROOT);
      // Hotbar and main inventory only. Swapping to the off hand is valid just
      // for player-inventory slots, and index 40 is the off hand itself: picking
      // it up would schedule a swap of the off hand with itself, which looks
      // exactly like the module doing nothing. Armour slots are excluded too.
      return ScreenUtils.ColorAnimator(
         var1x -> var1x.inventory instanceof PlayerInventory && var1x.getIndex() < 36 && this.matchesDisplayName(var1x.getStack(), s)
      );
   }

   /**
    * Records where a talisman came from so it can be put back. Only the two
    * name-matched talisman modes are tracked: the head and totem modes are left
    * to AutoTotem, which manages the off hand on its own terms.
    *
    * The inventory index is stored rather than the Slot, because Slot instances
    * are rebuilt whenever the screen handler changes.
    */
   public void rememberSwap(ModeSetting var1, Slot var2) {
      String s = this.talismanName(var1.get());
      if (s == null || var2 == null) {
         return;
      }

      // Tracking follows the most recent swap. While one talisman stays in the
      // off hand its inventory copy is gone, so the lookup above returns null and
      // this is skipped; tracking only moves when a different talisman is put in.
      if (!s.equals(this.swappedName)) {
         this.swappedSlotIndex = var2.getIndex();
         this.swappedName = s;
      }
   }

   /**
    * Swaps the talisman back out of the off hand. SWAP is its own inverse, so
    * clicking the original inventory slot again restores both items at once.
    *
    * Only fires while the off hand still holds the talisman that was put there:
    * if the player or another module has changed the off hand since, restoring
    * would overwrite whatever is in it now.
    */
   public void restoreSwap() {
      if (this.swappedName == null) {
         return;
      }

      if (!this.matchesDisplayName(minecraftClient3.player.getOffHandStack(), this.swappedName.toLowerCase(Locale.ROOT))) {
         this.swappedSlotIndex = -1;
         this.swappedName = null;
         return;
      }

      int i = this.swappedSlotIndex;
      Slot slot = i < 0 ? null : ScreenUtils.ColorAnimator(var1x -> var1x.inventory instanceof PlayerInventory && var1x.getIndex() == i);
      if (slot == null) {
         this.swappedSlotIndex = -1;
         this.swappedName = null;
         return;
      }

      if (TaskScheduler.Easing(AutoTotem.class) && TaskScheduler.Easing(AutoSwap.class) && TaskScheduler.Easing(OffHandManager.class)) {
         this.swappedSlotIndex = -1;
         this.swappedName = null;
         TaskScheduler.on23(OffHandManager.class, () -> {
            if (TaskScheduler.Easing(AutoTotem.class)) {
               ScreenUtils.on23(slot, Hand.OFF_HAND, true);
            }
         });
      }
   }

   /**
    * Whether the enemy-side condition holds for the selected mode.
    *
    * The setting means "when the enemy eats", but {@link #ColorAnimator} treats
    * any target without a sword as a trigger, so it is true almost permanently.
    * For the name-matched talisman modes the enemy has to actually be using an
    * item. The head and totem modes keep the original, wider condition so
    * existing setups are unaffected.
    */
   public boolean enemyConditionHolds(LivingEntity var1) {
      // Талисман ярости — ещё и по своему хп. Проверка стоит здесь, а не у самого свапа, чтобы
      // отказ шёл тем же путём, что и «враг не ест»: в ItemRegistry ветка else вызывает
      // restoreSwap(), поэтому талисман, вставший на низком хп, уедет назад сам, как только
      // хп поднялось выше порога. Если резать свап ниже, талисман остался бы в оффхенде висеть.
      if (this.swapEnemyMode.is(MODE_FURY_TALISMAN) && !this.furyHealthReached()) {
         return false;
      }

      return this.talismanName(this.swapEnemyMode.get()) != null ? var1.isUsingItem() : this.ColorAnimator(var1);
   }

   /** Своё хп опустилось до порога свапа на талисман ярости. */
   public boolean furyHealthReached() {
      return minecraftClient3.player != null && minecraftClient3.player.getHealth() <= this.furyHealthThreshold.getCurrent();
   }

   /** The tracked display name for a mode, or null if the mode is not name-matched. */
   public String talismanName(String var1) {
      if (MODE_CRUSHER_TALISMAN.equals(var1)) {
         return CRUSHER_TALISMAN_NAME;
      }

      return MODE_FURY_TALISMAN.equals(var1) ? FURY_TALISMAN_NAME : null;
   }

   public boolean matchesDisplayName(ItemStack var1, String var2) {
      if (var1.isEmpty()) {
         return false;
      }

      String s = ColorUtils.pattern10.matcher(var1.getName().getString()).replaceAll("");
      return s.toLowerCase(Locale.ROOT).contains(var2);
   }

   public String EventTick(String var1) {
      return switch (var1) {
         case "module.offhand.mode.cerberus" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjA5NWE3ZmQ5MGRhYTFiYmU3MDY5MDg5NzQwZTA1ZDBiZmM2NjI5NmVlM2M0MGVlNzFhNGUwYTY2MTZiMmJiYyJ9fX0=";
         case "module.offhand.mode.mythic" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmFmZjJlYjQ5OGU1YzZhMDQ0ODRmMGM5Zjc4NWI0NDg0NzlhYjIxM2RmOTVlYzkxMTc2YTMwOGExMmFkZDcwIn19fQ==";
         case "module.offhand.mode.legendary" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGM5MzY1NjQyYzZlZGRjZmVkZjViNWUxNGUyYmM3MTI1N2Q5ZTRhMzM2M2QxMjNjNmYzM2M1NWNhZmJmNmQifX19";
         default -> throw new RuntimeException("Unknown key: " + var1);
      };
   }

   public boolean ColorAnimator(LivingEntity var1) {
      if (var1.isUsingItem()) {
         return true;
      } else {
         return var1.getMainHandStack().isIn(ItemTags.SWORDS)
            ? false
            : var1.getOffHandStack().getItem() != Items.PLAYER_HEAD || this.NbtEditor(var1.getOffHandStack());
      }
   }

   public boolean NbtEditor(ItemStack var1) {
      NbtComponent nbtcomponent = (NbtComponent)var1.get(DataComponentTypes.CUSTOM_DATA);
      return nbtcomponent != null
         && nbtcomponent.copyNbt().contains("SkullOwner")
         && nbtcomponent.copyNbt()
            .get("SkullOwner")
            .toString()
            .contains(
               "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGM5MzY1NjQyYzZlZGRjZmVkZjViNWUxNGUyYmM3MTI1N2Q5ZTRhMzM2M2QxMjNjNmYzM2M1NWNhZmJmNmQifX19"
            );
   }
}
