# Применяет проверенные метки из labels_suggested.csv:
# 1) пользователь правит колонку code (неверные строки можно пометить skip в confidence)
# 2) python apply_labels.py — копирует файлы в data/ как solved_<код>_<i>.png
# 3) python train.py --data data --synth 1500 --pre-epochs 120 --epochs 120
import csv
import shutil
from pathlib import Path

SRC = Path('data_unlabeled')
DST = Path('data')
CSV = Path('labels_suggested.csv')

DST.mkdir(exist_ok=True)
n = 0
with open(CSV, newline='', encoding='utf-8') as f:
    for i, row in enumerate(csv.DictReader(f)):
        if row['confidence'].strip().lower() == 'skip':
            continue
        code = row['code'].strip()
        if len(code) != 5 or not code.isdigit():
            print(f"skip {row['filename']}: bad code '{code}'")
            continue
        src = SRC / row['filename']
        if not src.exists():
            print(f"skip {row['filename']}: file missing")
            continue
        shutil.copyfile(src, DST / f'solved_{code}_{i}.png')
        n += 1
print(f'copied {n} labeled files -> {DST}/')
