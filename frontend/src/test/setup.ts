import '@testing-library/jest-dom/vitest'

// Node 22+'s own experimental global `localStorage` shadows jsdom's working
// implementation and throws "getItem is not a function" without a
// --localstorage-file flag we don't pass. None of the first 7 test files
// happened to touch localStorage, so this went unnoticed until components
// that persist UI state (theme, panel widths, the last-viewed namespace) came
// under test. A plain in-memory Storage stands in for both environments.
function createMemoryStorage(): Storage {
  const store = new Map<string, string>()
  return {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => { store.set(key, String(value)) },
    removeItem: (key) => { store.delete(key) },
    clear: () => store.clear(),
    key: (index) => Array.from(store.keys())[index] ?? null,
    get length() { return store.size },
  }
}
Object.defineProperty(window, 'localStorage', { value: createMemoryStorage(), writable: true })
Object.defineProperty(globalThis, 'localStorage', { value: window.localStorage, writable: true })

// jsdom doesn't implement matchMedia at all — anything pulling in useTheme
// (most pages, via Sidebar) throws "window.matchMedia is not a function"
// without this. Reports no OS dark-mode preference; individual tests can
// override with their own mock if they need to exercise that branch.
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList
}
