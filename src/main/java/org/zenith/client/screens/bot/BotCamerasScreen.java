package org.zenith.client.screens.bot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.zenith.base.bot.client.BotClient;
import org.zenith.base.bot.client.HeadlessBots;
import org.zenith.base.bot.view.BotWorldView;
import org.zenith.base.bot.world.BotPlayer;
import org.zenith.base.font.Font;
import org.zenith.base.font.Fonts;
import org.zenith.core.MenuScreenId;
import org.zenith.render.ShapeRenderer;
import org.zenith.util.ArgbColor;
import org.zenith.utility.game.other.render.CustomScreen;
import org.zenith.utility.render.display.base.CornerRadius;
import org.zenith.utility.render.display.base.HudDrawContext;

/**
 * Стена камер: живой вид от каждого бота, как мониторы в комнате охраны.
 * Каждый бот рисуется в собственный FBO; за кадр обновляется только несколько
 * камер (круговой очередь), поэтому сетка остаётся плавной при большом числе ботов.
 */
public class BotCamerasScreen extends CustomScreen {
   /** Отступ от краёв экрана. */
   public static final float padding = 8.0F;
   /** Зазор между ячейками сетки. */
   public static final float gap = 6.0F;
   public static final float headerHeight = 24.0F;
   public static final float footerHeight = 12.0F;
   /** Высота строки подписи под видео. */
   public static final float labelHeight = 11.0F;
   /** Внутренний отступ ячейки вокруг видео. */
   public static final float cellPadding = 3.0F;
   public static final float maxCellWidth = 152.0F;
   public static final int maxColumns = 4;
   /** Сколько камер обновляется за один кадр. */
   public static final int rendersPerFrame = 3;
   /** Максимум одновременно живых видов (FBO + поток мешинга). */
   public static final int maxLiveViews = 16;
   public static final float scrollStep = 26.0F;
   /** Радиус секций у камер — меньше, чем у полного превью. */
   public static final int cameraSectionRadius = 3;
   public static final CornerRadius cellRadius = CornerRadius.MovementInputEvent(7.0F);
   public static final CornerRadius videoRadius = CornerRadius.MovementInputEvent(5.0F);
   public static final CornerRadius badgeRadius = CornerRadius.MovementInputEvent(3.0F);
   public static final Font titleFont = Fonts.MEDIUM.getFont(8.0F);
   public static final Font nickFont = Fonts.MEDIUM.getFont(6.0F);
   public static final Font smallFont = Fonts.REGULAR.getFont(5.0F);
   public static final ArgbColor backgroundColor = new ArgbColor(10, 10, 14, 255);
   public static final ArgbColor cellColor = new ArgbColor(16, 16, 20, 255);
   public static final ArgbColor cellHoverColor = new ArgbColor(27, 28, 34, 255);
   public static final ArgbColor videoEmptyColor = new ArgbColor(12, 12, 16, 255);
   public static final ArgbColor textColor = new ArgbColor(235, 235, 240, 255);
   public static final ArgbColor secondaryTextColor = new ArgbColor(150, 150, 162, 255);
   public static final ArgbColor liveColor = new ArgbColor(96, 220, 150, 255);
   public static final ArgbColor offlineColor = new ArgbColor(255, 99, 99, 255);
   public static final ArgbColor positionBackground = new ArgbColor(0, 0, 0, 140);
   public final ViewCache views = new ViewCache();
   /** Боты текущего кадра: сначала те, у кого есть мир. */
   public List<String> bots = List.of();
   public float scroll;
   public float maxScroll;
   public int columns = 1;
   public int rows = 1;
   public float cellWidth;
   public float cellHeight;
   public float videoHeight;
   public float stride;
   public float startX;
   public float startY;
   public float gridBottom;
   public int renderCursor;
   /** Стена камер открыта — возврат из управления ботом идёт обратно в камеры. */
   public static boolean fromCameras = false;
   public static MinecraftClient minecraftClient3 = MinecraftClient.getInstance();

   /** Забирает флаг: Esc в управлении ботом вернёт на стену камер. */
   public static boolean claimFromCameras() {
      boolean flag = fromCameras;
      fromCameras = false;
      return flag;
   }

