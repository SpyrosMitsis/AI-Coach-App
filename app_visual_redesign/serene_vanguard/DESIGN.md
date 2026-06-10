---
name: Serene Vanguard
colors:
  surface: '#121414'
  surface-dim: '#121414'
  surface-bright: '#383939'
  surface-container-lowest: '#0d0f0f'
  surface-container-low: '#1a1c1c'
  surface-container: '#1e2020'
  surface-container-high: '#282a2a'
  surface-container-highest: '#333535'
  on-surface: '#e2e2e2'
  on-surface-variant: '#c3c8c0'
  inverse-surface: '#e2e2e2'
  inverse-on-surface: '#2f3131'
  outline: '#8d928b'
  outline-variant: '#434842'
  surface-tint: '#b6ccb6'
  primary: '#b6ccb6'
  on-primary: '#223525'
  primary-container: '#8da38e'
  on-primary-container: '#263929'
  inverse-primary: '#4f6351'
  secondary: '#d3c4b3'
  on-secondary: '#382f23'
  secondary-container: '#51483a'
  on-secondary-container: '#c4b6a5'
  tertiary: '#bbcbba'
  on-tertiary: '#263428'
  tertiary-container: '#91a192'
  on-tertiary-container: '#2a382c'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d1e9d1'
  primary-fixed-dim: '#b6ccb6'
  on-primary-fixed: '#0d1f11'
  on-primary-fixed-variant: '#384b3a'
  secondary-fixed: '#f0e0ce'
  secondary-fixed-dim: '#d3c4b3'
  on-secondary-fixed: '#221a0f'
  on-secondary-fixed-variant: '#4f4538'
  tertiary-fixed: '#d7e7d6'
  tertiary-fixed-dim: '#bbcbba'
  on-tertiary-fixed: '#111f14'
  on-tertiary-fixed-variant: '#3c4a3e'
  background: '#121414'
  on-background: '#e2e2e2'
  surface-variant: '#333535'
typography:
  headline-xl:
    fontFamily: Hanken Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Hanken Grotesk
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  body-md:
    fontFamily: Hanken Grotesk
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-sm:
    fontFamily: Hanken Grotesk
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-caps:
    fontFamily: Hanken Grotesk
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  container-max: 1200px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 40px
---

## Brand & Style
The design system moves away from high-energy performance into a space of "Grounded Resilience." It targets health-conscious professionals seeking mental clarity and physical longevity. The aesthetic is a fusion of **Minimalism** and **Modern Corporate**, utilizing heavy whitespace and precise geometry to convey authority, while softening the emotional impact through organic color choices. The UI should evoke a sense of focused calm—reliable, sophisticated, and restorative.

## Colors
The palette is anchored by a deep Charcoal (`#121414`) base to maintain a premium, nocturnal focus for wellness tracking. The primary accent is **Sage Green** (`#8DA38E`), a muted, desaturated hue that replaces the previous high-contrast lime. This is supported by a **Warm Sand** (`#C2B4A3`) secondary tone for auxiliary information and a **Deep Moss** (`#5E6D5F`) for subtle UI distinctions. Surface colors should use slight increments of the neutral base (e.g., `#1C1F1F`) to maintain depth without breaking the dark-mode immersion.

## Typography
This design system utilizes **Hanken Grotesk** exclusively to bridge the gap between technical precision and human-centric design. Headlines use tight tracking and bold weights to establish a strong hierarchy. Body text is set with generous line height to ensure readability during low-light usage. Small labels and metadata should utilize the `label-caps` style with increased letter spacing to maintain clarity against the dark background.

## Layout & Spacing
The layout follows a **Fluid Grid** model with an 8px base unit. 
- **Desktop:** 12-column grid with 24px gutters and 40px side margins. 
- **Mobile:** 4-column grid with 16px gutters and 16px side margins.
Spacing is intentionally generous around primary content blocks to prevent visual clutter, reflecting the "calming" brand pillar. Content should be grouped into cards that span logical column intervals (e.g., 3, 4, or 6 columns on desktop).

## Elevation & Depth
Depth is achieved through **Tonal Layers** rather than heavy shadows. In this dark mode environment, higher elevation is represented by lighter surface colors. 
- **Level 0 (Base):** `#121414`.
- **Level 1 (Cards):** `#1C1F1F`.
- **Level 2 (Modals/Popovers):** `#252929`.
A very subtle, low-opacity Sage tint (`rgba(141, 163, 142, 0.05)`) can be applied to elevated surfaces to create a sense of atmospheric glow. Outlines should be kept to a minimum, used only for interactive states with a 1px stroke in Deep Moss.

## Shapes
The shape language is consistently **Rounded**, avoiding sharp clinical edges to remain approachable. Standard UI components (Buttons, Inputs) use a 0.5rem (8px) radius. Large containers and cards use a 1rem (16px) radius to create a soft, protective feel for data visualization and content modules.

## Components
- **Buttons:** Primary buttons use a solid Sage Green background with dark neutral text. Secondary buttons are "ghost" style with a 1px Warm Sand border.
- **Chips:** Used for health tags (e.g., "Deep Sleep," "Recovery"). These feature a Deep Moss background with Sage Green text for a low-contrast, harmonious look.
- **Lists:** Clean dividers using 1px strokes in the base neutral color + 5% lightness. Icons within lists should be monochromatic Sage Green.
- **Input Fields:** Darker than the card background to create an "inset" feel. Focus states utilize a subtle Sage Green glow (2px blur).
- **Cards:** The primary container. No borders; depth is strictly tonal.
- **Data Visualization:** Charts should use a gradient transition between Sage Green and Warm Sand to represent progress or health metrics, avoiding any "red" or "emergency" tones unless absolutely critical.