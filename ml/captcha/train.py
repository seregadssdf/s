# Локальное распознавание капчи Zenith: CNN ~500k параметров -> ONNX.
# Датасет: PNG с кодом в имени файла (wrong_cNN_CODE_TS.png, solved_CODE_TS.png, sent_CODE_TS.png).
# Запуск: python train.py --data data --epochs 250 --out captcha_digits.onnx
import argparse
import hashlib
import random
import re
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

import torch
import torch.nn as nn
import torch.nn.functional as F

IMG_W, IMG_H = 192, 144
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)
LABEL_RE = re.compile(r'^(?:wrong_c\d+|solved|sent|wrong)_(\d+)_(\d+)\.png$', re.IGNORECASE)


def load_dataset(paths):
    items, seen = [], set()
    for folder in paths:
        for p in sorted(Path(folder).glob('*.png')):
            m = LABEL_RE.match(p.name)
            if not m:
                continue
            code, _ts = m.group(1), m.group(2)
            if len(code) != 5 or not code.isdigit():
                continue
            h = hashlib.md5(p.read_bytes()).hexdigest()
            if h in seen:
                continue
            seen.add(h)
            img = Image.open(p).convert('RGB').resize((IMG_W, IMG_H), Image.BILINEAR)
            items.append((img, [int(c) for c in code], p.name))
    return items


def augment(img, rng):
    img = img.copy()
    w, h = img.size
    ang = rng.uniform(-10, 10)
    scale = rng.uniform(0.82, 1.12)
    tx, ty = rng.uniform(-0.07, 0.07) * w, rng.uniform(-0.07, 0.07) * h
    a = np.deg2rad(ang)
    ca, sa = np.cos(a), np.sin(a)
    cx, cy = w / 2, h / 2
    # обратная матрица аффинного преобразования для Image.transform
    m = np.array([ca / scale, -sa / scale, (1 - ca / scale) * cx + sa / scale * cy - tx / scale,
                  sa / scale, ca / scale, (1 - sa / scale) * cy - ca / scale * cx - ty / scale])
    img = img.transform((w, h), Image.AFFINE, tuple(m), resample=Image.BILINEAR, fillcolor=(255, 255, 255))

    arr = np.asarray(img).astype(np.float32)
    arr *= rng.uniform(0.7, 1.3)                      # яркость
    arr = (arr - 128) * rng.uniform(0.8, 1.2) + 128   # контраст
    arr *= np.array([rng.uniform(0.75, 1.25) for _ in range(3)], dtype=np.float32).reshape(1, 1, 3)  # каналы
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

    d = ImageDraw.Draw(img)
    for _ in range(rng.randint(1, 4)):                # шумовые линии как в генераторе
        col = tuple(rng.randint(0, 255) for _ in range(3))
        wdt = rng.randint(2, 5)
        x0, x1 = sorted((rng.uniform(0, w), rng.uniform(0, w)))
        y0, y1 = sorted((rng.uniform(0, h), rng.uniform(0, h)))
        if rng.random() < 0.5:
            xm, ym = rng.uniform(0, w), rng.uniform(0, h)
            d.line([x0, y0, xm, ym, x1, y1], fill=col, width=wdt, joint='curve')
        else:
            d.ellipse([x0, y0, x1, y1], outline=col, width=wdt)
    return img


def to_tensor(img):
    a = np.asarray(img, dtype=np.float32).transpose(2, 0, 1) / 255.0
    a = (a - IMAGENET_MEAN) / IMAGENET_STD
    return torch.from_numpy(a)


class CaptchaNetTL(nn.Module):
    # Переносное обучение: предобученный ShuffleNetV2 x0.5 (~1.4M) + 5 голов по 10 классов.
    # С нуля 425k не бутстрапаются на этой задаче (см. санити-чеки), ImageNet-фичи решают.
    def __init__(self, num_heads=5, num_classes=10):
        super().__init__()
        import torchvision
        self.backbone = torchvision.models.shufflenet_v2_x0_5(weights='IMAGENET1K_V1')
        self.backbone.fc = nn.Identity()
        self.fc = nn.Linear(1024, 256)
        self.heads = nn.ModuleList(nn.Linear(256, num_classes) for _ in range(num_heads))

    def forward(self, x):
        z = self.backbone(x)
        z = F.relu(self.fc(z))
        return torch.stack([h(z) for h in self.heads], dim=1)  # [B, 5, 10]


