package org.zenith.base.bot.modules.impl;

import com.darkmagician6.eventapi.EventTarget;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker.SerializedEntry;
import net.minecraft.entity.EntityPosition;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState.UpdateData;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import org.zenith.ZenithClient;
import org.zenith.base.bot.client.BotClient;
import org.zenith.base.bot.modules.api.BotModule;
import org.zenith.core.FileLogger;
import org.zenith.core.OcrSpaceCaptchaSolver;
import org.zenith.event.BotPacketEvent;
import org.zenith.module.Category;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.TextSetting;
import org.zenith.utility.mixin.accessors.ItemFrameEntityAccessor;

@ModuleInfo(name = "BotAutoCapcha", category = Category.MISC, description = "Автоматически решает пятизначную капчу через OCR.Space")
public final class BotAutoCapcha extends BotModule {
   public final TextSetting ocrApiKey = new TextSetting("OCR.Space API key",
      "Ключ OCR.Space; хранится в локальном конфиге бота", "", "Введите API-ключ").secret();
   public static final int MAP_SIZE = 128;
   public static final int API_WIDTH = 250;
   public static final int API_HEIGHT = 150;
   public static final int MAX_RETRIES = 3;
   public static final long POST_JOIN_WINDOW_MS = 15000L;
   public static final long KEYWORD_WINDOW_MS = 10000L;
   public static final long SETTLE_MS = 700L;
   public static final long WALL_WAIT_MS = 5000L;
   public static final long COOLDOWN_MS = 6000L;
   public static final String[] KEYWORDS = new String[]{
      "капч", "captcha", "введите код", "введи код", "код с карты", "введите номер", "номер с картин", "с картинки", "картинке", "введите число"
   };
   public static final String[] SUCCESS_MARKERS = new String[]{"успеш", "passed", "verified"};
   public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(var0 -> {
      Thread thread = new Thread(var0, "bot-auto-capcha");
      thread.setDaemon(true);
      return thread;
   });
   public final Map<Integer, int[]> buffers = new ConcurrentHashMap<>();
   public final Map<Integer, double[]> frames = new ConcurrentHashMap<>();
   public final Map<Integer, Integer> frameItems = new ConcurrentHashMap<>();
   public final Map<Integer, Integer> frameRotations = new ConcurrentHashMap<>();
   public final AtomicBoolean pending = new AtomicBoolean();
   public final AtomicBoolean solving = new AtomicBoolean();
   public final AtomicLong generation = new AtomicLong();
   public final Object bufferLock = new Object();
   public volatile double[] look;
   public volatile long joinTime;
   public volatile long keywordTime;
   public volatile long lastTileTime;
   public volatile long lastAttemptTime;
   public volatile int lastMapId = -1;
   public volatile int lastAttemptHash;
   public volatile String pendingSavePath;
   public volatile String pendingSentCode;

   @Override
   public synchronized void onEnable() {
      super.onEnable();
      this.resetState(true);
      this.log("enabled and armed");
   }

   @Override
   public synchronized void onDisable() {
      this.resetState(false);
      super.onDisable();
   }

