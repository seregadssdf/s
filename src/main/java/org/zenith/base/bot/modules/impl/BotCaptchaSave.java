package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker.SerializedEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState.UpdateData;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.zenith.base.bot.modules.api.BotModule;
import org.zenith.core.FileLogger;
import org.zenith.event.BotPacketEvent;
import org.zenith.module.Category;
import org.zenith.module.ModuleInfo;
import org.zenith.utility.mixin.accessors.ItemFrameEntityAccessor;

/**
 * Сбор датасета капч: в первые 10 секунд после коннекта бот копит все карты
 * (стена капчи из рамок) и сохраняет их в <runDir>/captcha/save/<метка>_<ник>/:
 * wall.png (собранная стена), wall_api.png (250x150 как для обучения),
 * tiles/*.png (каждая карта отдельно) и raw.json (сырые байты карт + метаданные).
 * В save/labels.csv дописывается строка — пользователь вписывает код капчи, и
 * размеченные файлы идут в обучение (ml/captcha/train.py).
 */
@ModuleInfo(name = "BotCaptchaSave", category = Category.MISC, description = "Сохраняет капчу с карт в первые 10 секунд после коннекта (датасет для обучения)")
public final class BotCaptchaSave extends BotModule {
   public static final int MAP_SIZE = 128;
   public static final long COLLECT_WINDOW_MS = 10000L;
   public static final long SETTLE_EXTRA_MS = 1500L;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(var0 -> {
      Thread thread = new Thread(var0, "bot-captcha-save");
      thread.setDaemon(true);
      return thread;
   });

   /** Карты капчи: id -> палитра 128x128 (индексы MapColor). */
   public final Map<Integer, int[]> buffers = new ConcurrentHashMap<>();
   /** Рамки: entityId -> [x, y, z, facing]. */
   public final Map<Integer, double[]> frames = new ConcurrentHashMap<>();
   /** Рамки: entityId -> id карты в ней. */
   public final Map<Integer, Integer> frameItems = new ConcurrentHashMap<>();
   /** Рамки: entityId -> поворот. */
   public final Map<Integer, Integer> frameRotations = new ConcurrentHashMap<>();
   /** Взгляд бота: [x, y, z, yaw, pitch] — для выбора стены, на которую он смотрит. */
   public volatile double[] look;
   public final AtomicLong generation = new AtomicLong();
   public final AtomicBoolean flushing = new AtomicBoolean();
   public volatile long joinTime;

   @Override
   public void onEnable() {
      super.onEnable();
      this.resetState();
      this.log("enabled: сбор капч на коннекте");
   }

   @Override
   public void onDisable() {
      this.resetState();
      super.onDisable();
   }

   @EventTarget
   public void onPacket(BotPacketEvent event) {
      Packet<?> packet = event.ItemScroller();
      if (packet instanceof GameJoinS2CPacket || packet instanceof PlayerRespawnS2CPacket) {
         this.resetState();
         this.joinTime = System.currentTimeMillis();
         long generation = this.generation.get();
         this.log("коннект — собираю карты капчи 10 секунд");
         EXECUTOR.schedule(() -> this.flush(generation), COLLECT_WINDOW_MS + SETTLE_EXTRA_MS, TimeUnit.MILLISECONDS);
         return;
      }

      if (packet instanceof PlayerPositionLookS2CPacket positionLook) {
         var position = positionLook.change();
         this.look = new double[]{
            position.position().x, position.position().y, position.position().z,
            position.yaw(), position.pitch()
         };
         return;
      }

      if (!this.collecting()) {
         return;
      }

      if (packet instanceof EntitySpawnS2CPacket entitySpawn
         && (entitySpawn.getEntityType() == EntityType.ITEM_FRAME || entitySpawn.getEntityType() == EntityType.GLOW_ITEM_FRAME)) {
         this.frames.put(
            entitySpawn.getEntityId(),
            new double[]{entitySpawn.getX(), entitySpawn.getY(), entitySpawn.getZ(), entitySpawn.getEntityData()}
         );
      } else if (packet instanceof EntityTrackerUpdateS2CPacket trackerUpdate) {
         this.handleTrackedValues(trackerUpdate);
      } else if (packet instanceof MapUpdateS2CPacket mapUpdate) {
         this.handleMapUpdate(mapUpdate.mapId().id(), (UpdateData)mapUpdate.updateData().orElse(null));
      }
   }

