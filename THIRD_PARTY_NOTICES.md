# Open-source licences

Willbolt itself is free software under the GNU General Public License, version 3 or later. Its source code is at
<https://github.com/zekomlek/willbolt>.

Willbolt is built from the parts below. Their licences ask that these notices travel with the app.

## Adult-content model (Apache License 2.0)

`Marqo/nsfw-image-detection-384`, a fine-tuned `timm/vit_tiny_patch16_384`, licensed under the Apache License 2.0. ONNX
conversion by KanariKanaru (`huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx`, revision of 2026-06-02, SHA-256
`e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2`, verified at download time). The model file is used
unchanged apart from that conversion. The authors report 98.56% accuracy on their own test set; that is not a guarantee for
phone screenshots. The full licence text is at the end of this page.

## Adult-site list (MIT, and Creative Commons Attribution 3.0)

Which sites: StevenBlack/hosts, "porn" extension (<https://github.com/StevenBlack/hosts>), itself a merge of community
lists. The list in the app is that list with redundant subdomains folded into their parent domains. The order, most visited
first, comes from the **Majestic Million** ranking (<https://majestic.com/reports/majestic-million>), which is licensed under
Creative Commons Attribution 3.0 (<https://creativecommons.org/licenses/by/3.0/>); only the order is derived from it. Both
are credited here as their licences require. Rebuild with `tools/make-adult-list.py`.

StevenBlack/hosts is used under the MIT licence:

> The MIT License (MIT)
>
> Copyright (c) 2026 Steven Black
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
> files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
> modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
> Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## ONNX Runtime (MIT)

`com.microsoft.onnxruntime:onnxruntime-android`, <https://github.com/microsoft/onnxruntime>. Willbolt removes the library's
telemetry component and turns its telemetry off; see the privacy policy.

> MIT License
>
> Copyright (c) Microsoft Corporation
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
> files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
> modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
> Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Libraries under the Apache License 2.0

* AndroidX (Core, Activity, Lifecycle, WorkManager, and the rest of Jetpack), Jetpack Compose and Material 3 -
  Copyright The Android Open Source Project, <https://developer.android.com/jetpack>.
* Kotlin standard library, kotlinx.coroutines and kotlinx.serialization - Copyright JetBrains s.r.o. and Kotlin Programming
  Language contributors, <https://kotlinlang.org>.

These are used under the Apache License 2.0, reproduced in full below. None of them was modified.

## Apache License 2.0

The full text follows, at the end of this page in the app.
