package org.zenith.module.misc;

import com.darkmagician6.eventapi.EventTarget;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.zenith.ZenithClient;
import org.zenith.core.AimSampleLibrary;
import org.zenith.core.CodecRow;
import org.zenith.core.PermissionListCodec;
import org.zenith.event.EventModifyMouseRotationInput;
import org.zenith.event.EventTick;
import org.zenith.managers.MotorIntentModel;
import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.rotation.MotorIntentRotationStrategy;
import org.zenith.rotation.Rotation;
import org.zenith.rotation.RotationMath;
import org.zenith.setting.BooleanSetting;
import org.zenith.setting.NumberSetting;
import org.zenith.setting.Setting;

/**
 * Запись своей наводки в библиотеку жестов моторной модели.
 *
 * <p>Пишутся щелчки мыши, а не углы: сырая дельта курсора из
 * {@link EventModifyMouseRotationInput} — это ровно те же {@code mouse_counts}, в которых лежит
 * {@code intent_library_rt.json}, потому что ванильный {@code changeLookDirection} умножает её на
 * тот же GCD, которым модель разворачивает свой выход. Через углы и обратно был бы лишний слой
 * квантования, из которого целые щелчки уже не восстановить.
 *
 * <p>Кадр жеста — один тик (50 мс, как в библиотеке), а мышь опрашивается каждый кадр, поэтому
 * дельты копятся до тика. Остаток от округления переносится на следующий тик, так что сумма
 * щелчков за жест совпадает с тем, что реально прошло через мышь.
 *
 * <p>Дрифт (оси 6-7 дескриптора) — уход самой цели за время жеста, в щелчках. Из кадров он не
 * выводится, поэтому считается живьём тем же способом, что и в
 * {@code MotorIntentRotationStrategy}: изменение требуемого угла до центра бокса за тик.
 *
 * <p>Интент размечается не эвристикой по дескриптору, а argmax'ом того же селектора, который
 * работает в рантайме. Правило по форме жеста давало 61% совпадения с разметкой библиотеки, kNN по
 * восьми осям — 77%, и flick в обоих случаях застревал на 40%: его метка опирается на живой
 * контекст, которого в дескрипторе нет. Селектор этот контекст видит, и главное — запись и запрос
 * тогда согласованы по построению: жест, записанный на состоянии, где селектор говорит flick,
 * попадёт в выборку ровно на таком же состоянии.
 */
@ModuleInfo(name = "AimRecorder", category = Category.MISC, description = "module.aimRecorder.desc")
public final class AimRecorder extends Module {
   public static final MinecraftClient minecraftClient3 = MinecraftClient.getInstance();
   public static final AimRecorder aimRecorder = new AimRecorder();
   /** Куда складываются сессии записи. */
   public static final Path DIR = Path.of("aimpipe_rec");
   /** Собранная библиотека: этот путь загрузчик пробует раньше ресурса из жара. */
   public static final Path MERGED = Path.of("intent_library_rt.json");

