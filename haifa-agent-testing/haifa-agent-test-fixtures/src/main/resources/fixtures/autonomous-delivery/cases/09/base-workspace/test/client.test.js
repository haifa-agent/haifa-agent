import assert from "node:assert/strict";
import test from "node:test";

import { fetchAllItems } from "../src/client.js";


test("follows pages and removes duplicate ids", async () => {
  const calls = [];
  const responses = new Map([
    ["https://service.test/items", { items: [{ id: "a" }, { id: "b", page: 1 }], nextCursor: "two" }],
    ["https://service.test/items?cursor=two", { items: [{ id: "b", page: 2 }, { id: "c" }], nextCursor: null }],
  ]);
  const fakeFetch = async (url) => {
    calls.push(url);
    return {
      ok: true,
      status: 200,
      json: async () => responses.get(url),
    };
  };
  const result = await fetchAllItems("https://service.test", { fetch: fakeFetch });
  assert.deepEqual(result, [{ id: "a" }, { id: "b", page: 1 }, { id: "c" }]);
  assert.deepEqual(calls, [
    "https://service.test/items",
    "https://service.test/items?cursor=two",
  ]);
});
