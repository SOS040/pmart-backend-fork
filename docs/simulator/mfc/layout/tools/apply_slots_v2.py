"""layout.json 의 단·칸 수를 MFC 문서 설계로 바꾼다(2026-11-16 사용자 "칸구조 A로").

시뮬레이터 layout.ts 가 아직 옛 칸 수(냉동·냉장 3, 상온 4)라 dump.mts 로 다시 뽑으면 옛 값이 나온다.
시뮬레이터가 반영하기 전까지 이 스크립트로 랙의 levels·slotsPerLevel 과 locations(칸마다 한 줄, 피킹 좌표 = 같은 랙)를 다시 만든다.
랙 배치·좌표·구역은 그대로. 시뮬레이터가 반영한 뒤에는 dump.mts 결과와 같아야 한다(코드·칸 수).
사용: python -I apply_slots_v2.py ../layout.json
"""
import json, sys

DESIGN = {"A": (5, 6), "B": (5, 7), "C": (6, 9), "D": (6, 10), "E": (6, 8), "F": (6, 10), "G": (6, 9), "H": (5, 5)}

p = sys.argv[1]
L = json.load(open(p, encoding="utf-8"))
pick = {}
for l in L["locations"]:
    pick.setdefault(l["rackId"], (l["pickX"], l["pickZ"]))
locs = []
for r in L["racks"]:
    lv, sl = DESIGN[r["group"][0]]
    r["levels"], r["slotsPerLevel"] = lv, sl
    px, pz = pick[r["id"]]
    for a in range(1, lv + 1):
        for b in range(1, sl + 1):
            locs.append({"code": f"{r['group']}-{r['rackNo']:02d}-{a}{b:02d}", "rackId": r["id"], "temp": r["temp"],
                         "level": a, "slot": b, "pickX": px, "pickZ": pz})
L["locations"] = locs
L["locationCount"] = len(locs)
L["sampleLocations"] = [l["code"] for l in locs[:3]]
with open(p, "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(L, ensure_ascii=False, indent=1))
print("칸", len(locs))
