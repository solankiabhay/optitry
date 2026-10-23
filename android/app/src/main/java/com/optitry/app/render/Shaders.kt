package com.optitry.app.render

/** GLSL ES 1.00 sources (OpenGL ES 2.0 is available on every supported device). */
internal object Shaders {
    private const val FRAG_PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /** Full-screen camera/photo quad. uVisible = fraction of the image that is on screen, centred. */
    const val BACKGROUND_VS = """
attribute vec2 aPos;
uniform vec2 uVisible;
uniform float uMirror;
varying vec2 vUv;
void main() {
    vec2 uv = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
    uv = vec2(0.5) + (uv - vec2(0.5)) * uVisible;
    if (uMirror > 0.5) uv.x = 1.0 - uv.x;
    vUv = uv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
    const val BACKGROUND_FS = FRAG_PRECISION + """
uniform sampler2D uTex;
varying vec2 vUv;
void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }
"""

    /** Depth-only pass for the invisible head occluder. */
    const val OCCLUDER_VS = """
attribute vec3 aPos;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
"""
    const val OCCLUDER_FS = FRAG_PRECISION + """
void main() { gl_FragColor = vec4(0.0); }
"""

    const val MESH_VS = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aAux;
uniform mat4 uMVP;
uniform mat4 uModel;
varying vec3 vPosCam;
varying vec3 vNormalCam;
varying vec3 vObj;
varying vec2 vAux;
void main() {
    vec4 pc = uModel * vec4(aPos, 1.0);
    vPosCam = pc.xyz;
    vNormalCam = mat3(uModel) * aNormal;
    vObj = aPos;
    vAux = aAux;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    private const val NOISE = """
float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
            mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
        mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
            mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}
"""

    private const val COMMON = NOISE + """
uniform float uAmbient;
uniform vec3 uLightDir;
varying vec3 vPosCam;
varying vec3 vNormalCam;
varying vec3 vObj;
varying vec2 vAux;

// Cheap studio-style environment: sky gradient plus one soft box, in camera space (y up).
vec3 envColor(vec3 r) {
    float t = clamp(r.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(vec3(0.16, 0.16, 0.19), vec3(0.88, 0.92, 1.0), smoothstep(0.15, 1.0, t));
    float box = smoothstep(0.82, 0.96, dot(r, normalize(vec3(-0.35, 0.55, 0.75))));
    float box2 = smoothstep(0.88, 0.98, dot(r, normalize(vec3(0.55, 0.25, 0.80))));
    return sky * uAmbient + vec3(1.0) * (box * 1.15 + box2 * 0.45);
}
"""

    /** Frame body, temples, hinges and nose pads (physically-inspired Blinn-Phong + environment reflection). */
    const val FRAME_FS = FRAG_PRECISION + COMMON + """
uniform vec3 uBase;
uniform vec3 uAccent;
uniform float uPattern;   // 0 solid, 1 tortoise, 2 gradient (browline), 3 crystal
uniform float uMetallic;
uniform float uAlpha;
uniform float uLensY;
void main() {
    vec3 N = normalize(vNormalCam);
    vec3 V = normalize(-vPosCam);
    if (dot(N, V) < 0.0) N = -N;               // two-sided: works under mirrored projection too
    float part = vAux.x;
    vec3 base = uBase;
    float metal = uMetallic;
    float gloss = 1.0;
    float alpha = uAlpha;
    float F = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);

    if (part > 2.5) {                           // hinge barrel
        base = vec3(0.78, 0.80, 0.84); metal = 0.9;
    } else if (part > 1.5) {                    // silicone nose pad
        base = vec3(0.88, 0.91, 0.93); metal = 0.0; gloss = 0.3; alpha = min(alpha, 0.8);
    } else if (uPattern > 0.5 && uPattern < 1.5) {
        float n = vnoise(vObj * vec3(2.2, 2.2, 1.1)) * 0.65 + vnoise(vObj * 6.5) * 0.35;
        base = mix(uBase, uAccent, smoothstep(0.42, 0.68, n));
        base *= mix(1.0, 0.45, smoothstep(0.62, 0.78, vnoise(vObj * 3.1 + 7.0)));
    } else if (uPattern > 1.5 && uPattern < 2.5) {
        float k = smoothstep(uLensY - 0.05, uLensY - 0.55, vObj.y);
        if (part < 0.5) base = mix(uBase, uAccent, k);
    } else if (uPattern > 2.5) {
        base = mix(uBase, vec3(1.0), F * 0.55);
    }

    vec3 L = normalize(uLightDir);
    float ndl = max(dot(N, L), 0.0);
    vec3 hemi = mix(vec3(0.30, 0.30, 0.34), vec3(0.80, 0.82, 0.88), N.y * 0.5 + 0.5) * uAmbient;
    vec3 H = normalize(L + V);
    float shin = mix(90.0, 45.0, metal);
    float spec = pow(max(dot(N, H), 0.0), shin) * (0.35 + 0.65 * metal) * gloss;
    vec3 R = reflect(-V, N);
    vec3 e = envColor(R);
    vec3 diffuse = base * (hemi + ndl * 0.85 * uAmbient);
    vec3 metalCol = base * e * 1.25 + base * 0.15 * ndl;
    vec3 col = mix(diffuse, metalCol, metal);
    col += e * F * (0.35 - 0.2 * metal) * gloss + vec3(spec);
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), alpha);
}
"""

    /** Alpha-blended lenses: tint, fresnel reflection of the environment, mirror coating and specular glints. */
    const val LENS_FS = FRAG_PRECISION + COMMON + """