   public void handleTrackedValues(EntityTrackerUpdateS2CPacket packet) {
      for (SerializedEntry<?> entry : packet.trackedValues()) {
         if (entry.value() instanceof ItemStack stack) {
            MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
            if (mapId != null) {
               this.frameItems.put(packet.id(), mapId.id());
            }
         } else if (entry.id() == ItemFrameEntityAccessor.getRotationData().id() && entry.value() instanceof Integer rotation) {
            this.frameRotations.put(packet.id(), rotation);
         }
      }
   }

   public void handleMapUpdate(int mapId, UpdateData update) {
      if (update == null || update.width() <= 0 || update.height() <= 0) {
         return;
      }

      byte[] colors = update.colors();
      int width = update.width();
      int height = update.height();
      if (colors.length < width * height) {
         return;
      }

      int[] buffer = this.buffers.computeIfAbsent(mapId, key -> new int[MAP_SIZE * MAP_SIZE]);
      for (int row = 0; row < height; row++) {
         int z = update.startZ() + row;
         if (z < 0 || z >= MAP_SIZE) {
            continue;
         }

         for (int col = 0; col < width; col++) {
            int x = update.startX() + col;
            if (x >= 0 && x < MAP_SIZE) {
               buffer[x + z * MAP_SIZE] = colors[col + row * width] & 255;
            }
         }
      }
   }

   public boolean collecting() {
      return this.isEnabled() && this.joinTime != 0L && System.currentTimeMillis() - this.joinTime <= COLLECT_WINDOW_MS;
   }