   public final NumberSetting targetRange = new NumberSetting(
      "module.aimRecorder.targetRange", 6.0F, 3.0F, 30.0F, 0.5F, "module.aimRecorder.targetRange.desc", " бл"
   );
   public final NumberSetting moveThreshold = new NumberSetting(
      "module.aimRecorder.moveThreshold", 1.0F, 1.0F, 20.0F, 1.0F, "module.aimRecorder.moveThreshold.desc", " щелч"
   );
   /**
    * Прямизна, ниже которой жест закрывается.
    *
    * <p>0.97 — не на глаз, а подгонкой под распределение библиотеки на живой записи: длина жеста
    * выходит мед 3 / p90 7 / p99 15 против эталонных 3 / 8 / 14, прямизна 0.982 / p10 0.883 против
    * 0.994 / 0.916. Среднее относительное отклонение по этим пяти числам 0.043, у 0.96 — 0.060,
    * у 0.95 — 0.120, у 0.98 — 0.157, у 0.99 — 0.210.
    *
    * <p>Порог зависит от {@link #quietTicks} и мерился вместе с ним: при тишине 6 лучшим выходил 0.95
    * (0.120 против 0.136 у соседей), потому что длинные хвосты тогда рвались только по прямизне. С
    * тишиной 1 хвост обрезается сам, и оптимум сдвигается к 0.97. Менять один параметр, не проверив
    * второй, смысла нет.
    */
   public final NumberSetting straightness = new NumberSetting(
      "module.aimRecorder.straightness", 0.97F, 0.80F, 0.99F, 0.01F, "module.aimRecorder.straightness.desc", ""
   );
   /**
    * Сколько подряд кадров ниже {@link #moveThreshold} закрывают жест.
    *
    * <p>1, а не «побольше для надёжности»: в библиотеке нет ни одного кадра (0,0) — ни внутри жеста,
    * ни на границе, то есть их нарезка рвала жест на первом же стоячем тике. Значение 2 и выше
    * оставляет стоячие тики внутри жеста (1.57% кадров против 0.00% у эталона) и удлиняет хвост
    * (p99 21 против 15).
    */
   public final NumberSetting quietTicks = new NumberSetting(
      "module.aimRecorder.quietTicks", 1.0F, 1.0F, 20.0F, 1.0F, "module.aimRecorder.quietTicks.desc", " тик"
   );
   public final NumberSetting maxFrames = new NumberSetting(
      "module.aimRecorder.maxFrames", 32.0F, 4.0F, 104.0F, 1.0F, "module.aimRecorder.maxFrames.desc", " кадр"
   );
   public final BooleanSetting mergeOnDisable = new BooleanSetting(
      "module.aimRecorder.mergeOnDisable", "module.aimRecorder.mergeOnDisable.desc", true
   );
   public final BooleanSetting onlyRecorded = new BooleanSetting(
      "module.aimRecorder.onlyRecorded", "module.aimRecorder.onlyRecorded.desc", false
   );

   @Override
   public List<Setting> getSettings() {
      List<Setting> arraylist = new ArrayList<>();
      arraylist.add(this.targetRange);
      arraylist.add(this.moveThreshold);
      arraylist.add(this.straightness);
      arraylist.add(this.quietTicks);
      arraylist.add(this.maxFrames);
      arraylist.add(this.mergeOnDisable);
      arraylist.add(this.onlyRecorded);
      return arraylist;
   }

   // --- состояние: накопление мыши ------------------------------------------
   /** Сырая дельта курсора, накопленная за кадры с прошлого тика. */
   public double mouseAccumX = 0.0;
   public double mouseAccumY = 0.0;
   /** Остаток округления, переносится на следующий тик, чтобы сумма щелчков за жест не терялась. */
   public double carryX = 0.0;
   public double carryY = 0.0;

   // --- состояние: текущий жест ---------------------------------------------
   public final List<int[]> frames = new ArrayList<>();
   /** Накопленный уход цели за жест, в щелчках. */
   public double driftYaw = 0.0;
   public double driftPitch = 0.0;
   /** Интент, размеченный селектором на старте жеста. -1 — жест не идёт. */
   public int gestureIntent = -1;
   /** Тиков без движения подряд. */
   public int quiet = 0;
   /** Требуемый угол до цели с прошлого тика — из него считается дрифт. */
   public Rotation prevRequired = null;
   /** Щелчки, отданные в прошлом тике: фича prev_cmd селектора. */
   public int prevCmdYaw = 0;
   public int prevCmdPitch = 0;

   /** Записанные жесты этой сессии. */
   public final List<AimSampleLibrary.Gesture> recorded = new ArrayList<>();
   /**
    * На каком счёте был отправлен прошлый отчёт в чат.
    *
    * <p>Отчёт нужен, потому что иначе о работе записи узнать неоткуда: состояние модулей в этом
    * клиенте нигде не отображается, а файл появляется только при выключении. Без него единственный
    * способ проверить, что жесты вообще пишутся, — выключить модуль и открыть JSON.
    */
   public int reportedAt = 0;

