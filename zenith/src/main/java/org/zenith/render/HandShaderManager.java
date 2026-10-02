package org.zenith.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.zenith.core.ShaderWrapper;

/**
 * Шейдеры руки на пайплайнах 1.21.11.
 *
 * <p>Раньше здесь жил RawShaderProgram с сырым glUseProgram. На 1.21.11 это
 * мёртвый путь: полноэкранный квад уходит через LegacyImmediateRenderer.draw,
 * который ставит свой RenderPipeline и тем самым сбрасывает сырую программу, а
 * glUniform и glBindTexture пишут в уже неактивный объект. Поэтому шейдеры
 * переведены на ShaderWrapper — тот же путь, которым в клиенте работает
 * десяток остальных шейдеров.
 */
public class HandShaderManager {
   public static MinecraftClient minecraftClient = MinecraftClient.getInstance();
   public static ShaderWrapper var057;
   public static final String string132 = "sirius_aqua";
   public static final Map<String, ShaderWrapper> map59 = new HashMap<>();
   public static final Set<String> set22 = new HashSet<>();
   public static SimpleFramebuffer simpleFramebuffer;
   public static boolean initialized = false;

   /** Порядок обязан совпадать с блоком ZenithData в zenith:hand_common.glsl. */
   public static final ShaderWrapper.UniformSpec[] val485 = new ShaderWrapper.UniformSpec[]{
      new ShaderWrapper.UniformSpec("resolution", 2),
      new ShaderWrapper.UniformSpec("handMotion", 2),
      new ShaderWrapper.UniformSpec("speed", 2),
      new ShaderWrapper.UniformSpec("time", 1),
      new ShaderWrapper.UniformSpec("effectAlpha", 1),
      new ShaderWrapper.UniformSpec("shift", 1)
   };

   public static void float246() {
      if (!initialized) {
         try {
            var057 = HudTabList("sirius_aqua");
            initialized = true;
            System.out.println("[Zenith/ShaderHand] sirius_aqua loaded OK");
         } catch (Exception exception) {
            System.err.println("[Zenith/ShaderHand] Failed to initialize hand shaders!");
            exception.printStackTrace();
         }
      }
   }

   public static ShaderWrapper HudStatusPanel(String var0) {
      if (var0 == null || var0.isBlank()) {
         return var057;
      }

      if (set22.contains(var0)) {
         return var057;
      }

      try {
         boolean flag = !map59.containsKey(var0);
         ShaderWrapper var05 = HudTabList(var0);
         if (flag) {
            System.out.println("[Zenith/ShaderHand] shader " + var0 + " loaded OK");
         }

         return var05;
      } catch (Exception exception) {
         set22.add(var0);
         System.err.println("[Zenith/ShaderHand] Failed to initialize hand shader: " + var0);
         exception.printStackTrace();
         return var057;
      }
   }

   public static ShaderWrapper HudTabList(String var0) {
      ShaderWrapper shaderwrapper = map59.get(var0);
      if (shaderwrapper == null) {
         // Композит анимированный: свой uniform-буфер, иначе кольцо общих
         // uniform'ов даёт мигание через кадр.
         shaderwrapper = new ShaderWrapper(
               Identifier.of("zenith", "hand/" + var0),
               VertexFormats.POSITION,
               Identifier.of("zenith", "hand/hand_blit"),
               Identifier.of("zenith", "hand/" + var0),
               java.util.List.of("ColorTexture", "DepthTexture"),
               val485
            )
            .useDedicatedUniformBuffer();
         map59.put(var0, shaderwrapper);
      }

      if ("sirius_aqua".equals(var0)) {
         var057 = shaderwrapper;
      }

      return shaderwrapper;
   }

   public static void float247() {
      if (minecraftClient != null && minecraftClient.getWindow() != null) {
         int i = minecraftClient.getWindow().getFramebufferWidth();
         int j = minecraftClient.getWindow().getFramebufferHeight();
         if (simpleFramebuffer == null || simpleFramebuffer.textureWidth != i || simpleFramebuffer.textureHeight != j) {
            if (simpleFramebuffer != null) {
               simpleFramebuffer.delete();
            }

            simpleFramebuffer = new SimpleFramebuffer("Zenith hand shader", i, j, true);
         }
      }
   }

   /** Привязывает цвет и глубину офскрина к именованным сэмплерам композита. */
   public static void bindHandTextures(SimpleFramebuffer var0) {
      LegacyImmediateRenderer.setTexture("ColorTexture", var0.getColorAttachmentView(), FilterMode.LINEAR);
      LegacyImmediateRenderer.setTexture("DepthTexture", var0.getDepthAttachmentView(), FilterMode.NEAREST);
   }

   public static void var14336() {
      RenderSystem.assertOnRenderThread();
      BufferBuilder bufferbuilder = Tessellator.getInstance().begin(DrawMode.QUADS, VertexFormats.POSITION);
      bufferbuilder.vertex(-1.0F, -1.0F, 0.0F);
      bufferbuilder.vertex(1.0F, -1.0F, 0.0F);
      bufferbuilder.vertex(1.0F, 1.0F, 0.0F);
      bufferbuilder.vertex(-1.0F, 1.0F, 0.0F);
      org.zenith.render.LegacyRenderBridge.draw(bufferbuilder.end());
   }

   public static ShaderWrapper var14337() {
      return var057;
   }

   public static SimpleFramebuffer string38() {
      return simpleFramebuffer;
   }

   public static boolean isInitialized() {
      return initialized;
   }
}