   /** LRU-кэш видов: вытесненный вид сразу закрывается. */
   private static final class ViewCache extends LinkedHashMap<String, BotWorldView> {
      ViewCache() {
         super(16, 0.75F, true);
      }

      @Override
      protected boolean removeEldestEntry(Map.Entry<String, BotWorldView> eldest) {
         boolean evict = this.size() > maxLiveViews;
         if (evict) {
            eldest.getValue().close();
         }

         return evict;
      }
   }

   public boolean canFeed(String name) {
      BotClient botclient = HeadlessBots.get(name);
      return botclient != null && botclient.isJoined() && botclient.getWorld() != null && botclient.getPlayer() != null;
   }

   public List<String> orderedBots() {
      List<String> list = HeadlessBots.allNames();
      List<String> list1 = new ArrayList<>(list.size());
      List<String> list2 = new ArrayList<>();

      for (String s : list) {
         (this.canFeed(s) ? list1 : list2).add(s);
      }

      list1.addAll(list2);
      return list1;
   }

   @Override
   public void render(HudDrawContext var1, float var2, float var3) {
      this.bots = this.orderedBots();
      this.dropStaleViews();
      this.layout();
      int i = 0;

      for (String s : this.bots) {
         if (this.canFeed(s)) {
            i++;
         }
      }

      var1.drawRoundedRect(0.0F, 0.0F, this.width, this.height, CornerRadius.var159, backgroundColor);
      String s1 = BotScreen.tr("module.bot.camerasTitle");
      var1.drawText(titleFont, s1, padding, padding, textColor);
      String s2 = i + " / " + this.bots.size();
      var1.drawText(smallFont, s2, padding + titleFont.width(s1) + 8.0F, padding + 3.0F, secondaryTextColor);
      if (this.bots.isEmpty()) {
         String s4 = BotScreen.tr("module.bot.camerasEmpty");
         var1.drawText(nickFont, s4, (this.width - nickFont.width(s4)) / 2.0F, this.height / 2.0F, secondaryTextColor);
         var1.draw();
         return;
      }

      String s3 = BotScreen.tr("module.bot.camerasHint");
      var1.drawText(smallFont, s3, padding, this.gridBottom + (footerHeight - smallFont.height()) / 2.0F, secondaryTextColor);

      int j = this.firstVisibleCell();
      int k = this.lastVisibleCell();
      List<String> list3 = new ArrayList<>();

      for (int l = j; l <= k; l++) {
         String s5 = this.bots.get(l);
         if (!this.canFeed(s5)) {
            continue;
         }

         BotClient botclient = HeadlessBots.get(s5);
         if (botclient == null) {
            continue;
         }

         BotWorldView botworldview = this.views.get(s5);
         if (botworldview == null || botworldview.client != botclient) {
            if (botworldview != null) {
               botworldview.close();
            }

            botworldview = new BotWorldView(botclient, cameraSectionRadius);
            this.views.put(s5, botworldview);
         }

         list3.add(s5);
      }

      // Камеры рисуются в свои FBO до отрисовки сетки.
      var1.draw();
      if (!list3.isEmpty()) {
         int i1 = Math.min(rendersPerFrame, list3.size());
         int j1 = Math.max(1, list3.size());
         int k1 = Math.floorMod(this.renderCursor, j1);

         for (int l1 = 0; l1 < i1; l1++) {
            String s6 = list3.get((k1 + l1) % list3.size());
            BotWorldView botworldview1 = this.views.get(s6);
            if (botworldview1 != null) {
               botworldview1.renderToFbo(this.videoPixelWidth(), this.videoPixelHeight());
            }
         }

         this.renderCursor = (k1 + i1) % j1;
      }

      var1.enableScissor(padding, startY, this.width - padding, this.gridBottom);

      for (int i2 = j; i2 <= k; i2++) {
         this.renderCell(var1, i2, var2, var3);
      }

      var1.disableScissor();
      var1.draw();
   }

