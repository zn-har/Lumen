package com.lumen.editor.render

/*
 * Coordinate convention: every intermediate texture stores image row 0 (top) at t = 0, so a
 * fullscreen pass with vUv = aPos * 0.5 + 0.5 addresses "global image uv" with y pointing down.
 * Only the display pass flips to screen space.
 */
object Shaders {

    const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val COMMON = """#version 300 es
precision highp float;
precision highp int;
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
float luma(vec3 c) { return dot(c, LW); }
float toP1(float y) { return pow(max(y, 0.0), 1.0 / 2.2); }
float fromP1(float p) { return pow(max(p, 0.0), 2.2); }
vec3 toP(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
vec3 fromP(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 srgbEncode(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    vec3 lo = l * 12.92;
    vec3 hi = 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), l));
}
// zero-luminance-ish chroma direction for an (a, b) = sat * (cos h, sin h) pair; red at h = 0
vec3 chromaDir(float a, float b) {
    return a * vec3(1.0, -0.5, -0.5) + b * vec3(0.0, 0.8660254, -0.8660254);
}
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
"""

    /** 4-tap tent downsample. uDark = 1 writes min(r,g,b) (dark channel) into alpha. */
    const val DOWNSAMPLE = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform int uDark;
void main() {
    vec4 a = texture(uTex, vUv + uTexel * vec2(-1.0, -1.0));
    vec4 b = texture(uTex, vUv + uTexel * vec2( 1.0, -1.0));
    vec4 c = texture(uTex, vUv + uTexel * vec2(-1.0,  1.0));
    vec4 d = texture(uTex, vUv + uTexel * vec2( 1.0,  1.0));
    vec4 s = (a + b + c + d) * 0.25;
    if (uDark == 1) {
        float da = min(a.r, min(a.g, a.b)), db = min(b.r, min(b.g, b.b));
        float dc = min(c.r, min(c.g, c.b)), dd = min(d.r, min(d.g, d.b));
        s.a = min(min(da, db), min(dc, dd));
    }
    o = s;
}
"""

    /** Separable 9-tap gaussian, uDir = texel * direction * spread. */
    const val BLUR = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform vec2 uDir;
const float W0 = 0.2042, W1 = 0.1802, W2 = 0.1238, W3 = 0.0663, W4 = 0.0276;
void main() {
    vec4 s = texture(uTex, vUv) * W0;
    s += (texture(uTex, vUv + uDir) + texture(uTex, vUv - uDir)) * W1;
    s += (texture(uTex, vUv + uDir * 2.0) + texture(uTex, vUv - uDir * 2.0)) * W2;
    s += (texture(uTex, vUv + uDir * 3.0) + texture(uTex, vUv - uDir * 3.0)) * W3;
    s += (texture(uTex, vUv + uDir * 4.0) + texture(uTex, vUv - uDir * 4.0)) * W4;
    o = s;
}
"""

    /** Copies .r of a mask texture (used to combine a component into its mask with blend modes). */
    const val COPY_R = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform int uInvert;
void main() {
    float a = texture(uTex, vUv).r;
    o = vec4(uInvert == 1 ? 1.0 - a : a);
}
"""

    const val COPY = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
void main() { o = texture(uTex, vUv); }
"""