   /**
    * Своя копия моторной стратегии — только чтобы собрать {@code Prediction} и разметить интент её
    * селектором. Отдельный экземпляр, а не {@code val001.ColorUtils()}, потому что тот ведёт
    * состояние живой ауры (прошлая ротация, дрифт, номер прошлого примитива), и запись сбивала бы
    * ей наводку. Создаётся при первом включении, а не в статическом поле: конструктор тянет
    * библиотеку на 23075 примитивов, и платить за это на старте клиента ради выключенного модуля
    * незачем.
    */
   public MotorIntentRotationStrategy probe = null;

   @Override
   public void onEnable() {
      super.onEnable();
      this.reset();
      if (this.probe == null) {
         this.probe = new MotorIntentRotationStrategy();
      }

      PermissionListCodec.onlyUserPrimitives = this.onlyRecorded.isEnabled();
   }

   @Override
   public void onDisable() {
      this.flush();
      this.reset();
      PermissionListCodec.onlyUserPrimitives = false;
      super.onDisable();
   }

   /** Сброс всего состояния записи. Незакрытый жест выбрасывается: без конца у него нет дескриптора. */
   public void reset() {
      this.mouseAccumX = 0.0;
      this.mouseAccumY = 0.0;
      this.carryX = 0.0;
      this.carryY = 0.0;
      this.frames.clear();
      this.driftYaw = 0.0;
      this.driftPitch = 0.0;
      this.gestureIntent = -1;
      this.quiet = 0;
      this.prevRequired = null;
      this.prevCmdYaw = 0;
      this.prevCmdPitch = 0;
      this.reportedAt = 0;
   }

   /**
    * Копим сырую дельту курсора.
    *
    * <p>Именно {@code cursorDeltaX/Y}, а не углы: ванильный {@code updateMouse} умножает их на
    * фактор чувствительности, а {@code changeLookDirection} — ещё на 0.15, и произведение это ровно
    * {@code Rotation.logger2()}, тот же GCD, которым модель разворачивает свой выход в градусы.
    * Значит дельта курсора и есть щелчок библиотеки, один к одному.
    */
   @EventTarget
   public void onMouseInput(EventModifyMouseRotationInput var1) {
      if (minecraftClient3.player != null && minecraftClient3.currentScreen == null) {
         this.mouseAccumX = this.mouseAccumX + var1.ItemFilterRules();
         this.mouseAccumY = this.mouseAccumY + var1.IntPair();
      }
   }

   /**
    * Щелчки за тик с переносом остатка.
    *
    * <p>Округление здесь, а не в кадре: за тик приходит несколько кадров мыши, и округлять каждый
    * значило бы терять до половины щелчка на каждом. Остаток переносится, поэтому сумма щелчков за
    * жест равна тому, что реально прошло через мышь, а не накопленной ошибке округления.
    */
   public int consume(boolean var1) {
      double d0 = (var1 ? this.mouseAccumX : this.mouseAccumY) + (var1 ? this.carryX : this.carryY);
      int i = (int)Math.round(d0);
      if (var1) {
         this.carryX = d0 - i;
         this.mouseAccumX = 0.0;
      } else {
         this.carryY = d0 - i;
         this.mouseAccumY = 0.0;
      }

      return i;
   }

