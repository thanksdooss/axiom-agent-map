// 저장소 루트의 data/ 를 프론트엔드가 쓰는 위치로 복사합니다.
//  - src/generated/: 번들에 들어가는 JSON (정답 DB 상태, 어휘, 질의 정답)
//  - public/samples/: 일괄 등록 시연용 원본 5개 파일
import { cpSync, mkdirSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const data = join(here, '..', '..', 'data');
const gen = join(here, '..', 'src', 'generated');
const samples = join(here, '..', 'public', 'samples');
mkdirSync(gen, { recursive: true });
mkdirSync(samples, { recursive: true });
for (const f of ['extraction_answer_key.json', 'query_golden_set.json', 'dashboard_expected.json']) {
  cpSync(join(data, 'answer_key', f), join(gen, f));
}
cpSync(join(data, 'vocabulary', 'vocabulary.json'), join(gen, 'vocabulary.json'));
for (const f of readdirSync(join(data, 'raw'))) cpSync(join(data, 'raw', f), join(samples, f));
console.log('data synced →', gen, samples);
