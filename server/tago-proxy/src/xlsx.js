// 작은 엑셀(xlsx) 읽기: zip 중앙 목록으로 파일을 찾아 풀고(deflate-raw), 시트를 [행][열] 글자 표로.
// 지자체 버스 시간표(제주)처럼 엑셀로만 주는 자료용. 수식·서식은 무시하고 값만.

async function inflateRaw(bytes) {
  const s = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

/** zip 바이트 → Map(이름 → 내용 바이트) (중앙 목록 기준, 저장·deflate 만) */
export async function unzip(buf) {
  const b = new Uint8Array(buf), v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  let eocd = -1;
  for (let i = b.length - 22; i >= Math.max(0, b.length - 65557); i--) if (v.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error("zip 아님");
  const count = v.getUint16(eocd + 10, true);
  let p = v.getUint32(eocd + 16, true);
  const out = new Map(), dec = new TextDecoder();
  for (let n = 0; n < count && p + 46 <= b.length; n++) {
    if (v.getUint32(p, true) !== 0x02014b50) break;
    const method = v.getUint16(p + 10, true), csize = v.getUint32(p + 20, true);
    const nlen = v.getUint16(p + 28, true), xlen = v.getUint16(p + 30, true), clen = v.getUint16(p + 32, true), local = v.getUint32(p + 42, true);
    const name = dec.decode(b.subarray(p + 46, p + 46 + nlen));
    p += 46 + nlen + xlen + clen;
    const start = local + 30 + v.getUint16(local + 26, true) + v.getUint16(local + 28, true);
    const data = b.subarray(start, start + csize);
    if (method === 0) out.set(name, data);
    else if (method === 8) out.set(name, await inflateRaw(data));
  }
  return out;
}

const unxml = (s) => s.replace(/<[^>]+>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
const colNo = (ref) => { let n = 0; for (const ch of /^[A-Z]+/.exec(ref)[0]) n = n * 26 + ch.charCodeAt(0) - 64; return n - 1; };

/** xlsx 바이트 → [{name, rows: [[글자...]...]}] (시트 순서대로) */
export async function readXlsx(buf) {
  const files = await unzip(buf);
  const text = (n) => (files.has(n) ? new TextDecoder().decode(files.get(n)) : "");
  const shared = [...text("xl/sharedStrings.xml").matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) => unxml(m[1]));
  const names = [...text("xl/workbook.xml").matchAll(/<sheet [^>]*name="([^"]*)"/g)].map((m) => unxml(m[1]));
  const sheets = [];
  for (let i = 1; files.has(`xl/worksheets/sheet${i}.xml`); i++) {
    const rows = [];
    for (const r of text(`xl/worksheets/sheet${i}.xml`).matchAll(/<row [^>]*>([\s\S]*?)<\/row>/g)) {
      const row = [];
      for (const c of r[1].matchAll(/<c r="([A-Z]+)\d+"([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const body = c[3] || "", vm = /<v>([\s\S]*?)<\/v>/.exec(body), im = /<is>([\s\S]*?)<\/is>/.exec(body);
        let val = vm ? unxml(vm[1]) : im ? unxml(im[1]) : "";
        if (/t="s"/.test(c[2]) && vm) val = shared[+vm[1]] ?? "";
        row[colNo(c[1])] = val;
      }
      rows.push(Array.from(row, (x) => x ?? ""));
    }
    sheets.push({ name: names[i - 1] || `sheet${i}`, rows });
  }
  return sheets;
}
