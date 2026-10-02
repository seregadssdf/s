#version 330

#moj_import <zenith:hand_common.glsl>

// Converted from Sirius chams shader: gang.frag
float rand(vec2 n) {
 return fract(cos(dot(n, vec2(2.9898, 20.1414))) * 5.5453);
}

float noise(vec2 n) {
  const vec2 d = vec2(0.0, 1.0);
  vec2 b = floor(n), f = smoothstep(vec2(0.0), vec2(1.0), fract(n));
  return mix(mix(rand(b), rand(b + d.yx), f.x), mix(rand(b + d.xy), rand(b + d.yy), f.x), f.y);
}

float fbm(vec2 n){
   float total=0.,amplitude=1.5;
   for(int i=0;i<18;i++){
       total+=noise(n)*amplitude;
        n+=n;
        amplitude*=.45;
   }
  return total;
}

float calcLuma(vec3 color) {
    return 0.299*color.r + 0.587*color.g + 0.114*color.b;
}

void main(){
    const vec3 c1=vec3(0.502, 0.1059, 0.1059);
    const vec3 c2=vec3(167./255.,93./255.,110./255.);
    const vec3 c3=vec3(0.4902, 0.5333, 0.4902);
    const vec3 c4=vec3(0.2118, 0.3451, 0.2706);
    const vec3 c5=vec3(0.3176, 0.2549, 0.4);
    const vec3 c6=vec3(0.8, 0.3569, 0.3569);
    
    vec2 p=handFragCoord4().xy*5./resolution.xx;
    float q=fbm(p-time*.05);
    vec2 r=vec2(fbm(p+q+time*speed.x-p.x-p.y),fbm(p+q-time*speed.y));
    vec3 c=mix(c1,c2,fbm(p+r))+mix(c3,c4,r.x)-mix(c5,c6,r.y);
    float grad=handFragCoord4().y/resolution.y;

    vec4 centerCol = handTexture(handUv);

    if (centerCol.a <= 0.001) {
        discard;
    }
    vec4 result;

    result = vec4(c*cos(shift*handFragCoord4().y/resolution.y),1.5);
    result.xyz*=1.15-grad;

    float alpha = calcLuma(result.rgb)*15;
    outColor = vec4(result.rgb, alpha);
    outColor.a *= effectAlpha;
}
