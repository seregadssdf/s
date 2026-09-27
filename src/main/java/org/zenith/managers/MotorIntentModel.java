package org.zenith.managers;

import java.util.Random;
import org.zenith.core.CodecRow;
import org.zenith.core.IntGridCell;
import org.zenith.core.InventoryCodec;
import org.zenith.core.PermissionListCodec;
import org.zenith.core.VectorRange;

public final class MotorIntentModel {
   public static final double double119 = 0.35;
   public static final int int404 = 16;
   /**
    * Температура сэмплирования интента. Та же величина входит в пересчёт множителя шансов в
    * {@link #trackingBias(float[])}, поэтому она одна на оба места: разойдись они — и подпись
    * слайдера «во столько раз реже» перестала бы соответствовать тому, что делает сэмплер.
    */
   public static final double double120 = 0.7;
   /**
    * Во сколько раз реже выбирать tracking. 1.0 — как решает селектор.
    *
    * <p>Поле статическое по той же причине, что {@link PermissionListCodec#onlyUserPrimitives}:
    * выбор интента живёт в глубине модели, куда настройку модуля не протащить, не меняя подписи
    * половины вызовов. Ставится аурой каждый тик и сбрасывается на её выключении — модель шарится
    * с другими модулями через RectBatch, и оставленное смещение утекло бы в их ротации.
    */
   public static volatile float trackingOdds = 1.0F;
   public static final double double121 = 1.0;
   public static final double double122 = 0.0;
   public static final double double123 = 0.0;
   public static final double double124 = 1.0;
   public static final double double125 = 1.0;
   public static final double double126 = 1.0;
   public static final int int405 = 40;
   public static final double double127 = 1.0;
   public static final double double128 = 1.0;
   public static final double double129 = 0.6;
   public static final double double130 = 0.4;
   public static final double double131 = 0.5;
   public static final double double132 = 40.0;
   public static final int int406 = 2;
   /**
    * Во сколько тиков растягивается один кадр жеста. 1 — как записано, 4 — каждый кадр выдаётся за
    * четыре тика четвертинками, то есть частота любой дрожи внутри жеста делится на четыре, а
    * движение по яву и питчу идёт мельче и плавнее. Сумма щелчков за кадр сохраняется точно.
    *
    * Мелкие кадры при этом становятся разреженными: кадр в один щелчок раскладывается как 0,1,0,0 —
    * то есть на доводке аура стоит три тика из четырёх и двигается на четвёртом. Так же выглядит
    * живая рука, но скорость слежения падает во столько же раз.
    */
   public static final int int411 = 4;
   /**
    * То же для фазы захвата, пока прицел ещё вне габаритов цели. Здесь растяжка стоит 1, то есть
    * жест играется с родной скоростью библиотеки: каждый тик даёт ненулевой ход, разреженных
    * подкадров нет, наводка идёт настолько быстро, насколько записана рука.
    *
    * Растяжка полезна только на доводке, где важна мелкая низкочастотная дрожь, а на захвате она
    * умножала длину жеста и вместе с ней время наводки: медианный flick 4 кадра превращался в 16
    * тиков, tracking 6 кадров — в 24 тика, и всё это время модель не пересматривает цель, потому
    * что реплей отдаётся кадр за кадром до конца. Усиление дрожи double138 при этом работает в
    * обеих фазах: оно не стоит ни одного лишнего тика.
    */
   public static final int int413 = 1;
   /**
    * Ширина удержания отклонения в кадрах на фазе захвата. Отклонение кадра от среднего по жесту —
    * это и есть дрожь; здесь оно усредняется по блокам такой ширины, так что внутри блока все кадры
    * получают одно значение и дрожь меняется раз в int414 тиков вместо каждого. 2 — вдвое реже.
    *
    * В отличие от растяжки, это не стоит ни одного лишнего тика: число кадров и средняя
    * составляющая, то есть ход к цели, остаются как были, поэтому скорость наводки не падает.
    * Растяжка на захвате как раз и была отключена ради скорости, а частоту дрожи снимаем отдельно.
    *
    * Усреднение пары слегка срезает амплитуду на шумной части жеста — плавные разгонные участки
    * почти не меняются. Если дрожь станет мелковата, поднимать нужно double138, а не это число.
    *
    * Применяется только к яву и только на жестах длиной от двух блоков. По библиотеке средний щелчок
    * по яву 46.7, по питчу 11.2 (медианы 28 и 7): питч живёт у самого шага квантования, и усреднение
    * пары меняло его потик на 54.7% от собственной амплитуды, то есть стирало форму доводки и
    * превращало её в дизеринг 0,1,0,1. У ява те же 43.2% работают как фильтр, потому что амплитуда
    * на порядок выше шага. Дрожь на глаз тоже ведёт яв, так что частота падает всё равно.
    *
    * Короткие жесты исключены отдельно: у correction медиана 2 кадра, у settle 1, и блок ширины 2
    * накрывает такой жест целиком. Отклонения от среднего в сумме по жесту дают ровно ноль, поэтому
    * блок на весь жест обнулял их все и жест становился ровной полкой без формы вообще.
    */
   public static final int int414 = 2;
   /**
    * Насколько щелчков усиленная траектория может отойти от записанной по накопленной сумме.
    *
    * Усиление double138 растягивает отклонения от среднего, и на разгонной части жеста это опережение
    * копится: жест уходит вперёд записанного, а сумма выравнивается только последним кадром. Пока
    * жест доигран, всё сходится, но реплей рвётся по трём условиям в UiAnimation — цель ушла, знак
    * развернулся, цель потеряна, — и тогда несведённое опережение остаётся как направленный сдвиг.
    * По питчу жест почти всегда начинается броском вверх, к голове, поэтому остаток копился в одну
    * сторону и питч уводило наверх. На доводке растяжка делает жест вчетверо длиннее, так что шанс
    * порвать его до конца там наибольший.
    *
    * Лечится не потолком, а тем, от чего считается отклонение: не от среднего по всему жесту, а от
    * локального тренда шириной int415. Тогда в «дрожь» попадает только высокочастотная часть, а
    * профиль разгона остаётся в тренде и опережение не копится дальше окна.
    */
   public static final int int415 = 5;
   /**
    * Потолок масштаба канала питча в scalePitch. 4.0 — жест может отдать вчетверо больше записанного,
    * то есть p99 библиотеки дотягивается с 25.8° до 103°, и близкая дистанция берётся за один жест.
    */
   public static final double double140 = 4.0;
   /**
    * Какая доля добавки к питчу идёт пропорционально форме жеста, остальное — ровно по кадрам.
    *
    * Чистое умножение (1.0) сохраняет форму идеально, но множит и пики: на потолке 3.0 самый крупный
    * кадр доходил до 10.8° за тик, и это ровно то щёлканье, которое видно глазом. Ровная добавка по
    * кадрам (0.0) пик снижает, но превращает жест в полку — начинается сразу с большого шага и форма
    * кисти пропадает.
    *
    * 0.35 берёт лучшее с обеих сторон. Замер по библиотеке, рывок считается как средняя вторая
    * разность подряд идущих кадров:
    *   потолок 3.0, доля 1.00 — пик 10.83°, рывок 5.67°, охват p99 77°
    *   потолок 4.0, доля 0.35 — пик  9.88°, рывок 3.88°, охват p99 103°
    * То есть по питчу стало быстрее на треть по охвату, а пик и рывок при этом ниже прежних.
    */
   public static final double double141 = 0.35;
   /**
    * Множитель отклонения кадра от среднего по жесту. Средняя составляющая — это ход к цели, разница
    * с ней — та самая дрожь живой руки. 1.0 — как записано, 1.3 — дрожь на 30% крупнее при том же
    * итоговом смещении.
    */
   public static final double double138 = 1.3;
   /**
    * Потолок паузы удержания в тиках. Набирается, только когда прицел уже внутри габаритов цели, и
    * на это время аура не двигается совсем. Сэмплер задержки берёт значение через expm1 от
    * логарифмического среднего с гауссовым шумом, поэтому хвост тяжёлый и упирается ровно в потолок.
    * Исходные 40 тиков — это 2 секунды: если цель за них отошла, наводка начинается заново. 10 тиков
    * дают полсекунды.
    */
   public static final int int412 = 10;
   public static final double double133 = 1.25;
   public static final double double134 = 25.0;
   public static final double double135 = 30.0;
   public static final double double136 = 15.0;
   public final InventoryCodec var132;
   public final PermissionListCodec var2;
   public final Random random7;
   public IntGridCell var2Var143;
   public int int407;
   public double double137;
   public boolean boolean182;
   public int int408;
   public boolean boolean183;
   public int int151;
   public int int152;
   public int int409;
   public int int410 = -1;
   public String string114 = "idle";

