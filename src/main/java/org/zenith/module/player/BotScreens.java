package org.zenith.module.player;

import com.darkmagician6.eventapi.EventTarget;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.zenith.base.bot.client.BotClient;
import org.zenith.base.bot.client.HeadlessBots;
import org.zenith.base.bot.view.BotWorldView;
import org.zenith.event.EventTriggerKeyEvent;
import org.zenith.event.RenderTickEvent;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.render.LegacyImmediateRenderer;
import org.zenith.setting.ButtonSetting;
import org.zenith.setting.KeySetting;
import org.zenith.setting.NumberSetting;
import org.zenith.setting.TextSetting;

@ModuleInfo(name = "BotScreens", category = Category.PLAYER,
   description = "Клиентские экраны ботов на стенах: ник бота, P — поставить, Delete — убрать под прицелом")
public final class BotScreens extends Module {
   public static final BotScreens botScreens = new BotScreens();
   private static final MinecraftClient MC = MinecraftClient.getInstance();
   private static final float WIDTH = 3.2F;
   private static final float HEIGHT = 1.8F;
   private static final int MAX_SCREENS = 6;
   public final TextSetting botName = new TextSetting("Ник бота", "", "Имя подключённого бота");
   public final KeySetting placeKey = new KeySetting("Поставить экран", 80);
   public final KeySetting removeKey = new KeySetting("Удалить экран под прицелом", 261);
   public final NumberSetting fps = new NumberSetting("FPS экранов", 5, 1, 10, 1);
   public final ButtonSetting clear = new ButtonSetting("Убрать все экраны", this::clearScreens);
   private final List<Monitor> monitors = new ArrayList<>();
   private final Map<String, Feed> feeds = new HashMap<>();
   private Object localWorld;
   private int nextFeed;

   private BotScreens() {
      WorldRenderEvents.START_MAIN.register(context -> {
         if (this.isEnabled()) {
            this.updateFeeds();
         }
      });
      WorldRenderEvents.END_MAIN.register(context -> {
         if (this.isEnabled()) {
            this.renderScreens(context);
         }
      });
   }

   @EventTarget
   public void onKey(EventTriggerKeyEvent event) {
      if (MC.currentScreen != null || MC.world == null || MC.player == null || event.TridentAimbot() != 1) {
         return;
      }
      if (event.is(this.placeKey.getKeyCode())) {
         this.place();
      } else if (event.is(this.removeKey.getKeyCode())) {
         this.remove();
      }
   }

