package com.pencilcam.app

/**
 * ============================================================================
 * Shaders
 * The GPU programs. Passes 1 to 3 are copied from the browser lab almost
 * word for word. Pass 0 is new: it turns the raw camera image upright.
 *
 *   Pass 0  COPY  : camera (special "OES" texture) -> upright, mirrored if needed
 *   Pass 1  BLUR H: brightness blurred sideways at two widths
 *   Pass 2  BLUR V: finishes the two blurs
 *   Pass 3  DRAW  : outlines + crosshatching + wobble + paper  -> screen
 * ============================================================================
 */
object Shaders {

    /** Plain full-screen rectangle used by passes 1 to 3. */
    const val QUAD_VS = """
attribute vec2 p;
varying vec2 v_uv;
void main(){ v_uv = p*0.5+0.5; gl_Position = vec4(p,0.0,1.0); }
"""

    /**
     * Pass 0 vertex shader. Works out where in the camera image each screen
     * point comes from: mirror, rotate around the center, then apply the
     * camera's own transform matrix.
     */
    const val COPY_VS = """
attribute vec2 p;
uniform mat4 u_st;
uniform float u_rot;
uniform float u_mirror;
varying vec2 v_tex;
void main(){
  vec2 c = (p*0.5+0.5) - 0.5;
  if (u_mirror > 0.5) c.x = -c.x;
  float a = radians(u_rot);
  c = vec2(cos(a)*c.x - sin(a)*c.y, sin(a)*c.x + cos(a)*c.y);
  v_tex = (u_st * vec4(c + 0.5, 0.0, 1.0)).xy;
  gl_Position = vec4(p,0.0,1.0);
}
"""

