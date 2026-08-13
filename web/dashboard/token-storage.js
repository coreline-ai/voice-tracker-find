((global) => {
  const TOKEN_KEY = 'airvoice-receiver-dashboard-token';
  const LEGACY_TOKEN_KEY = 'thinktank-receiver-dashboard-token';

  const read = (storage = global.sessionStorage) => {
    const current = storage.getItem(TOKEN_KEY);
    if (current) return current;
    const legacy = storage.getItem(LEGACY_TOKEN_KEY);
    if (!legacy) return '';
    storage.setItem(TOKEN_KEY, legacy);
    storage.removeItem(LEGACY_TOKEN_KEY);
    return legacy;
  };

  const write = (token, storage = global.sessionStorage) => {
    storage.setItem(TOKEN_KEY, token);
  };

  const clear = (storage = global.sessionStorage) => {
    storage.removeItem(TOKEN_KEY);
    storage.removeItem(LEGACY_TOKEN_KEY);
  };

  global.AirVoiceTokenStorage = Object.freeze({ TOKEN_KEY, read, write, clear });
})(globalThis);
