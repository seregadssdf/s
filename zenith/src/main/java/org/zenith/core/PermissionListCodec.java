package org.zenith.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

public final class PermissionListCodec {
   public static final int int373 = 0;
   public static final int int374 = 1;
   public static final int int375 = 2;
   public static final int int376 = 3;
   public static final int int377 = 4;
   public static final String[] call175 = new String[]{"flick", "correction", "tracking", "settle"};
   public static final int int378 = 8;
   /**
    * Веса осей дескриптора в отборе примитива. Нули на peak_phase и straightness — это «ось не
    * участвует», и цикл отбора такие оси пропускает по условию на строке 62.
    *
    * Раньше здесь стояло 0.5 на обеих. Селектор предсказывает только четыре параметра
    * (sum_yaw_rel_slog, sum_pitch_rel_slog, length_log, peak_speed_log), поэтому запрос по осям 4-5
    * собирается из нулей — см. MotorIntentModel.on23, где adouble1[4] и adouble1[5] выставлены в 0.0.
    * Но ноль в запросе не значит «всё равно»: он стандартизуется как (0 - mean)/std и становится
    * запросом на конкретное значение. По straightness среднее по библиотеке 0.97 при разбросе 0.068,
    * то есть ноль — это z = -14.2, и с весом 0.5 одна эта ось забирает 84% бюджета расстояния.
    * Оси, которая несёт запрошенный угол (sum_yaw_slog), остаётся 3.4%, а её собственный разброс по
    * библиотеке всего z = -1.42..1.29 — то есть отбор шёл по кривизне, а запрошенный угол в него
    * почти не попадал.
    *
    * Прямизна около нуля — это жест, который мотает кистью на месте: по замеру у flick-примитивов со
    * straightness < 0.5 медиана пути 239 щелчков, а итогового смещения 95, тогда как при
    * straightness > 0.9 путь 246 и смещение 242. Отбор выдавал именно первые: средняя сумма по яву в
    * шестнадцати ближайших держалась около 75-105 щелчков независимо от запроса, хотя на 90°
    * (424 щелчка) от жеста нужно на порядок больше. Отсюда и «плохо доводит на большой ошибке»:
    * ошибка растёт, а жест на неё не отвечает — аура водит прицелом рядом с целью вместо захода на
    * неё. С нулевыми весами те же шестнадцать дают 224 щелчка по яву при прямизне 0.985.
    */
   public static final float[] call099 = new float[]{1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.6F, 0.6F};
   /**
    * Индекс, с которого в библиотеке идут записанные игроком примитивы, или -1, если их нет.
    * Пишется в шапку сборщиком ({@code AimSampleLibrary.merge}) и читается загрузчиком ниже.
    */
   public int userFrom = -1;
   /**
    * Отбирать только из записанных примитивов. Ставится модулем записи и нужен для сравнения A/B:
    * иначе своя сотня жестов тонет в 23075 штатных и по поведению не отличить, работает она или нет.
    *
    * <p>Флаг статический, потому что отбор идёт из глубины модели, куда настройку модуля не
    * протащить, не меняя подписи половины вызовов.
    */
   public static boolean onlyUserPrimitives = false;
   public static final String string104 = "/assets/zenith/aimpipe/intent_library_rt.json";
   public static final Path path12 = Path.of("intent_library_rt.json");
   public static final Path[] call413 = new Path[]{
      Path.of("scripts", "aimpipe", "intent_library_rt.json"), Path.of("..", "scripts", "aimpipe", "intent_library_rt.json")
   };
   public final IntGridCell[] call104;
   public final NumericMatrix[] call080 = new NumericMatrix[4];

   /**
    * Запомнить границу записанных примитивов и разложить их количество по банкам интентов.
    *
    * <p>Считается один раз при загрузке, а не в отборе: отбор идёт каждый тик, и пробег по всему
    * банку ради счётчика был бы лишней работой на ровном месте.
    */
   public void setUserFrom(int var1) {
      this.userFrom = var1 < 0 || var1 >= this.call104.length ? -1 : var1;

      for (NumericMatrix l1i1liliili_ii1il11l111ii11iil : this.call080) {
         l1i1liliili_ii1il11l111ii11iil.userCount = 0;
         if (this.userFrom >= 0) {
            for (int i : l1i1liliili_ii1il11l111ii11iil.ids) {
               if (i >= this.userFrom) {
                  l1i1liliili_ii1il11l111ii11iil.userCount++;
               }
            }
         }
      }
   }

   /** Сколько записанных примитивов в банке интента. */
   public int userCount(int var1) {
      return this.call080[var1].userCount;
   }

