// Общий интерфейс шейдеров руки для пайплайнов 1.21.11.
//
// Раньше эти шейдеры грузились через RawShaderProgram (сырой glUseProgram) и
// получали uniform'ы напрямую через glUniform*. На 1.21.11 это не работает:
// LegacyImmediateRenderer.draw ставит свой RenderPipeline, из-за чего сырая
// программа отваливается, а сэмплеры/uniform'ы уходят в никуда. Теперь шейдеры
// идут через ShaderWrapper, значит все параметры приходят одним std140-блоком
// ZenithData, а текстуры — именованными сэмплерами.
//
// Порядок полей в блоке обязан совпадать с порядком UniformSpec в
// HandShaderManager: std140 раскладывает vec2 по 8 байт, float по 4.
//
// Вершинный шейдер — zenith:hand/hand_blit, он даёт handUv в [0..1].

uniform sampler2D ColorTexture;
uniform sampler2D DepthTexture;

layout(std140) uniform ZenithData {
    vec2 resolution;
    vec2 handMotion;
    vec2 speed;
    float time;
    float effectAlpha;
    float shift;
};

in vec2 handUv;

out vec4 outColor;

// В оригинальных шейдерах Sirius был uniform iMouse, который клиент никогда не
// заполнял. Оставляем нулём, чтобы тела шейдеров не пришлось править.
const vec2 iMouse = vec2(0.0);

#define surfacePosition ((handUv - handMotion) * 2.0)

float handDepthMask(vec2 sampleUv) {
    if (sampleUv.x < 0.0 || sampleUv.y < 0.0 || sampleUv.x > 1.0 || sampleUv.y > 1.0) {
        return 0.0;
    }

    float depthValue = texture(DepthTexture, sampleUv).r;
    return smoothstep(0.999, 0.990, depthValue);
}

float sampleMask(vec2 sampleUv) {
    if (sampleUv.x < 0.0 || sampleUv.y < 0.0 || sampleUv.x > 1.0 || sampleUv.y > 1.0) {
        return 0.0;
    }

    return max(texture(ColorTexture, sampleUv).a, handDepthMask(sampleUv));
}

vec4 handTexture(vec2 sampleUv) {
    vec4 sampledColor = texture(ColorTexture, sampleUv);
    sampledColor.a = max(sampledColor.a, sampleMask(sampleUv));
    return sampledColor;
}

#define texture2D(sourceTexture, sampleUv) handTexture(sampleUv)

vec2 handEffectCoord(vec2 sampleUv) {
    return (sampleUv - handMotion + 0.5) * resolution.xy;
}

vec4 handFragCoord4() {
    return vec4(handEffectCoord(handUv), 0.0, 1.0);
}
