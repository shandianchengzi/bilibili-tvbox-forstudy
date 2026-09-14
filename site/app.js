"use strict";

(() => {
  const fallbackCategories = [
    ["knowledge", "知识精选", "计算机、电子技术与科学原理。"],
    ["firmware_emulation", "嵌入式与固件仿真", "QEMU、FirmAE、Firmadyne 与固件重托管。"],
    ["chip_design", "芯片设计与 RISC-V", "RISC-V、SoC、处理器架构与芯片设计。"],
    ["hardware_design", "硬件设计与 PCB", "原理图、PCB、接口、电源与硬件调试。"],
    ["digital_circuits", "数字电路与 FPGA", "数字电路、Verilog、FPGA 与验证。"],
    ["ai_applications", "AI 应用", "机器学习、计算机视觉与边缘 AI。"],
    ["llm_applications", "大模型与智能体", "RAG、Agent、工具调用与工程落地。"],
    ["embodied_ai", "具身智能与机器人", "机器人感知、控制与仿真。"],
    ["engineering", "复杂工程问题与调试", "性能分析、故障定位与系统调试。"],
    ["homomorphic_encryption", "同态加密", "全同态加密、CKKS、BFV 与隐私计算。"],
    ["quantum_computing", "量子计算", "量子算法、量子电路与编程。"],
    ["audiobooks", "听书 / 有声书", "听书、小说有声书与文学朗读。"],
  ].map(([id, name, description]) => ({ id, name, description }));
  const stateLabels = { fresh: "本轮已更新", partial: "部分已更新", stale: "保留上次索引", empty: "暂无可用索引" };
  const grid = document.getElementById("interest-grid");
  const summary = document.getElementById("catalog-summary");
  const statusDot = document.getElementById("status-dot");
  const input = document.getElementById("import-url");
  const copyButton = document.getElementById("copy-button");
  const copyMessage = document.getElementById("copy-message");

  const base = new URL(window.location.href);
  base.search = "";
  base.hash = "";
  if (base.pathname.endsWith("/index.html")) base.pathname = base.pathname.slice(0, -10);
  if (!base.pathname.endsWith("/")) base.pathname += "/";
  if (base.protocol === "https:" || base.protocol === "http:") {
    input.value = new URL("tvbox.json", base).href;
    document.getElementById("config-link").href = input.value;
  }

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (typeof text === "string") element.textContent = text;
    return element;
  }

  function renderCategories(categories, catalog) {
    const rows = catalog && Array.isArray(catalog.categories) ? catalog.categories : [];
    const indexed = new Map(rows.filter(row => row && typeof row.id === "string").map(row => [row.id, row]));
    const fragment = document.createDocumentFragment();
    categories.slice(0, 50).forEach((category, index) => {
      const entry = indexed.get(category.id);
      const card = node("article", "interest-card");
      const heading = node("div", "interest-top");
      heading.append(node("span", "interest-symbol", String(index + 1).padStart(2, "0")), node("h3", "", category.name));
      const metadata = node("div", "interest-meta");
      const validStatus = entry && Object.prototype.hasOwnProperty.call(stateLabels, entry.status) ? entry.status : "";
      const count = entry && Array.isArray(entry.items) ? entry.items.filter(item => item && typeof item.bvid === "string" && /^BV[0-9A-Za-z]{10}$/.test(item.bvid)).length : null;
      metadata.append(node("span", "", count === null ? "公开视频合集" : `${count} 条索引`));
      metadata.append(node("span", `interest-state ${validStatus}`, validStatus ? stateLabels[validStatus] : "索引待读取"));
      card.append(heading, node("p", "", category.description || "按兴趣关键词整理公开视频。"), metadata);
      fragment.append(card);
    });
    grid.replaceChildren(fragment);
  }

  async function fetchJSON(filename) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(new URL(filename, base), { signal: controller.signal, credentials: "omit", cache: "no-cache" });
      if (!response.ok) throw new Error("resource unavailable");
      return await response.json();
    } finally {
      window.clearTimeout(timeout);
    }
  }

  copyButton.addEventListener("click", async () => {
    try {
      if (!navigator.clipboard || !window.isSecureContext) throw new Error("clipboard unavailable");
      await navigator.clipboard.writeText(input.value);
      copyMessage.textContent = "已复制。现在打开 TVBox，粘贴到配置地址。";
      copyButton.textContent = "已复制 ✓";
      window.setTimeout(() => { copyButton.textContent = "复制地址 ↗"; }, 2200);
    } catch (_) {
      input.focus();
      input.select();
      input.setSelectionRange(0, input.value.length);
      copyMessage.textContent = "地址已选中，请长按复制，或按 Ctrl+C / ⌘C。";
    }
  });

  renderCategories(fallbackCategories, null);
  Promise.allSettled([fetchJSON("interests.json"), fetchJSON("catalog.json")]).then(results => {
    const interestResult = results[0];
    let categories = fallbackCategories;
    if (interestResult.status === "fulfilled" && interestResult.value && interestResult.value.version === 1 && Array.isArray(interestResult.value.categories)) {
      const valid = interestResult.value.categories.filter(row => row && typeof row.id === "string" && typeof row.name === "string").map(row => ({ id: row.id.slice(0, 64), name: row.name.slice(0, 80), description: typeof row.description === "string" ? row.description.slice(0, 300) : "" }));
      if (valid.length) categories = valid;
    }
    const catalogResult = results[1];
    const catalog = catalogResult.status === "fulfilled" ? catalogResult.value : null;
    if (!catalog || catalog.schema !== 1 || !Array.isArray(catalog.categories)) {
      renderCategories(categories, null);
      statusDot.className = "status-dot warn";
      summary.textContent = "暂时无法读取公开索引。分类仍可查看；索引状态以更新记录为准。";
      return;
    }
    renderCategories(categories, catalog);
    const relevant = new Set(categories.map(category => category.id));
    const entries = catalog.categories.filter(row => row && relevant.has(row.id));
    const fresh = entries.filter(row => row.status === "fresh").length;
    const partial = entries.filter(row => row.status === "partial").length;
    const stale = entries.filter(row => row.status === "stale").length;
    const items = entries.reduce((count, row) => count + (Array.isArray(row.items) ? row.items.length : 0), 0);
    const date = typeof catalog.generated_at === "string" ? new Date(catalog.generated_at) : null;
    const updated = date && !Number.isNaN(date.getTime()) ? new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(date) : "未知时间";
    const complete = fresh === categories.length;
    statusDot.className = complete ? "status-dot good" : "status-dot warn";
    if (complete) summary.textContent = `${fresh} 个分类已更新 · ${items} 条公开索引 · ${updated}`;
    else if (items) summary.textContent = `${fresh + partial} 个分类已更新 · ${stale} 个保留旧索引 · 最近尝试 ${updated}`;
    else summary.textContent = `暂无可用视频索引 · 最近尝试 ${updated} · 请查看自动更新记录`;
  });
})();
