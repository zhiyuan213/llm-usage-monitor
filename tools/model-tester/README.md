# MeterTrail 桌面真实数据测试工具

Windows 独立版直接双击 `MeterTrailTester.exe`，无需安装 Python。源码版需安装 Python 3.10 或更新版本（包含 Tk），双击 `启动工具.bat`，也可运行 `python app.py`。启动脚本优先使用同目录 EXE，其次尝试 `py` 和 `python`。

填写兼容 OpenAI Chat Completions 的完整模型 API 地址、模型 Key、模型名称、供应商名称，以及 MeterTrail 地址和项目 Key。输入问题，点击“提问并记录”。真实模型调用会按供应商规则计费。

累计次数默认为 1，可设置为 1–50；达到次数自动上报，也可随时“立即上报”。每次独立问答生成一个 Trace 和一个 generation Observation，使用真实耗时、返回模型名称与 usage。未知用量为 null，不伪造为零；缓存从 DeepSeek 或 OpenAI usage 中读取。

HTTP 202 仅表示接收，工具继续查询批次结果，展示 accepted / duplicates / rejected。网络错误重试保留原事件 ID；已取得 batchId 时仅继续查询，不重复提交。业务拒绝保留数据并显示具体结果。配置与待上报数据只保存在内存，关闭前请完成上报。切换 MeterTrail 项目之前必须处理原有数据。

当前支持非流式文本问答，问题与回答正文只在工具中显示，不上报正文。密钥不落盘。尚未使用真实付费模型执行验收，需自行填入 Key 测试。
