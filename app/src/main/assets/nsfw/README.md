---
license: apache-2.0
library_name: onnx
pipeline_tag: image-classification
base_model: Marqo/nsfw-image-detection-384
tags:
- onnx
- onnxruntime
- image-classification
- nsfw
- vision-transformer
---

# NSFW Image Detection 384 — ONNX

ONNX export of [Marqo/nsfw-image-detection-384](https://huggingface.co/Marqo/nsfw-image-detection-384), a two-class image classifier based on `vit_tiny_patch16_384`. Run inference with ONNX Runtime without installing PyTorch or timm.

The weights were converted from [KanariKanaru/nsfw-image-detection-384](https://huggingface.co/KanariKanaru/nsfw-image-detection-384/tree/dcbee2f0570c16c3212bc6b81bb8911194b9fa62) at revision `dcbee2f0570c16c3212bc6b81bb8911194b9fa62`. This repository provides a format conversion, not a newly trained model.

NSFW content is subjective and contextual, and the model can make mistakes. Evaluate it on your own images and choose a threshold appropriate for your application.

## Model interface

| Property | Value |
| --- | --- |
| Model file | `model.onnx` |
| Format | ONNX, opset 17, float32 (not quantized) |
| Input | `input`, float32, shape `[1, 3, 384, 384]` (NCHW) |
| Color | RGB |
| Normalization | Scale pixels to `[0, 1]`, then `(x - 0.5) / 0.5` for each channel |
| Output | `logits`, float32, shape `[1, 2]` |
| Class order | Index 0: `nsfw` (NSFW); index 1: `safe` (SFW) |
| Postprocessing | Softmax over the class dimension |
| Batch size | Fixed at 1 |

Preprocessing and softmax run outside the ONNX graph. The packaged [manifest](./manifest.json) specifies resizing directly to 384 × 384 with a Catmull–Rom filter. The example below uses Pillow's bicubic resampling, whose results can differ from another library's implementation. Direct resizing can change the aspect ratio; the original timm configuration instead specifies a center crop with `crop_pct=1.0`. These preprocessing paths may give different predictions on non-square images.

## Python inference with ONNX Runtime

```bash
pip install onnxruntime numpy pillow huggingface_hub
```

Save an image as `image.jpg`, then run:

```python
import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from PIL import Image, ImageOps

model_path = hf_hub_download(
    repo_id="KanariKanaru/nsfw-image-detection-384-onnx",
    filename="model.onnx",
)
# If you already downloaded the model, use model_path = "model.onnx".
session = ort.InferenceSession(
    model_path,
    providers=["CPUExecutionProvider"],
)

with Image.open("image.jpg") as source:
    image = ImageOps.exif_transpose(source).convert("RGB")
    image = image.resize((384, 384), resample=Image.Resampling.BICUBIC)
    pixels = np.asarray(image, dtype=np.float32) / np.float32(255.0)

pixels = (pixels - np.float32(0.5)) / np.float32(0.5)
inputs = np.ascontiguousarray(pixels.transpose(2, 0, 1)[None, ...])
logits = session.run(["logits"], {"input": inputs})[0]

# Numerically stable softmax: the model returns logits, not probabilities.
exp_logits = np.exp(logits - logits.max(axis=-1, keepdims=True))
probabilities = exp_logits / exp_logits.sum(axis=-1, keepdims=True)
labels = ["nsfw", "safe"]
scores = dict(zip(labels, probabilities[0].tolist()))
print("Probabilities:", scores)
print("Class:", labels[int(probabilities[0].argmax())])

threshold = 0.5  # Example only; tune on representative data.
print("NSFW:", scores["nsfw"] >= threshold)
```

## Repository files

- `model.onnx`: model used by the inference example.
- `manifest.json`: input preprocessing, output labels, and artifact metadata.
- `conversion_report.json`: source revision, exporter versions, and numerical validation results.
- `SHA256SUMS`: checksums for the ONNX model and accompanying package metadata.
- `config.json` and `model.safetensors`: source timm configuration and weights; not required for ONNX Runtime inference.
- `MODEL_NOTICES.md` and `LICENSE-APACHE-2.0.txt`: attribution and license information.

To clone with the model weights, install [Git LFS](https://git-lfs.com/) first:

```bash
git lfs install
git clone https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx
cd nsfw-image-detection-384-onnx
shasum -a 256 -c SHA256SUMS
```

## Conversion validation

The [conversion report](./conversion_report.json) records the following comparison between the source model and ONNX Runtime:

| Metric | Recorded result |
| --- | --- |
| Maximum absolute logit difference | `7.152557373046875e-07` |
| Maximum absolute softmax score difference | `5.960464477539063e-08` |
| Top-label match | `1` |

The exporter used one synthetic random input for this check. These results check numerical agreement for that input; they do not constitute an evaluation of classification accuracy on real images.

## Original model evaluation

The original model card reports 98.56% accuracy on the authors' proprietary test set of 20,000 images (10,000 NSFW and 10,000 SFW). The model was trained on 200,000 images, evenly split between the two classes, and fine-tuned from [timm/vit_tiny_patch16_384.augreg_in21k_ft_in1k](https://huggingface.co/timm/vit_tiny_patch16_384.augreg_in21k_ft_in1k).

The following figures are reproduced from the original model card. They describe the source model's evaluation, not a separate benchmark of this ONNX export.

![Original model comparison](./images/Comparison.png)

### Thresholds and precision vs. recall

Adjusting the NSFW probability threshold trades off precision and recall. The best threshold depends on your images and how you weigh false positives and false negatives.

![Original model threshold evaluation](./images/ThresholdEvals.png)
![Original model precision and recall curves](./images/PrecisionRecallCurves.png)

## License and attribution

Apache-2.0. See [LICENSE-APACHE-2.0.txt](./LICENSE-APACHE-2.0.txt) and [MODEL_NOTICES.md](./MODEL_NOTICES.md). The original model was published by [Marqo](https://huggingface.co/Marqo/nsfw-image-detection-384); this repository distributes its ONNX conversion and accompanying files.

## Citation

```
@article{dosovitskiy2020vit,
  title={An Image is Worth 16x16 Words: Transformers for Image Recognition at Scale},
  author={Dosovitskiy, Alexey and Beyer, Lucas and Kolesnikov, Alexander and Weissenborn, Dirk and Zhai, Xiaohua and Unterthiner, Thomas and  Dehghani, Mostafa and Minderer, Matthias and Heigold, Georg and Gelly, Sylvain and Uszkoreit, Jakob and Houlsby, Neil},
  journal={ICLR},
  year={2021}
}
```


```
@misc{rw2019timm,
  author = {Ross Wightman},
  title = {PyTorch Image Models},
  year = {2019},
  publisher = {GitHub},
  journal = {GitHub repository},
  doi = {10.5281/zenodo.4414861},
  howpublished = {\url{https://github.com/huggingface/pytorch-image-models}}
}
```