import postcssPresetEnv from 'postcss-preset-env';

/**
 * The stylesheet is written for Chromium 68 (webOS 5, ADR-057): no flex gap, `inset`, logical
 * properties, min()/max()/clamp(), :focus-visible or aspect-ratio. What slips in anyway and can be
 * lowered is lowered here; scripts/check-css.mjs fails the build on what cannot.
 */
export default {
  plugins: [
    postcssPresetEnv({
      browsers: 'chrome 68',
      stage: 2,
      // The app is right to left throughout.
      logical: { inlineDirection: 'right-to-left' },
      features: {
        // On the television every focus is the remote's: the stylesheet uses :focus.
        'focus-visible-pseudo-class': false,
      },
    }),
  ],
};
