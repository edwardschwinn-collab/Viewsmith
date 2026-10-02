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
 *   (Oil painting only: two more passes at half size that lay down the
 *    paint, OIL_GRAD_FS and OIL_PAINT_FS)
 *   Pass 3  DRAW  : build the chosen look, then lay any finishes on top
 *
 * Why two blurs: subtract a wide blur from a narrow blur and what is left
 * are the edges in the picture. That difference is where the outlines go.
 * The narrow blur alone also gives a smooth "how dark is this spot" value
 * that decides how much shading a spot gets.
 *
 * All sizes in Pass 3 are in "720 units": the drawing is laid out as if the
 * picture were 720 pixels across its short side, whatever its real size.
 * That keeps the stroke spacing and wobble looking the same on a small
 * preview and on a 4K save.
 *
 * These are written in the plain GLSL ES 1.00 shader language, which every
 * phone with OpenGL ES 3.0 runs. That keeps them working on Pixels and other
 * phones, not just Samsungs: loops only have fixed counts, and nothing uses
 * vendor-only extensions (except the standard camera texture extension
 * that every Android phone has).
 * ============================================================================
 */
object Shaders {

    /**
     * Vertex shader for a plain full screen rectangle (Passes 1 and 2).
     * p runs from -1 to +1 across the screen. v_uv is the same spot in
     * texture terms, which run from 0 to 1.
     */
    const val QUAD_VS = """
attribute vec2 p;
varying vec2 v_uv;
void main(){ v_uv = p*0.5+0.5; gl_Position = vec4(p,0.0,1.0); }
"""

