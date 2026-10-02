package org.zenith.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение, счёт дескрипторов и сборка библиотеки жестов наводки.
 *
 * <p>Формат — тот же, что у {@code intent_library_rt.json}: примитив это тройка
 * [интент, кадры [[яв, питч], ...], дескриптор из восьми чисел], единицы — щелчки
 * мыши, тик 50 мс. Поэтому записанное этим классом кладётся к штатной библиотеке
 * без единой правки в загрузчике {@link PermissionListCodec}: он сам пробует
 * {@code run/intent_library_rt.json} раньше ресурса из жара.
 *
 * <p>Шесть из восьми осей дескриптора выводятся из кадров, и формулы здесь сверены
 * с самой библиотекой на всех 23075 примитивах — расхождение 5e-6, то есть
 * округление float32 в JSON. Оси дрифта из кадров не выводятся: это уход самой
 * цели за время жеста, он пишется живьём (см. {@code AimRecorder}).
 */
public final class AimSampleLibrary {
   public static final String ARTIFACT = "motor_intent_runtime_library";
   public static final int FORMAT_VERSION = 1;
   public static final int TICK_MS = 50;
   public static final String UNITS = "mouse_counts";
   /** Ось, с которой начинаются записанные примитивы в собранной библиотеке. */
   public static final String USER_FROM = "zenith_user_from";
   public static final String RESOURCE = "/assets/zenith/aimpipe/intent_library_rt.json";

   private AimSampleLibrary() {
   }

   /** Один записанный жест: интент, кадры в щелчках и накопленный уход цели. */
   public static final class Gesture {
      public final int intent;
      public final int[] yaw;
      public final int[] pitch;
      public final double driftYaw;
      public final double driftPitch;

      public Gesture(int intent, int[] yaw, int[] pitch, double driftYaw, double driftPitch) {
         this.intent = intent;
         this.yaw = yaw;
         this.pitch = pitch;
         this.driftYaw = driftYaw;
         this.driftPitch = driftPitch;
      }

      public int length() {
         return this.yaw.length;
      }
   }

   /** Знаковый логарифм — та же функция, которой модель жмёт свой запрос ({@code InventoryCodec.CloudApiClient}). */
   public static double slog(double var0) {
      return Math.copySign(Math.log1p(Math.abs(var0)), var0);
   }

   /**
    * Восемь осей дескриптора жеста.
    *
    * <p>Оси 0-5 считаются из кадров, 6-7 берутся из накопленного ухода цели. Порядок и формулы
    * обязаны совпадать с {@code descriptor_fields} библиотеки: sum_yaw_slog, sum_pitch_slog,
    * length_log, peak_speed_log, peak_phase, straightness, drift_yaw_slog, drift_pitch_slog.
    *
    * <p>{@code length_log} — это log(кадров + 1), а не log(кадров): проверено на всей библиотеке,
    * второй вариант расходится на 0.29 в медиане. {@code peak_phase} на жесте в один кадр равна 0.
    */
   public static double[] descriptor(Gesture var0) {
      int i = var0.length();
      long j = 0L;
      long k = 0L;
      double d0 = 0.0;
      double d1 = 0.0;
      int l = 0;

      for (int i1 = 0; i1 < i; i1++) {
         j += var0.yaw[i1];
         k += var0.pitch[i1];
         double d2 = Math.hypot(var0.yaw[i1], var0.pitch[i1]);
         d1 += d2;
         if (d2 > d0) {
            d0 = d2;
            l = i1;
         }
      }

      double d3 = Math.hypot(j, k);
      return new double[]{
         slog(j),
         slog(k),
         Math.log(i + 1.0),
         Math.log1p(d0),
         i < 2 ? 0.0 : (double)l / (i - 1),
         d1 > 0.0 ? d3 / d1 : 1.0,
         slog(var0.driftYaw),
         slog(var0.driftPitch)
      };
   }

   /** Обратная к {@link #slog}: из оси дескриптора назад в щелчки. */
   public static double unslog(double var0) {
      return Math.copySign(Math.expm1(Math.abs(var0)), var0);
   }

   /** Примитив как тройка [интент, кадры, дескриптор]. */
   public static JsonArray primitive(Gesture var0) {
      JsonArray jsonarray = new JsonArray();
      jsonarray.add(var0.intent);
      JsonArray jsonarray1 = new JsonArray();

      for (int i = 0; i < var0.length(); i++) {
         JsonArray jsonarray3 = new JsonArray();
         jsonarray3.add(var0.yaw[i]);
         jsonarray3.add(var0.pitch[i]);
         jsonarray1.add(jsonarray3);
      }

      jsonarray.add(jsonarray1);
      JsonArray jsonarray2 = new JsonArray();

      for (double d0 : descriptor(var0)) {
         jsonarray2.add(d0);
      }

      jsonarray.add(jsonarray2);
      return jsonarray;
   }

