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
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.PlayerInput;
import org.zenith.event.MovementInputEvent;
import org.zenith.setting.NumberSetting;

@ModuleInfo(name = "Strafe", description = "", category = Category.MOVEMENT)
public final class Strafe extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final Strafe strafe = new Strafe();
   public final NumberSetting radius = new NumberSetting("Radius", 1.0F, 0.1F, 6.0F, 0.1F, "", "b");

   @EventTarget
   public void onInput(MovementInputEvent event) {
      PlayerInput input = event.NoSweetSlow();
      event.on23(new PlayerInput(
         input.forward(), input.backward(), input.left(), input.right(),
         input.jump(), input.sneak(), input.sprint()
      ));
   }

}
