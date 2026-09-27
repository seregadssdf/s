package org.zenith.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gl.PostEffectPass;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.zenith.utility.mixin.accessors.PostEffectPassAccessor;

/** Writes Zenith's dynamic values to the std140 block used by post effects. */
public final class PostEffectUniforms {
   private PostEffectUniforms() {
   }

   public static Value floatValue(float value) {
      return new Value(Type.FLOAT, value, 0.0F, 0.0F, 0.0F);
   }

   public static Value vec2(float x, float y) {
      return new Value(Type.VEC2, x, y, 0.0F, 0.0F);
   }

   public static Value vec3(float x, float y, float z) {
      return new Value(Type.VEC3, x, y, z, 0.0F);
   }

   public static Value color(int color) {
      return new Value(
         Type.VEC4,
         (color >> 16 & 0xFF) / 255.0F,
         (color >> 8 & 0xFF) / 255.0F,
         (color & 0xFF) / 255.0F,
         (color >>> 24) / 255.0F
      );
   }

   private static final String BLOCK = "ZenithData";

   /**
    * Buffers owned by Zenith, keyed by the pass they belong to. Vanilla builds
    * the block once from the post-effect JSON with {@code USAGE_UNIFORM} only,
    * so it cannot be a copy destination. These values change every frame, so
    * the pass gets a writable replacement instead. Weakly keyed: a resource
    * reload discards the old passes.
    */
   private static final Map<PostEffectPass, GpuBuffer> OWNED = new WeakHashMap<>();

   public static void update(PostEffectPass pass, Value... values) {
      Map<String, GpuBuffer> uniforms = ((PostEffectPassAccessor)pass).getUniformBuffers();
      GpuBuffer bound = uniforms.get(BLOCK);
      if (bound == null) {
         // A pass that does not declare the block, such as the trailing blit.
         return;
      }

      Std140SizeCalculator size = new Std140SizeCalculator();
      for (Value value : values) {
         value.addSize(size);
      }
      int written = size.get();

      try (MemoryStack stack = MemoryStack.stackPush()) {
         Std140Builder builder = Std140Builder.onStack(stack, written);
         for (Value value : values) {
            value.write(builder);
         }

         GpuBuffer target = writableBuffer(pass, uniforms, bound, written);
         RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(0, written), builder.get());
      }
   }

   private static GpuBuffer writableBuffer(PostEffectPass pass, Map<String, GpuBuffer> uniforms, GpuBuffer bound, int written) {
      GpuBuffer owned = OWNED.get(pass);
      if (owned == bound && !owned.isClosed() && owned.size() >= written) {
         return owned;
      }

      // Vanilla sized the block from the JSON declaration; the shader reads that
      // much regardless of how many values Zenith supplies, so never shrink it.
      long length = Math.max(written, bound.isClosed() ? written : bound.size());
      GpuBuffer buffer = RenderSystem.getDevice()
         .createBuffer(() -> "Zenith post effect uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, (int)length);
      uniforms.put(BLOCK, buffer);
      OWNED.put(pass, buffer);

      // The pass closes only what its map still holds, so whatever was evicted
      // here is ours to release. Nothing pending references it: the frame graph
      // reads the map when it runs, which is after this swap.
      if (owned != null && owned != buffer && !owned.isClosed()) {
         owned.close();
      }
      if (bound != owned && !bound.isClosed()) {
         bound.close();
      }

      return buffer;
   }

   public record Value(Type type, float x, float y, float z, float w) {
      private void addSize(Std140SizeCalculator size) {
         switch (this.type) {
            case FLOAT -> size.putFloat();
            case VEC2 -> size.putVec2();
            case VEC3 -> size.putVec3();
            case VEC4 -> size.putVec4();
         }
      }

      private void write(Std140Builder builder) {
         switch (this.type) {
            case FLOAT -> builder.putFloat(this.x);
            case VEC2 -> builder.putVec2(new Vector2f(this.x, this.y));
            case VEC3 -> builder.putVec3(new Vector3f(this.x, this.y, this.z));
            case VEC4 -> builder.putVec4(new Vector4f(this.x, this.y, this.z, this.w));
         }
      }
   }

   public enum Type {
      FLOAT,
      VEC2,
      VEC3,
      VEC4
   }
}