    const val COPY_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES u_cam;
varying vec2 v_tex;
void main(){ gl_FragColor = texture2D(u_cam, v_tex); }
"""

    /** Pass 0 for File mode: copy a gallery photo (Bitmap) upright. */
    const val COPY2D_FS = """
precision mediump float;
uniform sampler2D u_img;
varying vec2 v_uv;
void main(){ gl_FragColor = texture2D(u_img, vec2(v_uv.x, 1.0 - v_uv.y)); }
"""

    private const val PREC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /** Pass 1: horizontal blur of brightness at sigma1 and sigma1*1.6. */
    const val BLUR_H_FS = PREC + """
uniform sampler2D u_src; uniform vec2 u_texel; uniform float u_s1;
varying vec2 v_uv;
float lum(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }
void main(){
  float s2 = u_s1*1.6;
  float a1=0.0, a2=0.0, w1s=0.0, w2s=0.0;
  for (int i=-12; i<=12; i++){
    float x = float(i);
    float w1 = exp(-x*x/(2.0*u_s1*u_s1));
    float w2 = exp(-x*x/(2.0*s2*s2));
    float l  = lum(texture2D(u_src, v_uv + vec2(x*u_texel.x, 0.0)).rgb);
    a1 += l*w1; a2 += l*w2; w1s += w1; w2s += w2;
  }
  gl_FragColor = vec4(a1/w1s, a2/w2s, lum(texture2D(u_src, v_uv).rgb), 1.0);
}
"""

    /** Pass 2: vertical blur. */
    const val BLUR_V_FS = PREC + """
uniform sampler2D u_tex; uniform vec2 u_texel; uniform float u_s1;
varying vec2 v_uv;
void main(){
  float s2 = u_s1*1.6;
  float a1=0.0, a2=0.0, w1s=0.0, w2s=0.0;
  for (int i=-12; i<=12; i++){
    float x = float(i);
    float w1 = exp(-x*x/(2.0*u_s1*u_s1));
    float w2 = exp(-x*x/(2.0*s2*s2));
    vec3 s = texture2D(u_tex, v_uv + vec2(0.0, x*u_texel.y)).rgb;
    a1 += s.r*w1; a2 += s.g*w2; w1s += w1; w2s += w2;
  }
  gl_FragColor = vec4(a1/w1s, a2/w2s, texture2D(u_tex, v_uv).b, 1.0);
}
"""

    /** Pass 3: the drawing itself. Same as the lab, plus ink color. */
    const val DRAW_FS = PREC + """
uniform sampler2D u_blur; uniform sampler2D u_src;
uniform vec2 u_res;
uniform float u_seed, u_strength, u_thr, u_hatch, u_spacing, u_boil, u_grain, u_style;
uniform vec3 u_ink;
varying vec2 v_uv;

float hash(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p, p+45.32); return fract(p.x*p.y); }
float vnoise(vec2 p){
  vec2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);
  float a=hash(i), b=hash(i+vec2(1.0,0.0)), c=hash(i+vec2(0.0,1.0)), d=hash(i+vec2(1.0,1.0));
  return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);
}
float hatchLayer(vec2 px, float ang, float spacing, float s){
  float ca = cos(ang), sa = sin(ang);
  vec2 r = vec2(ca*px.x - sa*px.y, sa*px.x + ca*px.y);
  float row = r.y/spacing;
  float wob = (vnoise(vec2(r.x*0.02, floor(row)*3.7 + s)) - 0.5)*0.6;
  float v = row + wob;
  float f = abs(fract(v) - 0.5)*2.0;
  float line = 1.0 - smoothstep(0.16, 0.42, f);
  float seg = vnoise(vec2(r.x/(spacing*7.0), floor(v)*11.3 + s*1.7));
  line *= smoothstep(0.25, 0.42, seg);
  line *= 0.6 + 0.4*vnoise(vec2(r.x/(spacing*3.0), floor(v)*5.1));
  return line;
}
void main(){
  vec2 uv = v_uv;
  vec3 src = texture2D(u_src, uv).rgb;
  vec2 px = uv*u_res;
  float s = u_seed;

  vec2 d = vec2(vnoise(px/45.0 + vec2(s*13.1, s*7.7)),
                vnoise(px/45.0 + vec2(s*5.3+31.0, s*11.9))) - 0.5;
  vec3 b = texture2D(u_blur, uv + d*u_boil*6.0/u_res).rgb;

  float diff = b.r - b.g;
  float edge = smoothstep(u_thr, u_thr*2.5 + 0.004, -diff) * u_strength;

  float tone = clamp((b.r - 0.5)*1.15 + 0.5, 0.0, 1.0);
  vec2 hpx = px + d*u_boil*4.0;
  float ja = (hash(vec2(s, 1.7)) - 0.5)*0.06*u_boil;
  float sp = u_spacing;
  float h = 0.0;
  h = max(h, hatchLayer(hpx,  0.785+ja, sp,      s)     * (1.0 - smoothstep(0.55,0.78,tone)));
  h = max(h, hatchLayer(hpx, -0.785+ja, sp,      s+3.0) * (1.0 - smoothstep(0.36,0.56,tone)));
  h = max(h, hatchLayer(hpx,  0.17+ja,  sp*0.85, s+7.0) * (1.0 - smoothstep(0.20,0.36,tone)));
  h = max(h, hatchLayer(hpx,  1.40+ja,  sp*0.6,  s+11.0)* (1.0 - smoothstep(0.06,0.20,tone)));
  h *= u_hatch;

  float dark = 1.0 - (1.0 - edge)*(1.0 - h*0.85);

  float tooth = vnoise(px*0.9)*0.6 + vnoise(px*0.31)*0.4;
  float fiber = vnoise(vec2(px.x*0.05, px.y*0.6));
  vec3 paper = vec3(0.955,0.945,0.915) - u_grain*(0.05*tooth + 0.03*fiber);

  vec3 inkc; float grainy;
  if (u_style < 0.5){ inkc = u_ink; grainy = 1.0; }
  else if (u_style < 1.5){ inkc = u_ink; grainy = 0.15; dark = smoothstep(0.25, 0.6, dark); }
  else { inkc = src*0.55; grainy = 0.8; paper *= mix(vec3(1.0), src*1.1, 0.35); }
  dark *= mix(1.0, 0.45 + 0.55*tooth*1.3, grainy*u_grain);
  dark = clamp(dark, 0.0, 1.0);
  gl_FragColor = vec4(mix(paper, inkc, dark), 1.0);
}
"""
}
