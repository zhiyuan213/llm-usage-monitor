# MeterTrail 桌面真实数据测试工具

Windows 独立版直接双击 `MeterTrailTester.exe`，无需安装 Python。源码版需安装 Python 3.10 或更新版本（包含 Tk），双击 `启动工具.bat`，也可运行 `python app.py`。启动脚本优先使用同目录 EXE，其次尝试 `py` 和 `python`。

源码运行前执行 `python -m pip install -r requirements.txt`。独立版内置 HTTPS 根证书，不依赖打包电脑上的证书路径；仍验证服务器证书和主机名。供应商填写 `deepseek` 等供应商标识，模型名称单独填写。

开发者可执行 `powershell -File tools/model-tester/build-windows.ps1 -Python python` 重新打包。脚本保留 Python 发行版附带的 TLS 证书资源。打包后运行 `MeterTrailTester.exe --self-test network-check.json` 可验证两个公开 HTTPS 地址连通性，不使用 Key、不发起模型推理；401 是预期的未登录结果。

填写兼容 OpenAI Chat Completions 的完整模型 API 地址、模型 Key、模型名称、供应商名称，以及 MeterTrail 地址和项目 Key。输入问题，点击“提问并记录”。真实模型调用会按供应商规则计费。

每组调用次数默认为 1，可设置为 1–50；达到次数自动上报，也可随时“立即上报”结束当前组。同一组问答共用一个 traceId，上报一个 Trace 和 N 个 generation Observation，调用记录显示一条，详情显示 N 次模型调用。每次模型问答不携带前一次的上下文；可在组内切换模型，记录各自实际模型、耗时和用量。Trace 耗时从第一次调用开始到最后一次完成，包括中间手动操作的等待时间。未知用量为 null，不伪造为零；缓存从 DeepSeek 或 OpenAI usage 中读取。

HTTP 202 仅表示接收，工具继续查询批次结果，展示 accepted / duplicates / rejected。网络错误重试保留原事件 ID；已取得 batchId 时仅继续查询，不重复提交。业务拒绝保留数据并显示具体结果。待上报数据保存在内存，关闭前请完成上报。切换 MeterTrail 项目之前必须处理原有数据。

点击“保存配置”或发送问题时，配置写入 `%LOCALAPPDATA%/MeterTrailTester/config.json`，下次启动自动读取。两个 Key 由 Windows DPAPI 加密，只有同一 Windows 账户可解密；API 地址、模型、供应商、累计次数正常保存。复制配置文件到其他账户不能直接使用 Key。

当前支持非流式文本问答，问题与回答正文只在工具中显示，不上报正文。尚未使用真实付费模型执行验收，需自行填入 Key 测试。`--integration-test result.json` 可在 EXE 中运行本机模拟问答、累计上报、结果查询、加密保存和重启恢复测试，不访问真实模型。