   /** Шапка библиотеки. Таксономия интентов обязана идти в том же порядке, иначе загрузчик её отвергнет. */
   public static JsonObject header() {
      JsonObject jsonobject = new JsonObject();
      jsonobject.addProperty("format_version", FORMAT_VERSION);
      jsonobject.addProperty("artifact", ARTIFACT);
      jsonobject.addProperty("units", UNITS);
      jsonobject.addProperty("tick_ms", TICK_MS);
      JsonArray jsonarray = new JsonArray();

      for (String s : PermissionListCodec.call175) {
         jsonarray.add(s);
      }

      jsonobject.add("intent_types", jsonarray);
      JsonArray jsonarray1 = new JsonArray();

      for (String s1 : new String[]{
         "sum_yaw_slog", "sum_pitch_slog", "length_log", "peak_speed_log", "peak_phase", "straightness", "drift_yaw_slog", "drift_pitch_slog"
      }) {
         jsonarray1.add(s1);
      }

      jsonobject.add("descriptor_fields", jsonarray1);
      return jsonobject;
   }

   /** Записать жесты в файл библиотечного формата. */
   public static void write(Path var0, List<Gesture> var1) throws Exception {
      JsonObject jsonobject = header();
      JsonArray jsonarray = new JsonArray();

      for (Gesture var0x : var1) {
         jsonarray.add(primitive(var0x));
      }

      jsonobject.addProperty("count", jsonarray.size());
      jsonobject.add("primitives", jsonarray);
      Path path = var0.getParent();
      if (path != null) {
         Files.createDirectories(path);
      }

      Files.writeString(var0, jsonobject.toString(), StandardCharsets.UTF_8);
   }

   /** Прочитать жесты назад. Дрифт восстанавливается из осей 6-7 обратным slog. */
   public static List<Gesture> read(Path var0) throws Exception {
      JsonObject jsonobject = JsonParser.parseString(Files.readString(var0, StandardCharsets.UTF_8)).getAsJsonObject();
      JsonArray jsonarray = jsonobject.getAsJsonArray("primitives");
      List<Gesture> arraylist = new ArrayList<>(jsonarray.size());

      for (int i = 0; i < jsonarray.size(); i++) {
         JsonArray jsonarray1 = jsonarray.get(i).getAsJsonArray();
         int j = jsonarray1.get(0).getAsInt();
         JsonArray jsonarray2 = jsonarray1.get(1).getAsJsonArray();
         int[] aint = new int[jsonarray2.size()];
         int[] aint1 = new int[jsonarray2.size()];

         for (int k = 0; k < jsonarray2.size(); k++) {
            JsonArray jsonarray4 = jsonarray2.get(k).getAsJsonArray();
            aint[k] = jsonarray4.get(0).getAsInt();
            aint1[k] = jsonarray4.get(1).getAsInt();
         }

         JsonArray jsonarray3 = jsonarray1.get(2).getAsJsonArray();
         arraylist.add(new Gesture(j, aint, aint1, unslog(jsonarray3.get(6).getAsDouble()), unslog(jsonarray3.get(7).getAsDouble())));
      }

      return arraylist;
   }

   /** Штатная библиотека из ресурса как JSON. */
   public static JsonObject baseLibrary() throws Exception {
      try (InputStream inputstream = AimSampleLibrary.class.getResourceAsStream(RESOURCE)) {
         if (inputstream == null) {
            throw new IllegalStateException("base library resource missing");
         }

         return JsonParser.parseString(new String(inputstream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      }
   }

   /**
    * Склеить штатную библиотеку с записанными жестами.
    *
    * <p>Записанные идут в хвост, и их первый индекс пишется в {@link #USER_FROM}. Загрузчик
    * незнакомые поля шапки не читает, поэтому файл остаётся валидным и для чужой версии клиента,
    * а отбор по флагу «только записанные» этот индекс использует как границу.
    *
    * <p>Подмешивание сдвигает нормировку банка, потому что mean/std в
    * {@link PermissionListCodec#PermissionListCodec} считаются по всем примитивам своего интента.
    * По замеру на 60 своих жестов на интент сдвиг среднего 0.005-0.014 сигмы у flick, correction и
    * tracking — отбор этого не чувствует. А вот settle такой же добавкой сдвигает std на 7.3%, а
    * среднее на 0.074 сигмы: банк маленький, 1073 примитива против 5-10 тысяч у остальных. То есть
    * записывать микродоводку в товарных количествах — единственный способ здесь что-то расшатать,
    * и если settle начнёт вести себя иначе, смотреть надо на его долю в собранной библиотеке.
    */
   public static JsonObject merge(List<Gesture> var0) throws Exception {
      JsonObject jsonobject = baseLibrary();
      JsonArray jsonarray = jsonobject.getAsJsonArray("primitives");
      int i = jsonarray.size();

      for (Gesture var0x : var0) {
         jsonarray.add(primitive(var0x));
      }

      jsonobject.addProperty("count", jsonarray.size());
      jsonobject.addProperty(USER_FROM, i);
      return jsonobject;
   }
}