   public MotorIntentModel(InventoryCodec var1, PermissionListCodec var2) {
      this.var132 = var1;
      this.var2 = var2;
      this.random7 = new Random();
      this.reset();
   }

   public void reset() {
      this.var2Var143 = null;
      this.int407 = 0;
      this.double137 = 0.0;
      this.boolean182 = false;
      this.int408 = 0;
      this.boolean183 = false;
      this.int151 = 0;
      this.int152 = 0;
      this.int409 = 0;
      this.int410 = -1;
      this.string114 = "idle";
   }

   public String list47() {
      return this.string114;
   }

   public int[] on23(MotorIntentModel.Prediction var1) {
      if (this.var2Var143 != null && this.UiAnimation(var1)) {
         this.var2Var143 = null;
         this.int407 = 0;
      }

      // Пауза удержания набирается только внутри габаритов цели (ниже, по double23 >= 0.5), поэтому
      // непустой счётчик всегда значит «пауза началась, когда цель была взята». Если цель с тех пор
      // вышла — ушла сама или прицел сорвало — доигрывать паузу нечего: она отдаёт нули, то есть аура
      // стоит на месте до десяти тиков (int412, полсекунды) ровно в тот момент, когда открылась
      // большая ошибка и нужен заход. Реплей жеста рвётся по этому же условию в UiAnimation
      // (flag2), а у паузы такой проверки не было — она и оставалась единственным способом
      // простоять полсекунды на растущей ошибке.
      //
      // Сбрасываем и сам счётчик, и boolean183: тик не тратится, потому что на фазе захвата гейт
      // решения всё равно пропускается, и управление сразу уходит в выбор жеста.
      if (this.int408 > 0 && var1.double23 < 0.5) {
         this.int408 = 0;
         this.boolean183 = false;
      }

      if (this.var2Var143 != null) {
         int l = this.var2Var143.val069[this.int407];
         int i1 = this.var2Var143.call106[this.int407];
         this.int407++;
         this.string114 = "replay:" + PermissionListCodec.call175[this.var2Var143.int154] + " " + this.int407 + "/" + this.var2Var143.length();
         if (this.int407 >= this.var2Var143.length()) {
            this.var2Var143 = null;
            this.int407 = 0;
         }

         this.ColorAnimator(l, i1);
         return new int[]{l, i1};
      } else if (this.int408 > 0) {
         this.int408--;
         if (this.int408 <= 0) {
            this.boolean183 = true;
         }

         this.string114 = "rest:" + this.int408 + " left";
         this.ColorAnimator(0, 0);
         return new int[]{0, 0};
      } else {
         CodecRow il11lill1lil1l1iill_ii1il11l111ii11iil = this.var132.SimpleItemBuilder(this.Easing(var1));
         double d0 = TextScanner((il11lill1lil1l1iill_ii1il11l111ii11iil.float64 + 0.0) / 1.0);
         // double23 — признак «прицел уже внутри габаритов цели» (MotorIntentRotationStrategy:163).
         // Пока он снят, идёт фаза захвата, и монетка гейта здесь пропускается: иначе на отказе
         // тик уходит впустую, а поскольку вне цели длинная пауза не набирается и вместо неё
         // ставится boolean183, получается чередование «тик едем, тик стоим» — половина скорости
         // наводки и заодно главный источник рваности. Когда цель взята, гейт работает как раньше,
         // вместе со своими паузами на удержании.
         boolean flag = this.boolean183 || var1.double23 < 0.5 || this.random7.nextDouble() < d0;
         this.boolean183 = false;
         if (!flag) {
            int j1 = 1;
            if (var1.double23 >= 0.5) {
               j1 = this.on23(il11lill1lil1l1iill_ii1il11l111ii11iil.call267);
               if (j1 > 1) {
                  this.int408 = j1 - 1;
               } else {
                  this.boolean183 = true;
               }
            }

            this.string114 = "wait dwell=" + j1;
            this.ColorAnimator(0, 0);
            return new int[]{0, 0};
         } else {
            int i = this.on23(trackingBias(il11lill1lil1l1iill_ii1il11l111ii11iil.call149), double120);
            double[] adouble = this.UiAnimation(il11lill1lil1l1iill_ii1il11l111ii11iil.call116[i]);
            double[] adouble1 = this.on23(adouble, var1);
            VectorRange l1i1liliili_illi1l1l1 = new VectorRange();
            l1i1liliili_illi1l1l1.double36 = var1.double11;
            l1i1liliili_illi1l1l1.double37 = var1.double12;
            l1i1liliili_illi1l1l1.double38 = var1.double19;
            l1i1liliili_illi1l1l1.double39 = var1.double20;
            l1i1liliili_illi1l1l1.double40 = (var1.double14 - var1.double13) * 0.5;
            l1i1liliili_illi1l1l1.double41 = (var1.double16 - var1.double15) * 0.5;
            l1i1liliili_illi1l1l1.int151 = this.int151;
            l1i1liliili_illi1l1l1.int152 = this.int152;
            IntGridCell l1i1liliili_l1i1illlili = this.var2
               .on23(i, adouble1, l1i1liliili_illi1l1l1, this.random7, 16, 0.35, this.int410, 1.0, 1.0, 0.6, 0.4, 0.5, 40.0);
            // Растяжка выбирается по фазе на момент старта жеста: пока цель не взята, играем с
            // родной скоростью, иначе растягиваем и получаем мелкую низкочастотную доводку.
            boolean flag1 = var1.double23 >= 0.5;
            l1i1liliili_l1i1illlili = stretchGesture(l1i1liliili_l1i1illlili, flag1 ? int411 : int413, flag1 ? 1 : int414, 1);
            l1i1liliili_l1i1illlili = scalePitch(l1i1liliili_l1i1illlili, var1.double12);
            this.int410 = l1i1liliili_l1i1illlili.int153;
            this.var2Var143 = l1i1liliili_l1i1illlili;
            this.int407 = 0;
            this.double137 = Math.hypot(var1.double11, var1.double12);
            this.boolean182 = var1.double23 >= 0.5;
            int j = l1i1liliili_l1i1illlili.val069[0];
            int k = l1i1liliili_l1i1illlili.call106[0];
            this.int407 = 1;
            this.string114 = "start:" + PermissionListCodec.call175[i] + " #" + l1i1liliili_l1i1illlili.int153 + " len=" + l1i1liliili_l1i1illlili.length();
            if (this.int407 >= l1i1liliili_l1i1illlili.length()) {
               this.var2Var143 = null;
               this.int407 = 0;
            }

            this.ColorAnimator(j, k);
            return new int[]{j, k};
         }
      }
   }

