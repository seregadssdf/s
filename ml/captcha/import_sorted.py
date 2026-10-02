import shutil
from pathlib import Path

base = Path(r'C:\zenith-source\run\captcha')
dst = Path(r'C:\zenith-source\ml\captcha\data')
n = 0
for folder, start in [('уверен', 500), ('не уверен', 800)]:
    for i, p in enumerate(sorted((base / folder).glob('*.png'))):
        code = p.stem
        if len(code) == 5 and code.isdigit():
            shutil.copyfile(p, dst / f'solved_{code}_{start + i}.png')
            n += 1
print('copied', n)
print('data total:', len(list(dst.glob('*.png'))))
