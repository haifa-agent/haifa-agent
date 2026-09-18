export async function fetchAllItems(baseUrl, options = {}) {
  const fetchFunction = options.fetch ?? globalThis.fetch;
  const response = await fetchFunction(`${baseUrl}/items`);
  if (!response.ok) {
    throw new Error(`items request failed: ${response.status}`);
  }
  const page = await response.json();
  return page.items;
}