   /**
    * Растягивает жест по времени и усиливает дрожь, не меняя итогового смещения.
    *
    * Жест — это массив пар щелчков, по паре за тик. Модель отдаёт кадр целиком, поэтому крупный кадр
    * виден как рывок, а вся дрожь живёт на частоте тика. Здесь каждый кадр делится на int411
    * подкадров, так что частота дрожи падает во столько же раз, а ход по яву и питчу идёт мельче.
    * Отдельно отклонение кадра от среднего по жесту умножается на double138: средняя составляющая —
    * это ход к цели, разница с ней — дрожь руки, и она становится крупнее при той же сумме.
    *
    * Суммы по обеим осям сохраняются точно: и усиление, и деление раскладывают дробный остаток
    * обратной связью по ошибке, поэтому модель на строках 58-61 MotorIntentRotationStrategy читает
    * ровно то, что просила, и петля не раскачивается.
    *
    * var1 — множитель растяжки для этого жеста: int411 на доводке, int413 на захвате.
    * var2 — ширина удержания отклонения по яву: int414 на захвате, 1 на доводке, где частоту уже
    * снижает сама растяжка. var3 — то же по питчу, всегда 1: см. int414, там про амплитуду.
    */
   /**
    * Догоняет питч, когда библиотека физически не покрывает нужный угол.
    *
    * Примитивы писались на боевых дистанциях, где вниз столько не крутят. По библиотеке сумма по
    * питчу на жест: максимум 556 щелчков (84.5°), p99 — 170 (25.8°), медиана — 22 (3.3°). А до центра
    * бокса вблизи нужно: на 1 блоке 235 щелчков (35.8°), на 0.5 — 363 (55.2°), на 0.2 — 490 (74.5°),
    * потому что глаз на 1.62, центр цели на 0.9, и с сокращением дистанции угол растёт как арктангенс.
    * Жестов с суммой от 200 щелчков в библиотеке 0.52%, от 300 — 0.08%, от 490 — один на 23075.
    *
    * Отбор по признакам выбирает ближайший доступный, и он всё равно короче нужного в разы. Реплей
    * отдаёт эту малую долю, дальше гейт решения снова кидает монетку, и на добор угла уходит десяток
    * жестов вместо одного — потому питч вниз почти и не крутит. По яву такого нет: там максимум 594°
    * и p90 равен 77°, любой разворот покрывается одним жестом.
    *
    * Масштабируем только канал питча и только когда жест не добирает: множитель ограничен double140,
    * чтобы форма кисти оставалась узнаваемой, а не превращалась в линейный доворот. Знаки должны
    * совпадать, иначе жест ведёт в другую сторону и растягивать его нельзя. Остаток округления
    * раскладывается по накопленной сумме, поэтому итог точный и обратная связь по drift не врёт.
    */
   public static IntGridCell scalePitch(IntGridCell var0, double var1) {
      if (var0 == null || var0.length() == 0) {
         return var0;
      } else {
         int i = 0;

         for (int j = 0; j < var0.call106.length; j++) {
            i += var0.call106[j];
         }

         if (i == 0 || var1 * i <= 0.0) {
            return var0;
         } else {
            double d0 = Math.abs(var1) / Math.abs((double)i);
            if (d0 <= 1.0 + 1.0E-6) {
               return var0;
            } else {
               double d1 = Math.min(d0, double140);
               int[] aint = new int[var0.call106.length];
               // Добавку делим: double141 пропорционально форме, остальное ровно по кадрам. Так сумма
               // растёт как надо, но крупные кадры не множатся целиком и пик за тик остаётся низким.
               double d3 = (double)i * (d1 - 1.0);
               double d2 = 0.0;
               int k = 0;

               for (int l = 0; l < var0.call106.length; l++) {
                  d2 += var0.call106[l]
                     + d3 * (double141 * var0.call106[l] / (double)i + (1.0 - double141) / var0.call106.length);
                  aint[l] = (int)Math.round(d2) - k;
                  k += aint[l];
               }

               return new IntGridCell(var0.int153, var0.int154, var0.val069, aint);
            }
         }
      }
   }