   public IntGridCell ModuleSnapshotDto(int var1) {
      return this.call104[var1];
   }

   public int size() {
      return this.call104.length;
   }

   public IntGridCell on23(
      int var1,
      double[] var2,
      VectorRange var3,
      Random var4,
      int var5,
      double var6,
      int var8,
      double var9,
      double var11,
      double var13,
      double var15,
      double var17,
      double var19
   ) {
      NumericMatrix l1i1liliili_ii1il11l111ii11iil = this.call080[var1];
      int i = l1i1liliili_ii1il11l111ii11iil.ids.length;
      double[] adouble = new double[i];
      // Фильтр по записанным включается только если их в банке хотя бы var5 (столько уходит в
      // финальный top-k). Меньше — и больше половины финальной выборки оказалось бы бесконечностями,
      // а медиана расстояний d14 ниже стала бы бесконечной: дальше -inf/inf даёт NaN и отбор ломается.
      boolean flag2 = onlyUserPrimitives && this.userFrom >= 0 && l1i1liliili_ii1il11l111ii11iil.userCount >= var5;

      for (int j = 0; j < i; j++) {
         double d0 = 0.0;
         float[] afloat = l1i1liliili_ii1il11l111ii11iil.call057[j];

         for (int k = 0; k < 8; k++) {
            if (call099[k] != 0.0F) {
               double d2 = (var2[k] - l1i1liliili_ii1il11l111ii11iil.call029[k]) / l1i1liliili_ii1il11l111ii11iil.call105[k];
               double d3 = (afloat[k] - d2) * call099[k];
               d0 += d3 * d3;
            }
         }

         adouble[j] = Math.sqrt(d0);
         if (l1i1liliili_ii1il11l111ii11iil.ids[j] == var8 && i > 1) {
            adouble[j] = Double.POSITIVE_INFINITY;
         }

         // Фильтр «только записанные»: штатные примитивы уводятся в бесконечность, поэтому в top-k
         // не попадают. Условие по userCount обязательно — если своих жестов этого интента нет,
         // фильтр отменяется для него, иначе бесконечными стали бы все и отбор вернул бы мусор.
         if (flag2 && l1i1liliili_ii1il11l111ii11iil.ids[j] < this.userFrom) {
            adouble[j] = Double.POSITIVE_INFINITY;
         }
      }

      int l1 = Math.min(var5 * 4, i);
      int[] aint = on23(adouble, l1);
      double d1 = Math.hypot(var3.double36, var3.double37);
      boolean flag = var13 > 0.0 && (var3.int151 != 0 || var3.int152 != 0);
      double d13 = Math.hypot(var3.int151, var3.int152);
      double[] adouble2 = new double[l1];

      for (int l = 0; l < l1; l++) {
         int i1 = aint[l];
         double d4 = adouble[i1];
         if (var9 > 0.0) {
            double d5 = var3.double36 - l1i1liliili_ii1il11l111ii11iil.call134[i1] + var3.double38 * l1i1liliili_ii1il11l111ii11iil.call172[i1];
            double d6 = var3.double37 - l1i1liliili_ii1il11l111ii11iil.call209[i1] + var3.double39 * l1i1liliili_ii1il11l111ii11iil.call172[i1];
            double d7 = Math.max(var3.double40 * var11, 1.0);
            double d8 = Math.max(var3.double41 * var11, 1.0);
            double d9 = Math.max(Math.abs(d5) - d7, 0.0);
            double d11 = Math.max(Math.abs(d6) - d8, 0.0);
            double d12 = Math.hypot(d9, d11);
            d4 += var9 * Math.log1p(d12);
         }

         if (flag) {
            double d15 = l1i1liliili_ii1il11l111ii11iil.call173[i1] - var3.int151;
            double d17 = l1i1liliili_ii1il11l111ii11iil.call135[i1] - var3.int152;
            double d20 = Math.hypot(d15, d17);
            double d23 = Math.hypot(l1i1liliili_ii1il11l111ii11iil.call173[i1], l1i1liliili_ii1il11l111ii11iil.call135[i1]);
            d4 += var13 * (d20 / (d13 + d23 + 1.0));
         }

         if (var15 > 0.0 && d1 > var19) {
            double d16 = var3.double36 - l1i1liliili_ii1il11l111ii11iil.call030[i1] + var3.double38 * l1i1liliili_ii1il11l111ii11iil.call174[i1];
            double d18 = var3.double37 - l1i1liliili_ii1il11l111ii11iil.call003[i1] + var3.double39 * l1i1liliili_ii1il11l111ii11iil.call174[i1];
            double d21 = Math.hypot(d16, d18) / d1;
            d4 += var15 * Math.abs(d21 - var17);
         }

         adouble2[l] = d4;
      }

      int i2 = Math.min(var5, l1);
      int[] aint1 = on23(adouble2, i2);
      if (var6 <= 1.0E-9) {
         return this.call104[l1i1liliili_ii1il11l111ii11iil.ids[aint[aint1[0]]]];
      }

      double[] adouble3 = new double[i2];

      for (int j1 = 0; j1 < i2; j1++) {
         adouble3[j1] = adouble2[aint1[j1]];
      }

      double d14 = Math.max(on23(adouble3), 1.0E-6);
      double[] adouble1 = new double[i2];
      double d19 = Double.NEGATIVE_INFINITY;

      for (int j2 = 0; j2 < i2; j2++) {
         adouble1[j2] = -adouble3[j2] / (d14 * var6);
         d19 = Math.max(d19, adouble1[j2]);
      }

      double d22 = 0.0;

      for (int k2 = 0; k2 < i2; k2++) {
         adouble1[k2] = Math.exp(adouble1[k2] - d19);
         d22 += adouble1[k2];
      }

      double d24 = var4.nextDouble() * d22;
      int l2 = i2 - 1;
      double d10 = 0.0;

      for (int k1 = 0; k1 < i2; k1++) {
         d10 += adouble1[k1];
         if (d24 <= d10) {
            l2 = k1;
            break;
         }
      }

      return this.call104[l1i1liliili_ii1il11l111ii11iil.ids[aint[aint1[l2]]]];
   }

