import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "katex/dist/katex.min.css";
import "./styles.css";

const adminPath = window.location.pathname === "/admin" ||
  window.location.pathname.startsWith("/admin/");
const { default: Application } = adminPath
  ? await import("./AdminApp")
  : await import("./App");

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Application />
  </StrictMode>,
);