   public static IntGridCell stretchGesture(IntGridCell var0, int var1, int var2, int var3) {
      if (var0 == null || var0.length() == 0 || var1 <= 1 && var2 <= 1 && var3 <= 1 && double138 == 1.0) {
         return var0;
      } else {
         return new IntGridCell(
            var0.int153, var0.int154, stretchChannel(var0.val069, var1, var2), stretchChannel(var0.call106, var1, var3)
         );
      }
   }

   /**
    * Одна ось: удержание отклонения по блокам, усиление отклонения от среднего, затем растяжка.
    * Сумма щелчков не меняется.
    */
   public static int[] stretchChannel(int[] var0, int var1, int var2) {
      int i = 0;

      for (int j = 0; j < var0.length; j++) {
         i += var0[j];
      }

      int k = Math.max(1, var1);
      // Блок шире половины жеста накрыл бы его целиком, а сумма отклонений от тренда по жесту близка к
      // нулю — так жест терял форму. На таких длинах удержание просто не применяем.
      int i2 = var0.length >= Math.max(1, var2) * 2 ? Math.max(1, var2) : 1;
      // Локальный тренд — скользящее среднее шириной int415 по центру кадра. Отклонение от него и есть
      // дрожь; профиль разгона остаётся в тренде, поэтому усиление не уводит траекторию.
      int l3 = int415 / 2;
      double[] adouble = new double[var0.length];

      for (int j3 = 0; j3 < var0.length; j3++) {
         int k3 = Math.max(0, j3 - l3);
         int i5 = Math.min(var0.length, j3 + l3 + 1);
         double d6 = 0.0;

         for (int j5 = k3; j5 < i5; j5++) {
            d6 += var0[j5];
         }

         d6 /= i5 - k3;
         adouble[j3] = d6 + (var0[j3] - d6) * double138;
      }

      // Удержание ставится на итоговое значение кадра, а не на отклонение: внутри блока тренд тоже
      // гуляет, и держать одно отклонение поверх плывущего тренда частоту не снижает.
      double[] adouble1 = new double[var0.length];

      for (int j2 = 0; j2 < var0.length; j2 += i2) {
         int k2 = Math.min(i2, var0.length - j2);
         double d3 = 0.0;

         for (int l2 = 0; l2 < k2; l2++) {
            d3 += adouble[j2 + l2];
         }

         d3 /= k2;

         for (int i3 = 0; i3 < k2; i3++) {
            adouble1[j2 + i3] = d3;
         }
      }

      int[] aint = new int[var0.length * k];
      int l = 0;
      double d4 = 0.0;

      // Ведём накопленную сумму, а не отдельные кадры: кадр — это разность соседних сумм, поэтому
      // ошибка округления не копится сама и остаток не приходится сбрасывать в последний кадр.
      for (int i1 = 0; i1 < var0.length; i1++) {
         d4 += adouble1[i1];
         int j1 = i1 == var0.length - 1 ? i - l : (int)Math.round(d4) - l;

         for (int k1 = 0; k1 < k; k1++) {
            int l1 = (int)Math.round((double)j1 * (k1 + 1) / k) - (int)Math.round((double)j1 * k1 / k);
            aint[i1 * k + k1] = l1;
         }

         l += j1;
      }

      return aint;
   }