   public static int[] on23(double[] var0, int var1) {
      Integer[] ainteger = new Integer[var0.length];

      for (int i = 0; i < var0.length; i++) {
         ainteger[i] = i;
      }

      Arrays.sort(ainteger, (var1x, var2x) -> Double.compare(var0[var1x], var0[var2x]));
      int[] aint = new int[var1];

      for (int j = 0; j < var1; j++) {
         aint[j] = ainteger[j];
      }

      return aint;
   }

   public static double on23(double[] var0) {
      double[] adouble = (double[])var0.clone();
      Arrays.sort(adouble);
      int i = adouble.length / 2;
      return adouble.length % 2 == 1 ? adouble[i] : 0.5 * (adouble[i - 1] + adouble[i]);
   }

   public PermissionListCodec(IntGridCell[] var1, float[][] var2) {
      this.call104 = var1;
      int[] aint = new int[4];

      for (IntGridCell l1i1liliili_l1i1illlili : var1) {
         aint[l1i1liliili_l1i1illlili.int154]++;
      }

      for (int j1 = 0; j1 < 4; j1++) {
         NumericMatrix l1i1liliili_ii1il11l111ii11iilxx = new NumericMatrix();
         int l1 = aint[j1];
         l1i1liliili_ii1il11l111ii11iilxx.ids = new int[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call057 = new float[l1][8];
         l1i1liliili_ii1il11l111ii11iilxx.call134 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call209 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call172 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call173 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call135 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call030 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call003 = new float[l1];
         l1i1liliili_ii1il11l111ii11iilxx.call174 = new float[l1];
         this.call080[j1] = l1i1liliili_ii1il11l111ii11iilxx;
      }

      int[] aint1 = new int[4];

      for (IntGridCell l1i1liliili_l1i1illlilix : var1) {
         NumericMatrix l1i1liliili_ii1il11l111ii11iilx = this.call080[l1i1liliili_l1i1illlilix.int154];
         int object = (int)(aint1[l1i1liliili_l1i1illlilix.int154]++);
         l1i1liliili_ii1il11l111ii11iilx.ids[object] = l1i1liliili_l1i1illlilix.int153;
         System.arraycopy(var2[l1i1liliili_l1i1illlilix.int153], 0, l1i1liliili_ii1il11l111ii11iilx.call057[object], 0, 8);
         l1i1liliili_ii1il11l111ii11iilx.call134[object] = l1i1liliili_l1i1illlilix.int155;
         l1i1liliili_ii1il11l111ii11iilx.call209[object] = l1i1liliili_l1i1illlilix.int156;
         l1i1liliili_ii1il11l111ii11iilx.call172[object] = l1i1liliili_l1i1illlilix.length();
         l1i1liliili_ii1il11l111ii11iilx.call173[object] = l1i1liliili_l1i1illlilix.val069[0];
         l1i1liliili_ii1il11l111ii11iilx.call135[object] = l1i1liliili_l1i1illlilix.call106[0];
         int i = Math.min(l1i1liliili_l1i1illlilix.length(), 4);
         int j = 0;
         int k = 0;

         for (int l = 0; l < i; l++) {
            j += l1i1liliili_l1i1illlilix.val069[l];
            k += l1i1liliili_l1i1illlilix.call106[l];
         }

         l1i1liliili_ii1il11l111ii11iilx.call030[object] = j;
         l1i1liliili_ii1il11l111ii11iilx.call003[object] = k;
         l1i1liliili_ii1il11l111ii11iilx.call174[object] = i;
      }

      for (int k1 = 0; k1 < 4; k1++) {
         NumericMatrix l1i1liliili_ii1il11l111ii11iil = this.call080[k1];
         int i2 = l1i1liliili_ii1il11l111ii11iil.ids.length;
         if (i2 == 0) {
            throw new IllegalStateException("library has no primitives of intent " + call175[k1]);
         }

         for (int j2 = 0; j2 < 8; j2++) {
            double d0 = 0.0;

            for (int k2 = 0; k2 < i2; k2++) {
               d0 += l1i1liliili_ii1il11l111ii11iil.call057[k2][j2];
            }

            d0 /= i2;
            double d1 = 0.0;

            for (int l2 = 0; l2 < i2; l2++) {
               double d3 = l1i1liliili_ii1il11l111ii11iil.call057[l2][j2] - d0;
               d1 += d3 * d3;
            }

            double d2 = Math.max(Math.sqrt(d1 / i2), 1.0E-6);
            l1i1liliili_ii1il11l111ii11iil.call029[j2] = (float)d0;
            l1i1liliili_ii1il11l111ii11iil.call105[j2] = (float)d2;

            for (int i1 = 0; i1 < i2; i1++) {
               l1i1liliili_ii1il11l111ii11iil.call057[i1][j2] = (float)((l1i1liliili_ii1il11l111ii11iil.call057[i1][j2] - d0) / d2);
            }
         }
      }
   }

   /**
    * Отпечаток источника, из которого библиотека загружена последний раз: путь, размер и время
    * правки, либо {@code "jar"} для ресурса из архива. Пустая строка — не загружали ни разу.
    *
    * <p>Пишется самим удачным разбором, а не отдельной проверкой путей: сборщик записи создаёт
    * {@code run/intent_library_rt.json}, и порядок источников в {@link #string49()} после этого
    * меняется — до записи бралcя ресурс из жара, после неё файл рядом с игрой. Отпечаток,
    * посчитанный «первым существующим путём» отдельно от загрузки, в этот момент указывал бы уже
    * на новый файл, тогда как в памяти лежала бы библиотека из жара, и {@link #libraryChanged()}
    * ответил бы «не менялась» ровно на той единственной правке, ради которой её и спрашивают.
    */
   public static volatile String librarySourceStamp = "";

   public static PermissionListCodec string49() {
      PermissionListCodec l1i1liliili = EnchantItemSpec(path12);
      if (l1i1liliili != null) {
         return l1i1liliili;
      }

      for (Path path : call413) {
         l1i1liliili = EnchantItemSpec(path);
         if (l1i1liliili != null) {
            return l1i1liliili;
         }
      }

      return string50();
   }

   /**
    * Изменился ли источник библиотеки с момента загрузки. Считается по тем же путям и в том же
    * порядке, что в {@link #string49()}, поэтому переход «ресурс из жара → свой файл» тоже виден.
    */
   public static boolean libraryChanged() {
      return !stampOf().equals(librarySourceStamp);
   }

   /** Отпечаток того источника, который взял бы {@link #string49()} прямо сейчас. */
   public static String stampOf() {
      String s = fileStamp(path12);
      if (s != null) {
         return s;
      }

      for (Path path : call413) {
         s = fileStamp(path);
         if (s != null) {
            return s;
         }
      }

      return "jar";
   }

   /**
    * Отпечаток файла, или null если файла нет. Размер вместе со временем правки: сборка идёт из
    * штатной библиотеки с нуля, поэтому размер после дозаписи одного-двух жестов может совпасть
    * с прошлым при заметно другом содержимом.
    */
   public static String fileStamp(Path var0) {
      try {
         if (!Files.isRegularFile(var0)) {
            return null;
         }

         return var0.toAbsolutePath() + ":" + Files.size(var0) + ":" + Files.getLastModifiedTime(var0).toMillis();
      } catch (Throwable throwable) {
         return null;
      }
   }

   public static PermissionListCodec EnchantItemSpec(Path var0) {
      try {
         if (!Files.isRegularFile(var0)) {
            return null;
         }

         String s = fileStamp(var0);
         PermissionListCodec l1i1liliili = ProfileItemBuilder(Files.readString(var0, StandardCharsets.UTF_8), var0.toAbsolutePath().toString());
         // Отпечаток ставится после разбора: на битом файле загрузка уходит к следующему источнику,
         // и пометить его как загруженный значило бы больше не пробовать перечитать.
         librarySourceStamp = s == null ? "" : s;
         return l1i1liliili;
      } catch (Throwable throwable) {
         System.err.println("[MotorIntent] failed to load " + var0 + ": " + throwable);
         return null;
      }
   }

   public static PermissionListCodec string50() {
      try (InputStream inputstream = PermissionListCodec.class.getResourceAsStream("/assets/zenith/aimpipe/intent_library_rt.json")) {
         if (inputstream == null) {
            return null;
         }

         PermissionListCodec l1i1liliili = ProfileItemBuilder(
            new String(inputstream.readAllBytes(), StandardCharsets.UTF_8), "/assets/zenith/aimpipe/intent_library_rt.json"
         );
         librarySourceStamp = "jar";
         return l1i1liliili;
      } catch (Throwable throwable1) {
         System.err.println("[MotorIntent] failed to load /assets/zenith/aimpipe/intent_library_rt.json: " + throwable1);
         return null;
      }
   }

   public static PermissionListCodec ProfileItemBuilder(String var0, String var1) {
      JsonObject jsonobject = JsonParser.parseString(var0).getAsJsonObject();
      if (jsonobject.get("format_version").getAsInt() == 1 && "motor_intent_runtime_library".equals(jsonobject.get("artifact").getAsString())) {
         JsonArray jsonarray = jsonobject.getAsJsonArray("intent_types");
         if (jsonarray.size() != 4) {
            throw new IllegalStateException("intent taxonomy mismatch");
         }

         for (int i = 0; i < 4; i++) {
            if (!call175[i].equals(jsonarray.get(i).getAsString())) {
               throw new IllegalStateException("intent taxonomy mismatch at " + i);
            }
         }

         JsonArray jsonarray4 = jsonobject.getAsJsonArray("primitives");
         IntGridCell[] al1i1liliili_l1i1illlili = new IntGridCell[jsonarray4.size()];
         float[][] afloat = new float[jsonarray4.size()][8];

         for (int j = 0; j < jsonarray4.size(); j++) {
            JsonArray jsonarray1 = jsonarray4.get(j).getAsJsonArray();
            int k = jsonarray1.get(0).getAsInt();
            if (k < 0 || k >= 4) {
               throw new IllegalStateException("bad intent index " + k);
            }

            JsonArray jsonarray2 = jsonarray1.get(1).getAsJsonArray();
            if (jsonarray2.size() < 1) {
               throw new IllegalStateException("empty primitive " + j);
            }

            int[] aint = new int[jsonarray2.size()];
            int[] aint1 = new int[jsonarray2.size()];

            for (int l = 0; l < jsonarray2.size(); l++) {
               JsonArray jsonarray3 = jsonarray2.get(l).getAsJsonArray();
               aint[l] = jsonarray3.get(0).getAsInt();
               aint1[l] = jsonarray3.get(1).getAsInt();
            }

            JsonArray jsonarray5 = jsonarray1.get(2).getAsJsonArray();
            if (jsonarray5.size() != 8) {
               throw new IllegalStateException("bad descriptor size for primitive " + j);
            }

            for (int i1 = 0; i1 < 8; i1++) {
               afloat[j][i1] = jsonarray5.get(i1).getAsFloat();
            }

            al1i1liliili_l1i1illlili[j] = new IntGridCell(j, k, aint, aint1);
         }

         if (jsonobject.has("count") && jsonobject.get("count").getAsInt() != al1i1liliili_l1i1illlili.length) {
            throw new IllegalStateException("library count mismatch");
         }

         PermissionListCodec l1i1liliili = new PermissionListCodec(al1i1liliili_l1i1illlili, afloat);
         if (jsonobject.has(AimSampleLibrary.USER_FROM)) {
            l1i1liliili.setUserFrom(jsonobject.get(AimSampleLibrary.USER_FROM).getAsInt());
         }

         System.out.println(
            "[MotorIntent] loaded library "
               + var1
               + " ("
               + al1i1liliili_l1i1illlili.length
               + " primitives"
               + (l1i1liliili.userFrom >= 0 ? ", " + (al1i1liliili_l1i1illlili.length - l1i1liliili.userFrom) + " recorded" : "")
               + ")"
         );
         return l1i1liliili;
      } else {
         throw new IllegalStateException("unsupported motor-intent library artifact");
      }
   }
}