   /** Одна ячейка: видео бота, подпись и статус. */
   public void renderCell(HudDrawContext var1, int var2, float var3, float var4) {
      float f = this.cellX(var2);
      float f1 = this.cellY(var2);
      if (f1 + this.cellHeight < startY || f1 > this.gridBottom) {
         return;
      }

      String s = this.bots.get(var2);
      boolean flag = this.canFeed(s);
      boolean flag1 = var3 >= f && var3 <= f + this.cellWidth && var4 >= f1 && var4 <= f1 + this.cellHeight;
      var1.drawRoundedRect(f, f1, this.cellWidth, this.cellHeight, cellRadius, flag1 ? cellHoverColor : cellColor);
      float f2 = f + cellPadding;
      float f3 = f1 + cellPadding;
      float f4 = this.cellWidth - cellPadding * 2.0F;
      float f5 = this.videoHeight;
      BotWorldView botworldview = this.views.get(s);
      boolean flag2 = flag && botworldview != null && botworldview.getColorAttachment() != null;
      if (flag2) {
         ShapeRenderer.on23(
            var1.getMatrices(),
            botworldview.getColorAttachment(),
            f2,
            f3,
            f4,
            f5,
            videoRadius,
            ArgbColor.var11934,
            0.0F,
            1.0F,
            1.0F,
            0.0F
         );
      } else {
         var1.drawRoundedRect(f2, f3, f4, f5, videoRadius, videoEmptyColor);
         String s1 = BotScreen.tr(flag ? "module.bot.loadingWorld" : "module.bot.notInGame");
         var1.drawText(smallFont, s1, f2 + (f4 - smallFont.width(s1)) / 2.0F, BotScreen.centeredTextY(smallFont, f3, f5), secondaryTextColor);
      }

      if (flag2) {
         // Индикатор живой камеры и координаты бота поверх видео.
         var1.drawRoundedRect(f2 + 4.0F, f3 + 4.0F, 6.0F, 6.0F, badgeRadius, liveColor);
         BotPlayer botplayer = HeadlessBots.get(s) != null ? HeadlessBots.get(s).getPlayer() : null;
         if (botplayer != null) {
            String s2 = (int)botplayer.getX() + " " + (int)botplayer.getY() + " " + (int)botplayer.getZ();
            float f6 = smallFont.width(s2) + 6.0F;
            float f7 = f3 + f5 - 13.0F;
            var1.drawRoundedRect(f2 + 4.0F, f7, f6, 10.0F, badgeRadius, positionBackground);
            var1.drawText(smallFont, s2, f2 + 7.0F, BotScreen.centeredTextY(smallFont, f7, 10.0F), textColor);
         }
      }

      float f8 = f3 + f5 + 4.0F;
      var1.drawText(nickFont, s, f + 4.0F, BotScreen.centeredTextY(nickFont, f8, labelHeight), flag ? textColor : secondaryTextColor);
      String s3;
      if (flag) {
         BotPlayer botplayer1 = HeadlessBots.get(s) != null ? HeadlessBots.get(s).getPlayer() : null;
         s3 = botplayer1 != null ? ((int)botplayer1.getHealth() + " HP") : "";
      } else {
         s3 = BotScreen.tr("module.bot.notInGame");
      }

      if (!s3.isEmpty()) {
         ArgbColor argbcolor = flag ? liveColor : offlineColor;
         var1.drawText(smallFont, s3, f + this.cellWidth - 4.0F - smallFont.width(s3), BotScreen.centeredTextY(smallFont, f8, labelHeight), argbcolor);
      }
   }

   /** Сетка подбирается под количество ботов и размер экрана, при необходимости скроллится. */
   public void layout() {
      int i = Math.max(1, this.bots.size());
      this.columns = MathHelper.clamp((int)Math.ceil(Math.sqrt(i * 1.8)), 1, Math.min(maxColumns, i));
      this.rows = Math.max(1, (this.bots.size() + this.columns - 1) / this.columns);
      float f = this.width - padding * 2.0F;
      this.startY = padding + headerHeight + padding;
      this.gridBottom = this.height - padding - footerHeight;
      float f1 = Math.max(24.0F, this.gridBottom - this.startY);
      float f2 = Math.min(maxCellWidth, (f - gap * (this.columns - 1)) / this.columns);
      float f3 = (f2 - cellPadding * 2.0F) * 9.0F / 16.0F;
      float f4 = f3 + cellPadding * 2.0F + 4.0F + labelHeight + cellPadding;
      if (f4 > f1) {
         f3 = Math.max(8.0F, (f1 - cellPadding * 2.0F - 4.0F - labelHeight - cellPadding) * 16.0F / 9.0F);
         f4 = f3 + cellPadding * 2.0F + 4.0F + labelHeight + cellPadding;
         f2 = f3 + cellPadding * 2.0F;
      }

      this.cellWidth = f2;
      this.videoHeight = f3;
      this.cellHeight = f4;
      this.stride = f4 + gap;
      this.startX = padding + (f - (this.columns * f2 + gap * (this.columns - 1))) / 2.0F;
      float f5 = this.rows * this.stride - gap;
      this.maxScroll = Math.max(0.0F, f5 - f1);
      this.scroll = MathHelper.clamp(this.scroll, 0.0F, this.maxScroll);
   }