uniform vec3 uTint;
uniform float uOpacity;
uniform float uMirror;
uniform float uGradient;
uniform float uLensY;
void main() {
    vec3 N = normalize(vNormalCam);
    vec3 V = normalize(-vPosCam);
    if (dot(N, V) < 0.0) N = -N;
    vec3 R = reflect(-V, N);
    float F = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 140.0);
    float fade = 1.0;
    if (uGradient > 0.5) fade = mix(0.35, 1.25, smoothstep(-2.0, 2.0, vObj.y - uLensY));
    float refl = clamp(F * 0.9 + uMirror * 0.55, 0.0, 1.0);
    vec3 col = mix(uTint * (0.55 + 0.45 * uAmbient), envColor(R), refl);
    float a = clamp(uOpacity * fade + F * (0.2 + 0.6 * uMirror) + uMirror * 0.18, 0.0, 0.96);
    col += vec3(spec) * 0.9;
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), max(a, clamp(spec, 0.0, 1.0)));
}
"""

    /** Eyelid opening polygon, drawn into the stencil buffer only (positions already in NDC). */
    const val MASK_VS = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    const val MASK_FS = FRAG_PRECISION + """
void main() { gl_FragColor = vec4(0.0); }
"""

    /** Cosmetic contact lens on the iris: coloured fibre pattern, limbal ring, clear pupil aperture, real catchlights kept. */
    const val IRIS_VS = """
attribute vec2 aLocal;
uniform vec2 uCenter;
uniform vec2 uRadiusNdc;
varying vec2 vLocal;
void main() {
    vLocal = aLocal;
    gl_Position = vec4(uCenter + aLocal * uRadiusNdc, 0.0, 1.0);
}
"""
    const val IRIS_FS = FRAG_PRECISION + NOISE + """
uniform sampler2D uTex;
uniform vec2 uView;
uniform vec2 uVisible;
uniform float uMirror;
uniform vec3 uColor;
uniform vec3 uColor2;
uniform float uOpacity;
uniform float uPupil;
varying vec2 vLocal;
void main() {
    float d = length(vLocal);
    if (d > 1.0) discard;
    vec2 s = gl_FragCoord.xy / uView;
    s.y = 1.0 - s.y;
    vec2 uv = vec2(0.5) + (s - vec2(0.5)) * uVisible;
    if (uMirror > 0.5) uv.x = 1.0 - uv.x;
    vec3 orig = texture2D(uTex, uv).rgb;
    float luma = dot(orig, vec3(0.299, 0.587, 0.114));
    float ang = atan(vLocal.y, vLocal.x);
    vec2 cs = vec2(cos(ang), sin(ang));
    float fibers = vnoise(vec3(cs * 5.0, d * 3.0)) * 0.6 + vnoise(vec3(cs * 14.0, d * 6.0)) * 0.4;
    vec3 col = mix(uColor2, uColor, smoothstep(uPupil, 0.85, d));
    col *= 0.70 + 0.60 * fibers;
    col = mix(col, col * 0.35, smoothstep(0.78, 1.0, d) * 0.75);   // limbal ring
    col *= 0.55 + 1.05 * luma;                                      // keep the eye's own shading and catchlights
    float a = uOpacity * smoothstep(uPupil - 0.05, uPupil + 0.08, d) * (1.0 - smoothstep(0.90, 1.0, d));
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), a);
}
"""
}