    /** Parametric mask components rendered into an R8 target. */
    const val MASK_COMPONENT = COMMON + """
in vec2 vUv;
out vec4 o;
uniform int uType;          // 0 linear, 1 radial, 2 color range, 3 luminance range, 4 ai
uniform float uAspect;      // image W / H
uniform vec4 uA;
uniform vec4 uB;
uniform int uInvert;
uniform sampler2D uSrc;     // sRGB source (linear on fetch)
uniform sampler2D uGuide;   // fine blur, linear
uniform sampler2D uAi;      // low-res segmentation
uniform vec2 uAiTexel;
uniform vec3 uSamples[8];   // color range samples, perceptual rgb
uniform int uSampleCount;

vec3 opp(vec3 c) {          // cheap opponent space for color distance
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    return vec3(l * 0.6, c.r - c.g, (c.r + c.g) * 0.5 - c.b);
}

void main() {
    float a = 0.0;
    vec2 asp = vec2(uAspect, 1.0);
    if (uType == 0) {
        vec2 p = vUv * asp, p0 = uA.xy * asp, p1 = uA.zw * asp;
        vec2 d = p1 - p0;
        float t = dot(p - p0, d) / max(dot(d, d), 1e-8);
        a = 1.0 - smoothstep(0.0, 1.0, t);
    } else if (uType == 1) {
        vec2 q = (vUv - uA.xy) * asp;
        float ca = cos(-uB.x), sa = sin(-uB.x);
        q = vec2(ca * q.x - sa * q.y, sa * q.x + ca * q.y);
        vec2 r2 = vec2(uA.z * uAspect, uA.w);
        float r = length(q / max(r2, vec2(1e-5)));
        float f = max(uB.y, 0.001);
        a = 1.0 - smoothstep(1.0 - f, 1.0, r);
    } else if (uType == 2) {
        vec3 c = opp(toP(texture(uSrc, vUv).rgb));
        float d = 10.0;
        for (int i = 0; i < 8; i++) {
            if (i >= uSampleCount) break;
            d = min(d, length(c - opp(uSamples[i])));
        }
        float t = mix(0.03, 0.4, uA.x);
        a = 1.0 - smoothstep(t * 0.45, t, d);
    } else if (uType == 3) {
        float l = toP1(luma(texture(uSrc, vUv).rgb));
        a = smoothstep(uA.x - uA.z - 1e-4, uA.x, l) * (1.0 - smoothstep(uA.y, uA.y + uA.w + 1e-4, l));
    } else if (uType == 4) {
        // joint bilateral upsample of the low-res AI mask, guided by image luminance
        float lc = toP1(luma(texture(uGuide, vUv).rgb));
        vec2 g = vUv / uAiTexel - 0.5;
        vec2 base = floor(g);
        vec2 fr = g - base;
        float acc = 0.0, wsum = 0.0;
        for (int j = -1; j <= 2; j++) {
            for (int i = -1; i <= 2; i++) {
                vec2 pos = (base + vec2(float(i), float(j)) + 0.5) * uAiTexel;
                float m = texture(uAi, pos).r;
                float lg = toP1(luma(texture(uGuide, pos).rgb));
                vec2 dd = vec2(float(i), float(j)) - fr;
                float ws = max(0.0, 1.0 - length(dd) / 2.0);
                float dl = lc - lg;
                float w = ws * exp(-dl * dl / (2.0 * 0.06 * 0.06)) + 1e-4;
                acc += m * w; wsum += w;
            }
        }
        a = clamp((acc / wsum - 0.5) * 1.35 + 0.5, 0.0, 1.0);
    }
    if (uInvert == 1) a = 1.0 - a;
    o = vec4(a);
}
"""

