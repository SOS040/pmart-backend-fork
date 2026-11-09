// 시뮬레이터 평면(buildLayout) 좌표를 JSON 으로 뽑는다. 시뮬레이터 저장소는 읽기만 한다.
// 실행(Node 23.6+ — TypeScript 를 그대로 실행): node dump.mts <출력.json> [layout.ts 경로]
//   기본 layout.ts 경로 = 이 파일 기준 ../../../../../pmart-mfc-simulation/src/domain/layout.ts
// 그림: python -I draw.py <출력.json> <floorplan.svg>
import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const src = process.argv[3]
  ? pathToFileURL(process.argv[3]).href
  : new URL('../../../../../pmart-mfc-simulation/src/domain/layout.ts', import.meta.url).href;
const mod = await import(src);
const L = mod.buildLayout();
const out = {
  width: L.width,
  depth: L.depth,
  buildingDepth: L.buildingDepth,
  zones: L.zones,
  walls: L.walls,
  racks: L.racks.map((r: any) => ({
    id: r.id, temp: r.temp, group: r.group, rackNo: r.rackNo, cx: r.cx, cz: r.cz,
    along: r.along, face: r.face, levels: r.levels, slotsPerLevel: r.slotsPerLevel,
  })),
  locationCount: L.locations.length,
  locations: L.locations.map((l: any) => ({
    code: l.code, rackId: l.rackId, temp: l.temp, level: l.level, slot: l.slot,
    pickX: Math.round(l.pick.x * 100) / 100, pickZ: Math.round(l.pick.z * 100) / 100,
  })),
  sampleLocations: L.locations.slice(0, 3).map((l: any) => l.code),
  obstacles: L.obstacles,
  stations: L.stations,
  shippingSlots: L.shippingSlots,
  reserves: L.reserves,
  dockSlots: L.dockSlots,
  points: L.points,
};
writeFileSync(process.argv[2], JSON.stringify(out, null, 1));
console.log('zones', L.zones.length, 'racks', L.racks.length, 'locations', L.locations.length);