   @EventTarget
   public void onUpdate(EventTick var1) {
      PermissionListCodec.onlyUserPrimitives = this.onlyRecorded.isEnabled();
      if (minecraftClient3.player == null || minecraftClient3.world == null) {
         this.reset();
         return;
      }

      int i = this.consume(true);
      int j = this.consume(false);
      if (minecraftClient3.currentScreen != null) {
         // В инвентаре мышь ведёт курсор, а не прицел: движение отсюда в жест не идёт, и открытый
         // экран заодно закрывает текущий жест — иначе он склеился бы через паузу произвольной длины.
         this.close();
         this.prevRequired = null;
         return;
      }

      PlayerEntity playerentity = this.pickTarget();
      if (playerentity == null) {
         this.close();
         this.prevRequired = null;
         return;
      }

      Rotation ililiiili1ll1li11 = new Rotation(minecraftClient3.player.getYaw(), minecraftClient3.player.getPitch(), true);
      Box box = playerentity.getBoundingBox();
      Vec3d vec3d = minecraftClient3.player.getEyePos();
      Rotation ililiiili1ll1li112 = RotationMath.Event08(box.getCenter().subtract(vec3d));
      float f = MotorIntentRotationStrategy.gcdStep();
      // Дрифт — изменение требуемого угла за тик в щелчках, то есть уход самой цели (и своего
      // положения) независимо от того, куда я в этом тике повернул. Это ровно определение
      // yaw_drift/pitch_drift в MotorIntentRotationStrategy:174, и считать надо каждый тик, а не
      // только внутри жеста, иначе первый кадр остался бы без опоры.
      double d2 = 0.0;
      double d3 = 0.0;
      if (this.prevRequired != null && f > 0.0F) {
         d2 = MathHelper.wrapDegrees(ililiiili1ll1li112.GrimGlide() - this.prevRequired.GrimGlide()) / f;
         d3 = (ililiiili1ll1li112.GuiWalk() - this.prevRequired.GuiWalk()) / f;
         this.driftYaw = this.driftYaw + d2;
         this.driftPitch = this.driftPitch + d3;
      }

      this.prevRequired = ililiiili1ll1li112;
      boolean flag = Math.abs(i) + Math.abs(j) >= (int)this.moveThreshold.getCurrent();
      if (this.gestureIntent < 0) {
         if (flag) {
            // Метка ставится по состоянию на старте: селектор видит ошибку, габариты цели, дрифт и
            // флаг «на цели» — тот же вход, по которому он будет спрашивать жест в рантайме.
            this.gestureIntent = this.classify(ililiiili1ll1li11, vec3d, playerentity, box);
            this.driftYaw = 0.0;
            this.driftPitch = 0.0;
            this.frames.clear();
            this.frames.add(new int[]{i, j});
            this.quiet = 0;
         }
      } else if (this.breaksStraightness(i, j)) {
         // Кадр ломает прямизну: жест кончился на прошлом тике, а этот кадр — начало следующего.
         // Уход цели за этот тик отдаём новому жесту: он относится к нему, а не к закрытому.
         this.driftYaw = this.driftYaw - d2;
         this.driftPitch = this.driftPitch - d3;
         this.close();
         // Новый жест начинается только с двигавшегося кадра. Стоячий тик может оказаться здесь
         // потому, что пара первых кадров прямизну не проверяет: уже сломанная пара доживает до
         // третьего кадра, и разрыв срабатывает на нём, каким бы он ни был. Без этой проверки жест
         // начинался бы с (0,0) — таких кадров в библиотеке нет ни одного (было 0.26% кадров).
         // Иначе жест просто остаётся закрытым: close уже сбросил интент в -1, и следующий
         // двигавшийся кадр начнёт новый жест обычной ветвью старта.
         if (flag) {
            this.gestureIntent = this.classify(ililiiili1ll1li11, vec3d, playerentity, box);
            this.frames.add(new int[]{i, j});
            this.quiet = 0;
            this.driftYaw = d2;
            this.driftPitch = d3;
         }
      } else {
         this.frames.add(new int[]{i, j});
         this.quiet = flag ? 0 : this.quiet + 1;
         if (this.quiet >= (int)this.quietTicks.getCurrent() || this.frames.size() >= (int)this.maxFrames.getCurrent()) {
            this.close();
         }
      }

      this.prevCmdYaw = i;
      this.prevCmdPitch = j;
      // Отчёт каждые 25 жестов: реже — и первые минуты записи выглядят как будто ничего не работает,
      // чаще — засоряет чат в бою, где жестов набегает несколько в секунду.
      if (this.recorded.size() >= this.reportedAt + 25) {
         this.reportedAt = this.recorded.size();
         this.notify("Жестов: " + this.recorded.size() + " (" + this.byIntent() + ")");
      }
   }

