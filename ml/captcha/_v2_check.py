import random

import synth

rng = random.Random(5)
for i in range(4):
    im, c = synth.gen_captcha(rng)
    im.save(f'_v2_{i}.png')
    print(i, c)