   public int on23(float[][] var1) {
      float[] afloat = new float[var1.length];

      for (int i = 0; i < var1.length; i++) {
         afloat[i] = var1[i][0];
      }

      int j = this.on23(afloat, 1.0);
      double d0 = var1[j][1];
      double d1 = ItemSpec(var1[j][2], -2.5, 0.7F);
      d0 += Math.exp(d1) * 1.0 * this.random7.nextGaussian();
      int k = (int)Math.round(Math.expm1(Math.max(d0, 0.0)));
      return Math.max(1, Math.min(k, int412));
   }

   public boolean UiAnimation(MotorIntentModel.Prediction var1) {
      if (this.int407 < 2) {
         return false;
      }

      double d0 = Math.hypot(var1.double11, var1.double12);
      boolean flag = d0 > this.double137 * 1.25 && d0 > this.double137 + 25.0;
      double d1 = 0.0;
      double d2 = 0.0;

      for (int i = this.int407; i < this.var2Var143.length(); i++) {
         d1 += this.var2Var143.val069[i];
         d2 += this.var2Var143.call106[i];
      }

      double d3 = Math.hypot(d1, d2);
      boolean flag1 = d0 >= 30.0 && d3 >= 15.0 && d1 * var1.double11 + d2 * var1.double12 < 0.0;
      boolean flag2 = this.boolean182 && var1.double23 < 0.5;
      return flag || flag1 || flag2;
   }

