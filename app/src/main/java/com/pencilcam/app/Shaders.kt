package com.pencilcam.app

/**
 * ============================================================================
 * Shaders
 * These are the small programs that run on the phone's graphics chip (GPU).
 * Every frame goes through four passes, one after the other:
 *
 *   Pass 0  COPY  : take the raw picture (camera, video file, or photo) and
 *                   turn it upright, mirrored if needed, into a normal texture
 *   Pass 1  BLUR H: blur the brightness sideways at two different widths
 *   Pass 2  BLUR V: finish those two blurs top to bottom
 *   Pass 3  DRAW  : build the drawing (outlines, shading, paper) on screen
 *
 * Why two blurs: subtract a wide blur from a narrow blur and what is left
 * are the edges in the picture. That difference is where the outlines go.
 * The narrow blur alone also gives a smooth "how dark is this spot" value
 * that decides how much shading a spot gets.
 *
 * All sizes in Pass 3 are in "720 units": the drawing is laid out as if the
 * picture were 720 pixels across its short side, whatever its real size.
 * That keeps the stroke spacing and wobble looking the same on a small
 * preview and on a full size save.
 * ============================================================================
 */
object Shaders {

    /**
     * Vertex shader for a plain full screen rectangle (Passes 1 to 3).
     * p runs from -1 to +1 across the screen. v_uv is the same spot in
     * texture terms, which run from 0 to 1.
     */
    const val QUAD_VS = """
attribute vec2 p;
varying vec2 v_uv;
void main(){ v_uv = p*0.5+0.5; gl_Position = vec4(p,0.0,1.0); }
"""

    /**
     * Pass 0 vertex shader for the camera and for video files.
     * For each corner of the output, it works out which spot of the incoming
     * frame to read: mirror it if asked, turn it around the center by u_rot
     * degrees, then apply u_st, the frame's own transform matrix that
     * Android hands over with every camera or video frame.
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

    /**
     * Pass 0 fragment shader for the camera and video files. Those frames
     * arrive as a special "external" texture (OES), which the later passes
     * can't read directly, so this copies it into a normal one.
     */
    const val COPY_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES u_cam;
varying vec2 v_tex;
void main(){ gl_FragColor = texture2D(u_cam, v_tex); }
"""

    /** Pass 0 for a gallery photo. Bitmaps load upside down for OpenGL, so flip Y. */
    const val COPY2D_FS = """
precision mediump float;
uniform sampler2D u_img;
varying vec2 v_uv;
void main(){ gl_FragColor = texture2D(u_img, vec2(v_uv.x, 1.0 - v_uv.y)); }
"""

    // Use high precision math when the chip has it (the S24 does).
    private const val PREC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /**
     * Pass 1: sideways blur of brightness, done twice at once.
     * Red channel  = blur at width u_s1 (the "Line softness" slider)
     * Green channel = blur at 1.6 times that width
     * Blue channel  = the plain unblurred brightness
     */
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

    /** Pass 2: the same two blurs, top to bottom, finishing what Pass 1 started. */
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

    /**
     * Pass 3: the drawing itself. u_style picks the look:
     *   0 Pencil, 1 Ink pen, 2 Colored pencil, 3 Comic,
     *   4 Halftone, 5 Watercolor, 6 Chalkboard
     *
     * Inputs:
     *   u_blur     the two blurs from Passes 1 and 2 (red = narrow, green = wide)
     *   u_src      the upright color picture from Pass 0
     *   u_res      picture size in 720 units
     *   u_seed     changes on every wobble step, so the lines redraw a little differently
     *   u_strength outline darkness       u_thr     edge threshold (from the Detail slider)
     *   u_hatch    amount of shading      u_spacing stroke or dot spacing
     *   u_boil     wobble amount          u_grain   paper grain
     *   u_ink      ink color
     */
    const val DRAW_FS = PREC + """
uniform sampler2D u_blur; uniform sampler2D u_src;
uniform vec2 u_res;
uniform float u_seed, u_strength, u_thr, u_hatch, u_spacing, u_boil, u_grain, u_style;
uniform vec3 u_ink;
varying vec2 v_uv;

// ---- Helpers ---------------------------------------------------------------

// A cheap random number from a 2D position (same input, same answer).
float hash(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p, p+45.32); return fract(p.x*p.y); }

// Smooth random "value noise": random numbers on a grid, blended in between.
// This drives the wobble, the paper texture, and the unevenness of strokes.
float vnoise(vec2 p){
  vec2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);
  float a=hash(i), b=hash(i+vec2(1.0,0.0)), c=hash(i+vec2(0.0,1.0)), d=hash(i+vec2(1.0,1.0));
  return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);
}

float lum(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }

// Turn a point around the origin by angle a (radians).
vec2 rot(vec2 p, float a){ float c = cos(a), s = sin(a); return vec2(c*p.x - s*p.y, s*p.x + c*p.y); }