    /**
     * Vertex shader for Pass 3. Same as QUAD_VS, but it can draw the picture
     * turned a quarter turn (u_turn = 1). That's only used when a phone's
     * video encoder won't take a tall frame: the drawing goes in sideways
     * and the video file is tagged to be turned back upright when played.
     */
    const val DRAW_VS = """
attribute vec2 p;
uniform float u_turn;
varying vec2 v_uv;
void main(){
  vec2 c = p*0.5;
  if (u_turn > 0.5) c = vec2(-c.y, c.x);
  v_uv = c + 0.5;
  gl_Position = vec4(p,0.0,1.0);
}
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
    /**
     * Hold to compare: show the plain upright picture (the output of Pass
     * 0) on screen with no look at all. Only used for the screen; anything
     * being recorded or saved keeps the look.
     */
    const val PLAIN_FS = """
precision mediump float;
uniform sampler2D u_img;
varying vec2 v_uv;
void main(){ gl_FragColor = vec4(texture2D(u_img, v_uv).rgb, 1.0); }
"""

    const val COPY2D_FS = """
precision mediump float;
uniform sampler2D u_img;
varying vec2 v_uv;
void main(){ gl_FragColor = texture2D(u_img, vec2(v_uv.x, 1.0 - v_uv.y)); }
"""

    // High precision math. OpenGL ES 3.0 phones all have it in fragment shaders.
    private const val PREC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /**
     * Pass 1: sideways blur of brightness, done twice at once.
     * Red channel   = blur at width u_s1 (the "Line softness" slider)
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
     * Oil pass A (only when Oil painting is the look). Works at half size.
     * Finds which way the brightness is changing at each spot (a Sobel
     * filter: compare the neighbors on the left with the right, and above
     * with below). Saved as red = sideways change, green = up/down change,
     * both shifted so 0.5 means "no change".
     */
    const val OIL_GRAD_FS = PREC + """
uniform sampler2D u_src; uniform vec2 u_texel;
varying vec2 v_uv;
float L(vec2 o){ return dot(texture2D(u_src, v_uv + o*u_texel).rgb, vec3(0.299,0.587,0.114)); }
void main(){
  float tl = L(vec2(-1.0, 1.0)), t = L(vec2(0.0, 1.0)), tr = L(vec2(1.0, 1.0));
  float l  = L(vec2(-1.0, 0.0)),                        r = L(vec2(1.0, 0.0));
  float bl = L(vec2(-1.0,-1.0)), b = L(vec2(0.0,-1.0)), br = L(vec2(1.0,-1.0));
  float gx = (tr + 2.0*r + br - tl - 2.0*l - bl)/4.0;
  float gy = (tl + 2.0*t + tr - bl - 2.0*b - br)/4.0;
  gl_FragColor = vec4(gx*0.5 + 0.5, gy*0.5 + 0.5, 0.0, 1.0);
}
"""

    /**
     * Oil pass B: lay down the paint, at half size (Pass 3 stretches it back
     * up, which also softens it like real paint).
     *
     * This is an "anisotropic Kuwahara" filter, which is the plain Kuwahara
     * idea with the brush turned to follow the picture:
     *  1. Look at the brightness changes (from pass A) in a 5 x 5 patch
     *     around this spot and work out the main direction the edges run
     *     (the "structure tensor" and its eigenvector, a bit of 2 x 2 matrix
     *     math). Also how strongly they agree on a direction (0 = no
     *     direction, like a flat wall; 1 = a clean edge).
     *  2. Lay an oval brush along that direction: longer along the edge,
     *     narrower across it, more so the stronger the direction.
     *  3. Split the oval into four quarters, average each one, and keep the
     *     quarter that's most even. Smooth areas become flat dabs of paint,
     *     and the strokes run along the shapes instead of in a grid.
     * Output: paint color in rgb, stroke direction in alpha (0 to 1 = 0 to 180 degrees).
     */
    const val OIL_PAINT_FS = PREC + """
uniform sampler2D u_src; uniform sampler2D u_grad;
uniform vec2 u_res; uniform vec2 u_gtexel; uniform float u_brush;
varying vec2 v_uv;
void main(){
  // 1. Structure tensor from a 5 x 5 patch of gradients, 2 texels apart
  float E = 0.0, F = 0.0, G = 0.0;
  for (int j = -2; j <= 2; j++){
    for (int i = -2; i <= 2; i++){
      vec2 g = texture2D(u_grad, v_uv + vec2(float(i), float(j))*2.0*u_gtexel).rg*2.0 - 1.0;
      E += g.x*g.x; F += g.y*g.y; G += g.x*g.y;
    }
  }
  float disc = sqrt((E - F)*(E - F) + 4.0*G*G);
  float l1 = 0.5*(E + F + disc), l2 = 0.5*(E + F - disc);
  vec2 t = vec2(l1 - E, -G);                        // direction the edge runs
  t = dot(t, t) < 1e-10 ? vec2(1.0, 0.0) : normalize(t);
  float A = (l1 + l2) > 1e-6 ? (l1 - l2)/(l1 + l2) : 0.0;   // how directional, 0 to 1
  vec2 n = vec2(-t.y, t.x);

  // 2. The oval brush, in 720 units
  float ra = u_brush*(1.0 + 1.2*A), rb = u_brush/(1.0 + 1.2*A);
  vec2 p = v_uv*u_res;

  // 3. Four quarters, 4 x 4 samples each
  vec3 m0 = vec3(0.0), m1 = vec3(0.0), m2 = vec3(0.0), m3 = vec3(0.0);
  vec3 s0 = vec3(0.0), s1 = vec3(0.0), s2 = vec3(0.0), s3 = vec3(0.0);
  for (int j = 0; j < 4; j++){
    for (int i = 0; i < 4; i++){
      float ox = float(i)/3.0*ra, oy = float(j)/3.0*rb;
      vec3 c;
      c = texture2D(u_src, (p + t*ox + n*oy)/u_res).rgb; m0 += c; s0 += c*c;
      c = texture2D(u_src, (p - t*ox + n*oy)/u_res).rgb; m1 += c; s1 += c*c;
      c = texture2D(u_src, (p + t*ox - n*oy)/u_res).rgb; m2 += c; s2 += c*c;
      c = texture2D(u_src, (p - t*ox - n*oy)/u_res).rgb; m3 += c; s3 += c*c;
    }
  }
  m0 /= 16.0; m1 /= 16.0; m2 /= 16.0; m3 /= 16.0;
  float v0 = dot(s0/16.0 - m0*m0, vec3(1.0));
  float v1 = dot(s1/16.0 - m1*m1, vec3(1.0));
  float v2 = dot(s2/16.0 - m2*m2, vec3(1.0));
  float v3 = dot(s3/16.0 - m3*m3, vec3(1.0));
  vec3 best = m0; float bv = v0;
  if (v1 < bv){ bv = v1; best = m1; }
  if (v2 < bv){ bv = v2; best = m2; }
  if (v3 < bv){ bv = v3; best = m3; }
  gl_FragColor = vec4(best, fract(atan(t.y, t.x)/3.14159265 + 1.0));
}
"""

    /**
     * Pass 3: the drawing itself. u_style picks the look (see Looks.names):
     *   0 Pencil, 1 Ink pen, 2 Colored pencil, 3 Comic, 4 Halftone,
     *   5 Watercolor, 6 Chalkboard, 7 Oil painting, 8 Neon edges,
     *   9 Crayon edges, 10 Old west, 11 Inverted, 12 Predator vision
     * Then the finishes (vignette, matte, glossy, polaroid) go on top.
     *
     * Inputs:
     *   u_blur     the two blurs from Passes 1 and 2 (red = narrow, green = wide)
     *   u_src      the upright color picture from Pass 0
     *   u_res      picture size in 720 units
     *   u_seed     changes on every wobble step, so the lines redraw a little differently
     *   u_strength outline darkness       u_thr     edge threshold (from the Detail slider)
     *   u_hatch    amount of shading      u_spacing stroke spacing
     *   u_boil     wobble amount          u_grain   paper grain
     *   u_ink      ink color (for Neon edges, the neon tube color)
     *   u_p        the current look's own four settings
     *   u_finOn    which finishes are on (x vignette, y matte, z glossy, w polaroid)
     *   u_finA/B   each finish's first and second slider, same order
     *   u_frameRot which edge of the picture is the viewer's bottom (0, 90, 180, 270),
     *              so the polaroid's thick edge, the glossy shine, oil paint
     *              highlights, and Old west creases are all lit from the viewer's top
     *   u_time     seconds, for slow cycling (neon rainbow). The video's own clock when saving.
     *   u_dmg      Old west damage layout number ("New damage" changes it)
     *   u_paint    the oil paint image from the two oil passes (only used by Oil painting)
     *   u_caption  the polaroid caption, drawn as handwriting into a small image;
     *              u_capOn = 1 if there is one
     */
    const val DRAW_FS = PREC + """
uniform sampler2D u_blur; uniform sampler2D u_src; uniform sampler2D u_paint; uniform sampler2D u_caption;
uniform vec2 u_res;
uniform float u_seed, u_strength, u_thr, u_hatch, u_spacing, u_boil, u_grain, u_style;
uniform vec3 u_ink;
uniform vec4 u_p;
uniform vec4 u_finOn, u_finA, u_finB;
uniform float u_frameRot, u_time, u_dmg, u_capOn;
uniform float u_mix;          // Mix slider: 1 = all look, 0 = the plain picture
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

// Hue, saturation, brightness to red, green, blue. Hue runs 0 to 1 around the color wheel.
vec3 hsv2rgb(float h, float s, float v){
  vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0/3.0, 1.0/3.0))*6.0 - 3.0) - 1.0, 0.0, 1.0);
  return v*mix(vec3(1.0), k, s);
}