   /** Разбивка записанного по интентам — по ней видно, чего в наборе не хватает. */
   public String byIntent() {
      int[] aint = new int[4];

      for (AimSampleLibrary.Gesture aimsamplelibrary_gesture : this.recorded) {
         if (aimsamplelibrary_gesture.intent >= 0 && aimsamplelibrary_gesture.intent < 4) {
            aint[aimsamplelibrary_gesture.intent]++;
         }
      }

      StringBuilder stringbuilder = new StringBuilder();

      for (int i = 0; i < 4; i++) {
         if (i > 0) {
            stringbuilder.append(", ");
         }

         stringbuilder.append(PermissionListCodec.call175[i]).append(' ').append(aint[i]);
      }

      return stringbuilder.toString();
   }

   /**
    * Кончился ли жест на прошлом тике: кадр {@code (var1, var2)} уводит прямизну ниже порога.
    *
    * <p>Прямизна тут — та же величина, что в дескрипторе (ось 5): длина суммарного смещения, делённая
    * на длину пути. Единица — идеально прямой отрезок, ноль — движение вернулось в начало. Считается
    * по кадру-кандидату, а не по уже накопленному жесту, поэтому ломающий кадр в закрытый жест не
    * попадает, а становится первым кадром следующего.
    *
    * <p>Изначально жест закрывался по тишине ({@code quietTicks} подряд кадров ниже
    * {@code moveThreshold}), и это было неверно: живая рука не останавливается совсем, поэтому
    * закрытие срабатывало только по упору в {@code maxFrames}. На реальной записи это дало 154 жеста
    * с медианами 15/21/26 кадров против 4/2/6 в библиотеке, 34% упёрлись в потолок 32, settle не
    * нашлось ни одного, а нулевых кадров стало 2.79% против 0.00% у эталона.
    *
    * <p>Критерий выбран измерением на той же записи против эталонной статистики библиотеки
    * (медиана 3 / p90 8 / p99 14 / прямизна 0.994 / p10 0.916). Смена знака дала 4/12/23/0.983/0.813,
    * угол свыше 45° — 4/11/26/0.989/0.850, провал скорости ниже половины — 3/8/12/0.976/0.699,
    * прямизна ниже 0.95 — 3/8/14/0.977/0.910, то есть единственный вариант, попавший и по хвостам,
    * и по самой прямизне. Порог тоже мерился: ошибка 0.265 на 0.97, 0.189 на 0.96, 0.070 на 0.95,
    * 0.289 на 0.94 и 1.209 на 0.90.
    *
    * <p>Первые два кадра не проверяются: на одном кадре прямизна тождественно равна единице, а на
    * двух любой поворот уже даёт просадку, и жесты дробились бы до пары кадров каждый.
    */
   public boolean breaksStraightness(int var1, int var2) {
      if (this.frames.size() < 2) {
         return false;
      } else {
         double d0 = 0.0;
         double d1 = 0.0;
         double d2 = 0.0;

         for (int[] aint : this.frames) {
            d0 += aint[0];
            d1 += aint[1];
            d2 += Math.hypot(aint[0], aint[1]);
         }

         d0 += var1;
         d1 += var2;
         d2 += Math.hypot(var1, var2);
         // Путь нулевой — жест стоит на месте, делить не на что: прямизна по определению дескриптора
         // равна единице, ломать нечего.
         return d2 > 0.0 && Math.hypot(d0, d1) / d2 < this.straightness.getCurrent();
      }
   }