   @EventTarget
   public synchronized void onPacket(BotPacketEvent var1) {
      Packet packet = var1.ItemScroller();
      BotAutoCapcha_SolveTarget botautocapcha_solvetarget = this.currentTarget();
      if (packet instanceof GameJoinS2CPacket || packet instanceof PlayerRespawnS2CPacket) {
         this.resetState(true);
         this.log("join detected, armed");
      } else if (packet instanceof PlayerPositionLookS2CPacket playerpositionlooks2cpacket) {
         EntityPosition playerposition = playerpositionlooks2cpacket.change();
         this.look = new double[]{
            playerposition.position().x, playerposition.position().y, playerposition.position().z,
            playerposition.yaw(), playerposition.pitch()
         };
      } else {
         if (packet instanceof EntitySpawnS2CPacket entityspawns2cpacket
            && (entityspawns2cpacket.getEntityType() == EntityType.ITEM_FRAME || entityspawns2cpacket.getEntityType() == EntityType.GLOW_ITEM_FRAME)) {
            this.frames
               .put(
                  entityspawns2cpacket.getEntityId(),
                  new double[]{
                     entityspawns2cpacket.getX(),
                     entityspawns2cpacket.getY(),
                     entityspawns2cpacket.getZ(),
                     entityspawns2cpacket.getEntityData()
                  }
               );
            return;
         }

         if (packet instanceof EntityTrackerUpdateS2CPacket entitytrackerupdates2cpacket) {
            this.handleTrackedValues(entitytrackerupdates2cpacket, botautocapcha_solvetarget);
            return;
         }

         if (packet instanceof MapUpdateS2CPacket mapupdates2cpacket) {
            this.handleMapUpdate(mapupdates2cpacket.mapId().id(), (UpdateData)mapupdates2cpacket.updateData().orElse(null), botautocapcha_solvetarget);
            return;
         }

         String s = packetText(packet);
         if (s != null) {
            this.handleText(s, botautocapcha_solvetarget);
         }
      }
   }

   public void handleTrackedValues(EntityTrackerUpdateS2CPacket var1, BotAutoCapcha_SolveTarget var2) {
      for (SerializedEntry serializedentry : var1.trackedValues()) {
         if (serializedentry.value() instanceof ItemStack itemstack) {
            Object var8 = itemstack.get(DataComponentTypes.MAP_ID);
            if (var8 != null) {
               this.frameItems.put(var1.id(), ((MapIdComponent)var8).id());
               this.lastTileTime = System.currentTimeMillis();
               this.tryTrigger(var2);
            }
         } else if (serializedentry.id() == ItemFrameEntityAccessor.getRotationData().id()
            && serializedentry.value() instanceof Integer integer) {
            this.frameRotations.put(var1.id(), integer);
            this.lastTileTime = System.currentTimeMillis();
         }
      }
   }

   public void handleMapUpdate(int var1, UpdateData var2, BotAutoCapcha_SolveTarget var3) {
      if (var2 != null && var2.width() > 0 && var2.height() > 0) {
         byte[] abyte = var2.colors();
         int i = var2.width();
         int j = var2.height();
         if (abyte.length >= i * j) {
            synchronized (this.bufferLock) {
               int[] aint = this.buffers.computeIfAbsent(var1, var0 -> new int[16384]);

               for (int k = 0; k < j; k++) {
                  int l = var2.startZ() + k;
                  if (l >= 0 && l < 128) {
                     for (int i1 = 0; i1 < i; i1++) {
                        int j1 = var2.startX() + i1;
                        if (j1 >= 0 && j1 < 128) {
                           aint[j1 + l * 128] = abyte[i1 + k * i] & 255;
                        }
                     }
                  }
               }
            }

            this.lastMapId = var1;
            this.lastTileTime = System.currentTimeMillis();
            this.tryTrigger(var3);
         }
      }
   }

   public void handleText(String var1, BotAutoCapcha_SolveTarget var2) {
      if (!var1.isBlank()) {
         String s = var1.toLowerCase(Locale.ROOT);

         for (String s1 : KEYWORDS) {
            if (s.contains(s1)) {
               this.keywordTime = System.currentTimeMillis();
               this.log("captcha prompt: " + FileLogger.trim(var1));
               this.markSavedWrong();
               this.tryTrigger(var2);
               return;
            }
         }

         for (String s2 : SUCCESS_MARKERS) {
            if (s.contains(s2)) {
               this.log("captcha accepted: " + FileLogger.trim(var1));
               this.markSavedSolved();
               this.resetState(false);
               return;
            }
         }
      }
   }

