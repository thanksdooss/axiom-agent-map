// 일괄 등록: 부서가 제출한 파일(json·yaml·csv·xlsx·docx)을 읽어 Agent와 관계 후보를 만듭니다.
// 정형 파일은 필드명 매핑으로, Word 서술형은 문장 규칙(또는 LLM)으로 원문 표기를 뽑고, 표준화는 vocab.ts가 맡습니다.
import JSZip from 'jszip';
import { load as loadYaml } from 'js-yaml';
import type {
  CommitResult, Evidence, GraphEdge, ImportPreview, PendingEntity, RelType, ReviewFlag, SpecialCase,
} from '../api/types';
import { findMentions } from './mentions';
import type { AgentRec, State } from './store';
import { nameOf, nextRelId, today } from './store';
import {
  NAMES, parseDate, parseOwner, parseUsage, REL_LABEL, resolve, resolveStatus, SENSITIVITY, squash, vocabulary,
} from './vocab';

interface RawField {
  value: string;
  ev: Evidence;
}

export interface RawAgent {
  file: string;
  format: string;
  parser: 'structured' | 'rule' | 'llm';
  submitter: string | null;
  submitterEv: Evidence | null;
  name: RawField;
  dept: RawField | null;
  owner: RawField | null;
  type: RawField | null;
  desc: string;
  date: RawField | null;
  usage: RawField | null;
  status: RawField | null;
  note: RawField | null;
  platforms: RawField[];
  data: RawField[];
  calls: RawField[];
  providedTo: RawField[];
  negatives: { text: string; ev: Evidence; targets: string[] }[];
}

const FIELDS: Record<string, string[]> = {
  name: ['agentName', 'name', 'agent_nm', 'Agent 이름', '에이전트명', 'Agent명'],
  dept: ['dept', 'team', 'dept_nm', '부서'],
  owner: ['owner', 'maintainer', 'manager', '담당자'],
  type: ['category', 'type', 'task_type', '업무 유형'],
  desc: ['description', 'summary', 'desc', '주요 기능'],
  platforms: ['connectedPlatforms', 'integrations', 'system', '연동 시스템'],
  data: ['dataSources', 'uses_data', 'data_used', '사용 데이터'],
  calls: ['callsAgents', 'depends_on', 'linked_agent', '연계 Agent'],
  date: ['launchDate', 'since', 'open_dt', '도입 시기'],
  usage: ['monthlyUsage', 'calls_per_month', 'monthly_req', '월 사용량'],
  status: ['status', 'state', '상태'],
  note: ['note', '비고'],
};
const LIST_FIELDS = new Set(['platforms', 'data', 'calls']);
const fieldOf = (key: string) => Object.entries(FIELDS).find(([, keys]) => keys.includes(key))?.[0] ?? null;
const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function emptyAgent(file: string, format: string, parser: RawAgent['parser']): RawAgent {
  return {
    file, format, parser, submitter: null, submitterEv: null, name: { value: '', ev: { file, locator: '', text: '' } },
    dept: null, owner: null, type: null, desc: '', date: null, usage: null, status: null, note: null,
    platforms: [], data: [], calls: [], providedTo: [], negatives: [],
  };
}

/** 정형 레코드(키→값)를 RawAgent로. loc(key, j)는 출처 위치를 돌려줍니다. */
function fromRecord(
  rec: Record<string, unknown>, file: string, format: string, loc: (key: string, j?: number) => string,
  splitter: RegExp,
): RawAgent {
  const a = emptyAgent(file, format, 'structured');
  for (const [key, value] of Object.entries(rec)) {
    const f = fieldOf(key);
    if (!f) continue;
    if (LIST_FIELDS.has(f)) {
      const items = Array.isArray(value) ? value.map(str) : str(value).split(splitter);
      const list = items.map((s) => s.trim()).filter(Boolean);
      (a[f as 'platforms' | 'data' | 'calls'] as RawField[]).push(
        ...list.map((v, j) => ({ value: v, ev: { file, locator: loc(key, j), text: v } })),
      );
    } else if (f === 'desc') {
      a.desc = str(value);
    } else {
      const v = str(value);
      (a as unknown as Record<string, RawField>)[f] = { value: v, ev: { file, locator: loc(key), text: v } };
    }
  }
  return a;
}

