import os
import shutil

base = r'C:\zenith-source\ml\captcha'
for f in ['_eval.py', '_overfit_test2.py', '_pool_check.py', '_sample.py', '_eval_onnx.py']:
    p = os.path.join(base, f)
    if os.path.exists(p):
        os.remove(p)
        print('removed', f)
shutil.rmtree(os.path.join(base, '__pycache__'), ignore_errors=True)
print('cleanup done')