   /** Сохранение собранного: стена целиком + отдельные карты + сырые данные + строка в labels.csv. */
   public void flush(long generation) {
      if (generation != this.generation.get() || !this.isEnabled() || !this.flushing.compareAndSet(false, true)) {
         return;
      }

      try {
         // Только та группа карт, на которую смотрит бот.
         Map<Integer, int[]> tiles = this.lookedTiles();
         if (tiles.isEmpty()) {
            this.log("капча не пришла — сохранять нечего");
            return;
         }

         BufferedImage wall = this.stitchWall(tiles);
         String label = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(this.joinTime);
         String botName = this.botLabel().replaceAll("[^A-Za-z0-9_-]", "_");
         Path dir = net.minecraft.client.MinecraftClient.getInstance().runDirectory.toPath()
            .resolve("captcha").resolve("save").resolve(label + "_" + botName);
         Path tilesDir = dir.resolve("tiles");
         Files.createDirectories(tilesDir);

         JsonObject meta = new JsonObject();
         meta.addProperty("bot", this.botLabel());
         meta.addProperty("server", this.bot() == null ? "" : this.bot().getAddress());
         meta.addProperty("joinedAt", this.joinTime);
         meta.addProperty("savedAt", System.currentTimeMillis());

         JsonArray mapsArray = new JsonArray();
         int index = 0;
         for (Map.Entry<Integer, int[]> entry : tiles.entrySet()) {
            int[] buffer = entry.getValue();
            if (isUniform(buffer) && tiles.size() > 1) {
               continue;
            }

            String tileName = String.format("tile_%02d_map%d.png", index, entry.getKey());
            ImageIO.write(render(buffer), "png", tilesDir.resolve(tileName).toFile());
            meta.addProperty("tile_" + index, tileName);

            JsonObject mapJson = new JsonObject();
            mapJson.addProperty("mapId", entry.getKey());
            Integer frameId = this.frameByMap(entry.getKey());
            if (frameId != null) {
               mapJson.addProperty("frame", frameId);
               mapJson.addProperty("rotation", this.frameRotations.getOrDefault(frameId, 0));
               double[] pos = this.frames.get(frameId);
               if (pos != null) {
                  mapJson.addProperty("x", pos[0]);
                  mapJson.addProperty("y", pos[1]);
                  mapJson.addProperty("z", pos[2]);
                  mapJson.addProperty("facing", (int)pos[3]);
               }
            }

            byte[] raw = new byte[MAP_SIZE * MAP_SIZE];
            for (int i = 0; i < raw.length; i++) {
               raw[i] = (byte)buffer[i];
            }

            mapJson.addProperty("rawBase64", Base64.getEncoder().encodeToString(raw));
            mapsArray.add(mapJson);
            index++;
         }

         if (wall == null) {
            wall = gridFallback(new ArrayList<>(tiles.values()));
         }

         ImageIO.write(wall, "png", dir.resolve("wall.png").toFile());
         ImageIO.write(resizeForApi(wall), "png", dir.resolve("wall_api.png").toFile());
         meta.addProperty("tiles", index);
         meta.addProperty("wallWidth", wall.getWidth());
         meta.addProperty("wallHeight", wall.getHeight());
         meta.add("maps", mapsArray);
         Files.writeString(dir.resolve("raw.json"), GSON.toJson(meta), StandardCharsets.UTF_8);

         Path labels = dir.getParent().resolve("labels.csv");
         if (!Files.exists(labels) || Files.size(labels) == 0L) {
            Files.writeString(labels, "filename,bot,joinedAt,code,confidence\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         }

         String wallName = dir.getFileName().toString() + "/wall_api.png";
         Files.writeString(labels, wallName + "," + this.botLabel() + "," + this.joinTime + ",,\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         this.log("капча сохранена: " + dir + " (карт: " + index + ")");
      } catch (Exception exception) {
         this.log("сохранение капчи не удалось: " + rootMessage(exception));
      } finally {
         this.flushing.set(false);
      }
   }

   private Integer frameByMap(int mapId) {
      for (Map.Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         if (entry.getValue() == mapId) {
            return entry.getKey();
         }
      }

      return null;
   }

   /** Группа карт, на которую смотрит бот (плоскость рейкаста), упорядоченная по id карты. */
   private Map<Integer, int[]> lookedTiles() {
      Integer target = this.raycastFrame();
      if (target == null) {
         target = this.facingPlaneFrame();
      }

      double[] targetFrame = target == null ? null : this.frames.get(target);
      if (targetFrame == null) {
         return new TreeMap<>();
      }

      long plane = planeKey((int)targetFrame[3], targetFrame);
      Map<Integer, int[]> group = new TreeMap<>();
      for (Map.Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         double[] frame = this.frames.get(entry.getKey());
         int[] buffer = this.buffers.get(entry.getValue());
         if (frame != null && buffer != null && planeKey((int)frame[3], frame) == plane) {
            group.put(entry.getValue(), buffer);
         }
      }

      return group;
   }

   /** Стена из группы карт в реальной раскладке (с учётом поворотов рамок). */
   private BufferedImage stitchWall(Map<Integer, int[]> tiles) {
      double[] targetFrame = null;
      int facing = 2;
      for (Map.Entry<Integer, int[]> entry : tiles.entrySet()) {
         Integer frameId = this.frameByMap(entry.getKey());
         double[] frame = frameId == null ? null : this.frames.get(frameId);
         if (frame != null) {
            facing = (int)frame[3];
            targetFrame = frame;
            break;
         }
      }

      if (targetFrame == null) {
         return null;
      }

      List<int[]> group = new ArrayList<>(tiles.values());
      List<double[]> groupFrames = new ArrayList<>();
      for (Integer mapId : tiles.keySet()) {
         Integer frameId = this.frameByMap(mapId);
         groupFrames.add(frameId == null ? null : this.frames.get(frameId));
      }

      if (group.isEmpty()) {
         return null;
      }

      boolean horizontalZ = facing >= 4;
      double minH = Double.MAX_VALUE;
      double maxH = -Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (double[] frame : groupFrames) {
        double h = horizontalZ ? frame[2] : frame[0];
         minH = Math.min(minH, h);
         maxH = Math.max(maxH, h);
         minY = Math.min(minY, frame[1]);
         maxY = Math.max(maxY, frame[1]);
      }

      int cols = (int)Math.round(maxH - minH) + 1;
      int rows = (int)Math.round(maxY - minY) + 1;
      if (cols < 1 || rows < 1 || cols > 8 || rows > 8 || cols * rows > group.size() * 4) {
         return null;
      }

      BufferedImage image = new BufferedImage(cols * MAP_SIZE, rows * MAP_SIZE, 1);
      Graphics2D graphics = image.createGraphics();
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      int drawn = 0;
      for (int i = 0; i < group.size(); i++) {
         double[] frame = groupFrames.get(i);
         if (frame == null) {
            continue;
         }

         int[] buffer = group.get(i);
         int rotation = 0;
         for (Map.Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
            double[] candidate = this.frames.get(entry.getKey());
            if (candidate == frame) {
               rotation = this.frameRotations.getOrDefault(entry.getKey(), 0) & 3;
               break;
            }
         }

         buffer = rotateClockwise(buffer, rotation);
         double h = horizontalZ ? frame[2] : frame[0];
         int col = switch (facing) {
            case 3, 4 -> (int)Math.round(h - minH);
            default -> (int)Math.round(maxH - h);
         };
         int row = (int)Math.round(maxY - frame[1]);
         if (col >= 0 && col < cols && row >= 0 && row < rows) {
            graphics.drawImage(render(buffer), col * MAP_SIZE, row * MAP_SIZE, null);
            drawn++;
         }
      }

      graphics.dispose();
      return drawn == 0 ? null : image;
   }

   /** Рейкаст взглядом бота: рамка, в которую попадает луч (как в BotAutoCapcha). */
   public Integer raycastFrame() {
      double[] look = this.look;
      if (look == null) {
         return null;
      }

      double yaw = Math.toRadians(look[3]);
      double pitch = Math.toRadians(look[4]);
      double dirX = -Math.sin(yaw) * Math.cos(pitch);
      double dirY = -Math.sin(pitch);
      double dirZ = Math.cos(yaw) * Math.cos(pitch);
      Integer target = null;
      double best = Double.MAX_VALUE;
      for (Map.Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         if (!this.buffers.containsKey(entry.getValue())) {
            continue;
         }

         double[] frame = this.frames.get(entry.getKey());
         if (frame == null) {
            continue;
         }

         int facing = (int)frame[3];
         if (facing < 2 || facing > 5) {
            continue;
         }

         // Нормаль передней стороны рамки: 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x)
         double normalZ = facing == 2 ? -1.0 : facing == 3 ? 1.0 : 0.0;
         double normalX = facing == 4 ? -1.0 : facing == 5 ? 1.0 : 0.0;
         if (dirX * normalX + dirZ * normalZ >= -0.05) {
            continue;
         }

         double plane = facing >= 4 ? frame[0] + 0.5 : frame[2] + 0.5;
         double dirH = facing >= 4 ? dirX : dirZ;
         if (Math.abs(dirH) < 1.0E-4) {
            continue;
         }

         double originH = facing >= 4 ? look[0] : look[2];
         double distance = (plane - originH) / dirH;
         if (distance <= 0.0 || distance > 48.0) {
            continue;
         }

         double hitX = look[0] + dirX * distance - (frame[0] + 0.5);
         double hitY = look[1] + dirY * distance - (frame[1] + 0.5);
         double hitZ = look[2] + dirZ * distance - (frame[2] + 0.5);
         double hitH = facing >= 4 ? hitZ : hitX;
         if (Math.abs(hitH) <= 0.55 && Math.abs(hitY) <= 0.55 && distance < best) {
            best = distance;
            target = entry.getKey();
         }
      }

      return target;
   }

   /** Запасной вариант, если взгляд не попал: ближайшая рамка в плоскости, повёрнутой к боту. */
   private Integer facingPlaneFrame() {
      double[] look = this.look;
      if (look == null) {
         return this.frameItems.keySet().stream().filter(key -> this.buffers.containsKey(this.frameItems.get(key)))
            .findFirst().orElse(null);
      }

      double yaw = Math.toRadians(look[3]);
      double viewX = -Math.sin(yaw);
      double viewZ = Math.cos(yaw);
      Integer target = null;
      double best = -Double.MAX_VALUE;
      for (Map.Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         if (!this.buffers.containsKey(entry.getValue())) {
            continue;
         }

         double[] frame = this.frames.get(entry.getKey());
         if (frame == null || (int)frame[3] < 2 || (int)frame[3] > 5) {
            continue;
         }

         double deltaX = frame[0] - look[0];
         double deltaZ = frame[2] - look[2];
         double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
         if (distance < 0.01 || distance > 32.0) {
            continue;
         }

         double facing = deltaX / distance * viewX + deltaZ / distance * viewZ;
         double score = facing < 0.2 ? -1.0 : facing * 100.0 / (1.0 + distance);
         if (score > best) {
            best = score;
            target = entry.getKey();
         }
      }

      return target;
   }

   /** Ключ плоскости стены: сторона рамки + координата стены (как в BotAutoCapcha). */
   public static long planeKey(int facing, double[] frame) {
      double plane = facing >= 4 ? frame[0] : frame[2];
      return (long)facing << 40 ^ Math.round(plane * 4.0) & 1099511627775L;
   }

   public static int[] rotateClockwise(int[] palette, int times) {
      int[] current = palette;
      for (int i = 0; i < times; i++) {
         int[] rotated = new int[MAP_SIZE * MAP_SIZE];
         for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
               rotated[x + y * MAP_SIZE] = current[y + (MAP_SIZE - 1 - x) * MAP_SIZE];
            }
         }

         current = rotated;
      }

      return current;
   }

   /** Запасной вариант без рамок: все карты подряд сеткой. */
   private static BufferedImage gridFallback(List<int[]> tiles) {
      int count = tiles.size();
      int cols = Math.max(1, (int)Math.ceil(Math.sqrt(count)));
      int rows = Math.max(1, (count + cols - 1) / cols);
      BufferedImage image = new BufferedImage(cols * MAP_SIZE, rows * MAP_SIZE, 1);
      Graphics2D graphics = image.createGraphics();
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      int index = 0;
      for (int[] tile : tiles) {
         graphics.drawImage(render(tile), index % cols * MAP_SIZE, index / cols * MAP_SIZE, null);
         index++;
      }

      graphics.dispose();
      return image;
   }

   public void resetState() {
      this.generation.incrementAndGet();
      this.buffers.clear();
      this.frames.clear();
      this.frameItems.clear();
      this.frameRotations.clear();
      this.look = null;
      this.joinTime = 0L;
   }

   public void log(String message) {
      FileLogger.log("[captcha-save:" + this.botLabel() + "] " + message);
   }

   public String botLabel() {
      return this.bot() == null ? "bot" : this.bot().getName();
   }

   public static BufferedImage render(int[] palette) {
      BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, 1);
      for (int y = 0; y < MAP_SIZE; y++) {
         for (int x = 0; x < MAP_SIZE; x++) {
            int color = MapColor.getRenderColor(palette[x + y * MAP_SIZE]);
            if (color >>> 24 == 0) {
               color = 0xFFFFFF;
            }

            image.setRGB(x, y, color & 0xFFFFFF);
         }
      }

      return image;
   }

   public static BufferedImage resizeForApi(BufferedImage source) {
      if (source.getWidth() == 250 && source.getHeight() == 150) {
         return source;
      }

      BufferedImage image = new BufferedImage(250, 150, 1);
      Graphics2D graphics = image.createGraphics();
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, 250, 150, null);
      graphics.dispose();
      return image;
   }

   public static boolean isUniform(int[] palette) {
      int first = palette[0];
      for (int value : palette) {
         if (value != first) {
            return false;
         }
      }

      return true;
   }

   public static String rootMessage(Throwable throwable) {
      Throwable root = throwable;
      while (root.getCause() != null && root.getCause() != root) {
         root = root.getCause();
      }

      String message = root.getMessage();
      return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
   }
}