export function parseJson(text: string, file: string): RawAgent[] {
  const doc = JSON.parse(text) as unknown;
  const list = (Array.isArray(doc) ? doc : (doc as { agents?: unknown[] }).agents ?? []) as Record<string, unknown>[];
  const prefix = Array.isArray(doc) ? '' : '/agents';
  return list.map((rec, n) =>
    fromRecord(rec, file, 'json', (k, j) => `${prefix}/${n}/${k}${j === undefined ? '' : `/${j}`}`, /\s*[,;]\s*/),
  );
}

export function parseYaml(text: string, file: string): RawAgent[] {
  const doc = loadYaml(text) as Record<string, unknown> | unknown[];
  const list = (Array.isArray(doc) ? doc : (doc.agents as unknown[]) ?? []) as Record<string, unknown>[];
  // 줄 번호 찾기: 각 Agent 블록의 키·목록 항목 줄
  const lines = text.split(/\r?\n/);
  const pos: Record<string, number>[] = [];
  const itemPos: Record<string, number[]>[] = [];
  let n = -1;
  let key = '';
  lines.forEach((line, i) => {
    const start = /^- (\w+):/.exec(line);
    const field = /^ {2}(\w+):/.exec(line);
    const item = /^ {2}- /.exec(line);
    if (start) {
      n += 1;
      pos[n] = {};
      itemPos[n] = {};
      key = start[1];
      pos[n][key] = i + 1;
    } else if (field && n >= 0) {
      key = field[1];
      pos[n][key] = i + 1;
    } else if (item && n >= 0) {
      (itemPos[n][key] ??= []).push(i + 1);
    }
  });
  const submitterLine = lines.findIndex((l) => /^제출부서:/.test(l));
  return list.map((rec, k) => {
    const a = fromRecord(rec, file, 'yaml', (key2, j) =>
      j === undefined
        ? `${pos[k]?.[key2] ?? '?'}행 (agents[${k}].${key2})`
        : `${itemPos[k]?.[key2]?.[j] ?? '?'}행 (agents[${k}].${key2}[${j}])`, /\s*[,;]\s*/);
    if (submitterLine >= 0) {
      a.submitter = lines[submitterLine].replace(/^제출부서:\s*/, '');
      a.submitterEv = { file, locator: `${submitterLine + 1}행 (제출부서)`, text: lines[submitterLine] };
    }
    return a;
  });
}

export function parseCsvText(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') {
      row.push(cell);
      cell = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else cell += c;
  }
  if (cell || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows.filter((r) => r.some((x) => x.trim()));
}

export function parseCsv(text: string, file: string): RawAgent[] {
  const [header, ...rows] = parseCsvText(text.replace(/^﻿/, ''));
  return rows.map((r, n) => {
    const rec = Object.fromEntries(header.map((h, i) => [h, r[i] ?? '']));
    return fromRecord(rec, file, 'csv', (k) => `${n + 2}행 ${k}열`, /\s*;\s*/);
  });
}

const unxml = (s: string) =>
  s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

