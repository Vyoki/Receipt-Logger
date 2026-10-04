// Browser check of the office copy (run in CI after the core tests wrote a sample with OFFICE_SAMPLE):
//   node web/check-office.mjs <office-sample.html> <e-invoice.xml> <screenshot dir>
// Opens the page in Chromium with the network cut off, checks a wrong and the right password, every tab, a product
// and a document detail, CSV export, dropping an e-invoice, and that nothing tried to connect anywhere.
import { chromium } from "playwright";
import { pathToFileURL } from "node:url";
import fs from "node:fs";
import path from "node:path";

const [page0, xml, outDir = "office-screens"] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const fail = msg => { console.error("FAIL: " + msg); process.exitCode = 1; };
const launch = process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : (process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {});
const browser = await chromium.launch(launch);
const shots = [];
for (const [scheme, theme] of [["light", "light"], ["dark", "dark"]]) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, colorScheme: scheme, locale: "it-IT", acceptDownloads: true });
  const requests = [];
  ctx.on("request", r => { if (!r.url().startsWith("file:") && !r.url().startsWith("data:") && !r.url().startsWith("blob:")) requests.push(r.url()); });
  const page = await ctx.newPage();
  const errors = [];
  page.on("pageerror", e => errors.push(e.message));
  page.on("console", m => { if (m.type() === "error" && !/Content Security Policy/.test(m.text())) errors.push(m.text()); });
  await page.goto(pathToFileURL(path.resolve(page0)).href);

  await page.fill("#pw", "sbagliata");
  await page.click("#unlockBtn");
  await page.waitForFunction(() => document.getElementById("pwError").textContent.length > 0, null, { timeout: 30000 });
  if (!(await page.textContent("#pwError")).includes("errata")) fail("wrong password message");

  await page.fill("#pw", "prova-ufficio");
  await page.click("#unlockBtn");
  await page.waitForSelector("#app:not(.hidden)", { timeout: 30000 });
  const biz = await page.textContent("#bizName");
  if (biz !== "RISTORANTE PROVA SAS") fail("business name: " + biz);
  await page.selectOption("#periodSel", "all");
  const kpi = await page.textContent(".kpi .value");
  if (!/€/.test(kpi)) fail("spend KPI: " + kpi);
  if (scheme === "light") { await page.screenshot({ path: `${outDir}/office-overview.png`, fullPage: true }); shots.push("office-overview.png"); }
  else { await page.screenshot({ path: `${outDir}/office-overview-dark.png`, fullPage: true }); shots.push("office-overview-dark.png"); }
  if (scheme === "dark") { await ctx.close(); continue; }

  for (const tab of ["Fornitori", "Prodotti", "Variazioni prezzi", "Documenti", "Lotti"]) {
    await page.click(`nav.tabs button:has-text("${tab}")`);
    const rows = await page.locator("tbody tr").count();
    if (rows === 0) fail("no rows in " + tab);
    const file = "office-" + tab.toLowerCase().replace(/\s+/g, "-") + ".png";
    await page.screenshot({ path: `${outDir}/${file}`, fullPage: true });
    shots.push(file);
  }
  // A description with quotes and "</script>" is shown as text, not run.
  await page.click('nav.tabs button:has-text("Prodotti")');
  await page.fill(".search", "detergente");
  if (!(await page.textContent("tbody")).includes('</script>')) fail("escaped product name");
  await page.fill(".search", "");
  // Product detail with its price history.
  await page.click("tbody tr >> nth=0");
  await page.waitForSelector("dialog[open] svg polyline");
  await page.screenshot({ path: `${outDir}/office-product.png` }); shots.push("office-product.png");
  await page.click("#dlgClose");
  // CSV export.
  const [dl] = await Promise.all([page.waitForEvent("download"), page.click("text=Esporta CSV")]);
  const csv = fs.readFileSync(await dl.path(), "utf8");
  if (!csv.startsWith("﻿Prodotto;")) fail("CSV header: " + csv.slice(0, 40));
  // Lots search.
  await page.click('nav.tabs button:has-text("Lotti")');
  await page.fill(".search", "L12-105");
  if ((await page.locator("tbody tr").count()) < 1) fail("lot search");
  // Drop an e-invoice: it joins the data.
  await page.click('nav.tabs button:has-text("Documenti")');
  const before = await page.locator("tbody tr").count();
  await page.setInputFiles("#fileInput", xml);
  await page.waitForFunction(n => document.querySelectorAll("tbody tr").length > n, before, { timeout: 10000 }).catch(() => fail("dropped e-invoice not added"));
  if (!(await page.textContent("#toast")).includes("aggiunt")) fail("toast after drop");
  await page.setInputFiles("#fileInput", xml);
  await page.waitForTimeout(300);
  if (!(await page.textContent("#toast")).includes("già")) fail("duplicate e-invoice not recognised");
  await page.screenshot({ path: `${outDir}/office-documents-dropped.png`, fullPage: true }); shots.push("office-documents-dropped.png");
  // English.
  await page.click("#langBtn");
  if (!(await page.textContent("nav.tabs")).includes("Suppliers")) fail("English labels");

  if (errors.length) fail("page errors: " + errors.join(" | "));
  if (requests.length) fail("network requests: " + requests.join(", "));
  await ctx.close();
}
// The empty page (no data) works as an e-invoice viewer.
{
  const tpl = fs.readFileSync(page0, "utf8").replace(/\/\*KR-DATA\*\/[\s\S]*?\/\*KR-END\*\//, "/*KR-DATA*/null/*KR-END*/");
  const empty = path.join(outDir, "empty.html");
  fs.writeFileSync(empty, tpl);
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: "en-GB" });
  const page = await ctx.newPage();
  const errors = [];
  page.on("pageerror", e => errors.push(e.message));
  await page.goto(pathToFileURL(path.resolve(empty)).href);
  await page.setInputFiles("#fileInput", xml);
  await page.click('nav.tabs button:has-text("Documents")');
  if (!(await page.textContent("tbody")).includes("ABC S.r.l.")) fail("e-invoice in the empty viewer");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  if (overflow) fail("horizontal scroll on a phone-width screen");
  await page.screenshot({ path: `${outDir}/office-phone-width.png`, fullPage: true }); shots.push("office-phone-width.png");
  fs.unlinkSync(empty);
  if (errors.length) fail("page errors (empty viewer): " + errors.join(" | "));
  await ctx.close();
}
await browser.close();
console.log(`Office copy check: ${process.exitCode ? "FAILED" : "OK"} (${shots.length} screenshots)`);
