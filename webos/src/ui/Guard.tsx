import { Component, type ComponentChildren } from 'preact';
import { watch } from './watch';

/**
 * Catches a render that throws, so the screen says so in Hebrew instead of freezing: without a
 * catcher Preact rethrows and stops rendering altogether, which on the television looks like an
 * interface that stopped answering or went away. The error is kept for `shidur.state()`.
 */
export class Guard extends Component<{ children: ComponentChildren }, { failed: string | null }> {
  state = { failed: null as string | null };

  componentDidCatch(err: unknown) {
    const text = err instanceof Error ? `${err.message} ${(err.stack ?? '').split('\n')[1] ?? ''}` : String(err);
    watch.noteError('render', text);
    this.setState({ failed: text });
  }

  render() {
    if (this.state.failed === null) return this.props.children;
    return (
      <div class="crashed">
        <h2>המסך לא הצליח להיבנות</h2>
        <p class="faint ltr">{this.state.failed.slice(0, 200)}</p>
        <button class="btn" autoFocus onClick={() => this.setState({ failed: null })}>לנסות שוב</button>
      </div>
    );
  }
}