   public void tryTrigger(BotAutoCapcha_SolveTarget var1) {
      if (var1 != null && (this.lastMapId >= 0 || !this.frameItems.isEmpty())) {
         long i = System.currentTimeMillis();
         boolean flag = this.joinTime != 0L && i - this.joinTime <= 15000L;
         boolean flag1 = this.keywordTime != 0L && i - this.keywordTime <= 10000L;
         if ((flag || flag1) && this.pending.compareAndSet(false, true)) {
            long j = this.generation.get();
            EXECUTOR.submit(() -> {
               try {
                  long k;
                  while (j == this.generation.get() && (k = System.currentTimeMillis() - this.lastTileTime) < 700L) {
                     Thread.sleep(700L - k);
                  }

                  if (j == this.generation.get()) {
                     this.awaitFullWall(j);
                     this.launch(var1, j);
                  }
               } catch (InterruptedException interruptedexception) {
                  Thread.currentThread().interrupt();
               } finally {
                  this.pending.set(false);
               }
            });
         }
      }
   }

   public void awaitFullWall(long var1) throws InterruptedException {
      long i = System.currentTimeMillis() + 5000L;

      while (var1 == this.generation.get() && System.currentTimeMillis() < i) {
         BotAutoCapcha_Stitched botautocapcha_stitched = this.stitchCaptchaWall();
         if (botautocapcha_stitched == null || botautocapcha_stitched.tiles() >= botautocapcha_stitched.cols() * botautocapcha_stitched.rows()) {
            return;
         }

         Thread.sleep(250L);
      }
   }

   public void launch(BotAutoCapcha_SolveTarget var1, long var2) {
      if (var2 == this.generation.get() && this.isEnabled() && this.solving.compareAndSet(false, true)) {
         long tileTime = this.lastTileTime;
         BotAutoCapcha_Stitched botautocapcha_stitched = this.stitchCaptchaWall();
         BufferedImage bufferedimage;
         int i;
         if (botautocapcha_stitched != null) {
            if (botautocapcha_stitched.tiles() != botautocapcha_stitched.cols() * botautocapcha_stitched.rows()) {
               this.solving.set(false);
               return;
            }
            bufferedimage = botautocapcha_stitched.image();
            i = botautocapcha_stitched.hash();
            log(var1, "prepared wall " + botautocapcha_stitched.cols() + "x" + botautocapcha_stitched.rows() + " tiles=" + botautocapcha_stitched.tiles());
         } else {
            int[] aint = this.cloneBuffer(this.lastMapId);
            if (aint == null || isUniform(aint)) {
               this.solving.set(false);
               return;
            }

            bufferedimage = render(aint);
            i = Arrays.hashCode(aint);
            log(var1, "prepared map " + this.lastMapId);
         }

         long j = System.currentTimeMillis();
         if (i == this.lastAttemptHash && j - this.lastAttemptTime < 6000L) {
            this.solving.set(false);
         } else {
            this.lastAttemptHash = i;
            this.pendingSavePath = this.saveCaptcha(bufferedimage);
            this.pendingSentCode = null;
            EXECUTOR.submit(() -> this.solve(bufferedimage, var1, var2, tileTime));
         }
      }
   }

   public void solve(BufferedImage var1, BotAutoCapcha_SolveTarget var2, long var3, long tileTime) {
      try {
         if (!this.isCurrentRequest(var2, var3, tileTime)) {
            return;
         }
         log(var2, "OCR.Space captcha request");
         String answer = OcrSpaceCaptchaSolver.solve(encodePng(var1), this.ocrApiKey.getValue(), MinecraftClient.getInstance().runDirectory.toPath(),
            () -> this.isCurrentRequest(var2, var3, tileTime));
         if (!this.isCurrentRequest(var2, var3, tileTime)) {
            log(var2, "discarded stale OCR.Space response");
            return;
         }
         if (answer == null || !answer.matches("[0-9]{5}")) {
            feedback(var2, "OCR.Space не распознал ровно 5 цифр; ответ не отправлен");
            return;
         }
         synchronized (this) {
            if (!this.isCurrentRequest(var2, var3, tileTime)) {
               return;
            }
            if (this.sendAnswer(var2, answer)) {
               this.pendingSentCode = answer;
               this.markSaved("sent", answer);
               log(var2, "OCR.Space answer sent: " + answer);
               feedback(var2, "ответ капчи отправлен -> " + answer);
               this.resetState(false);
            } else {
               feedback(var2, "не удалось отправить ответ капчи");
            }
         }
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
      } catch (Exception exception) {
         log(var2, "OCR.Space request failed: " + exception.getClass().getSimpleName());
         if (this.isCurrentRequest(var2, var3, tileTime)) {
            feedback(var2, "OCR.Space недоступен: проверь ключ, лимит и соединение");
         }
      } finally {
         if (var3 == this.generation.get()) {
            this.lastAttemptTime = System.currentTimeMillis();
            this.solving.set(false);
         }
      }
   }