    /** Brush dabs: aPos = clip position, aLocal = -1..1 inside dab, aParam = (flow, feather). */
    const val DAB_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in vec2 aParam;
out vec2 vLocal;
out vec2 vParam;
void main() {
    vLocal = aLocal;
    vParam = aParam;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val DAB_FS = """#version 300 es
precision mediump float;
in vec2 vLocal;
in vec2 vParam;
out vec4 o;
void main() {
    float r = length(vLocal);
    float f = max(vParam.y, 0.001);
    float a = vParam.x * (1.0 - smoothstep(1.0 - f, 1.0, r));
    o = vec4(a);
}
"""

    /** Adds one mask's weighted local adjustment vector into 4 RGBA16F targets (additive blend). */
    const val ACCUMULATE = COMMON + """
in vec2 vUv;
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 o2;
layout(location = 3) out vec4 o3;
uniform sampler2D uMask;
uniform int uInvert;
uniform vec4 uP0, uP1, uP2, uP3;
void main() {
    float a = texture(uMask, vUv).r;
    if (uInvert == 1) a = 1.0 - a;
    o0 = uP0 * a; o1 = uP1 * a; o2 = uP2 * a; o3 = uP3 * a;
}
"""

    /** The whole global + local develop pipeline in one pass. Output is display-encoded sRGB. */
    const val DEVELOP = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform sampler2D uFine;
uniform sampler2D uMid;
uniform sampler2D uLarge;
uniform sampler2D uP0;
uniform sampler2D uP1;
uniform sampler2D uP2;
uniform sampler2D uP3;
uniform sampler2D uP4;   // local grading: (S.a, S.b, S.l, M.a)
uniform sampler2D uP5;   // (M.b, M.l, H.a, H.b)
uniform sampler2D uP6;   // (H.l, G.a, G.b, G.l)
uniform int uHasLocalGrade;
uniform sampler2D uLut;
uniform int uHasLocal;
uniform mat3 uToGlobal;
uniform mat3 uToCrop;
uniform vec4 uSrcRect;
uniform vec2 uSrcTexel;
uniform float uAspect;
uniform float uCropAspect;
uniform float uGrainScale;
uniform int uSwizzle;
uniform vec4 uL0;   // exposure, contrast, highlights, shadows
uniform vec4 uL1;   // whites, blacks, temp, tint
uniform vec4 uL2;   // saturation, vibrance, texture, clarity
uniform vec4 uL3;   // dehaze, sharpen amount, radius scale, sharpen masking
uniform vec4 uL4;   // noise lum, noise color, distortion, lens vignette
uniform vec4 uVig;  // amount, midpoint, feather, roundness
uniform vec4 uVig2; // highlight protect, grain amount, grain size, grain roughness
uniform float uHslCenter[8];
uniform float uHslHue[8];
uniform float uHslSat[8];
uniform float uHslLum[8];
uniform vec4 uGradeS;
uniform vec4 uGradeM;
uniform vec4 uGradeH;
uniform vec4 uGradeG;
uniform vec2 uGradeBB;
uniform int uCurveOn;
uniform int uHslOn;
uniform int uGradeOn;
uniform int uBw;

vec3 wbGain(float temp, float tint) {
    vec3 g = vec3(1.0 + 0.42 * temp, 1.0, 1.0 - 0.42 * temp);
    g *= vec3(1.0 + 0.18 * tint, 1.0 - 0.28 * tint, 1.0 + 0.18 * tint);
    g = max(g, vec3(0.05));
    return g / luma(g);
}

float radius2(vec2 g) {
    vec2 d = (g - 0.5) * vec2(uAspect, 1.0);
    return dot(d, d) / (0.25 * (uAspect * uAspect + 1.0));
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float lut(float x, int ch) {
    vec4 v = texture(uLut, vec2(clamp(x, 0.0, 1.0) * 0.99609375 + 0.001953125, 0.5));
    return ch == 0 ? v.r : ch == 1 ? v.g : ch == 2 ? v.b : v.a;
}

float valueNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i), b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0)), d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void outColor(vec3 c) {
    // ordered dither against 8-bit banding
    c += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;
    c = clamp(c, 0.0, 1.0);
    o = uSwizzle == 1 ? vec4(c.b, c.g, c.r, 1.0) : vec4(c, 1.0);
}

