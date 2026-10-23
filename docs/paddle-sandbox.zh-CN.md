# Paddle 沙箱联调

正式账号主体资格等待 Paddle 人工确认；沙箱测试不代表正式收款获批。

## 服务器配置

编辑服务器原有 `.env`，不复制 `.env.example` 覆盖配置：

```dotenv
PADDLE_API_URL=https://sandbox-api.paddle.com
PADDLE_CORE_PRICE=pri_01m5ker5hhkh3ybcccvh5fy3ce
PADDLE_PRO_PRICE=pri_01m5kex3pya50cseaf0t22c4p5
PADDLE_API_KEY=<沙箱服务端密钥>
PADDLE_WEBHOOK_SECRET=<此通知地址的签名密钥>
PADDLE_CLIENT_TOKEN=<test_开头的客户端Token>
PUBLIC_URL=https://metertrail.com
```

只有客户端 Token 会经 `/v1/checkout/config` 返回给浏览器；API 密钥和回调签名密钥仅用于后端。API 地址与客户端 Token 环境不匹配时拒绝打开收银台。配置更新必须重新创建 API 和 Worker 容器，单独 restart 不会读取新环境变量。

沙箱 API 密钥权限：Transactions Write、Subscriptions Read、Customer portal sessions Write。商品和通知配置手工在后台操作，不需要赋予后端相关写权限。

## Paddle 后台

- 沙箱 Core 29 USD/月、Pro 199 USD/月，数量范围 1–1，关闭试用。
- My account > Settings > Checkout configuration（部分界面显示 Hosted checkouts）设置默认付款链接 `https://metertrail.com/pay`。必须在创建交易之前配置。正式环境需要对应域名审核。
- Events > Notifications：Webhook 地址 `https://metertrail.com/webhooks/paddle`，API version 1，Usage type Both，订阅 Transaction、Subscription、Adjustment 分组。保存该地址的 Secret key 为服务器 `PADDLE_WEBHOOK_SECRET`。
- 配置完成前不运行模拟通知。模拟数据如不能关联测试工作空间，会返回 ignored；通知 HTTP 200 只证明接收，不代表实际套餐更新。

## 部署

```bash
cd /home/ubuntu/llm-usage-monitor
git pull --ff-only origin main &&
sudo docker compose build api-blue web &&
sudo docker compose up -d --no-deps api-blue worker web
```

## 验收（待实际执行）

使用专门的测试账号，避免将沙箱交易混入真实业务账单。

1. 访问 `/v1/checkout/config`，确认 enabled=true、environment=sandbox，只公开前端 Token。
2. 测试账号在套餐与账单选择 Core，后端创建交易并绑定工作空间，跳转 `/pay?_ptxn=txn_...`。页面显示沙箱测试提示。
3. 使用 Paddle 官方测试卡 `4242 4242 4242 4242`，填写任意未来有效期和测试安全码，不用真实信用卡。
4. 浏览器收银台完成只显示等待回调确认，不直接授予套餐。通知验签通过后，在后台刷新查看 Core、账期、额度和交易记录。
5. 在 Paddle 通知日志核对交付结果。重复投递不能重复计费或重置用量；过时通知不能覆盖最新订阅状态。
6. 账单门户：查看发票、付款方式和取消流程，核对服务器订阅状态更新。
7. 测试付款失败、退款调整记录，以及 Pro 购买。续费和取消生效按 Paddle 实际事件时间验证。

尚未完成真实 Paddle 沙箱验收；本地模拟和自动化检查不能代替上述操作。自动收取超额费用仍不在本次固定套餐收银台接入范围内。

## 官方参考

- https://developer.paddle.com/get-started/quickstart/
- https://developer.paddle.com/build/transactions/default-payment-link/
- https://developer.paddle.com/webhooks/about/signature-verification/