export async function parseXlsx(buf: ArrayBuffer, file: string): Promise<RawAgent[]> {
  const zip = await JSZip.loadAsync(buf);
  const wb = (await zip.file('xl/workbook.xml')?.async('string')) ?? '';
  const sheetName = /<sheet [^>]*name="([^"]+)"/.exec(wb)?.[1] ?? 'Sheet1';
  const sheetPath = Object.keys(zip.files).filter((p) => /^xl\/worksheets\/sheet\d+\.xml$/.test(p)).sort()[0];
  const shared: string[] = [];
  const ss = await zip.file('xl/sharedStrings.xml')?.async('string');
  if (ss) {
    for (const si of ss.match(/<si>[\s\S]*?<\/si>/g) ?? []) {
      shared.push(unxml((si.match(/<t[^>]*>([\s\S]*?)<\/t>/g) ?? []).map((t) => t.replace(/<[^>]+>/g, '')).join('')));
    }
  }
  const xml = (await zip.file(sheetPath)!.async('string')) ?? '';
  const cells = new Map<string, string>();
  const cellRe = /<c r="([A-Z]+)(\d+)"([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g;
  let m: RegExpExecArray | null;
  let maxRow = 0;
  while ((m = cellRe.exec(xml))) {
    const [, col, row, attrs, inner = ''] = m;
    const t = /<t[^>]*>([\s\S]*?)<\/t>/.exec(inner);
    const v = /<v>([\s\S]*?)<\/v>/.exec(inner);
    let value = '';
    if (/t="s"/.test(attrs) && v) value = shared[Number(v[1])] ?? '';
    else if (t) value = unxml(t[1]);
    else if (v) value = v[1];
    cells.set(`${col}${row}`, value);
    maxRow = Math.max(maxRow, Number(row));
  }
  const cols = [...new Set([...cells.keys()].map((k) => k.replace(/\d+/, '')))].sort((a, b) => a.length - b.length || (a < b ? -1 : 1));
  const header = cols.map((c) => cells.get(`${c}1`) ?? '');
  const out: RawAgent[] = [];
  for (let r = 2; r <= maxRow; r++) {
    const rec: Record<string, unknown> = {};
    cols.forEach((c, i) => header[i] && (rec[header[i]] = cells.get(`${c}${r}`) ?? ''));
    if (!Object.values(rec).some((v) => str(v).trim())) continue;
    const colOf = (k: string) => cols[header.indexOf(k)];
    out.push(fromRecord(rec, file, 'xlsx', (k) => `${sheetName}!${colOf(k)}${r}`, /\s*\n\s*|\s*,\s*/));
  }
  return out;
}

// ---------------------------------------------------------------- Word 서술형 (규칙 기반 문장 추출)
const sentencesOf = (p: string) => p.split(/(?<=\.)\s+/).filter(Boolean);
const DATE_RES = [
  /\d{4}년 \d{1,2}월 (?:초|중순|말)/, /\d{4}[-./]\d{1,2}[-./]\d{1,2}/, /\d{4}년 \d{1,2}월/, /\b\d{2}\/\d{1,2}\/\d{1,2}/,
  /\d{4}[-./]\d{1,2}/, /\b[A-Z][a-z]{2} \d{4}/,
];
const NEGATION = /(사용하지|쓰지|이용하지|참고하지)\s*않/;

export async function docxParagraphs(buf: ArrayBuffer): Promise<string[]> {
  const zip = await JSZip.loadAsync(buf);
  const xml = (await zip.file('word/document.xml')!.async('string')) ?? '';
  return (xml.match(/<w:p[ >][\s\S]*?<\/w:p>/g) ?? []).map((p) =>
    unxml((p.match(/<w:t[^>]*>([\s\S]*?)<\/w:t>/g) ?? []).map((t) => t.replace(/<[^>]+>/g, '')).join('')),
  );
}

const HEADING = /^\s*\d+\.\s*(.+?Agent)\s*$/;

/** Word 서술형 → RawAgent. batchNames는 같은 일괄 등록에 함께 들어온 Agent 이름(다른 파일 포함)입니다. */
export function parseNarrative(paras: string[], file: string, state: State, batchNames: string[] = []): RawAgent[] {
  const out: RawAgent[] = [];
  const known = [
    ...state.agents,
    ...batchNames.filter((n) => !state.agents.some((a) => squash(a.name) === squash(n)))
      .map((name, k) => ({ id: `__batch${k}`, name }) as AgentRec),
  ];
  const temp = { ...state, agents: known } as State;
  for (let i = 0; i < paras.length; i++) {
    const head = HEADING.exec(paras[i]);
    if (!head || i + 1 >= paras.length) continue;
    const a = emptyAgent(file, 'docx', 'rule');
    a.name = { value: head[1], ev: { file, locator: `문단 ${i + 1}`, text: head[1] } };
    const self = squash(head[1]);
    const p = i + 2;
    const sents = sentencesOf(paras[i + 1]);
    const ev = (s: number): Evidence => ({ file, locator: `문단 ${p} 문장 ${s + 1}`, text: sents[s] });
    sents.forEach((s, k) => {
      const ms = findMentions(temp, s, { workTypePrefOnly: true });
      const negated = NEGATION.test(s);
      if (negated) {
        a.negatives.push({ text: s, ev: ev(k), targets: ms.filter((m) => m.kind === 'data' || m.kind === 'agent').map((m) => m.id ?? m.raw) });
        return;
      }
      const dep = ms.find((m) => m.kind === 'department');
      if (!a.dept && dep) a.dept = { value: dep.raw, ev: ev(k) };
      if (!a.owner) {
        const named = /([가-힣]{2,4})\s*(변호사|님)/.exec(s);
        const team = /(\S+팀)\s*담당자가/.exec(s);
        if (team) a.owner = { value: `${team[1]} 담당자`, ev: ev(k) };
        else if (/담당자는\s*(아직\s*)?정해지지/.test(s)) a.owner = { value: '', ev: ev(k) };
        else if (named) a.owner = { value: named[2] === '변호사' ? `${named[1]} 변호사` : named[1], ev: ev(k) };
      }
      const wt = ms.find((m) => m.kind === 'work_type');
      if (!a.type && wt) {
        a.type = { value: wt.raw, ev: ev(k) };
        a.desc = s;
      }
      if (/연동|연결|로 결과를/.test(s)) {
        ms.filter((m) => m.kind === 'platform').forEach((m) => a.platforms.push({ value: m.raw, ev: ev(k) }));
      }
      if (/참고|확인합니다|씁니다|검색해|바탕으로|읽/.test(s)) {
        ms.filter((m) => m.kind === 'data' || (m.kind === 'unregistered' && m.reason && isDataLabel(m.raw)))
          .forEach((m) => a.data.push({ value: m.raw, ev: ev(k) }));
      }
      if (/호출|결과를|결과와/.test(s)) {
        const provider = /^(.*?Agent)가 이 Agent의/.exec(s);
        for (const m of ms) {
          if (m.kind === 'agent' && squash(nameOf(temp, m.id!)) === self) continue;
          if (provider && m.kind === 'agent' && m.end <= provider[1].length) {
            a.providedTo.push({ value: nameOf(temp, m.id!), ev: ev(k) });
          } else if (m.kind === 'agent' || m.kind === 'partialAgent' || (m.kind === 'unregistered' && !isDataLabel(m.raw))) {
            a.calls.push({ value: m.kind === 'agent' ? nameOf(temp, m.id!) : m.raw, ev: ev(k) });
          }
        }
      }
      if (!a.date) {
        for (const re of DATE_RES) {
          const d = re.exec(s);
          if (d) {
            a.date = { value: d[0], ev: ev(k) };
            break;
          }
        }
      }
      if (!a.usage) {
        const u = /(\d[\d,.]*)건\s*(정도|내외)/.exec(s);
        if (u) a.usage = { value: u[0], ev: ev(k) };
        else if (/하루 한 번/.test(s)) a.usage = { value: '하루 한 번', ev: ev(k) };
        else if (/사용량은 아직|집계하지 않/.test(s)) a.usage = { value: '', ev: ev(k) };
      }
      if (!a.status) {
        if (/파일럿/.test(s)) a.status = { value: '파일럿', ev: ev(k) };
        else if (/운영 중/.test(s)) a.status = { value: '운영 중', ev: ev(k) };
        else if (/중단|중지/.test(s)) a.status = { value: '중지', ev: ev(k) };
      }
    });
    out.push(a);
    i += 1;
  }
  return out;
}

const isDataLabel = (raw: string) => Object.keys(vocabulary.unregistered_labels.data ?? {}).some((k) => squash(k) === squash(raw));

/** 여러 파일을 읽어 RawAgent 목록으로. 정형 파일을 먼저 읽고, 그 이름들을 알고서 Word 문장을 분석합니다. */
export async function readFiles(state: State, files: { name: string; buf: ArrayBuffer }[]) {
  const raws: RawAgent[] = [];
  const meta: ImportPreview['files'] = [];
  const docs: { name: string; paras: string[] }[] = [];
  for (const f of files) {
    const ext = f.name.split('.').pop()?.toLowerCase();
    const text = () => new TextDecoder('utf-8').decode(f.buf);
    let got: RawAgent[] = [];
    if (ext === 'json') got = parseJson(text(), f.name);
    else if (ext === 'yaml' || ext === 'yml') got = parseYaml(text(), f.name);
    else if (ext === 'csv') got = parseCsv(text(), f.name);
    else if (ext === 'xlsx') got = await parseXlsx(f.buf, f.name);
    else if (ext === 'docx') {
      docs.push({ name: f.name, paras: await docxParagraphs(f.buf) });
      continue;
    } else throw new Error(`지원하지 않는 형식: ${f.name}`);
    raws.push(...got);
    meta.push({ name: f.name, format: ext!, agents: got.length, parser: 'structured' });
  }
  const names = [
    ...raws.map((r) => r.name.value),
    ...docs.flatMap((d) => d.paras.map((p) => HEADING.exec(p)?.[1]).filter((x): x is string => !!x)),
  ];
  for (const d of docs) {
    const got = parseNarrative(d.paras, d.name, state, names);
    raws.push(...got);
    meta.push({ name: d.name, format: 'docx', agents: got.length, parser: 'rule' });
  }
  // 파일 순서대로 ID를 매기도록 원래 업로드 순서로 정렬
  const order = new Map(files.map((f, i) => [f.name, i]));
  raws.sort((a, b) => (order.get(a.file) ?? 0) - (order.get(b.file) ?? 0));
  meta.sort((a, b) => (order.get(a.name) ?? 0) - (order.get(b.name) ?? 0));
  return { raws, meta };
}

// ---------------------------------------------------------------- 표준화 → 미리보기
interface Batch {
  preview: ImportPreview;
  newAgents: AgentRec[];
  edges: (GraphEdge & { duplicateOf: string | null })[];
}
const batches = new Map<string, Batch>();

export function buildPreview(state: State, raws: RawAgent[], files: ImportPreview['files']): ImportPreview {
  const batchId = `B${Date.now().toString(36)}`;
  const byName = new Map(state.agents.map((a) => [squash(a.name), a.id]));
  let max = Math.max(0, ...state.agents.map((a) => Number(a.id.slice(1)) || 0));
  const recs: AgentRec[] = [];
  const isNew = new Map<string, boolean>();
  for (const r of raws) {
    const key = squash(r.name.value);
    if (byName.has(key)) {
      isNew.set(byName.get(key)!, false);
      continue;
    }
    max += 1;
    const id = `A${String(max).padStart(3, '0')}`;
    byName.set(key, id);
    isNew.set(id, true);
  }
  const names = (id: string) => recs.find((a) => a.id === id)?.name ?? nameOf(state, id);
  const edges: Batch['edges'] = [];
  const pending: PendingEntity[] = [];
  const special: SpecialCase[] = [];
  let tmp = 0;
  const add = (type: RelType, source: string, target: string, status: 'CONFIRMED' | 'CANDIDATE', ev: Evidence, reason: string | null, extra?: Partial<GraphEdge>) => {
    const dupInBatch = edges.find((e) => e.type === type && e.source === source && e.target === target);
    if (dupInBatch) {
      dupInBatch.evidence.push(ev);
      if (status === 'CANDIDATE' && dupInBatch.status === 'CONFIRMED') {
        dupInBatch.status = 'CANDIDATE';
        dupInBatch.reviewReason = reason;
      }
      return dupInBatch;
    }
    const existing = state.edges.find((e) => e.type === type && e.source === source && e.target === target && e.status !== 'REJECTED');
    tmp += 1;
    const edge = {
      id: `NEW${tmp}`, type, typeLabel: REL_LABEL[type], source, target, sourceName: '', targetName: '', status,
      reviewReason: reason, evidence: [ev], reviewedBy: status === 'CONFIRMED' ? '별칭 사전 자동 확정' : null,
      reviewedAt: status === 'CONFIRMED' ? today() : null, duplicateOf: existing?.id ?? null, ...extra,
    };
    edges.push(edge);
    return edge;
  };

  for (const r of raws) {
    const id = byName.get(squash(r.name.value))!;
    const review: ReviewFlag[] = [];
    const flag = (field: string, raw: string, reason: string, candidate: string | number | null = null) =>
      review.push({ field, raw, candidate, reason });

    // 소속
    let dept: string | null = null;
    if (r.dept && r.dept.value.trim()) {
      const res = resolve('department', r.dept.value);
      if (res.id) {
        dept = res.id;
        if (res.reason) flag('department', r.dept.value, res.reason, res.id);
        add('BELONGS_TO', id, res.id, res.how === 'review' ? 'CANDIDATE' : 'CONFIRMED', r.dept.ev, res.reason);
      } else flag('department', r.dept.value, res.reason ?? '표준 목록에 없는 부서');
    } else {
      const hint = r.submitter ? findMentions(state, r.submitter, { kinds: ['department'] })[0] : undefined;
      if (hint?.id) {
        const reason = `소속 부서 누락 — 제출 부서(${r.submitter})로 보아 ${NAMES.get(hint.id)}으로 추정`;
        dept = hint.id;
        flag('department', '', reason, hint.id);
        add('BELONGS_TO', id, hint.id, 'CANDIDATE', r.submitterEv!, reason);
      } else flag('department', '', '소속 부서 누락');
    }
    // 업무 유형
    let wt: string | null = null;
    if (r.type?.value) {
      const res = resolve('work_type', r.type.value);
      if (res.id) {
        wt = res.id;
        add('HAS_WORK_TYPE', id, res.id, res.how === 'review' ? 'CANDIDATE' : 'CONFIRMED', r.type.ev, res.reason);
      } else flag('work_type', r.type.value, res.reason ?? '표준 목록에 없는 업무 유형');
    }
    // 플랫폼·데이터
    for (const p of r.platforms) {
      const res = resolve('platform', p.value);
      if (res.reason && res.id) flag('platforms', p.value, res.reason, res.id);
      if (res.id) add('USES_PLATFORM', id, res.id, res.how === 'review' ? 'CANDIDATE' : 'CONFIRMED', p.ev, res.reason);
      else {
        flag('platforms', p.value, res.reason ?? '미등록 플랫폼');
        pending.push(pendingOf(pending.length, p.value, 'Platform', id, res.reason ?? '미등록 플랫폼', p.ev));
      }
    }
    for (const d of r.data) {
      const res = resolve('data', d.value);
      if (res.id) {
        if (res.reason) flag('data', d.value, res.reason, res.id);
        add('ACCESSES_DATA', id, res.id, res.how === 'review' ? 'CANDIDATE' : 'CONFIRMED', d.ev, res.reason);
      } else {
        flag('data', d.value, res.reason ?? '표준 목록에 없는 데이터');
        pending.push(pendingOf(pending.length, d.value, 'DataAsset', id, res.reason ?? '표준 목록에 없는 데이터', d.ev));
      }
    }
    // 결과사용
    for (const c of r.calls) {
      const target = byName.get(squash(c.value));
      if (target === id) {
        const reason = '자기 참조 — 출발과 도착이 같은 관계는 저장하지 않음';
        flag('calls', c.value, reason);
        special.push({ case: '자기 참조', agentId: id, raw: c.value, expected: '검증 단계에서 거부', evidence: c.ev });
        continue;
      }
      if (target) {
        const ev = { ...c.ev, ...(names(target) !== c.value && nameOf(state, target) !== c.value ? { match: `공백 차이로 '${nameOf(state, target)}'와 매칭` } : {}) };
        add('USES_RESULT_OF', id, target, 'CONFIRMED', ev, null);
        continue;
      }
      const unreg = vocabulary.unregistered_labels.agent?.[c.value];
      const partial = /^(.+?)\s?Agent$/.exec(c.value);
      const options = partial
        ? [...byName.entries()].filter(([k, v]) => v !== id && k.includes(squash(partial[1]))).map(([, v]) => v).sort()
        : [];
      if (!unreg && options.length > 1) {
        const words = (c.ev.text ?? '').match(/[가-힣]{2,}/g) ?? [];
        const scored = options.map((o) => ({ o, s: words.filter((w) => names(o).includes(w) || nameOf(state, o).includes(w)).length }));
        const best = scored.filter((x) => x.s === Math.max(...scored.map((y) => y.s)) && x.s > 0);
        const reason = `'${c.value}'와 부분 일치하는 Agent가 여러 개 — 후보를 보여주고 사용자 선택 필요`;
        flag('calls', c.value, reason, best.length === 1 ? best[0].o : null);
        special.push({ case: '모호한 이름', agentId: id, raw: c.value, expected: '후보를 보여주고 사용자가 고름', evidence: c.ev });
        if (best.length === 1) add('USES_RESULT_OF', id, best[0].o, 'CANDIDATE', c.ev, reason, { candidatesShown: options });
        continue;
      }
      const reason = unreg ?? '등록되지 않은 대상 — 외부 대상으로 등록할지 확인';
      flag('calls', c.value, reason);
      pending.push(pendingOf(pending.length, c.value, 'Agent/외부 서비스', id, reason, c.ev));
      special.push({ case: '미등록 대상', agentId: id, raw: c.value, expected: '미등록 대상 검토 항목으로 보냄', evidence: c.ev });
    }
    for (const c of r.providedTo) {
      const src = byName.get(squash(c.value));
      if (!src) continue;
      const ev = { ...c.ev, directionNote: `제공하는 쪽(${r.name.value})이 주어인 문장 — 관계 방향은 ${c.value} → ${r.name.value}` };
      const e = add('USES_RESULT_OF', src, id, 'CONFIRMED', ev, null);
      special.push({ case: '중복 진술·방향', agentId: id, raw: c.value, expected: `${c.value} → ${r.name.value} 관계 하나로 합치고 근거 ${e.evidence.length}건 유지`, evidence: ev });
    }
    for (const n of r.negatives) {
      special.push({ case: '부정문', agentId: id, expected: '관계를 만들지 않음', evidence: n.ev });
    }
    if (r.note?.value && /예정|계획/.test(r.note.value)) {
      special.push({ case: '미래 계획', agentId: id, raw: r.note.value, expected: '관계를 만들지 않음', evidence: r.note.ev });
    }
    const [owners, ownerReason] = parseOwner(r.owner?.value);
    if (ownerReason) flag('owners', r.owner?.value ?? '', ownerReason);
    const [start, dateReason] = parseDate(r.date?.value);
    if (dateReason) flag('start_date', r.date?.value ?? '', dateReason, start);
    const [usage, usageReason] = parseUsage(r.usage?.value);
    if (usageReason) flag('monthly_calls', r.usage?.value ?? '', usageReason, usage);
    if (isNew.get(id)) {
      recs.push({
        id, name: r.name.value, department: dept, owners, work_type: wt, description: r.desc, start_date: start,
        monthly_calls: usage, status: resolveStatus(r.status?.value), source_file: r.file, review,
      });
    }
  }
  for (const e of edges) {
    e.sourceName = names(e.source);
    e.targetName = names(e.target);
  }
  const summaries = recs.map((a) => ({
    id: a.id, name: a.name, departmentId: a.department, departmentName: a.department ? NAMES.get(a.department) ?? null : null,
    workTypeId: a.work_type, workTypeName: a.work_type ? NAMES.get(a.work_type) ?? null : null, status: a.status,
    owners: a.owners,
    platformIds: edges.filter((e) => e.source === a.id && e.type === 'USES_PLATFORM').map((e) => e.target),
    dataIds: edges.filter((e) => e.source === a.id && e.type === 'ACCESSES_DATA').map((e) => e.target),
    highSensitivity: edges.some((e) => e.source === a.id && e.type === 'ACCESSES_DATA' && SENSITIVITY.get(e.target) === '상'),
    reviewFlagCount: a.review.length, isNew: true, reviewFlags: a.review, sourceFile: a.source_file,
  }));
  const preview: ImportPreview = { batchId, files, agents: summaries, relationships: edges, pending, specialCases: special };
  batches.set(batchId, { preview, newAgents: recs, edges });
  return preview;
}

function pendingOf(n: number, raw: string, kind: string, by: string, reason: string, ev: Evidence): PendingEntity {
  return { id: `PN${Date.now().toString(36)}${n}`, rawLabel: raw, kind, mentionedBy: by, mentionedByName: by, reason, evidence: ev, state: 'OPEN' };
}

export function commitBatch(state: State, batchId: string): CommitResult {
  const b = batches.get(batchId);
  if (!b) throw new Error('미리보기가 만료됐습니다. 다시 불러와 주세요.');
  state.agents.push(...b.newAgents);
  let added = 0;
  let merged = 0;
  for (const e of b.edges) {
    const existing = e.duplicateOf ? state.edges.find((x) => x.id === e.duplicateOf) : undefined;
    if (existing) {
      existing.evidence.push(...e.evidence);
      merged += 1;
      continue;
    }
    const { duplicateOf: _d, ...edge } = e;
    void _d;
    state.edges.push({ ...edge, id: nextRelId(state), sourceName: nameOf(state, e.source), targetName: nameOf(state, e.target) });
    added += 1;
  }
  for (const p of b.preview.pending) state.pending.push({ ...p, mentionedByName: nameOf(state, p.mentionedBy) });
  batches.delete(batchId);
  return { addedAgents: b.newAgents.length, addedRelationships: added, mergedEvidence: merged, pending: b.preview.pending.length };
}
