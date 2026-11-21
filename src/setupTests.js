import "@testing-library/jest-dom/vitest";

// jsdom does not implement these browser APIs used by the component
window.alert = vi.fn();
window.confirm = vi.fn(() => true);

// crypto.randomUUID fallback for the chat message ids
if (!globalThis.crypto) {
  globalThis.crypto = { randomUUID: () => "test-uuid" };
}
