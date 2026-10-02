import os

os.environ['OMP_NUM_THREADS'] = '12'
os.environ['MKL_NUM_THREADS'] = '12'

import sys

LOG = r'C:\zenith-source\ml\captcha\train_sorted.log'
ROOT = r'C:\zenith-source\ml\captcha'

sys.path.insert(0, ROOT)
sys.stdout = open(LOG, 'w', buffering=1)
sys.stderr = sys.stdout
sys.argv = ['train.py', '--data', 'data', '--synth', '1500',
            '--pre-epochs', '60', '--epochs', '150', '--out', 'captcha_digits.onnx']

import torch
torch.set_num_threads(12)

import runpy
runpy.run_path(ROOT + r'\train.py', run_name='__main__')