void main() {
    vec2 g = (uToGlobal * vec3(vUv, 1.0)).xy;
    if (g.x < 0.0 || g.y < 0.0 || g.x > 1.0 || g.y > 1.0) { outColor(vec3(0.0)); return; }

    // lens distortion: radial warp of the sampling position
    float k = uL4.z;
    vec2 gd = 0.5 + (g - 0.5) * (1.0 + k * radius2(g)) / (1.0 + max(k, 0.0));
    vec2 su = (gd - uSrcRect.xy) / uSrcRect.zw;
    vec3 lin = texture(uSrc, su).rgb;

    vec4 P0 = vec4(0.0), P1 = vec4(0.0), P2 = vec4(0.0), P3 = vec4(0.0);
    if (uHasLocal == 1) {
        P0 = texture(uP0, g); P1 = texture(uP1, g); P2 = texture(uP2, g); P3 = texture(uP3, g);
    }
    vec2 st = uSrcTexel * uL3.z;

    // luminance noise reduction: small bilateral
    float nr = clamp(uL4.x + P3.w, 0.0, 1.0);
    if (nr > 0.002) {
        float yc = toP1(luma(lin));
        float sig = mix(0.015, 0.11, nr);
        vec3 acc = vec3(0.0); float ws = 0.0;
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                vec3 s = texture(uSrc, su + vec2(float(i), float(j)) * st).rgb;
                float d = toP1(luma(s)) - yc;
                float w = exp(-d * d / (2.0 * sig * sig) - float(i * i + j * j) / 6.0);
                acc += s * w; ws += w;
            }
        }
        lin = mix(lin, acc / ws, nr);
    }

    // unsharp detail (applied after tone in perceptual space)
    float sharpAmt = uL3.y + P3.z;
    float sharpDelta = 0.0;
    if (abs(sharpAmt) > 0.002) {
        vec3 b = (texture(uSrc, su + vec2(st.x, 0.0)).rgb + texture(uSrc, su - vec2(st.x, 0.0)).rgb +
                  texture(uSrc, su + vec2(0.0, st.y)).rgb + texture(uSrc, su - vec2(0.0, st.y)).rgb) * 0.25;
        float det = toP1(luma(lin)) - toP1(luma(b));
        float edgeW = uL3.w > 0.0 ? smoothstep(uL3.w * 0.03, uL3.w * 0.03 + 0.012, abs(det)) : 1.0;
        sharpDelta = clamp(det * sharpAmt * 1.6 * edgeW, -0.15, 0.15);
    }

    vec3 fine = texture(uFine, gd).rgb;
    float cnr = uL4.y;
    if (cnr > 0.002) {
        float y = luma(lin), yf = max(luma(fine), 1e-5);
        lin = mix(lin, fine * (y / yf), cnr * 0.85);
    }

    // lens vignetting correction
    lin *= 1.0 + uL4.w * radius2(gd) * 1.4;

    // white balance + exposure
    vec3 gain = wbGain(clamp(uL1.z + P1.z, -1.6, 1.6), clamp(uL1.w + P1.w, -1.6, 1.6)) * exp2(uL0.x + P0.x);
    lin *= gain;
    fine *= gain;
    vec3 mid = texture(uMid, gd).rgb * gain;
    vec4 lg = texture(uLarge, gd);
    vec3 large = lg.rgb * gain;
    float dark = lg.a * min(gain.r, min(gain.g, gain.b));

    // dehaze (dark channel prior approximation)
    float dh = clamp(uL3.x + P2.w, -1.0, 1.0);
    if (dh > 0.0) {
        float A = 0.95;
        float t = max(1.0 - 0.9 * dh * clamp(dark / A, 0.0, 1.0), 0.15);
        lin = max((lin - A * (1.0 - t)) / t, vec3(0.0));
        large = max((large - A * (1.0 - t)) / t, vec3(0.0));
    } else if (dh < 0.0) {
        lin = mix(lin, vec3(0.75), -dh * 0.55);
        large = mix(large, vec3(0.75), -dh * 0.55);
    }

    float Y = luma(lin);
    float p = toP1(Y);
    float pf = toP1(luma(fine)), pm = toP1(luma(mid)), pb = toP1(luma(large));

    float tex = clamp(uL2.z + P2.y, -1.0, 1.0);
    p += tex * (p - pf) * 1.6;
    float cla = clamp(uL2.w + P2.z, -1.0, 1.0);
    float midW = 1.0 - pow(abs(2.0 * clamp(p, 0.0, 1.0) - 1.0), 2.0);
    p += cla * (p - pm) * 1.25 * (0.35 + 0.65 * midW);

    // highlights / shadows on a partly local base layer
    float base = mix(p, pb, 0.5);
    float detail = p - base;
    float sh = clamp(uL0.w + P0.w, -1.0, 1.0);
    float hl = clamp(uL0.z + P0.z, -1.0, 1.0);
    float wS = smoothstep(0.0, 0.22, base) * (1.0 - smoothstep(0.22, 0.72, base));
    base += sh * 0.24 * wS;
    float wH = smoothstep(0.35, 0.95, base);
    base += hl > 0.0 ? hl * 0.18 * wH : hl * 0.32 * wH;
    p = base + detail;

    float wt = clamp(uL1.x + P1.x, -1.0, 1.0);
    float bk = clamp(uL1.y + P1.y, -1.0, 1.0);
    p += wt * 0.2 * smoothstep(0.4, 1.0, p);
    p += bk * 0.12 * (1.0 - smoothstep(0.0, 0.45, p));

    float ct = clamp(uL0.y + P0.y, -1.0, 1.0);
    const float piv = 0.46;
    float pc = clamp(p, 0.0, 1.0);
    float s = pc < piv ? piv * pow(pc / piv, 2.0) : 1.0 - (1.0 - piv) * pow((1.0 - pc) / (1.0 - piv), 2.0);
    s += p - pc;
    p = ct >= 0.0 ? mix(p, s, ct * 0.6) : mix(p, piv + (p - piv) * 0.45, -ct * 0.7);

    float Yn = fromP1(p);
    lin = Y > 1e-4 ? lin * min(Yn / Y, 32.0) : vec3(Yn);
    vec3 c = toP(lin);
    c += sharpDelta;

    // saturation, vibrance, local color
    float gray = luma(c);
    float sv = clamp(uL2.x + P2.x, -1.0, 1.0);
    c = gray + (c - gray) * (1.0 + sv);
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    float vib = uL2.y;
    if (vib != 0.0) {
        float sat = mx - mn;
        float skin = (c.r > c.g && c.g > c.b) ? 0.55 : 1.0;
        c = gray + (c - gray) * (1.0 + vib * (1.0 - smoothstep(0.0, 0.55, sat)) * skin);
    }
    if (uHasLocal == 1) c += chromaDir(P3.x, P3.y) * 0.3 * smoothstep(0.0, 0.15, gray);

    // tone curve
    if (uCurveOn == 1) {
        c = clamp(c, 0.0, 1.0);
        c = vec3(lut(c.r, 3), lut(c.g, 3), lut(c.b, 3));
        c = vec3(lut(c.r, 0), lut(c.g, 1), lut(c.b, 2));
    }

    // HSL mixer, 8 bands with smooth interpolation between neighbours
    if (uHslOn == 1) {
        vec3 h = rgb2hsv(clamp(c, 0.0, 1.0));
        float hd = h.x * 360.0;
        float dH = 0.0, dS = 0.0, dL = 0.0;
        for (int i = 0; i < 8; i++) {
            int j = i == 7 ? 0 : i + 1;
            float c0 = uHslCenter[i];
            float c1 = j == 0 ? 360.0 : uHslCenter[j];
            if (hd >= c0 && hd < c1) {
                float t = smoothstep(0.0, 1.0, (hd - c0) / (c1 - c0));
                dH = mix(uHslHue[i], uHslHue[j], t);
                dS = mix(uHslSat[i], uHslSat[j], t);
                dL = mix(uHslLum[i], uHslLum[j], t);
            }
        }
        float sw = smoothstep(0.02, 0.18, h.y);
        h.x = fract((hd + dH * sw) / 360.0);
        h.y = clamp(h.y * (1.0 + dS * sw), 0.0, 1.0);
        vec3 nc = hsv2rgb(h);
        nc *= 1.0 + dL * sw * 0.55 * h.y;
        c = nc;
    }

    // black & white (after the mixer so HSL luminance acts as a B&W mix, before grading so toning works)
    if (uBw == 1) c = vec3(luma(clamp(c, 0.0, 1.0)));

    // color grading (global wheels + per-pixel local wheels from masks)
    vec3 gS = uGradeS.xyz, gM = uGradeM.xyz, gH = uGradeH.xyz, gG = uGradeG.xyz;
    if (uHasLocalGrade == 1) {
        vec4 q4 = texture(uP4, g), q5 = texture(uP5, g), q6 = texture(uP6, g);
        gS += q4.xyz; gM += vec3(q4.w, q5.x, q5.y); gH += vec3(q5.z, q5.w, q6.x); gG += q6.yzw;
    }
    if (uGradeOn == 1 || uHasLocalGrade == 1) {
        float yg = luma(clamp(c, 0.0, 1.0));
        float b = uGradeBB.y * 0.22;
        float kk = 0.06 + 0.3 * uGradeBB.x;
        float s1 = 0.33 + b, s2 = 0.67 + b;
        float ws = 1.0 - smoothstep(s1 - kk, s1 + kk, yg);
        float wh = smoothstep(s2 - kk, s2 + kk, yg);
        float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
        c += ws * (chromaDir(gS.x, gS.y) + gS.z);
        c += wm * (chromaDir(gM.x, gM.y) + gM.z);
        c += wh * (chromaDir(gH.x, gH.y) + gH.z);
        c += chromaDir(gG.x, gG.y) + gG.z;
    }

    // post-crop vignette
    if (uVig.x != 0.0) {
        vec2 cs = (uToCrop * vec3(vUv, 1.0)).xy;
        vec2 d = (cs - 0.5) * 2.0;
        float rnd = uVig.w;
        if (rnd > 0.0) d.x *= mix(1.0, uCropAspect, rnd);
        else if (rnd < 0.0) d.y *= mix(1.0, 1.0 / uCropAspect, -rnd);
        float e = 2.0 + max(-rnd, 0.0) * 4.0;
        float r = pow(pow(abs(d.x), e) + pow(abs(d.y), e), 1.0 / e);
        float inner = mix(0.15, 1.25, uVig.y) * (1.0 - uVig.z * 0.45);
        float outer = inner + mix(0.04, 1.1, uVig.z);
        float w = smoothstep(inner, outer, r);
        if (uVig.x < 0.0) {
            float protect = 1.0 - uVig2.x * smoothstep(0.55, 1.0, luma(c));
            c *= 1.0 + uVig.x * w * protect;
        } else {
            c += (1.0 - c) * uVig.x * w * 0.85;
        }
    }

    // grain
    if (uVig2.y > 0.0) {
        vec2 gp = g * uGrainScale / mix(1.0, 5.0, uVig2.z);
        float n = valueNoise(gp) - 0.5;
        n = mix(n, hash12(floor(gp * 2.3)) - 0.5, uVig2.w * 0.6);
        float l = luma(clamp(c, 0.0, 1.0));
        c += n * uVig2.y * 0.3 * (1.0 - abs(l * 2.0 - 1.0) * 0.6);
    }

    outColor(srgbEncode(fromP(c)));
}
"""

    /** Draws the developed image (or the original) to the screen with zoom/pan/crop. */
    const val DISPLAY = COMMON + """
