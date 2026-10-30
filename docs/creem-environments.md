# Creem 正式与测试切换

服务端 `.env` 使用 `CREEM_MODE=test` 或 `CREEM_MODE=live` 切换。此开关不向普通用户开放。

两套配置分别保存：

| 配置 | 测试 | 正式 |
| --- | --- | --- |
| API Key | CREEM_TEST_API_KEY | CREEM_LIVE_API_KEY |
| Webhook 签名密钥 | CREEM_TEST_WEBHOOK_SECRET | CREEM_LIVE_WEBHOOK_SECRET |
| Core 商品 | CREEM_TEST_CORE_PRODUCT | CREEM_LIVE_CORE_PRODUCT |
| Pro 商品 | CREEM_TEST_PRO_PRODUCT | CREEM_LIVE_PRO_PRODUCT |

API 地址随模式固定切换，不能另配地址。显式模式缺少配置时停止创建支付，不回退到另一套配置。密钥环境不匹配或模式拼写错误时启动失败。

`CREEM_CHECKOUT_ENABLED` 仍是支付入口总开关，设为 `false` 可关闭新结账。

修改后按发布流程重新创建 API 和 worker 容器；已有容器不会自动读取修改后的 `.env`。正式模式开通前必须确认商品、签名密钥和 Webhook 事件配置完整，验证结账链接后由用户完成付款。

未设置模式时使用 `legacy`，兼容原有 `CREEM_API_KEY`、`CREEM_WEBHOOK_SECRET`、`CREEM_CORE_PRODUCT`、`CREEM_PRO_PRODUCT`、`CREEM_API_URL`。迁移时先将现有测试配置复制到 `CREEM_TEST_*`，再显式选择 `test`。测试订阅不转换为正式订阅；不要在真实订阅运行期间随意切到测试模式，当前 Webhook 只验证所选环境的签名。