   public float[] Easing(MotorIntentModel.Prediction var1) {
      float[] afloat = new float[this.var132.int366];

      for (int i = 0; i < this.var132.int366; i++) {
         String s = this.var132.call180[i];

         afloat[i] = (float)(switch (s) {
            case "center_yaw" -> var1.double11;
            case "center_pitch" -> var1.double12;
            case "box_min_yaw" -> var1.double13;
            case "box_max_yaw" -> var1.double14;
            case "box_min_pitch" -> var1.double15;
            case "box_max_pitch" -> var1.double16;
            case "center_yaw_change" -> var1.double17;
            case "center_pitch_change" -> var1.double18;
            case "yaw_drift" -> var1.double19;
            case "pitch_drift" -> var1.double20;
            case "sim_2t_yaw" -> var1.double21;
            case "sim_2t_pitch" -> var1.double22;
            case "inside" -> var1.double23;
            case "distance" -> var1.double24;
            case "target_motion_x" -> var1.double25;
            case "target_motion_y" -> var1.double26;
            case "target_motion_z" -> var1.double27;
            case "player_motion_x" -> var1.double28;
            case "player_motion_y" -> var1.double29;
            case "player_motion_z" -> var1.double30;
            case "prev_cmd_yaw" -> this.int151;
            case "prev_cmd_pitch" -> this.int152;
            case "rest_run_so_far_log" -> this.int409;
            case "in_gesture" -> this.int151 == 0 && this.int152 == 0 ? 0.0 : 1.0;
            default -> throw new IllegalStateException("unknown selector input " + this.var132.call180[i]);
         });
      }

      return afloat;
   }

