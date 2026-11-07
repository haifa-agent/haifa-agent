import { defineConfig } from "vite";
export default defineConfig({
  build: {
    emptyOutDir: false,
    target: "es2022",
    lib: { entry: "src/index.ts", formats: ["es"], fileName: () => "index.js" },
    minify: false,
  },
});