   /**
    * Цель: ближайший игрок в радиусе.
    *
    * <p>Своя выборка, а не цель ауры: смысл записи в том, чтобы наводил живой человек, то есть аура
    * при этом выключена и её цели просто нет.
    */
   public PlayerEntity pickTarget() {
      PlayerEntity playerentity = null;
      double d0 = this.targetRange.getCurrent() * this.targetRange.getCurrent();

      for (PlayerEntity playerentity1 : minecraftClient3.world.getPlayers()) {
         if (playerentity1 != minecraftClient3.player && playerentity1.isAlive() && !playerentity1.isSpectator()) {
            double d1 = minecraftClient3.player.squaredDistanceTo(playerentity1);
            if (d1 <= d0) {
               d0 = d1;
               playerentity = playerentity1;
            }
         }
      }

      return playerentity;
   }

   /**
    * Интент жеста — argmax селектора на состоянии старта.
    *
    * <p>{@code Prediction} собирается публичным {@code on23} моторной стратегии, то есть теми же
    * двадцатью четырьмя фичами и с той же симуляцией на два тика, что в рантайме. Поля
    * {@code prev_cmd_*} берутся из своего прошлого тика, поэтому вход селектора совпадает с тем,
    * который он увидит, когда будет спрашивать жест на похожем состоянии.
    *
    * <p>При любом сбое возвращается correction: это самый населённый интент библиотеки (10461 из
    * 23075), так что жест не потеряется и осядет там, где помешает меньше всего.
    */
   public int classify(Rotation var1, Vec3d var2, PlayerEntity var3, Box var4) {
      try {
         MotorIntentModel motorintentmodel = this.probe.zClass026;
         if (motorintentmodel == null) {
            return PermissionListCodec.int374;
         }

         this.probe.vec3d44 = null;
         this.probe.box10 = null;
         this.probe.boolean198 = false;
         Vec3d vec3d = var3.getVelocity();
         Vec3d vec3d1 = minecraftClient3.player.getVelocity();
         MotorIntentModel.Prediction ii1ll11lil1l1i1ll1llli1l_ii1il11l111ii11iil = this.probe
            .on23(var1, var2, var3, var4, vec3d, vec3d1, this.prevCmdYaw, this.prevCmdPitch);
         if (ii1ll11lil1l1i1ll1llli1l_ii1il11l111ii11iil == null) {
            return PermissionListCodec.int374;
         }

         // Дрифт в фичах — свой, посчитанный по уходу цели: стратегия отдала бы нули, потому что её
         // boolean198 снят (она не ведёт наводку, пока пишем).
         ii1ll11lil1l1i1ll1llli1l_ii1il11l111ii11iil.double19 = this.driftYaw;
         ii1ll11lil1l1i1ll1llli1l_ii1il11l111ii11iil.double20 = this.driftPitch;
         motorintentmodel.int151 = this.prevCmdYaw;
         motorintentmodel.int152 = this.prevCmdPitch;
         motorintentmodel.int409 = 0;
         CodecRow il11lill1lil1l1iill = motorintentmodel.var132.SimpleItemBuilder(motorintentmodel.Easing(ii1ll11lil1l1i1ll1llli1l_ii1il11l111ii11iil));
         int i = 0;

         for (int j = 1; j < 4; j++) {
            if (il11lill1lil1l1iill.call149[j] > il11lill1lil1l1iill.call149[i]) {
               i = j;
            }
         }

         return i;
      } catch (Throwable throwable) {
         return PermissionListCodec.int374;
      }
   }

   /**
    * Закрыть жест и положить его в сессию.
    *
    * <p>Хвостовые нулевые кадры срезаются: они набегают от ожидания тишины, а в дескрипторе тянут
    * length_log вверх и peak_phase вниз, то есть описывают жест, которого не было. По библиотеке у
    * settle медиана ровно один кадр, так что лишний хвост тут виден сразу.
    */
   public void close() {
      if (this.gestureIntent >= 0) {
         int i = this.frames.size();

         while (i > 1 && this.frames.get(i - 1)[0] == 0 && this.frames.get(i - 1)[1] == 0) {
            i--;
         }

         if (i >= 1) {
            int[] aint = new int[i];
            int[] aint1 = new int[i];

            for (int j = 0; j < i; j++) {
               aint[j] = this.frames.get(j)[0];
               aint1[j] = this.frames.get(j)[1];
            }

            this.recorded.add(new AimSampleLibrary.Gesture(this.gestureIntent, aint, aint1, this.driftYaw, this.driftPitch));
         }
      }

      this.frames.clear();
      this.gestureIntent = -1;
      this.quiet = 0;
      this.driftYaw = 0.0;
      this.driftPitch = 0.0;
   }