class CaptchaNet(nn.Module):
    def __init__(self, num_heads=5, num_classes=10):
        super().__init__()
        self.body = nn.Sequential(
            nn.Conv2d(3, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(64, 96, 3, padding=1), nn.BatchNorm2d(96), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(96, 128, 3, padding=1), nn.BatchNorm2d(128), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(128, 160, 3, padding=1), nn.BatchNorm2d(160), nn.ReLU(), nn.MaxPool2d(2),
            nn.AdaptiveAvgPool2d(1),
        )
        self.fc = nn.Linear(160, 256)
        self.heads = nn.ModuleList(nn.Linear(256, num_classes) for _ in range(num_heads))

    def forward(self, x):
        z = self.body(x).flatten(1)
        z = F.relu(self.fc(z))
        return torch.stack([h(z) for h in self.heads], dim=1)  # [B, 5, 10]


def evaluate(model, val):
    model.eval()
    with torch.no_grad():
        x = torch.stack([to_tensor(im) for im, _, _ in val])
        pred = model(x).argmax(-1)
        y = torch.tensor([lab for _, lab, _ in val])
        digit_acc = (pred == y).float().mean().item()
        exact = (pred == y).all(dim=1).float().mean().item()
    return digit_acc, exact


def run_epochs(model, data, epochs, lr, batch, rng, tag, val=None):
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    sch = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(epochs, 1))
    best, best_state = -1.0, None
    for epoch in range(1, epochs + 1):
        model.train()
        order = list(range(len(data)))
        rng.shuffle(order)
        tot_loss = 0.0
        for i in range(0, len(order), batch):
            chunk = [data[j] for j in order[i:i + batch]]
            x = torch.stack([to_tensor(augment(im, rng)) for im, _, _ in chunk])
            y = torch.tensor([lab for _, lab, _ in chunk], dtype=torch.long)
            logits = model(x)
            loss = F.cross_entropy(logits.flatten(0, 1), y.reshape(-1))
            opt.zero_grad()
            loss.backward()
            opt.step()
            tot_loss += loss.item() * len(chunk)
        sch.step()
        if val is not None and (epoch % 5 == 0 or epoch == epochs):
            digit_acc, exact = evaluate(model, val)
            score = exact * 100.0 + digit_acc
            if score >= best:
                best, best_state = score, {k: v.clone() for k, v in model.state_dict().items()}
            print(f'[{tag}] epoch {epoch:4d}  loss {tot_loss / len(data):.4f}  '
                  f'val digit {digit_acc:.3f}  val exact {exact:.3f}', flush=True)
    return best_state


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', nargs='+', default=['data'])
    ap.add_argument('--synth', type=int, default=1200)
    ap.add_argument('--pre-epochs', type=int, default=10)
    ap.add_argument('--epochs', type=int, default=250)
    ap.add_argument('--batch', type=int, default=8)
    ap.add_argument('--lr', type=float, default=3e-4)
    ap.add_argument('--out', default='captcha_digits.onnx')
    args = ap.parse_args()

    rng = random.Random(42)
    data = load_dataset(args.data)
    train, val = [], []
    if len(data) >= 8:
        rng.shuffle(data)
        nval = max(4, len(data) // 6)
        train, val = data[nval:], data[:nval]
        print(f'dataset: {len(data)} labeled ({len(train)} train / {len(val)} val)', flush=True)
    else:
        print(f'dataset: {len(data)} labeled — режим только-синтетика (метки придут из solved_* файлов)', flush=True)

    model = CaptchaNetTL()
    print('params:', sum(p.numel() for p in model.parameters()), flush=True)

    if args.synth > 0:
        import synth as synth_gen
        srng = random.Random(123)
        synth_data = []
        for i in range(args.synth):
            img, code = synth_gen.gen_captcha(srng)
            img = img.resize((IMG_W, IMG_H), Image.BILINEAR)
            synth_data.append((img, [int(c) for c in code], f'synth_{i}'))
        print(f'synth: {len(synth_data)} generated', flush=True)
        rng.shuffle(synth_data)
        sval, strain = synth_data[:60], synth_data[60:]
        if not train:
            # честной разметки нет: отбираем чекпоинт по синтетическому холдауту
            best_state = run_epochs(model, strain, args.pre_epochs, 1e-3, 16, rng, 'pre', val=sval)
            model.load_state_dict(best_state)
            torch.save(model.state_dict(), 'best.pt')
            model.eval()
            demo = torch.zeros(1, 3, IMG_H, IMG_W)
            torch.onnx.export(model, demo, args.out, opset_version=18,
                              input_names=['image'], output_names=['logits'],
                              dynamic_axes={'image': {0: 'batch'}, 'logits': {0: 'batch'}})
            print(f'synth-only model exported -> {args.out}')
            return
        run_epochs(model, strain, args.pre_epochs, 1e-3, 16, rng, 'pre')
        digit_acc, exact = evaluate(model, val)
        print(f'after pretrain: val digit {digit_acc:.3f}  val exact {exact:.3f}', flush=True)
        # rehearsal: подмешиваем синтетику в fine-tune против катастрофического забывания
        rng.shuffle(strain)
        train = train + strain[:max(300, len(train))]

    best_val, best_state = -1.0, None
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    sch = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    for epoch in range(1, args.epochs + 1):
        model.train()
        order = list(range(len(train)))
        rng.shuffle(order)
        tot_loss = 0.0
        for i in range(0, len(order), args.batch):
            chunk = [train[j] for j in order[i:i + args.batch]]
            x = torch.stack([to_tensor(augment(im, rng)) for im, _, _ in chunk])
            y = torch.tensor([lab for _, lab, _ in chunk], dtype=torch.long)
            logits = model(x)
            loss = F.cross_entropy(logits.flatten(0, 1), y.reshape(-1))
            opt.zero_grad()
            loss.backward()
            opt.step()
            tot_loss += loss.item() * len(chunk)
        sch.step()

        # на крошечном вале exact почти всегда 0 — отбираем чекпоинт по составной метрике
        digit_acc, exact = evaluate(model, val)
        score = exact * 100.0 + digit_acc
        if score >= best_val:
            best_val, best_state = score, {k: v.clone() for k, v in model.state_dict().items()}
        if epoch % 5 == 0 or epoch == args.epochs:
            print(f'epoch {epoch:4d}  loss {tot_loss / len(train):.4f}  '
                  f'val digit {digit_acc:.3f}  val exact {exact:.3f}  score {score:.1f}', flush=True)

    model.load_state_dict(best_state)
    torch.save(model.state_dict(), 'best.pt')
    model.eval()
    demo = torch.zeros(1, 3, IMG_H, IMG_W)
    torch.onnx.export(model, demo, args.out, opset_version=18,
                      input_names=['image'], output_names=['logits'],
                      dynamic_axes={'image': {0: 'batch'}, 'logits': {0: 'batch'}})
    print(f'best val score {best_val:.1f}; ONNX -> {args.out}')


if __name__ == '__main__':
    main()
