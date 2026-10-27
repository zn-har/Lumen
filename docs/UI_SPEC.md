# Lumen UI spec v2

Sources: the user's own phone (Lightroom 11.x Android, frames 2026-10-22), and frame-by-frame study of
"2026 LIGHTROOM MOBILE TUTORIAL | Everything you need to know" (youtube l6Rg-N3Le-o, iOS),
"Lightroom Advanced Masking Tutorial" (1dmMUACDlmE, Android tablet) and "Masking in Lightroom Mobile" (2s8sZknCriM).
Recreate layout and interactions with our own icons, text and code. Never copy their assets.

## 1. Editor shell
- Image area black. Panels dark grey (LColors.Controls). Values right-aligned, labels left.
- **Top bar**: `<` back · spacer · undo (long-press = redo, tiny corner triangle) · share · `⋯` (in a circle).
  In Masking mode the top bar shows: undo · overlay-visibility toggle (square icon with slash) · `⋯`.
  In Crop mode: left = orientation swap icon (portrait/landscape), right = undo.
- **No tool open**: tools sit in a *floating rounded pill* (dark, ~16dp margin, radius 16dp) at the bottom:
  icon above label: Presets · Crop · Edit · Masking (· Remove later).
- **Tool open**: the tool row becomes a full-width icon-only strip; selected = blue rounded square.
  A small dot under a tool/category icon means "has edits".
- **Slider drag focus** (very important): while a slider is dragged, everything else in the bottom area fades out
  (other sliders, sub-tabs, category row, tool strip) – only the dragged slider stays visible – and a pill at the top
  centre of the photo shows "Exposure: + 0.28". Releasing restores the panel. Same for wheels: "Shadows Hue: 69° Sat: 3".
- Double-tap a slider resets it. Values: exposure "+ 0.28", others "+ 13" (space after sign).

## 2. Edit
Bottom-up: tool strip → category row → panel.
- Category row: Auto (action) │ Light · Color · Effects · Detail · Optics. Selected = grey rounded square.
- **Light**: "Curve" outlined pill top-right; Exposure, Contrast, Highlights, Shadows, Whites, Blacks.
- **Curve mode** (replaces panel + strips): curve editor drawn in a square over the lower part of the photo with a 4×4 faint
  grid, white line (red/green/blue line for those channels), white point dots. Bottom: "Curve" left + "Done" outlined
  pill right; channel rings row: white ●, red, green, blue outlines (selected ring gets a filled inner dot) + parametric icon.
- **Color**: top row: B&W switch (left) · "Grading" pill · "Color mix" pill (right). Then "White balance  As shot ⌄" dropdown
  (As shot, Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Flash, Custom) + eyedropper square button (tap photo to
  neutralise). Temp (blue→yellow track), Tint (green→magenta), Vibrance, Saturation.
- **Color mix mode**: header "Color mix" · targeted-adjust crosshair button (centre) · "Done" pill. 8 colour rings
  (selected = filled, larger). Hue / Saturation / Luminance sliders with tracks tinted by the selected colour.
  Targeted mode: crosshair turns blue, tabs Hue | Saturation | Luminance under the rings; dragging vertically on the photo
  adjusts the band under the finger.
- **Grading mode**: header "Shadows ⋯ ⌄" (dropdown: 3-way, Shadows, Midtones, Highlights, Global). A colour wheel
  peeks above the sliders as a half disc. Sliders: Hue (rainbow track), Saturation (track grey→hue), Luminance,
  Blending, Balance. Dragging on the wheel → panel collapses, full wheel shown big at the bottom with a teardrop
  pin coloured by the picked hue; top pill "Shadows Hue: 69° Sat: 3".
- **Effects**: sub-tabs Effects | Vignette | Grain (text tabs, underline). Effects: Texture, Clarity, Dehaze.
- **Detail**: sub-tabs Sharpening | Noise | Color noise.
- **Optics**: switches "Remove chromatic aberration", "Enable lens corrections"; then Distortion / Vignetting sliders.

## 3. Crop and geometry (full-screen mode)
- Photo with white corner L-handles + edge bars, outside of crop dimmed; rotation dial (dotted arc, "-0°") under the frame.
- Pill row: "Auto geometry" (straighten automatically, may be a stub) + pencil button.
- Tool row (icon over label): Aspect ("3 × 4" shows current), Locked/Unlocked, Straighten, Rotate (90°), Flip H, Flip V.
- Aspect opens a list sheet "Aspect ratio": Original (2 × 3), Custom, 1 × 1, 1 × 2, 2 × 3, 3 × 4, 4 × 5, 5 × 7,
  8.5 × 11, 9 × 16, 10 × 16 — current one in blue.
- Mode bar: ✕ · "Crop and geometry" · ✓.

## 4. Presets (full-screen mode)
List rows (thumbnail of the photo with the preset applied or a swatch, name, `⋯`), grouped; tabs Recommended /
Categories / Yours; mode bar ✕ · "Presets" · ✓.

## 5. Masking (full-screen mode)
- Bottom: sliders panel; category row Light · Color · Effects · Detail · Optics (grey selected); mode bar ✕ Masking ✓.
- **Create new Mask** sheet (when no mask, or after +): title row + help; rounded list rows with icon:
  Select subject, Select sky, Select background, Select people │ Brush, Linear gradient, Radial gradient │
  Color range, Luminance range.
- **Right mask panel** (expanded): rounded dark column:
  1. blue filled "+" circle (new mask),
  2. one square **black/white mask thumbnail** per mask (white = selected area); selected has white border,
  3. the selected mask is wrapped in a darker rounded group that also shows its components as small indented chips
     (component icon, blue border when selected, tiny corner triangle = has menu) and a combined "⊖⊕" button
     (Add / Subtract).
  Tap selected thumbnail → menu: Add to "Mask 1", Subtract from "Mask 1", Invert, Hide, Rename, Duplicate, Delete.
  Tap component → menu: Invert, Duplicate, Delete (and Intersect).
  **Collapsed**: a single square thumbnail button at the bottom-right of the photo; tap expands.
- **Left component column** (bottom-left over photo, only when a component is selected): component icon, invert, trash;
  for Brush: brush/eraser toggle, size, feather, flow.
- On-photo handles: radial = dashed ellipse, blue centre pin, 4 white edge handles; linear = 3 lines + blue pin;
  AI components show a blue person/sky pin at the component's centre.
- Local Color category: Temp, Tint, Saturation, Hue (rainbow), "Use fine adjustment" switch, Color (2-D hue×saturation
  swatch with a ring picker; label "Color  Hue° Sat").