   /** Папка записей внутри run. */
   public static Path dir() {
      return MinecraftClient.getInstance().runDirectory.toPath().resolve(DIR);
   }

   /**
    * Сохранить сессию и, если включено, собрать библиотеку.
    *
    * <p>Сессия пишется отдельным файлом, а собранная библиотека — рядом с игрой под тем именем,
    * которое {@link PermissionListCodec} пробует раньше ресурса из жара. Поэтому «применить»
    * означает просто перезапуск клиента, без правок в коде и без пересборки жара.
    */
   public void flush() {
      this.close();
      if (!this.recorded.isEmpty()) {
         int i = this.recorded.size();
         String s = this.byIntent();

         try {
            Path path = dir().resolve("rec-" + System.currentTimeMillis() + ".json");
            AimSampleLibrary.write(path, this.recorded);
            this.recorded.clear();
            int j = -1;
            if (this.mergeOnDisable.isEnabled()) {
               j = this.mergeAll();
            }

            this.notify("Записано " + i + " (" + s + ")" + (j >= 0 ? ", в библиотеке " + j : ", нужен перезапуск"));
         } catch (Throwable throwable) {
            // recorded не чистим: жесты остаются в памяти и уйдут в файл со следующей попытки,
            // а не потеряются из-за одной неудачной записи.
            this.notify("Не удалось сохранить: " + throwable.getClass().getSimpleName());
            System.err.println("[AimRecorder] save failed");
            throwable.printStackTrace();
         }
      }
   }

   /**
    * Склеить все сессии из папки со штатной библиотекой и записать результат.
    *
    * <p>Собирается всегда с нуля из штатной библиотеки, а не досыпается в уже собранную: иначе
    * повторный запуск сборки удваивал бы записанное, а граница {@code zenith_user_from} со второго
    * раза указывала бы в середину своих же примитивов.
    *
    * @return сколько всего примитивов в собранной библиотеке
    */
   public int mergeAll() throws Exception {
      List<AimSampleLibrary.Gesture> arraylist = new ArrayList<>();
      Path path = dir();
      if (Files.isDirectory(path)) {
         try (Stream<Path> stream = Files.list(path)) {
            for (Path path2 : stream.filter(var0 -> var0.getFileName().toString().endsWith(".json")).sorted().toList()) {
               try {
                  arraylist.addAll(AimSampleLibrary.read(path2));
               } catch (Throwable throwable) {
                  System.err.println("[AimRecorder] skipped " + path2.getFileName() + ": " + throwable.getMessage());
               }
            }
         }
      }

      JsonObject jsonobject = AimSampleLibrary.merge(arraylist);
      Path path1 = MinecraftClient.getInstance().runDirectory.toPath().resolve(MERGED);
      Files.writeString(path1, jsonobject.toString(), StandardCharsets.UTF_8);
      return jsonobject.getAsJsonArray("primitives").size();
   }

   public void notify(String var1) {
      try {
         ZenithClient.on23().ConfigJsonUtil().on23("M", Text.of(var1));
      } catch (Throwable throwable) {
         System.out.println("[AimRecorder] " + var1);
      }
   }

   /** Строка состояния для отладки: идёт ли жест, сколько кадров, сколько записано. */
   public String status() {
      return (this.gestureIntent < 0 ? "idle" : PermissionListCodec.call175[this.gestureIntent] + " " + this.frames.size() + "f")
         + " rec="
         + this.recorded.size();
   }
}