   public float cellX(int var1) {
      return this.startX + var1 % this.columns * (this.cellWidth + gap);
   }

   public float cellY(int var1) {
      return this.startY + var1 / this.columns * this.stride - this.scroll;
   }

   public int firstVisibleCell() {
      int i = (int)Math.floor(this.scroll / Math.max(1.0F, this.stride));
      return Math.min(this.bots.size() - 1, Math.max(0, i * this.columns));
   }

   public int lastVisibleCell() {
      int i = (int)Math.ceil((this.scroll + (this.gridBottom - startY)) / Math.max(1.0F, this.stride));
      return Math.max(-1, Math.min(this.bots.size() - 1, i * this.columns - 1));
   }

   public int hitCell(double var1, double var3) {
      for (int i = 0; i < this.bots.size(); i++) {
         float f = this.cellX(i);
         float f1 = this.cellY(i);
         if (f1 + this.cellHeight < startY || f1 > this.gridBottom) {
            continue;
         }

         if (var1 >= f && var1 <= f + this.cellWidth && var3 >= f1 && var3 <= f1 + this.cellHeight) {
            return i;
         }
      }

      return -1;
   }

   /** Закрывает виды ботов, которых больше нет или у которых нет мира. */
   public void dropStaleViews() {
      if (this.views.isEmpty()) {
         return;
      }

      Iterator<Map.Entry<String, BotWorldView>> iterator = this.views.entrySet().iterator();

      while (iterator.hasNext()) {
         Map.Entry<String, BotWorldView> entry = iterator.next();
         if (!this.bots.contains(entry.getKey()) || !this.canFeed(entry.getKey())) {
            entry.getValue().close();
            iterator.remove();
         }
      }
   }

   public void closeViews() {
      for (BotWorldView botworldview : this.views.values()) {
         botworldview.close();
      }

      this.views.clear();
   }

   public int videoPixelWidth() {
      float f = minecraftClient3.getWindow().getScaleFactor();
      return Math.max(1, (int)((this.cellWidth - cellPadding * 2.0F) * f));
   }

   public int videoPixelHeight() {
      float f = minecraftClient3.getWindow().getScaleFactor();
      return Math.max(1, (int)(this.videoHeight * f));
   }

   @Override
   public void onMouseClicked(double var1, double var3, MenuScreenId var5) {
      if (var5 != MenuScreenId.call004) {
         return;
      }

      int i = this.hitCell(var1, var3);
      if (i >= 0 && i < this.bots.size()) {
         fromCameras = true;
         minecraftClient3.setScreen(new BotControlScreen(this.bots.get(i)));
      }
   }

   @Override
   public boolean mouseScrolled(double var1, double var3, double var5, double var7) {
      this.scroll = MathHelper.clamp(this.scroll - (float)var7 * scrollStep, 0.0F, this.maxScroll);
      return true;
   }

   public boolean keyPressed(int var1, int var2, int var3) {
      if (var1 == 256) {
         fromCameras = false;
         minecraftClient3.setScreen(new BotScreen());
         return true;
      } else {
         return super.keyPressed(var1, var2, var3);
      }
   }

   public void removed() {
      this.closeViews();
      super.removed();
   }

   public void renderBackground(DrawContext var1, int var2, int var3, float var4) {
   }

   public boolean shouldPause() {
      return false;
   }

   public boolean shouldCloseOnEsc() {
      return false;
   }
}
