/**
 * Semantic design tokens for the mobile app.
 *
 * These tokens mirror the naming conventions used in web artifacts (index.css)
 * so that multi-artifact projects share a cohesive visual identity.
 *
 * Replace the placeholder values below with values that match the project's
 * brand. If a sibling web artifact exists, read its index.css and convert the
 * HSL values to hex so both artifacts use the same palette.
 *
 * To add dark mode, add a `dark` key with the same token names.
 * The useColors() hook will automatically pick it up.
 */

const colors = {
  light: {
    // Legacy aliases (kept for backward compatibility)
    text: '#122033',
    tint: '#146BFF',

    // Core surfaces
    background: '#F4F7FB',
    foreground: '#122033',

    // Cards / elevated surfaces
    card: '#FFFFFF',
    cardForeground: '#122033',

    // Primary action color (buttons, links, active states)
    primary: '#146BFF',
    primaryForeground: '#FFFFFF',

    // Secondary / less-emphasis interactive surfaces
    secondary: '#EAF0F7',
    secondaryForeground: '#314158',

    // Muted / subdued elements (dividers, timestamps, placeholders)
    muted: '#EEF3F8',
    mutedForeground: '#718096',

    // Accent highlights (badges, selected items, focus rings)
    accent: '#DDF7F0',
    accentForeground: '#117461',

    // Destructive actions (delete, error states)
    destructive: '#D94B5B',
    destructiveForeground: '#FFFFFF',

    // Borders and input outlines
    border: '#E1E8F0',
    input: '#D7E1EC',

    // App-specific semantic accents
    primarySoft: '#E5EEFF',
    success: '#17A673',
    successSoft: '#E2F7EF',
    warning: '#D98A21',
    warningSoft: '#FFF2DE',
    violet: '#8064D9',
    violetSoft: '#EEEAFE',
  },

  dark: {
    text: '#F3F7FC',
    tint: '#69A0FF',
    background: '#0F1724',
    foreground: '#F3F7FC',
    card: '#172235',
    cardForeground: '#F3F7FC',
    primary: '#69A0FF',
    primaryForeground: '#0F1724',
    secondary: '#223149',
    secondaryForeground: '#DCE7F5',
    muted: '#1D2B40',
    mutedForeground: '#93A5BC',
    accent: '#183C3A',
    accentForeground: '#79DEC3',
    destructive: '#F07987',
    destructiveForeground: '#220B10',
    border: '#2A3A50',
    input: '#33465F',
    primarySoft: '#203A68',
    success: '#55D5A8',
    successSoft: '#153B34',
    warning: '#F3B55D',
    warningSoft: '#4A3519',
    violet: '#B3A0FF',
    violetSoft: '#302757',
  },

  // Border radius (in px). Sync from the sibling web artifact's --radius
  // CSS variable. This value applies to cards, buttons, inputs, and modals.
  radius: 16,
};

export default colors;