in vec2 vUv;
out vec4 o;
uniform sampler2D uImg;
uniform sampler2D uOrig;
uniform sampler2D uMask;
uniform mat3 uScreenToGlobal;
uniform mat3 uScreenToCrop;
uniform int uShowMask;
uniform vec4 uMaskColor;
uniform int uCompare;      // 0 edited, 1 original, 2 split
uniform float uSplit;
uniform int uCropMode;     // dim outside crop
uniform vec3 uBg;
void main() {
    vec2 s = vec2(vUv.x, 1.0 - vUv.y);
    vec2 g = (uScreenToGlobal * vec3(s, 1.0)).xy;
    if (g.x < 0.0 || g.y < 0.0 || g.x > 1.0 || g.y > 1.0) { o = vec4(uBg, 1.0); return; }
    bool orig = uCompare == 1 || (uCompare == 2 && s.x < uSplit);
    vec3 c = orig ? srgbEncode(texture(uOrig, g).rgb) : texture(uImg, g).rgb;
    if (uShowMask == 1) {
        float a = texture(uMask, g).r;
        c = mix(c, uMaskColor.rgb, a * uMaskColor.a);
    }
    if (uCropMode == 1) {
        vec2 cs = (uScreenToCrop * vec3(s, 1.0)).xy;
        if (cs.x < 0.0 || cs.y < 0.0 || cs.x > 1.0 || cs.y > 1.0) c *= 0.35;
    }
    o = vec4(c, 1.0);
}
"""
}
