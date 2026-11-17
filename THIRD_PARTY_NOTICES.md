# Third-party notices

本项目在构建与运行时会用到下列第三方组件。分发本项目的 APK 时请保留本文件。

## GenieX (Qualcomm AI Hub)

- 项目：https://github.com/qualcomm/GenieX
- 许可证：BSD 3-Clause
- 版权：Copyright (c) 2024-2026, Qualcomm Technologies, Inc. and/or its subsidiaries. All rights reserved.

`app/libs/geniex-android.aar` 在构建时由 `scripts/fetch-geniex-sdk.sh` 从官方 Release 下载，
不提交进仓库。该 AAR 内含 Qualcomm 的 QNN 运行库（`libQnnHtp.so`、`libQnnSystem.so`、
HTP skel 等）与 `libgeniex.so`，其使用同样受上述 BSD 3-Clause 许可及 Qualcomm 的相关条款约束。

官网：https://geniex.aihub.qualcomm.com

### 许可证全文

```
BSD 3-Clause License

Copyright (c) 2024-2026, Qualcomm Technologies, Inc. and/or its subsidiaries.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
