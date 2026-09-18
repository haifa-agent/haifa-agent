import { fetchAllItems } from "./client.js";

if (process.argv.length !== 3) {
  console.error("usage: node src/cli.js BASE_URL");
  process.exitCode = 2;
} else {
  try {
    const items = await fetchAllItems(process.argv[2]);
    console.log(JSON.stringify(items));
  } catch (error) {
    console.error(`items-client: ${error.message}`);
    process.exitCode = 1;
  }
}
