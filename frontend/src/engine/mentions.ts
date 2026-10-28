// 문장 속에서 Agent·데이터·플랫폼·부서·업무 유형 표기를 찾습니다.
// 띄어쓰기가 달라도 맞추고("상품 정보 DB" = "상품정보 DB"), 긴 표기부터 찾아 겹치는 짧은 표기는 건너뜁니다.
import type { State } from './store';
import { labelsFor, resolve, squash, vocabulary } from './vocab';

export type MentionKind = 'agent' | 'partialAgent' | 'data' | 'platform' | 'department' | 'work_type' | 'unregistered';

export interface Mention {
  kind: MentionKind;
  raw: string;
  id: string | null;
  how: 'exact' | 'alias' | 'review' | 'unregistered' | 'unknown' | 'name';
  reason: string | null;
  start: number;
  end: number;
  options?: string[];
}

const esc = (c: string) => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const isAsciiWord = (c: string | undefined) => !!c && /[A-Za-z0-9]/.test(c);

function labelRegex(label: string): RegExp {
  const chars = [...label.replace(/\s+/g, '')].map(esc);
  return new RegExp(chars.join('\\s*'), 'g');
}

const regexCache = new Map<string, RegExp>();
function rx(label: string): RegExp {
  let r = regexCache.get(label);
  if (!r) {
    r = labelRegex(label);
    regexCache.set(label, r);
  }
  r.lastIndex = 0;
  return r;
}

class Spans {
  private taken: [number, number][] = [];
  free(s: number, e: number) {
    return this.taken.every(([a, b]) => e <= a || s >= b);
  }
  take(s: number, e: number) {
    this.taken.push([s, e]);
  }
}

function scan(text: string, labels: string[], spans: Spans, onHit: (label: string, s: number, e: number) => void, asciiBoundary = true) {
  for (const label of labels) {
    const r = rx(label);
    let m: RegExpExecArray | null;
    while ((m = r.exec(text))) {
      const s = m.index;
      const e = s + m[0].length;
      if (!m[0]) break;
      if (asciiBoundary && /^[A-Za-z0-9]/.test(label) && isAsciiWord(text[s - 1])) continue;
      if (asciiBoundary && /[A-Za-z0-9]$/.test(label) && isAsciiWord(text[e])) continue;
      if (!spans.free(s, e)) continue;
      spans.take(s, e);
      onHit(label, s, e);
    }
  }
}

export interface FindOptions {
  kinds?: MentionKind[];
  /** 업무 유형은 대표 표기만(별칭 '검토'·'분석'처럼 흔한 말은 제외) */
  workTypePrefOnly?: boolean;
  excludeAgentIds?: string[];
}

export function findMentions(state: State, text: string, opts: FindOptions = {}): Mention[] {
  const kinds = new Set<MentionKind>(opts.kinds ?? ['agent', 'partialAgent', 'data', 'platform', 'department', 'work_type', 'unregistered']);
  const spans = new Spans();
  const out: Mention[] = [];
  const agents = [...state.agents].sort((a, b) => b.name.length - a.name.length);

  if (kinds.has('agent')) {
    scan(text, agents.map((a) => a.name), spans, (label, s, e) => {
      const a = agents.find((x) => x.name === label)!;
      out.push({ kind: 'agent', raw: text.slice(s, e), id: a.id, how: 'name', reason: null, start: s, end: e });
    });
    // "고객 분석 결과"처럼 Agent를 빼고 부른 경우 — 뒤에 '결과'가 올 때만
    const short = agents.map((a) => a.name.replace(/\s*Agent$/, '')).filter((n) => n.length >= 2);
    for (const label of short) {
      const r = new RegExp(`${[...label.replace(/\s+/g, '')].map(esc).join('\\s*')}(?=\\s*(의\\s*)?결과)`, 'g');
      let m: RegExpExecArray | null;
      while ((m = r.exec(text))) {
        const s = m.index;
        const e = s + m[0].length;
        if (!spans.free(s, e)) continue;
        spans.take(s, e);
        const a = agents.find((x) => x.name.replace(/\s*Agent$/, '') === label)!;
        out.push({ kind: 'agent', raw: text.slice(s, e), id: a.id, how: 'name', reason: null, start: s, end: e });
      }
    }
  }
  if (kinds.has('unregistered')) {
    const labels = Object.values(vocabulary.unregistered_labels).flatMap((m) => Object.keys(m));
    scan(text, labels, spans, (label, s, e) => {
      const kind = Object.entries(vocabulary.unregistered_labels).find(([, m]) => label in m)![0];
      out.push({
        kind: 'unregistered', raw: text.slice(s, e), id: null, how: 'unregistered',
        reason: vocabulary.unregistered_labels[kind][label], start: s, end: e,
      });
    });
  }
  // 업무 유형 대표 표기는 "모니터링 Agent"처럼 Agent 앞에 오기 쉬우므로 부분 이름보다 먼저 찾습니다.
  if (kinds.has('work_type') && opts.workTypePrefOnly) {
    scan(text, vocabulary.work_types.map((w) => w.prefLabel), spans, (label, s, e) => {
      const r = resolve('work_type', label);
      out.push({ kind: 'work_type', raw: text.slice(s, e), id: r.id, how: r.how, reason: r.reason, start: s, end: e });
    });
  }
  if (kinds.has('partialAgent')) {
    const r = /([가-힣A-Za-z0-9&]+)\s?Agent/g;
    let m: RegExpExecArray | null;
    while ((m = r.exec(text))) {
      const s = m.index;
      const e = s + m[0].length;
      if (!spans.free(s, e)) continue;
      const token = squash(m[1]);
      // "이 Agent", "해당 Agent" 같은 지시어는 이름이 아닙니다.
      if (token.length < 2 || ['해당', '그런', '다른', '같은'].includes(token)) continue;
      const options = state.agents
        .filter((a) => !(opts.excludeAgentIds ?? []).includes(a.id) && squash(a.name).includes(token))
        .map((a) => a.id)
        .sort();
      if (!options.length) continue;
      spans.take(s, e);
      if (options.length === 1) {
        out.push({ kind: 'agent', raw: m[0], id: options[0], how: 'name', reason: null, start: s, end: e });
      } else {
        out.push({
          kind: 'partialAgent', raw: m[0], id: null, how: 'unknown',
          reason: `'${m[0]}'와 부분 일치하는 Agent가 여러 개 — 후보를 보여주고 사용자 선택 필요`, start: s, end: e, options,
        });
      }
    }
  }
  // 부서·플랫폼·데이터·업무 유형 표기를 한데 모아 긴 것부터 찾습니다. ("재무 전표"가 부서 별칭 "재무"보다 먼저)
  const vocabKinds: ('department' | 'platform' | 'data' | 'work_type')[] = ['department', 'platform', 'data', 'work_type'];
  const combined: { label: string; kind: (typeof vocabKinds)[number] }[] = [];
  for (const vk of vocabKinds) {
    if (!kinds.has(vk) || (vk === 'work_type' && opts.workTypePrefOnly)) continue;
    for (const label of labelsFor(vk)) combined.push({ label, kind: vk });
  }
  combined.sort((a, b) => squash(b.label).length - squash(a.label).length);
  for (const { label, kind } of combined) {
    scan(text, [label], spans, (_l, s, e) => {
      const r = resolve(kind, label);
      out.push({ kind, raw: text.slice(s, e), id: r.id, how: r.how, reason: r.reason, start: s, end: e });
    });
  }
  return out.sort((a, b) => a.start - b.start);
}