// One set of parallel pencil strokes at angle ang. Each stroke wanders a
// little, breaks into dashes here and there, and varies in pressure, so it
// reads as drawn by hand instead of ruled.
float hatchLayer(vec2 px, float ang, float spacing, float s){
  vec2 r = rot(px, ang);
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

// Crosshatching built up in four layers. Each layer only shows up once the
// spot is dark enough, the same way you'd shade with a pencil: one direction
// for light gray, cross it for darker, and so on down to black.
float hatching(vec2 hpx, float tone, float ja, float sp, float s){
  float h = 0.0;
  h = max(h, hatchLayer(hpx,  0.785+ja, sp,      s)     * (1.0 - smoothstep(0.55,0.78,tone)));
  h = max(h, hatchLayer(hpx, -0.785+ja, sp,      s+3.0) * (1.0 - smoothstep(0.36,0.56,tone)));
  h = max(h, hatchLayer(hpx,  0.17+ja,  sp*0.85, s+7.0) * (1.0 - smoothstep(0.20,0.36,tone)));
  h = max(h, hatchLayer(hpx,  1.40+ja,  sp*0.6,  s+11.0)* (1.0 - smoothstep(0.06,0.20,tone)));
  return h;
}

// Halftone: how much of one round dot covers this spot. The picture is
// split into a grid of cells turned to angle ang. amt (0 to 1) is how much
// ink that cell needs; the dot grows until the darkest cells merge.
float dotAt(vec2 px, float ang, float cell, float amt){
  vec2 g = fract(rot(px, ang)/cell) - 0.5;
  float rad = sqrt(clamp(amt, 0.0, 1.0)) * 0.72;
  float aa = 1.2/cell;                              // about one pixel of soft edge
  return 1.0 - smoothstep(rad - aa, rad + aa, length(g));
}

// The middle of the halftone cell this spot is in, back in picture units.
// Every spot in a cell reads its color from here, so each dot is one solid size.
vec2 cellCenter(vec2 px, float ang, float cell){
  vec2 c = (floor(rot(px, ang)/cell) + 0.5)*cell;
  return rot(c, -ang);
}

// Split a color into printing inks: cyan, magenta, yellow, and black (k).
vec3 toCmy(vec3 c, out float k){
  k = 1.0 - max(max(c.r, c.g), c.b);
  return (1.0 - c - k)/max(1.0 - k, 0.001);
}

// ---- Main ------------------------------------------------------------------
void main(){
  vec2 uv = v_uv;
  vec3 src = texture2D(u_src, uv).rgb;
  vec2 px = uv*u_res;                               // this spot, in 720 units
  float s = u_seed;

  // Wobble: nudge where we read the blurs by a slowly varying random amount.
  // A new seed every step makes the lines "boil" like hand-drawn animation.
  vec2 d = vec2(vnoise(px/45.0 + vec2(s*13.1, s*7.7)),
                vnoise(px/45.0 + vec2(s*5.3+31.0, s*11.9))) - 0.5;
  vec3 b = texture2D(u_blur, uv + d*u_boil*6.0/u_res).rgb;

  // Outlines: narrow blur minus wide blur. Strongly negative = a dark edge.
  float diff = b.r - b.g;
  float edge = smoothstep(u_thr, u_thr*2.5 + 0.004, -diff) * u_strength;

  // Tone: how bright this spot is, with a little extra contrast.
  float tone = clamp((b.r - 0.5)*1.15 + 0.5, 0.0, 1.0);
  vec2 hpx = px + d*u_boil*4.0;                     // stroke positions wobble too
  float ja = (hash(vec2(s, 1.7)) - 0.5)*0.06*u_boil;// and their angles jitter a hair
  float sp = u_spacing;

  // Paper: warm off-white with a fine tooth and long fibers.
  float tooth = vnoise(px*0.9)*0.6 + vnoise(px*0.31)*0.4;
  float fiber = vnoise(vec2(px.x*0.05, px.y*0.6));
  vec3 paper = vec3(0.955,0.945,0.915) - u_grain*(0.05*tooth + 0.03*fiber);

  vec3 col;

  if (u_style < 2.5){
    // ---- 0 Pencil, 1 Ink pen, 2 Colored pencil ----
    float h = hatching(hpx, tone, ja, sp, s) * u_hatch;
    float dark = 1.0 - (1.0 - edge)*(1.0 - h*0.85);
    vec3 inkc; float grainy;
    if (u_style < 0.5){ inkc = u_ink; grainy = 1.0; }                                   // pencil skips over the paper tooth
    else if (u_style < 1.5){ inkc = u_ink; grainy = 0.15; dark = smoothstep(0.25, 0.6, dark); } // ink is solid and crisp
    else { inkc = src*0.55; grainy = 0.8; paper *= mix(vec3(1.0), src*1.1, 0.35); }     // strokes take the picture's color
    dark *= mix(1.0, 0.45 + 0.55*tooth*1.3, grainy*u_grain);
    col = mix(paper, inkc, clamp(dark, 0.0, 1.0));
  }
  else if (u_style < 3.5){
    // ---- 3 Comic: flat bold colors, heavy black outlines, dots in the shadows ----
    vec3 c = mix(vec3(lum(src)), src, 1.4);         // punch up the color
    c = clamp(floor(c*3.0 + 0.5)/3.0, 0.0, 1.0);    // snap each channel to 4 levels
    c = mix(c, vec3(1.0), 0.06);                    // lift it a touch so it isn't muddy
    float shadow = 1.0 - smoothstep(0.18, 0.45, tone);
    float dots = dotAt(hpx, 0.785, sp*1.2, shadow*0.6) * u_hatch;
    c = mix(c, c*0.35, dots);
    c *= 1.0 - u_grain*0.04*tooth;
    float line = smoothstep(0.12, 0.45, edge*1.6);
    col = mix(c, u_ink*0.35, line);
  }
  else if (u_style < 4.5){
    // ---- 4 Halftone: four ink dot screens like a printed newspaper photo ----
    // Each ink sits on its own angle (cyan 15, magenta 75, yellow 0, black 45
    // degrees), the same angles printers use so the dots don't clash.
    float cell = sp*1.4;
    float k;
    vec2 hp = px + d*u_boil*3.0;
    float aC = 0.2618, aM = 1.309, aY = 0.0, aK = 0.785;
    vec3 cmy; float kk;
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aC, cell)/u_res).rgb, k);
    float C = dotAt(hp, aC, cell, cmy.x);
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aM, cell)/u_res).rgb, k);
    float M = dotAt(hp, aM, cell, cmy.y);
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aY, cell)/u_res).rgb, k);
    float Y = dotAt(hp, aY, cell, cmy.z);
    toCmy(texture2D(u_src, cellCenter(hp, aK, cell)/u_res).rgb, kk);
    float K = dotAt(hp, aK, cell, kk);
    // Inks print on top of each other, so each one multiplies the paper down.
    col = paper;
    col *= mix(vec3(1.0), vec3(0.00, 0.63, 0.89), C);
    col *= mix(vec3(1.0), vec3(0.90, 0.10, 0.52), M);
    col *= mix(vec3(1.0), vec3(1.00, 0.92, 0.00), Y);
    col *= mix(vec3(1.0), u_ink*0.5, K);
    col = mix(col, u_ink*0.5, edge*0.8);
  }
  else if (u_style < 5.5){
    // ---- 5 Watercolor: soft washes that bleed past the edges ----
    // Read the picture at spots pushed around by slow noise so the color
    // runs a little outside the lines, then average a ring of 8 samples to
    // soften it like wet paint.
    vec2 bleed = (vec2(vnoise(px/28.0 + s*0.7), vnoise(px/28.0 + vec2(19.0, 7.0) + s*0.7)) - 0.5)*16.0*(0.4 + u_boil);
    vec2 wp = px + bleed;
    vec3 w = texture2D(u_src, wp/u_res).rgb;
    for (int i = 0; i < 8; i++){
      float a = float(i)*0.785;
      w += texture2D(u_src, (wp + vec2(cos(a), sin(a))*5.0)/u_res).rgb;
    }
    w /= 9.0;
    w = mix(w, floor(w*5.0 + 0.5)/5.0, 0.45);       // layered washes: soft steps in color
    w = mix(vec3(lum(w)), w, 1.2);                  // a little more color
    w = mix(vec3(1.0), w, 0.85);                    // see-through paint, the paper shows
    float pool = smoothstep(0.004, 0.03, abs(diff));// paint pools darker where a wash ends
    w *= 1.0 - 0.18*pool;
    w *= 1.0 - u_grain*0.10*(1.0 - tooth);          // pigment settles into the paper grain
    col = paper*w;
    col = mix(col, u_ink, edge*0.35);               // light pencil underdrawing
  }
  else {
    // ---- 6 Chalkboard: white chalk on a slate green board ----
    // Same strokes as pencil, but chalk adds light instead of dark, so the
    // shading goes on the BRIGHT parts of the picture.
    vec3 board = vec3(0.13, 0.17, 0.15) + u_grain*(0.03*tooth - 0.015);
    float h = hatching(hpx, 1.0 - tone, ja, sp, s) * u_hatch;
    float chalk = 1.0 - (1.0 - edge)*(1.0 - h*0.85);
    chalk *= mix(1.0, 0.35 + 0.65*tooth*1.3, u_grain);// chalk skips over the board's grain
    col = mix(board, vec3(0.93, 0.93, 0.88), clamp(chalk, 0.0, 1.0));
  }

  gl_FragColor = vec4(col, 1.0);
}
"""
}