// Heat camera colors. t runs 0 (cold) to 1 (hot). pal picks the palette:
//   0 Ironbow:   black, deep blue, purple, red, orange, yellow, white
//   1 White-hot: black cold, white hot (how most real thermal scopes look)
//   2 Black-hot: the reverse, white cold, black hot
//   3 Rainbow:   blue cold through green and yellow to red, white at the very top
vec3 thermal(float t, float pal){
  if (pal > 2.5){
    vec3 r = hsv2rgb(0.66*(1.0 - t), 1.0, 0.35 + 0.65*smoothstep(0.0, 0.25, t));
    return mix(r, vec3(1.0), smoothstep(0.93, 1.0, t));
  }
  if (pal > 1.5) return vec3(1.0 - t)*vec3(0.97, 1.0, 0.97);
  if (pal > 0.5) return vec3(t)*vec3(0.97, 1.0, 0.97);
  vec3 c = mix(vec3(0.0), vec3(0.10,0.0,0.45), smoothstep(0.0,0.2,t));
  c = mix(c, vec3(0.55,0.0,0.65), smoothstep(0.2,0.4,t));
  c = mix(c, vec3(0.95,0.10,0.10), smoothstep(0.4,0.6,t));
  c = mix(c, vec3(1.0,0.55,0.0), smoothstep(0.6,0.78,t));
  c = mix(c, vec3(1.0,0.95,0.2), smoothstep(0.78,0.92,t));
  return mix(c, vec3(1.0), smoothstep(0.92,1.0,t));
}

// Which way is UP for the person looking at the picture, in picture
// coordinates. Everything that's "lit from above" uses this.
vec2 upVec(){
  if (u_frameRot < 45.0) return vec2(0.0, 1.0);
  if (u_frameRot < 135.0) return vec2(-1.0, 0.0);
  if (u_frameRot < 225.0) return vec2(0.0, -1.0);
  return vec2(1.0, 0.0);
}

// ---- Old west damage helpers ----

// One fold crease: a long line across the print through point p0 at angle
// ang, wandering a little like a real fold. Returns the signed distance from
// the crease (in 720 units), positive on the side toward the light.
float creaseSide(vec2 px, vec2 p0, float ang, vec2 up){
  vec2 dir = vec2(cos(ang), sin(ang));
  vec2 nrm = vec2(-dir.y, dir.x);
  if (dot(nrm, up) < 0.0) nrm = -nrm;               // point the normal toward the light
  float along = dot(px - p0, dir);
  return dot(px - p0, nrm) + (vnoise(vec2(along*0.02, ang*7.0)) - 0.5)*5.0;
}

// How a crease looks: a thin highlight on its upper edge (catching the
// light from above) and a soft shadow just below it. Returns +bright / -dark.
float creaseLight(float sd){
  float hi = smoothstep(2.2, 0.4, sd)*step(0.0, sd);
  float sh = smoothstep(6.0, 0.0, -sd)*step(sd, 0.0);
  return 0.35*hi - 0.30*sh;
}

// Scratches on a print, at random angles. The print is cut into 180 unit
// cells; some cells hold one scratch: a short nick, a long hairline, or a
// long one with a slight curve. Looks at the 5 x 5 cells around this spot so
// long scratches carry across cell lines. ds = damage layout, amt = how many.
float printScratches(vec2 px, float ds, float amt){
  float best = 0.0;
  vec2 cell = floor(px/180.0);
  for (int j = -2; j <= 2; j++){
    for (int i = -2; i <= 2; i++){
      vec2 c = cell + vec2(float(i), float(j));
      float h1 = hash(c + ds*1.37);
      if (h1 > 0.1 + 0.55*amt) continue;
      float h2 = hash(c*1.7 + ds*2.11), h3 = hash(c*2.3 + ds*0.73), h4 = hash(c*3.1 + ds*1.91);
      float kind = hash(c*4.7 + ds);
      vec2 start = (c + vec2(h2, h3))*180.0;
      float ang = h4*6.2832;
      float len = kind < 0.5 ? mix(6.0, 24.0, h2) : mix(90.0, 380.0, h3);
      float wdt = kind < 0.5 ? 1.4 : 0.6;
      vec2 dir = vec2(cos(ang), sin(ang)), nrm = vec2(-dir.y, dir.x);
      vec2 q = px - start;
      float along = dot(q, dir);
      float bend = kind > 0.8 ? (h1 - 0.05)*0.004*along*along : 0.0;   // the curved ones
      float across = abs(dot(q, nrm) - bend);
      float inside = step(0.0, along)*step(along, len);
      float line = inside*(1.0 - smoothstep(wdt*0.5, wdt*1.6, across));
      line *= 0.55 + 0.45*vnoise(vec2(along*0.05, h1*50.0));          // uneven, like a real scrape
      best = max(best, line);
    }
  }
  return best;
}

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

