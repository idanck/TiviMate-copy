import postcssPresetEnv from 'postcss-preset-env';

/**
 * The stylesheet is the computer version's, written for current browsers; webOS 6 runs Chromium 79
 * (ADR-052). This lowers what can be lowered at build time:
 *
 * - cascade layers (Chromium 99) into plain rules of the same weight order;
 * - logical properties and `inset` (Chromium 87) into physical ones, right to left, as the app is;
 * - `gap` in flex containers (Chromium 84) into margins, below.
 *
 * What cannot be lowered (container queries, aspect-ratio, :has, dvh) was rewritten in the
 * stylesheet itself.
 */

/**
 * Flex `gap` for Chromium 79 and older (webOS 6). For every rule that makes a flex container and
 * gives it a gap, a margin goes on each child after the first, along the main axis, under
 * `html.no-flex-gap` (set by main.tsx only where the browser lacks flex gap, so newer televisions
 * keep the real gap and never get both). Rows run right to left, so the space is on the right of
 * each later child. Wrapped rows keep only the gap within a line.
 */
function flexGapFallback() {
  const lengthOf = (value, axis) => {
    const parts = value.trim().split(/\s+(?![^(]*\))/);
    // `gap: <row> <column>`; a single value is both.
    return axis === 'row' ? parts[0] : parts[1] ?? parts[0];
  };
  return {
    postcssPlugin: 'shidur-flex-gap-fallback',
    Rule(rule, { Rule }) {
      if (rule.selector.includes('no-flex-gap') || rule.parent?.type === 'atrule' && rule.parent.name === 'keyframes') return;
      let flex = false;
      let column = false;
      let rowGap;
      let columnGap;
      // Direct declarations only (rule.each: inside a plugin the nodes are proxies, so comparing a
      // declaration's parent to the rule would never match).
      rule.each((d) => {
        if (d.type !== 'decl') return;
        const v = d.value.trim();
        if (d.prop === 'display' && /^(inline-)?flex$/.test(v)) flex = true;
        if (d.prop === 'flex-direction' && v.startsWith('column')) column = true;
        if (d.prop === 'flex-flow' && v.startsWith('column')) column = true;
        if (d.prop === 'gap') {
          rowGap = lengthOf(v, 'row');
          columnGap = lengthOf(v, 'column');
        }
        if (d.prop === 'row-gap') rowGap = v;
        if (d.prop === 'column-gap') columnGap = v;
      });
      if (!flex) return;
      const space = column ? rowGap : columnGap;
      if (!space || /^0(px)?$/.test(space) || space === 'normal') return;
      const selector = rule.selectors.map((s) => `html.no-flex-gap ${s} > * + *`).join(', ');
      const fallback = new Rule({ selector });
      fallback.append({ prop: column ? 'margin-top' : 'margin-right', value: space });
      rule.after(fallback);
    },
  };
}
flexGapFallback.postcss = true;

export default {
  plugins: [
    flexGapFallback(),
    postcssPresetEnv({
      browsers: 'chrome 79',
      stage: 2,
      logical: { inlineDirection: 'right-to-left' },
      features: {
        // On the television every focus is the remote's: styles.css uses :focus throughout.
        'focus-visible-pseudo-class': false,
      },
    }),
  ],
};