   private void place() {
      this.checkWorld();
      String name = this.botName.getValue().trim();
      BotClient bot = HeadlessBots.get(name);
      if (bot == null || !bot.isJoined()) {
         this.message("Укажите ник подключённого бота в настройках BotScreens");
         return;
      }
      if (!(MC.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
         || hit.getSide().getAxis() == Direction.Axis.Y) {
         this.message("Наведите прицел на вертикальную стену");
         return;
      }
      if (this.monitors.size() >= MAX_SCREENS) {
         this.message("Максимум 6 экранов; удалите один клавишей Delete");
         return;
      }
      Vec3d normal = Vec3d.of(hit.getSide().getVector());
      Vec3d center = hit.getPos().add(normal.multiply(0.025));
      this.monitors.add(new Monitor(bot.getName(), center, hit.getSide()));
      this.message("Экран " + bot.getName() + " установлен (виден только вам)");
   }

   private void remove() {
      Vec3d eye = MC.player.getEyePos();
      Vec3d ray = MC.player.getRotationVec(1);
      Monitor nearest = null;
      double distance = 8;
      for (Monitor monitor : this.monitors) {
         double t = monitor.intersection(eye, ray);
         if (t > 0 && t < distance) {
            nearest = monitor;
            distance = t;
         }
      }
      if (nearest != null) {
         this.monitors.remove(nearest);
         this.releaseUnusedFeeds();
         this.message("Экран удалён");
      }
   }

   private void checkWorld() {
      if (this.localWorld != MC.world) {
         this.clearScreens();
         this.localWorld = MC.world;
      }
   }

   @EventTarget
   public void checkWorld(RenderTickEvent event) {
      this.checkWorld();
   }

   private void updateFeeds() {
      this.checkWorld();
      this.releaseUnusedFeeds();
      if (MC.world == null || MC.player == null || this.monitors.isEmpty()) {
         return;
      }
      for (Monitor monitor : this.monitors) {
         BotClient bot = HeadlessBots.get(monitor.botName);
         Feed feed = this.feeds.get(monitor.botName);
         if (feed != null && (feed.bot != bot || bot == null || !bot.isJoined())) {
            feed.view.close();
            this.feeds.remove(monitor.botName);
         }
         if (bot != null && bot.isJoined()) {
            this.feeds.computeIfAbsent(monitor.botName, key -> new Feed(bot));
         }
      }
      List<Feed> visible = this.feeds.values().stream().filter(feed -> feed.bot.isJoined()
         && this.monitors.stream().anyMatch(m -> m.botName.equals(feed.bot.getName())
            && m.center.squaredDistanceTo(MC.player.getEntityPos()) < 32 * 32)).toList();
      if (!visible.isEmpty()) {
         Feed feed = visible.get(Math.floorMod(this.nextFeed++, visible.size()));
         long now = System.currentTimeMillis();
         if (now - feed.updatedAt >= (long)(1000 / this.fps.getCurrent())) {
            feed.ready = feed.view.renderToFbo(320, 180);
            feed.updatedAt = now;
         }
      }
   }

   private void renderScreens(WorldRenderContext context) {
      if (MC.world == null || MC.player == null) {
         return;
      }
      Vec3d camera = MC.gameRenderer.getCamera().getCameraPos();
      LegacyImmediateRenderer.clearTarget();
      LegacyImmediateRenderer.clearShader();
      LegacyImmediateRenderer.enableDepthTest();
      LegacyImmediateRenderer.depthMask(true);
      LegacyImmediateRenderer.setShaderColor(1, 1, 1, 1);
      for (Monitor monitor : this.monitors) {
         if (monitor.center.squaredDistanceTo(camera) > 32 * 32 || camera.subtract(monitor.center).dotProduct(monitor.normal()) <= 0) {
            continue;
         }
         Matrix4f matrix = new Matrix4f(context.matrices().peek().getPositionMatrix());
         Vec3d position = monitor.center.subtract(camera);
         Vec3d right = monitor.right();
         matrix.translate((float)position.x, (float)position.y, (float)position.z);
         matrix.rotateY((float)Math.atan2(right.z * -1, right.x));
         Feed feed = this.feeds.get(monitor.botName);
         boolean ready = feed != null && feed.bot.isJoined() && feed.ready && feed.view.getColorAttachment() != null;
         this.quad(matrix, -WIDTH / 2 - 0.06F, -HEIGHT / 2 - 0.06F, WIDTH + 0.12F, HEIGHT + 0.12F, 0xFF101014, false);
         if (ready) {
            LegacyImmediateRenderer.setTexture(feed.view.getColorAttachment());
            this.quad(new Matrix4f(matrix).translate(0, 0, 0.002F), -WIDTH / 2, -HEIGHT / 2, WIDTH, HEIGHT, 0xFFFFFFFF, true);
         }
         this.drawOverlay(new Matrix4f(matrix).translate(-WIDTH / 2, HEIGHT / 2, 0.01F).scale(0.01F, -0.01F, 0.01F),
            monitor.botName, ready ? feed.bot : null);
      }
      LegacyImmediateRenderer.setTexture((com.mojang.blaze3d.textures.GpuTextureView)null);
   }

   private void quad(Matrix4f matrix, float x, float y, float width, float height, int color, boolean textured) {
      BufferBuilder b = Tessellator.getInstance().begin(DrawMode.QUADS,
         textured ? VertexFormats.POSITION_TEXTURE_COLOR : VertexFormats.POSITION_COLOR);
      if (textured) {
         b.vertex(matrix, x, y, 0).texture(0, 0).color(color);
         b.vertex(matrix, x + width, y, 0).texture(1, 0).color(color);
         b.vertex(matrix, x + width, y + height, 0).texture(1, 1).color(color);
         b.vertex(matrix, x, y + height, 0).texture(0, 1).color(color);
      } else {
         b.vertex(matrix, x, y, 0).color(color);
         b.vertex(matrix, x + width, y, 0).color(color);
         b.vertex(matrix, x + width, y + height, 0).color(color);
         b.vertex(matrix, x, y + height, 0).color(color);
      }
      LegacyImmediateRenderer.draw(b.end());
   }

   private void drawOverlay(Matrix4f matrix, String name, BotClient bot) {
      var consumers = MC.getBufferBuilders().getEntityVertexConsumers();
      MC.textRenderer.draw(name + (bot == null ? " — отключён / загрузка" : ""), 5, 5, 0xFFFFFFFF, true,
         matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0x90000000, 15728880);
      if (bot != null && bot.getWorld() != null) {
         Scoreboard board = bot.getWorld().getScoreboard();
         Team team = board.getScoreHolderTeam(bot.getName());
         ScoreboardObjective objective = null;
         if (team != null) {
            ScoreboardDisplaySlot slot = ScoreboardDisplaySlot.fromFormatting(team.getColor());
            if (slot != null) {
               objective = board.getObjectiveForSlot(slot);
            }
         }
         if (objective == null) {
            objective = board.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
         }
         if (objective != null) {
            List<Text> lines = new ArrayList<>();
            lines.add(objective.getDisplayName());
            board.getScoreboardEntries(objective).stream().filter(entry -> !entry.hidden())
               .sorted(InGameHud.SCOREBOARD_ENTRY_COMPARATOR).limit(15)
               .forEach(entry -> lines.add(Team.decorateName(board.getScoreHolderTeam(entry.owner()), entry.name())));
            int y = 20;
            for (Text line : lines) {
               MC.textRenderer.draw(line, Math.max(4, 315 - MC.textRenderer.getWidth(line)), y, 0xFFFFFFFF, true,
                  matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0x80000000, 15728880);
               y += 9;
            }
         }
         if (bot.getPlayer() != null) {
            MC.textRenderer.draw("HP " + (int)bot.getPlayer().getHealth(), 5, 166, 0xFFFFFFFF, true,
               matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0x80000000, 15728880);
         }
      }
      consumers.draw();
   }

   private void releaseUnusedFeeds() {
      this.feeds.entrySet().removeIf(entry -> {
         if (this.monitors.stream().anyMatch(m -> m.botName.equals(entry.getKey()))) {
            return false;
         }
         entry.getValue().view.close();
         return true;
      });
   }

   public void clearScreens() {
      this.feeds.values().forEach(feed -> feed.view.close());
      this.feeds.clear();
      this.monitors.clear();
   }

   @Override
   public void onDisable() {
      this.clearScreens();
      super.onDisable();
   }

   private void message(String text) {
      if (MC.player != null) {
         MC.player.sendMessage(Text.literal("[BotScreens] " + text), false);
      }
   }

   record Monitor(String botName, Vec3d center, Direction facing) {
      Vec3d normal() {
         return Vec3d.of(this.facing.getVector());
      }

      Vec3d right() {
         return new Vec3d(this.normal().z, 0, -this.normal().x);
      }

      double intersection(Vec3d eye, Vec3d ray) {
         double denominator = ray.dotProduct(this.normal());
         if (denominator >= -0.0001) {
            return -1;
         }
         double t = this.center.subtract(eye).dotProduct(this.normal()) / denominator;
         Vec3d offset = eye.add(ray.multiply(t)).subtract(this.center);
         return t > 0 && Math.abs(offset.y) <= HEIGHT / 2
            && Math.abs(offset.dotProduct(this.right())) <= WIDTH / 2 ? t : -1;
      }
   }

   private static final class Feed {
      final BotClient bot;
      final BotWorldView view;
      long updatedAt;
      boolean ready;

      Feed(BotClient bot) {
         this.bot = bot;
         this.view = new BotWorldView(bot);
      }
   }
}