   public double[] UiAnimation(float[][] var1) {
      float[] afloat = new float[var1.length];

      for (int i = 0; i < var1.length; i++) {
         afloat[i] = var1[i][0];
      }

      int k = this.on23(afloat, 1.0);
      double[] adouble = new double[4];

      for (int j = 0; j < adouble.length; j++) {
         adouble[j] = var1[k][1 + j];
      }

      return adouble;
   }

   public double[] on23(double[] var1, MotorIntentModel.Prediction var2) {
      double d0 = Math.max(Math.expm1(var1[2]), 1.0);
      double d1 = Math.max(d0 - 2.0, 0.0);
      double d2 = var2.double21 + var2.double19 * d1;
      double d3 = var2.double22 + var2.double20 * d1;
      return new double[]{
         var1[0] + InventoryCodec.CloudApiClient((float)d2),
         var1[1] + InventoryCodec.CloudApiClient((float)d3),
         var1[2],
         var1[3],
         0.0,
         0.0,
         InventoryCodec.CloudApiClient((float)(var2.double19 * d0)),
         InventoryCodec.CloudApiClient((float)(var2.double20 * d0))
      };
   }

   /**
    * Смещает логит tracking так, чтобы его шансы против остальных интентов делились ровно на
    * {@link #trackingOdds}. Вычитается {@code T·ln(f)}: в softmax это умножает вес tracking на
    * {@code 1/f}, а вес — величина ненормированная, поэтому множитель шансов выходит точно f,
    * какими бы ни были сами логиты. Проверено численно: при f = 2/3/5 отношение шансов
    * 2.000000/3.000000/5.000000 на произвольных логитах.
    *
    * <p>Освободившаяся масса раскладывается между flick, correction и settle пропорционально их
    * прежним долям — соотношения между ними сохраняются до машинного нуля. Смещение не переливает
    * tracking в какой-то один интент, а просто убирает его часть из розыгрыша.
    *
    * <p>Tracking не запрещается: при любом конечном f вес остаётся положительным, так что на
    * состояниях, где ничего другого не подходит, интент по-прежнему доступен. Жёсткий запрет
    * оставил бы модель вообще без подходящего банка.
    *
    * <p>Возвращается копия: {@code call149} лежит в {@link CodecRow}, который селектор
    * переиспользует между тиками, и правка на месте копилась бы тик за тиком.
    */
   public static float[] trackingBias(float[] var0) {
      float f = trackingOdds;
      if (var0 == null || var0.length != 4 || !(f > 1.0F) || Float.isInfinite(f)) {
         return var0;
      }

      float[] afloat = var0.clone();
      afloat[PermissionListCodec.int375] = afloat[PermissionListCodec.int375] - (float)(double120 * Math.log(f));
      return afloat;
   }

   public int on23(float[] var1, double var2) {
      double d0 = Double.NEGATIVE_INFINITY;

      for (float f : var1) {
         d0 = Math.max(d0, f);
      }

      double[] adouble = new double[var1.length];
      double d2 = 0.0;

      for (int j = 0; j < var1.length; j++) {
         adouble[j] = Math.exp((var1[j] - d0) / var2);
         d2 += adouble[j];
      }

      double d3 = this.random7.nextDouble() * d2;
      double d1 = 0.0;

      for (int i = 0; i < var1.length; i++) {
         d1 += adouble[i];
         if (d3 <= d1) {
            return i;
         }
      }

      return var1.length - 1;
   }

   public static int EnchantItemSpec(float[] var0) {
      int i = 0;

      for (int j = 1; j < var0.length; j++) {
         if (var0[j] > var0[i]) {
            i = j;
         }
      }

      return i;
   }

   public void ColorAnimator(int var1, int var2) {
      this.int151 = var1;
      this.int152 = var2;
      this.int409 = var1 == 0 && var2 == 0 ? this.int409 + 1 : 0;
   }

   public static double TextScanner(double var0) {
      return 1.0 / (1.0 + Math.exp(-var0));
   }

   public static double ItemSpec(double var0, double var2, double var4) {
      return Math.max(var2, Math.min(var4, var0));
   }


   public static final class Prediction {
      public double double11;
      public double double12;
      public double double13;
      public double double14;
      public double double15;
      public double double16;
      public double double17;
      public double double18;
      public double double19;
      public double double20;
      public double double21;
      public double double22;
      public double double23;
      public double double24;
      public double double25;
      public double double26;
      public double double27;
      public double double28;
      public double double29;
      public double double30;
   }
}
