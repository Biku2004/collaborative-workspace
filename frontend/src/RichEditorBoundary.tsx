import { Component, type ReactNode } from 'react';

/** Keeps a failed editor download from taking down the entire workspace view. */
export default class RichEditorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <p className="alert" role="alert">The rich-text editor could not load. Reconnect and reload to try again; locally stored drafts may still be available.</p> : this.props.children;
  }
}