   private boolean isCurrentRequest(BotAutoCapcha_SolveTarget target, long generation, long tileTime) {
      return generation == this.generation.get() && tileTime == this.lastTileTime && this.isEnabled() && target.isConnected();
   }

   public int expectedLength(BotAutoCapcha_SolveTarget var1) {
      String s = var1.bot().getAddress();
      return s.toLowerCase(Locale.ROOT).contains("funtime.su") ? 5 : 0;
   }

   public boolean sendAnswer(BotAutoCapcha_SolveTarget var1, String var2) {
      return var1.bot().sendChat(var2);
   }

   public BotAutoCapcha_Stitched stitchCaptchaWall() {
      Map<Long, List<int[]>> hashmap = new HashMap<>();

      for (Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         double[] adouble = this.frames.get(entry.getKey());
         if (adouble != null && this.buffers.containsKey(entry.getValue())) {
            int i = (int)adouble[3];
            if (i >= 2 && i <= 5) {
               double d0 = i >= 4 ? adouble[0] : adouble[2];
               long j = (long)i << 40 ^ Math.round(d0 * 4.0) & 1099511627775L;
               hashmap.computeIfAbsent(j, var0 -> new ArrayList<>()).add(new int[]{entry.getKey(), entry.getValue()});
            }
         }
      }

      List<int[]> list = null;
      Integer integer = this.raycastFrame();
      if (integer != null) {
         double[] adouble0 = this.frames.get(integer);
         if (adouble0 != null && this.buffers.containsKey(this.frameItems.get(integer))) {
            list = hashmap.get(this.planeKey((int)adouble0[3], adouble0));
            if (list != null) {
               this.log("raycast wall: frame=" + integer + " tiles=" + list.size());
            }
         }
      }

      if (list == null) {
         list = this.choosePlane(hashmap);
      }

      if (list != null && !list.isEmpty()) {
         double[] adouble3 = this.frames.get(list.getFirst()[0]);
         if (adouble3 == null) {
            return null;
         }

         int l1 = (int)adouble3[3];
         boolean flag = l1 >= 4;
         double d5 = Double.MAX_VALUE;
         double d6 = -Double.MAX_VALUE;
         double d1 = Double.MAX_VALUE;
         double d2 = -Double.MAX_VALUE;

         for (int[] aint : list) {
            double[] adouble1 = this.frames.get(aint[0]);
            if (adouble1 != null) {
               double d3 = flag ? adouble1[2] : adouble1[0];
               d5 = Math.min(d5, d3);
               d6 = Math.max(d6, d3);
               d1 = Math.min(d1, adouble1[1]);
               d2 = Math.max(d2, adouble1[1]);
            }
         }

         int i2 = (int)Math.round(d6 - d5) + 1;
         int j2 = (int)Math.round(d2 - d1) + 1;
         if (i2 >= 1 && j2 >= 1 && i2 <= 8 && j2 <= 8 && i2 * j2 >= 2) {
            BufferedImage bufferedimage = new BufferedImage(i2 * 128, j2 * 128, 1);
            Graphics2D graphics2d = bufferedimage.createGraphics();
            graphics2d.setColor(Color.WHITE);
            graphics2d.fillRect(0, 0, bufferedimage.getWidth(), bufferedimage.getHeight());
            int k = 1;
            int l = 0;

            for (int[] aint1 : list) {
               int[] aint2 = this.cloneBuffer(aint1[1]);
               double[] adouble2 = this.frames.get(aint1[0]);
               if (aint2 != null && adouble2 != null) {
                  int i1 = this.frameRotations.getOrDefault(aint1[0], 0) & 3;
                  aint2 = rotateClockwise(aint2, i1);
                  double d4 = flag ? adouble2[2] : adouble2[0];

                  int j1 = switch (l1) {
                     case 3, 4 -> (int)Math.round(d4 - d5);
                     default -> (int)Math.round(d6 - d4);
                  };
                  int k1 = (int)Math.round(d2 - adouble2[1]);
                  if (j1 >= 0 && j1 < i2 && k1 >= 0 && k1 < j2) {
                     graphics2d.drawImage(render(aint2), j1 * 128, k1 * 128, null);
                     k = k * 31 + aint1[1];
                     k = k * 31 + Arrays.hashCode(aint2);
                     k = k * 31 + j1 * 17 + k1;
                     l++;
                  }
               }
            }

            graphics2d.dispose();
            return l == 0 ? null : new BotAutoCapcha_Stitched(bufferedimage, k, i2, j2, l);
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   public long planeKey(int facing, double[] frame) {
      double d0 = facing >= 4 ? frame[0] : frame[2];
      return (long)facing << 40 ^ Math.round(d0 * 4.0) & 1099511627775L;
   }

   // Рейкаст взглядом бота: возвращает id рамки, в которую попадает луч (примерно 1x1 область).
   // Анти-капча расставляет фейковые стены вокруг бота — правильная всегда под прицелом.
   public Integer raycastFrame() {
      double[] adouble = this.look;
      if (adouble == null) {
         return null;
      }

      double d0 = Math.toRadians(adouble[3]);
      double d1 = Math.toRadians(adouble[4]);
      double d2 = -Math.sin(d0) * Math.cos(d1);
      double d3 = -Math.sin(d1);
      double d4 = Math.cos(d0) * Math.cos(d1);
      Integer integer = null;
      double d5 = Double.MAX_VALUE;

      for (Entry<Integer, Integer> entry : this.frameItems.entrySet()) {
         if (!this.buffers.containsKey(entry.getValue())) {
            continue;
         }

         double[] adouble1 = this.frames.get(entry.getKey());
         if (adouble1 == null) {
            continue;
         }

         int i = (int)adouble1[3];
         if (i < 2 || i > 5) {
            continue;
         }

         // Нормаль передней стороны рамки: 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x)
         double d6 = i == 2 ? -1.0 : i == 3 ? 1.0 : 0.0;
         double d7 = i == 4 ? -1.0 : i == 5 ? 1.0 : 0.0;
         if (d2 * d6 + d4 * d7 >= -0.05) {
            continue;
         }

         double d8 = i >= 4 ? adouble1[0] + 0.5 : adouble1[2] + 0.5;
         double d9 = i >= 4 ? d2 : d4;
         if (Math.abs(d9) < 1.0E-4) {
            continue;
         }

         double d10 = (d8 - (i >= 4 ? adouble[0] : adouble[2])) / d9;
         if (d10 <= 0.0 || d10 > 48.0) {
            continue;
         }

         double d11 = adouble[0] + d2 * d10 - (adouble1[0] + 0.5);
         double d12 = adouble[1] + d3 * d10 - (adouble1[1] + 0.5);
         double d13 = adouble[2] + d4 * d10 - (adouble1[2] + 0.5);
         double d14 = i >= 4 ? d13 : d11;
         if (Math.abs(d14) <= 0.55 && Math.abs(d12) <= 0.55 && d10 < d5) {
            d5 = d10;
            integer = entry.getKey();
         }
      }

      return integer;
   }

   public List<int[]> choosePlane(Map<Long, List<int[]>> var1) {
      if (var1.isEmpty()) {
         return null;
      }

      List<int[]> list = null;
      double d0 = -1.0;
      double[] adouble = this.look;
      if (adouble != null) {
         double d1 = Math.toRadians(adouble[3]);
         double d2 = -Math.sin(d1);
         double d3 = Math.cos(d1);

         for (List<int[]> list2 : var1.values()) {
            double d4 = 0.0;
            double d5 = 0.0;
            int i = 0;

            for (int[] aint : list2) {
               double[] adouble1 = this.frames.get(aint[0]);
               if (adouble1 != null) {
                  d4 += adouble1[0];
                  d5 += adouble1[2];
                  i++;
               }
            }

            if (i != 0) {
               d4 /= i;
               d5 /= i;
               double d9 = d4 - adouble[0];
               double d10 = d5 - adouble[2];
               double d6 = Math.sqrt(d9 * d9 + d10 * d10);
               if (!(d6 < 0.01) && !(d6 > 32.0)) {
                  double d7 = d9 / d6 * d2 + d10 / d6 * d3;
                  double d8 = d7 < 0.2 ? -1.0 : d7 * 100.0 / (1.0 + d6);
                  if (d8 > d0) {
                     d0 = d8;
                     list = list2;
                  }
               }
            }
         }
      }

      if (list == null) {
         for (List<int[]> list1 : var1.values()) {
            if (list == null || list1.size() > list.size()) {
               list = list1;
            }
         }
      }

      return list;
   }

   public int[] cloneBuffer(int var1) {
      synchronized (this.bufferLock) {
         int[] aint = this.buffers.get(var1);
         return aint == null ? null : (int[])aint.clone();
      }
   }

   // --- Сбор датасета: каждая капча пишется в <runDir>/captcha/hard и перемаркивается по исходу ---

   public String saveCaptcha(BufferedImage var1) {
      try {
         Path path = MinecraftClient.getInstance().runDirectory.toPath().resolve("captcha").resolve("hard");
         Files.createDirectories(path);
         String s = "unread_" + System.currentTimeMillis() + ".png";
         Path path2 = path.resolve(s);
         ImageIO.write(var1, "png", path2.toFile());
         return path2.toString();
      } catch (Exception var6) {
         this.log("captcha save failed: " + rootMessage(var6));
         return null;
      }
   }

   public void markSaved(String var1, String var2) {
      String s = this.pendingSavePath;
      if (s != null && Files.exists(Path.of(s))) {
         String s1 = Path.of(s).getFileName().toString();
         int i = s1.indexOf('_');
         int j = s1.lastIndexOf('.');
         String s2 = i >= 0 && j > i ? s1.substring(i + 1, j) : String.valueOf(System.currentTimeMillis());
         String s3 = var1 + (var2 == null ? "" : "_" + var2) + "_" + s2 + ".png";
         Path path = Path.of(s).getParent().resolve(s3);
         try {
            Files.move(Path.of(s), path, StandardCopyOption.REPLACE_EXISTING);
            this.pendingSavePath = path.toString();
         } catch (Exception var10) {
            this.log("captcha rename failed: " + rootMessage(var10));
         }
      }
   }

   public void markSavedSolved() {
      String s = this.pendingSentCode;
      if (s != null) {
         this.markSaved("solved", s);
      }
   }

   public void markSavedWrong() {
      String s = this.pendingSentCode;
      if (s != null) {
         this.markSaved("wrong", s);
         this.pendingSentCode = null;
      }
   }

   public synchronized void resetState(boolean var1) {
      this.generation.incrementAndGet();
      synchronized (this.bufferLock) {
         this.buffers.clear();
      }

      this.frames.clear();
      this.frameItems.clear();
      this.frameRotations.clear();
      this.pending.set(false);
      this.solving.set(false);
      this.look = null;
      this.lastMapId = -1;
      this.lastAttemptHash = 0;
      this.lastAttemptTime = 0L;
      this.lastTileTime = System.currentTimeMillis();
      this.keywordTime = 0L;
      this.joinTime = var1 ? System.currentTimeMillis() : 0L;
   }

   public BotAutoCapcha_SolveTarget currentTarget() {
      BotClient botclient = this.bot();
      return botclient != null && botclient.isJoined() ? new BotAutoCapcha_SolveTarget(botclient) : null;
   }

   public void log(String var1) {
      FileLogger.log("[" + this.botLabel() + "] " + var1);
   }

   public static void log(BotAutoCapcha_SolveTarget var0, String var1) {
      FileLogger.log("[" + var0.label() + "] " + var1);
   }

   public String botLabel() {
      BotClient botclient = this.bot();
      return botclient == null ? "bot" : botclient.getName();
   }

   public static void feedback(BotAutoCapcha_SolveTarget var0, String var1) {
      var0.bot().execute(() -> var0.bot().systemMessage("AutoCapcha: " + var1));
   }

   public static String packetText(Packet<?> var0) {
      if (var0 instanceof GameMessageS2CPacket gamemessages2cpacket) {
         return gamemessages2cpacket.content().getString();
      } else if (var0 instanceof ProfilelessChatMessageS2CPacket profilelesschatmessages2cpacket) {
         return profilelesschatmessages2cpacket.message().getString();
      } else if (var0 instanceof ChatMessageS2CPacket chatmessages2cpacket) {
         return chatmessages2cpacket.unsignedContent() == null ? chatmessages2cpacket.body().content() : chatmessages2cpacket.unsignedContent().getString();
      } else if (var0 instanceof OverlayMessageS2CPacket overlaymessages2cpacket) {
         return overlaymessages2cpacket.text().getString();
      } else if (var0 instanceof SubtitleS2CPacket subtitles2cpacket) {
         return subtitles2cpacket.text().getString();
      } else {
         return var0 instanceof TitleS2CPacket titles2cpacket ? titles2cpacket.text().getString() : null;
      }
   }

   public static boolean isSafeAnswer(String var0) {
      if (var0 == null) {
         return false;
      }

      String s = var0.trim();
      return !s.isEmpty() && s.length() <= 64 ? s.codePoints().allMatch(var0x -> Character.isLetterOrDigit(var0x) || var0x == 95 || var0x == 45) : false;
   }

   public static int[] rotateClockwise(int[] var0, int var1) {
      int[] aint = var0;

      for (int i = 0; i < var1; i++) {
         int[] aint1 = new int[16384];

         for (int j = 0; j < 128; j++) {
            for (int k = 0; k < 128; k++) {
               aint1[k + j * 128] = aint[j + (127 - k) * 128];
            }
         }

         aint = aint1;
      }

      return aint;
   }

   public static BufferedImage resizeForApi(BufferedImage var0) {
      if (var0.getWidth() == 250 && var0.getHeight() == 150) {
         return var0;
      }

      BufferedImage bufferedimage = new BufferedImage(250, 150, 1);
      Graphics2D graphics2d = bufferedimage.createGraphics();
      graphics2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics2d.drawImage(var0, 0, 0, 250, 150, null);
      graphics2d.dispose();
      return bufferedimage;
   }

   public static byte[] encodePng(BufferedImage var0) throws Exception {
      ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();
      if (!ImageIO.write(var0, "png", bytearrayoutputstream)) {
         throw new IllegalStateException("PNG encoder is unavailable");
      } else {
         return bytearrayoutputstream.toByteArray();
      }
   }

   public static String rootMessage(Throwable var0) {
      Throwable throwable = var0;

      while (throwable.getCause() != null && throwable.getCause() != throwable) {
         throwable = throwable.getCause();
      }

      String s = throwable.getMessage();
      return s != null && !s.isBlank() ? s : throwable.getClass().getSimpleName();
   }

   public static BufferedImage render(int[] var0) {
      BufferedImage bufferedimage = new BufferedImage(128, 128, 1);

      for (int i = 0; i < 128; i++) {
         for (int j = 0; j < 128; j++) {
            int k = MapColor.getRenderColor(var0[j + i * 128]);
            if (k >>> 24 == 0) {
               k = 16777215;
            }

            bufferedimage.setRGB(j, i, k & 16777215);
         }
      }

      return bufferedimage;
   }

   public static boolean isUniform(int[] var0) {
      int i = var0[0];

      for (int j : var0) {
         if (j != i) {
            return false;
         }
      }

      return true;
   }
}