// The finishes need to know which way is up for the person looking at the
// picture. These convert between picture coordinates and "viewer"
// coordinates, where the viewer's bottom edge is y = 0.
vec2 toViewer(vec2 uv){
  if (u_frameRot < 45.0) return uv;
  if (u_frameRot < 135.0) return vec2(uv.y, 1.0 - uv.x);
  if (u_frameRot < 225.0) return 1.0 - uv;
  return vec2(1.0 - uv.y, uv.x);
}
vec2 fromViewer(vec2 q){
  if (u_frameRot < 45.0) return q;
  if (u_frameRot < 135.0) return vec2(1.0 - q.y, q.x);
  if (u_frameRot < 225.0) return 1.0 - q;
  return vec2(q.y, 1.0 - q.x);
}

// ---- Main ------------------------------------------------------------------
void main(){
  // ---- Where this spot is. Normally that's just v_uv. With the polaroid
  // finish on, the picture shrinks inside a white border (thick at the
  // viewer's bottom), so every spot inside the border reads from a
  // matching spot of a slightly cropped picture. Cropped, not squashed.
  vec2 uv = v_uv;
  vec2 q = toViewer(v_uv);                          // this spot, as the viewer sees it
  bool sideways = u_frameRot > 45.0 && u_frameRot < 135.0 || u_frameRot > 225.0 && u_frameRot < 315.0;
  vec2 vres = sideways ? u_res.yx : u_res;          // picture size as the viewer sees it
  vec2 pq = q;                                      // spot within the picture area, 0 to 1
  bool onBorder = false;
  if (u_finOn.w > 0.5){
    float side = mix(14.0, 46.0, u_finA.w);         // border width in 720 units
    vec2 lo = vec2(side, side*3.2)/vres;            // left and bottom border
    vec2 hi = vec2(1.0) - vec2(side)/vres;          // right and top border
    pq = (q - lo)/(hi - lo);
    onBorder = pq.x < 0.0 || pq.x > 1.0 || pq.y < 0.0 || pq.y > 1.0;
    vec2 inner = (hi - lo)*vres;                    // picture area in 720 units
    float kk = max(inner.x/vres.x, inner.y/vres.y);
    vec2 crop = inner/(kk*vres);                    // how much of the full picture fits
    uv = fromViewer(0.5 + (clamp(pq, 0.0, 1.0) - 0.5)*crop);
  }

  vec3 src = texture2D(u_src, uv).rgb;
  vec2 px = uv*u_res;                               // this spot, in 720 units
  float s = u_seed;

  // Wobble: nudge where we read the blurs by a slowly varying random amount.
  // A new seed every step makes the lines "boil" like hand-drawn animation.
  vec2 d = vec2(vnoise(px/45.0 + vec2(s*13.1, s*7.7)),
                vnoise(px/45.0 + vec2(s*5.3+31.0, s*11.9))) - 0.5;
  vec3 b = texture2D(u_blur, uv + d*u_boil*6.0/u_res).rgb;

  // Outlines: narrow blur minus wide blur. Strongly negative = the dark side
  // of an edge (where pencil lines go). edgeAbs catches both sides, for the
  // looks that draw glowing or bright edges.
  float diff = b.r - b.g;
  float edge = smoothstep(u_thr, u_thr*2.5 + 0.004, -diff) * u_strength;
  float edgeAbs = smoothstep(u_thr, u_thr*2.5 + 0.004, abs(diff)) * u_strength;

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
    // ---- 3 Comic: flat bold colors, heavy outlines, dots in the shadows ----
    // u_p: color levels, shadow dots, outline weight
    float lv = max(u_p.x - 1.0, 1.0);
    vec3 c = mix(vec3(lum(src)), src, 1.4);         // punch up the color
    c = clamp(floor(c*lv + 0.5)/lv, 0.0, 1.0);      // snap each channel to a few levels
    c = mix(c, vec3(1.0), 0.06);                    // lift it a touch so it isn't muddy
    float shadow = 1.0 - smoothstep(0.18, 0.45, tone);
    float dots = dotAt(hpx, 0.785, sp*1.2, shadow*0.6) * u_p.y;
    c = mix(c, c*0.35, dots);
    c *= 1.0 - u_grain*0.04*tooth;
    float line = smoothstep(0.12, 0.45, edge*(0.6 + 1.7*u_p.z));
    col = mix(c, u_ink*0.35, line);
  }
  else if (u_style < 4.5){
    // ---- 4 Halftone: ink dot screens like a printed newspaper photo ----
    // u_p: dot size, color (0 = black ink only, 1 = full color), outlines.
    // Each ink sits on its own angle (cyan 15, magenta 75, yellow 0, black
    // 45 degrees), the same angles printers use so the dots don't clash.
    float cell = u_p.x;
    float k; float kk;
    vec2 hp = px + d*u_boil*3.0;
    float aC = 0.2618, aM = 1.309, aY = 0.0, aK = 0.785;
    vec3 cmy;
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aC, cell)/u_res).rgb, k);
    float C = dotAt(hp, aC, cell, cmy.x);
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aM, cell)/u_res).rgb, k);
    float M = dotAt(hp, aM, cell, cmy.y);
    cmy = toCmy(texture2D(u_src, cellCenter(hp, aY, cell)/u_res).rgb, k);
    float Y = dotAt(hp, aY, cell, cmy.z);
    vec3 kc = texture2D(u_src, cellCenter(hp, aK, cell)/u_res).rgb;
    toCmy(kc, kk);
    float K = dotAt(hp, aK, cell, kk);
    // Inks print on top of each other, so each one multiplies the paper down.
    vec3 full = paper;
    full *= mix(vec3(1.0), vec3(0.00, 0.63, 0.89), C);
    full *= mix(vec3(1.0), vec3(0.90, 0.10, 0.52), M);
    full *= mix(vec3(1.0), vec3(1.00, 0.92, 0.00), Y);
    full *= mix(vec3(1.0), u_ink*0.5, K);
    // Black only: one screen, dot size from brightness
    vec3 mono = paper*mix(vec3(1.0), u_ink*0.5, dotAt(hp, aK, cell, 1.0 - lum(kc)));
    col = mix(mono, full, u_p.y);
    col = mix(col, u_ink*0.5, edge*u_p.z);
  }
  else if (u_style < 5.5){
    // ---- 5 Watercolor: soft washes that bleed past the edges ----
    // u_p: bleed, color strength, edge pooling.
    // Read the picture at spots pushed around by slow noise so the color
    // runs outside the pencil lines, then average a ring of 8 samples to
    // soften it like wet paint. The pencil underdrawing stays where it was,
    // so the paint visibly slips past it.
    vec2 bleed = (vec2(vnoise(px/28.0 + s*0.7), vnoise(px/28.0 + vec2(19.0, 7.0) + s*0.7)) - 0.5)*16.0*(0.25 + 1.4*u_p.x + 0.4*u_boil);
    vec2 wp = px + bleed;
    vec3 w = texture2D(u_src, wp/u_res).rgb;
    for (int i = 0; i < 8; i++){
      float a = float(i)*0.785;
      w += texture2D(u_src, (wp + vec2(cos(a), sin(a))*5.0)/u_res).rgb;
    }
    w /= 9.0;
    w = mix(w, floor(w*5.0 + 0.5)/5.0, 0.45);       // layered washes: soft steps in color
    w = mix(vec3(lum(w)), w, 1.2);                  // a little more color
    w = mix(vec3(1.0), w, 0.4 + 0.6*u_p.y);         // see-through paint, the paper shows
    // Pooling: when a wash dries, the pigment gathers at its edge and dries
    // darker there. Find the wash edges where the paint actually went (the
    // bled position, not the original lines) and darken them in their own
    // color, a bit like doubling up the paint.
    vec3 wb = texture2D(u_blur, wp/u_res).rgb;
    float pool = smoothstep(0.003, 0.025, abs(wb.r - wb.g));
    w = mix(w, w*w*0.92, pool*u_p.z);
    // Granulation: pigment settles into the low spots of the paper, more so
    // in the darker, heavier washes
    float heavy = 1.0 - lum(w);
    w *= 1.0 - u_grain*(0.06 + 0.30*heavy)*(1.0 - tooth);
    col = paper*w;
    col = mix(col, u_ink, edge*0.35);               // light pencil underdrawing
  }
  else if (u_style < 6.5){
    // ---- 6 Chalkboard: white chalk on a slate green board ----
    // u_p: chalk shading, board darkness, chalk dust.
    // Same strokes as pencil, but chalk adds light instead of dark, so the
    // shading goes on the BRIGHT parts of the picture.
    vec3 board = mix(vec3(0.20, 0.25, 0.22), vec3(0.07, 0.09, 0.08), u_p.y) + u_grain*(0.03*tooth - 0.015);
    board += u_p.z*0.07*vnoise(px/60.0 + 5.0);       // old erased chalk haze
    float h = hatching(hpx, 1.0 - tone, ja, sp*1.3, s) * u_p.x * 0.6;   // chalk is sparser than pencil
    float chalk = 1.0 - (1.0 - edge)*(1.0 - h*0.85);
    chalk *= mix(1.0, 0.35 + 0.65*tooth*1.3, u_grain);// chalk skips over the board's grain
    col = mix(board, vec3(0.93, 0.93, 0.88), clamp(chalk, 0.0, 1.0));
  }
  else if (u_style < 7.5){
    // ---- 7 Oil painting ----
    // u_p: brush size, impasto depth, canvas.
    // The paint itself was laid down by the two oil passes (OIL_GRAD_FS and
    // OIL_PAINT_FS) at half size, with strokes that follow the shapes in
    // the picture. u_paint holds that: color in rgb, and the stroke
    // direction in alpha (0 to 1 = 0 to 180 degrees). Reading it here
    // stretches it back up to full size.
    vec4 pt = texture2D(u_paint, uv + d*u_boil*2.0/u_res);
    vec3 oc = pt.rgb;
    // A painter's limited palette: snap colors toward a few steps, then
    // push the saturation so it reads as pigment, not a photo
    oc = mix(oc, floor(oc*7.0 + 0.5)/7.0, 0.55);
    oc = clamp(mix(vec3(lum(oc)), oc, 1.35), 0.0, 1.0);
    // Impasto: the strokes are raised paint. Their height is the paint's
    // brightness plus bristle ridges running along the stroke direction.
    // Compare the height a little above and a little below this spot (as
    // the viewer sees it): rising toward the light = highlight on top,
    // falling away = shadow underneath.
    float ang = pt.a*3.14159265;
    vec2 td = vec2(cos(ang), sin(ang)), tn = vec2(-td.y, td.x);
    vec2 up = upVec();
    float e = 1.6;
    vec2 pa = px + up*e, pb = px - up*e;
    float ha = lum(texture2D(u_paint, pa/u_res).rgb)*0.75 + 0.25*vnoise(vec2(dot(pa, td)*0.04, dot(pa, tn)*0.3));
    float hb = lum(texture2D(u_paint, pb/u_res).rgb)*0.75 + 0.25*vnoise(vec2(dot(pb, td)*0.04, dot(pb, tn)*0.3));
    float slope = (ha - hb)*1.6;
    oc += u_p.y*(0.55*max(slope, 0.0) - 0.45*max(-slope, 0.0));
    // A subtle canvas weave under the paint, showing most where paint is thin
    float weave = sin(px.x*2.3)*sin(px.y*2.3)*0.5 + 0.5;
    float thread = vnoise(vec2(px.x*0.9, px.y*0.08))*0.5 + vnoise(vec2(px.x*0.08, px.y*0.9))*0.5;
    oc *= 1.0 - u_p.z*(0.06*weave + 0.05*thread);
    col = oc;
  }
  else if (u_style < 8.5){
    // ---- 8 Neon edges: glowing tubes of light on black ----
    // u_p: glow, mode (0 Solid, 1 Rainbow, 2 Two-tone, 3 Scene color),
    // rainbow speed. u_ink is the neon color picked on the Lines tab.
    // The glow is the edge signal sampled in a ring around this spot, so
    // light spills out from every line.
    float g = 0.0;
    float R = 3.0 + 9.0*u_p.x;
    for (int i = 0; i < 8; i++){
      float a = float(i)*0.785 + s*0.3;
      vec3 bb = texture2D(u_blur, uv + vec2(cos(a), sin(a))*R/u_res).rgb;
      g += smoothstep(u_thr, u_thr*2.5 + 0.004, abs(bb.r - bb.g));
    }
    g = g/8.0*u_strength;
    vec3 bg = vec3(0.015, 0.015, 0.03);
    float glowAmt = 0.6 + 1.8*u_p.x;
    vec3 nc;
    if (u_p.y < 0.5) nc = u_ink;                                       // Solid: the picked color
    else if (u_p.y < 1.5){                                             // Rainbow: hue sweeps across
      float hue = 0.55*uv.x + 0.30*uv.y + 0.10*vnoise(px/160.0)        // the picture and slowly
                + u_time*(0.02 + 0.25*u_p.z);                          // turns over time
      nc = hsv2rgb(fract(hue), 0.85, 1.0);
    }
    else if (u_p.y < 2.5) nc = u_ink;                                  // Two-tone: see below
    else {                                                             // Scene color: the real
      float mx = max(max(src.r, src.g), src.b);                        // color, pushed to full
      nc = src/max(mx, 0.08);                                          // brightness and saturated
      nc = clamp(mix(vec3(lum(nc)), nc, 1.5), 0.0, 1.0);
    }
    if (u_p.y > 1.5 && u_p.y < 2.5){
      // Two-tone: a white-hot core inside a colored glow, like a real tube
      col = bg + nc*g*(glowAmt + 0.4) + vec3(1.0)*edgeAbs*1.5 + nc*edgeAbs*0.3;
    } else {
      col = bg + nc*g*glowAmt + mix(nc, vec3(1.0), 0.55)*edgeAbs*1.4;
    }
  }
  else if (u_style < 9.5){
    // ---- 9 Crayon edges: thick waxy outlines and loose colored scribbles ----
    // u_p: stroke thickness, color fill, wax texture.
    // Fatten the outline by also checking a few spots to each side.
    float t = 2.0 + 6.0*u_p.x;
    float ed = smoothstep(u_thr*0.6, u_thr*1.8 + 0.003, abs(diff));
    for (int i = 0; i < 4; i++){
      float a = float(i)*1.571 + 0.4;
      vec3 bb = texture2D(u_blur, uv + (vec2(cos(a), sin(a))*t + d*u_boil*4.0)/u_res).rgb;
      ed = max(ed, smoothstep(u_thr*0.6, u_thr*1.8 + 0.003, abs(bb.r - bb.g)));
    }
    ed *= u_strength;
    vec3 wax = mix(vec3(lum(src)), src, 1.8)*0.5;   // crayon takes the picture's color, deeper
    wax = mix(wax, u_ink, 0.35);
    // Wax skips over the bumps in the paper, leaving little white specks
    float cover = 1.0 - u_p.z*0.75*smoothstep(0.35, 0.75, tooth*0.7 + 0.3*vnoise(vec2(px.x*0.2, px.y*1.5)));
    float fill = hatchLayer(hpx, 0.6 + ja, sp*1.6, s);
    vec3 fillCol = mix(vec3(lum(src)), src, 1.4);
    col = paper;
    col = mix(col, fillCol*0.9, 0.8*u_p.y*fill*cover*(1.0 - tone*0.4));
    col = mix(col, wax, clamp(ed, 0.0, 1.0)*cover);
  }
  else if (u_style < 10.5){
    // ---- 10 Old west: an old sepia photograph that's been through a lot ----
    // u_p: sepia, scratches, wear, damage mode (0 Print, 1 Film).
    // u_dmg picks the damage layout; "New damage" in the Style tab changes
    // it. In Print mode everything stays put, like damage on a real print.
    // In Film mode vertical scratches and dust flicker frame to frame like
    // an old reel going through a projector.
    float wear = u_p.z;
    float ds = u_dmg;
    vec2 up = upVec();
    float mid = smoothstep(0.30, 0.45, wear);       // folds and corner wear start here
    float high = smoothstep(0.65, 0.80, wear);      // cross folds, cracks, chips, dog-ears

    float l = mix(lum(src), b.r, 0.3);              // a little soft, like an old lens
    l = clamp((l - 0.5)*1.35 + 0.52, 0.0, 1.0);
    l = mix(l, 0.62 + (l - 0.5)*0.55, wear*0.6);    // fading: blacks lift, contrast flattens
    float blot = vnoise(px/140.0 + vec2(3.1, 7.7) + ds);
    l *= 1.0 - 0.25*wear*smoothstep(0.55, 0.9, blot);                 // uneven chemistry
    l *= 1.0 - 0.45*wear*smoothstep(0.35, 0.8, length(uv - 0.5));     // darker toward the edges

    if (u_p.w < 0.5){
      // Print: scratches at random angles, fixed in place
      float sc = printScratches(px, ds, u_p.y);
      l = mix(l, 0.96, sc*0.85);
      l = mix(l, 0.06, u_p.y*step(0.9988, hash(floor(px/1.8) + ds*7.0)));     // specks of dust
    } else {
      // Film: new vertical scratches and dust about 18 times a second
      float fr = s + floor(u_time*18.0);
      float sx = px.x + (vnoise(vec2(px.y*0.01, fr)) - 0.5)*30.0;
      float scratch = step(0.997 - 0.003*u_p.y, hash(vec2(floor(sx/1.5), fr))) * step(0.3, vnoise(vec2(sx*0.01, px.y*0.02 + fr)));
      float dust = step(0.9985, hash(floor(px/1.8) + fr*7.0));
      l = mix(l, 0.95, scratch*0.8*min(1.0, u_p.y*2.0));
      l = mix(l, 0.05, u_p.y*dust);
    }

    vec3 darkT = vec3(0.10, 0.08, 0.06), midT = vec3(0.47, 0.38, 0.27), lightT = vec3(0.93, 0.87, 0.75);
    vec3 tinted = l < 0.5 ? mix(darkT, midT, l*2.0) : mix(midT, lightT, (l - 0.5)*2.0);
    col = mix(vec3(l), tinted, u_p.x);
    col *= 1.0 - u_grain*0.06*tooth;

    // Age spots ("foxing"): brown freckles that creep in as the print ages
    float fox = smoothstep(0.80, 0.86, vnoise(px*0.035 + ds*13.0)) + 0.6*step(0.93, vnoise(px*0.16 + ds*5.0));
    col = mix(col, col*vec3(0.78, 0.62, 0.42), clamp(fox, 0.0, 1.0)*smoothstep(0.15, 0.9, wear)*0.8);

    vec3 paperBack = vec3(0.90, 0.86, 0.76);        // bare paper where the picture layer is gone
    if (mid > 0.0){
      // One fold crease, roughly across or down the middle
      vec2 c1 = u_res*(0.5 + (vec2(hash(vec2(ds, 1.0)), hash(vec2(ds, 2.0))) - 0.5)*0.4);
      float a1 = (hash(vec2(ds, 3.0)) > 0.5 ? 0.0 : 1.5708) + (hash(vec2(ds, 4.0)) - 0.5)*0.35;
      float sd1 = creaseSide(px, c1, a1, up);
      col += creaseLight(sd1)*mid;
      float nearCrease = abs(sd1);
      if (high > 0.0){
        // A second fold crossing the first
        float a2 = a1 + 1.5708 + (hash(vec2(ds, 5.0)) - 0.5)*0.3;
        vec2 c2 = u_res*(0.5 + (vec2(hash(vec2(ds, 6.0)), hash(vec2(ds, 7.0))) - 0.5)*0.4);
        float sd2 = creaseSide(px, c2, a2, up);
        col += creaseLight(sd2)*high;
        nearCrease = min(nearCrease, abs(sd2));
        // Cracked picture layer along the creases: a web of fine cracks
        // and flakes that have fallen off, showing bare paper underneath
        float near = smoothstep(22.0, 4.0, nearCrease)*high;
        float crack = 1.0 - smoothstep(0.0, 0.05, abs(vnoise(px*0.09 + ds) - 0.5));
        float flake = smoothstep(0.62, 0.70, vnoise(px*0.06 + ds*3.0));
        col = mix(col, paperBack, near*clamp(crack*0.8 + flake, 0.0, 1.0));
      }
      // Corner wear: the corners get rubbed pale from handling
      float corner = smoothstep(55.0, 8.0, length(min(px, u_res - px)));
      col = mix(col, paperBack, clamp(corner*(0.6 + 0.4*vnoise(px*0.2 + ds))*mid, 0.0, 1.0)*0.85);
    }
    if (high > 0.0){
      // A dog-eared corner: one corner folded over, showing the cream back
      // of the print, with a shadow just past the fold line
      float ci = floor(hash(vec2(ds, 9.0))*4.0);
      vec2 cpx = vec2(mod(ci, 2.0), floor(ci/2.0))*u_res;
      vec2 lc = abs(px - cpx);
      float t = lc.x + lc.y;
      float sz = (50.0 + 70.0*hash(vec2(ds, 10.0)))*high;
      if (t < sz) col = paperBack*(0.80 + 0.14*(t/sz));
      else col *= 1.0 - 0.30*smoothstep(8.0, 0.0, t - sz);
      // Chipped edges: bites out of the border showing the dark table underneath
      float edgeD = min(min(px.x, px.y), min(u_res.x - px.x, u_res.y - px.y));
      float chip = (3.0 + 16.0*smoothstep(0.55, 0.9, vnoise(vec2((px.x + px.y)*0.04, ds))))*high;
      col = mix(col, vec3(0.11, 0.10, 0.09), 1.0 - smoothstep(chip - 1.0, chip + 1.0, edgeD));
    }
  }
  else if (u_style < 11.5){
    // ---- 11 Inverted: a color negative ----
    // u_p: contrast, color (0 = black and white), outlines
    vec3 inv = 1.0 - src;
    inv = (inv - 0.5)*(0.6 + 1.2*u_p.x) + 0.5;
    inv = mix(vec3(lum(inv)), inv, u_p.y);
    col = mix(clamp(inv, 0.0, 1.0), vec3(0.97), edgeAbs*u_p.z);
  }
  else {
    // ---- 12 Predator vision: a fake heat camera ----
    // u_p: edge heat, contrast, scan lines, palette. Bright spots read hot
    // and so do busy edges. No real temperature involved, it just looks the part.
    float heat = (b.r - 0.5)*(0.8 + 1.8*u_p.y) + 0.5 + edgeAbs*u_p.x*0.6;
    heat += (vnoise(px/90.0 + s*0.4) - 0.5)*0.05;   // a slight shimmer
    col = thermal(clamp(heat, 0.0, 1.0), u_p.w);
    col *= 1.0 - u_p.z*0.3*(0.5 + 0.5*sin(px.y*2.2));
  }

  // ---- Mix: blend the look back toward the plain picture. The finishes
  // still go on top at full strength, so a polaroid stays a polaroid.
  col = mix(src, col, u_mix);

  // ---- Finishes, always in this order so they stack the same way every time:
  // matte, glossy punch, polaroid tones, vignette, polaroid border, glossy shine.
  if (u_finOn.y > 0.5){
    // Matte: lift the blacks, calm the color, soften the contrast
    col = col*(1.0 - 0.22*u_finA.y) + 0.10*u_finA.y;
    col = mix(col, vec3(lum(col)), 0.35*u_finB.y);
    col = mix(col, vec3(0.5), 0.12*u_finB.y);
  }
  if (u_finOn.z > 0.5){
    // Glossy, part one: a print coating makes colors deeper and contrast crisper
    col = (col - 0.5)*(1.0 + 0.3*u_finB.z) + 0.5;
    col = mix(vec3(lum(col)), col, 1.0 + 0.35*u_finB.z);
  }
  if (u_finOn.w > 0.5){
    // Polaroid tones: faded, warm, slightly washed out
    float fd = u_finB.w;
    col = mix(col, vec3(lum(col)), 0.25*fd);
    col = col*vec3(1.0, 0.98, 0.90)*(1.0 - 0.12*fd) + vec3(0.07, 0.06, 0.04)*fd*1.2;
    // The border's top edge shades the very top of the picture (light from above)
    col *= 1.0 - 0.10*(1.0 - smoothstep(0.0, 0.03, 1.0 - pq.y));
  }
  if (u_finOn.x > 0.5){
    // Vignette: darken toward the corners of the picture area
    vec2 c = (pq - 0.5)*vec2(vres.x/vres.y, 1.0);
    float r = length(c)/length(vec2(vres.x/vres.y, 1.0)*0.5);   // 0 at the center, 1 in the corners
    float start = mix(0.7, 0.05, u_finB.x);
    col *= 1.0 - u_finA.x*smoothstep(start, start + 0.55, r);
  }
  col = clamp(col, 0.0, 1.0);
  if (onBorder){
    // The polaroid's white border: a slightly textured card, a touch
    // brighter at the top where the light hits it
    vec2 bq = q*vres;
    float t = vnoise(bq*0.8)*0.5 + vnoise(bq*0.25)*0.5;
    col = (vec3(0.95, 0.94, 0.90) - 0.025*t*(0.5 + u_grain))*(0.97 + 0.04*q.y);
    if (u_capOn > 0.5){
      // The caption, written on the thick bottom border like you'd do with
      // a pen. u_caption is a 1024 x 192 image of the handwriting, made by
      // the app whenever the caption changes. Center it on the bottom
      // border, about 60% of the border's height, shrunk if it's too wide.
      float side = mix(14.0, 46.0, u_finA.w);
      float bottomH = side*3.2;
      float boxH = bottomH*0.62;
      float boxW = boxH*(1024.0/192.0);
      float maxW = vres.x - side*2.0 - 8.0;
      if (boxW > maxW){ boxH *= maxW/boxW; boxW = maxW; }
      vec2 o = vec2((vres.x - boxW)*0.5, (bottomH - boxH)*0.5);
      vec2 cuv = (bq - o)/vec2(boxW, boxH);
      if (cuv.x >= 0.0 && cuv.x <= 1.0 && cuv.y >= 0.0 && cuv.y <= 1.0){
        vec4 ct = texture2D(u_caption, vec2(cuv.x, 1.0 - cuv.y));   // the image's top row is its top
        col = col*(1.0 - ct.a) + ct.rgb;                            // ink over the card
      }
    }
  }
  if (u_finOn.z > 0.5){
    // Glossy, part two: the shine. A soft glow toward the top and one
    // reflection band near the top edge, because the light is overhead.
    float band = smoothstep(0.45, 1.0, q.y);
    float y = (q.y - 0.82 + 0.15*(q.x - 0.5))*9.0;
    float streak = exp(-y*y);
    col += (1.0 - col)*u_finA.z*(0.14*band + 0.40*streak);
  }

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}
