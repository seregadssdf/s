package org.zenith.client.screens.bot;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.math.Vector2f;
import net.minecraft.text.Text;
import org.zenith.util.ArgbColor;
import org.zenith.base.bot.client.HeadlessBots;
import org.zenith.base.font.Fonts;
import org.zenith.core.MenuScreenId;
import org.zenith.hud.SearchBox;
import org.zenith.utility.game.other.render.CustomScreen;
import org.zenith.utility.render.display.base.CornerRadius;
import org.zenith.utility.render.display.base.HudDrawContext;

// Ввод анархии выбранного бота: Enter сохраняет номер для кнопки «Разойтись», Esc отменяет.
public class BotAnarchyInputScreen extends CustomScreen {
   public final BotScreen parent;
   public final String botName;
   public final SearchBox input = new SearchBox(new Vector2f(0.0F, 0.0F), Fonts.MEDIUM.getFont(6.0F), "Анархия...", 0.0F);

   public BotAnarchyInputScreen(BotScreen parent, String botName) {
      super();
      this.parent = parent;
      this.botName = botName;
      this.input.EventItemRenderHook(4);
   }

   @Override
   protected void init() {
      int i = HeadlessBots.getAnarchy(this.botName);
      this.input.HudHotbarPanel(i > 0 ? String.valueOf(i) : "");
      this.input.EventItemRenderHook(4);
      this.input.VelocityChangeEvent(true);
   }

   @Override
   public void render(HudDrawContext hud, float mouseX, float mouseY) {
      float f = this.width / 2.0F - 90.0F;
      float f1 = this.height / 2.0F - 34.0F;
      hud.drawRoundedRect(f, f1, 180.0F, 92.0F, CornerRadius.MovementInputEvent(8.0F), ArgbColor.HudRenderEvent(-15780324));
      hud.drawText(Fonts.MEDIUM.getFont(6.5F), this.botName + " — анархия", f + 10.0F, f1 + 8.0F, ArgbColor.var11934);
      hud.drawRoundedRect(f + 10.0F, f1 + 26.0F, 164.0F, 20.0F, CornerRadius.MovementInputEvent(6.0F), ArgbColor.HudRenderEvent(-14503824));
      this.input.setWidth(126.0F);
      this.input
         .on23(
            hud,
            f + 8.0F,
            f1 + 28.0F + (20.0F - this.input.call050().height()) / 2.0F,
            ArgbColor.var11934,
            ArgbColor.HudRenderEvent(-10066330)
         );
      hud.drawText(Fonts.MEDIUM.getFont(5.5F), "Цифры 1–999, Enter — сохранить, Esc — отмена", f + 8.0F, f1 + 56.0F, ArgbColor.HudRenderEvent(-6710887));
   }

   @Override
   public void onMouseClicked(double mouseX, double mouseY, MenuScreenId button) {
      float f = this.width / 2.0F - 90.0F;
      float f1 = this.height / 2.0F - 34.0F;
      if (mouseX >= f + 10.0F && mouseX <= f + 174.0F && mouseY >= f1 + 26.0F && mouseY <= f1 + 46.0F) {
         this.input.onMouseClicked(mouseX, mouseY, button);
         if (!this.input.isSelected()) {
            this.input.VelocityChangeEvent(true);
            this.input.CrosshairTargetUpdateEvent(false);
         }

      }

      this.input.VelocityChangeEvent(false);
   }

   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (keyCode == 256) {
         this.close();
         return true;
      }

      if ((keyCode == 257 || keyCode == 335) && this.input.isSelected()) {
         this.apply();
         return true;
      }

      if (this.input.isSelected()) {
         return this.input.keyPressed(keyCode, scanCode, modifiers);
      }

      return super.keyPressed(keyCode, scanCode, modifiers);
   }

   @Override
   public boolean charTyped(char chr, int modifiers) {
      return this.input.charTyped(chr, modifiers) || super.charTyped(new CharInput(chr, modifiers));
   }

   public void apply() {
      String s = this.input.getText().trim();
      if (s.isBlank()) {
        this.close();
        return;
      }

      try {
         int i = Integer.parseInt(s);
         if (HeadlessBots.setAnarchy(this.botName, i)) {
            if (this.client.player != null) {
               this.client.player.sendMessage(Text.literal("§7[Bots] §f" + this.botName + " → §a/an" + i), false);
            }

            this.close();
         }
      } catch (NumberFormatException var4) {
      }
   }

   public void close() {
      this.client.setScreen(this.parent);
   }

   @Override
   public boolean shouldPause() {
      return false;
   }
}
