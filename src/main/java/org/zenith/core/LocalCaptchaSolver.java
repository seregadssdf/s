package org.zenith.core;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

// Локальное распознавание капчи нейросетью без облака. Модель обучается скриптом
// ml/captcha/train.py и кладётся в resources/captcha/captcha_digits.onnx; вход 192x144 RGB,
// выход [1, 5, 10] — логиты пяти цифр. Любая ошибка тихо отключает решатель (фолбэк в облако).
public final class LocalCaptchaSolver {
   public static final int WIDTH = 192;
   public static final int HEIGHT = 144;
   private static final float[] MEAN = new float[]{0.485F, 0.456F, 0.406F};
   private static final float[] STD = new float[]{0.229F, 0.224F, 0.225F};
   private static volatile OrtSession session;
   private static volatile boolean failed;

   private LocalCaptchaSolver() {
   }

   public static boolean isAvailable() {
      return !failed && session() != null;
   }

   public static String solve(BufferedImage var0) {
      if (failed) {
         return null;
      }

      try {
         OrtSession ortsession = session();
         if (ortsession == null) {
            return null;
         }

         OnnxTensor onnxtensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(), FloatBuffer.wrap(preprocess(var0)),
            new long[]{1L, 3L, HEIGHT, WIDTH});

         String s;
         try (OrtSession.Result ortsession_result = ortsession.run(Collections.singletonMap("image", onnxtensor))) {
            float[][][] afloat = (float[][][])ortsession_result.get(0).getValue();
            StringBuilder stringbuilder = new StringBuilder(5);

            for (int i = 0; i < 5; i++) {
               int j = 0;
               for (int k = 1; k < 10; k++) {
                  if (afloat[0][i][k] > afloat[0][i][j]) {
                     j = k;
                  }
               }

               stringbuilder.append(j);
            }

            s = stringbuilder.toString();
         } finally {
            onnxtensor.close();
         }

         return s;
      } catch (Throwable var13) {
         failed = true;
         FileLogger.log("[captcha-nn] solver disabled after error: " + var13);
         return null;
      }
   }

   private static OrtSession session() {
      if (session != null) {
         return session;
      }

      synchronized (LocalCaptchaSolver.class) {
         if (session != null) {
            return session;
         }

         try (InputStream inputstream = LocalCaptchaSolver.class.getResourceAsStream("/captcha/captcha_digits.onnx")) {
            if (inputstream == null) {
               FileLogger.log("[captcha-nn] model resource is missing");
               failed = true;
               return null;
            }

            ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();
            byte[] abyte = new byte[8192];

            int i;
            while ((i = inputstream.read(abyte)) >= 0) {
               bytearrayoutputstream.write(abyte, 0, i);
            }

            session = OrtEnvironment.getEnvironment().createSession(bytearrayoutputstream.toByteArray());
            FileLogger.log("[captcha-nn] model loaded, " + bytearrayoutputstream.size() + " bytes");
            return session;
         } catch (Exception var9) {
            FileLogger.log("[captcha-nn] model load failed: " + var9);
            failed = true;
            return null;
         }
      }
   }

   private static float[] preprocess(BufferedImage var0) {
      BufferedImage bufferedimage = new BufferedImage(WIDTH, HEIGHT, 2);
      Graphics2D graphics2d = bufferedimage.createGraphics();
      graphics2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics2d.drawImage(var0, 0, 0, WIDTH, HEIGHT, null);
      graphics2d.dispose();

      float[] afloat = new float[3 * HEIGHT * WIDTH];
      for (int i = 0; i < HEIGHT; i++) {
         for (int j = 0; j < WIDTH; j++) {
            int k = bufferedimage.getRGB(j, i);
            int base = i * WIDTH + j;
            afloat[base] = ((k >> 16 & 255) / 255.0F - MEAN[0]) / STD[0];
            afloat[HEIGHT * WIDTH + base] = ((k >> 8 & 255) / 255.0F - MEAN[1]) / STD[1];
            afloat[2 * HEIGHT * WIDTH + base] = ((k & 255) / 255.0F - MEAN[2]) / STD[2];
         }
      }

      return afloat;
   }
}
