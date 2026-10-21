# 腾讯云邮件推送接入

个人认证的新账号使用 SES API，不依赖 SMTP 权限。代码使用官方 Java SES SDK，固定调用 HTTPS `ses.tencentcloudapi.com`；中国站支持 `ap-hongkong`、`ap-guangzhou`，这里选择与已验证发信域名一致的 `ap-hongkong`。云服务器位于新加坡不改变邮件服务地域。

## 控制台准备

1. 验证 `mail.metertrail.com` 的 MX/SPF/DKIM/DMARC，保留全部验证记录。
2. 创建发信地址 `noreply@mail.metertrail.com`。
3. 在中国香港地域创建两个**纯文本**模板并等待审核通过：

| 模板名称建议 | 上传文件 | 唯一变量 | 用途 |
| --- | --- | --- | --- |
| MeterTrail 邮箱验证码 | `ops/email/verification.txt` | `{{code}}` | 注册、重置密码、密钥操作等安全验证；只传六位验证码 |
| MeterTrail 用量与告警通知 | `ops/email/notification.txt` | `{{message}}` | 额度提醒、用户配置的告警；正文作为纯文本 |

记录两个模板 ID。不要使用营销类型模板，不要把通知模板改成 HTML，避免项目名称等用户输入被作为 HTML 解释。模板文件不是 API 凭证，可以下载上传；只有审核通过后才能用于正式发送。

4. 在访问管理 CAM 创建仅用于程序调用的专用子用户，关联自定义策略 `ops/email/send-only-policy.json`，只授予 `ses:SendEmail`。SES 此接口使用操作级权限，资源填写 `*`。为该子用户创建 API 密钥，保存 SecretId 和 SecretKey；不要用主账号全权限密钥，不要把密钥提交到 Git 或发到聊天中。

## 服务器更新

在 Xshell 中执行：

```bash
cd /home/ubuntu/llm-usage-monitor
git pull --ff-only origin main
bash scripts/configure-tencent-ses.sh
```

脚本依次询问两个已审核模板 ID、发信地址、SES 地域、SecretId、SecretKey。默认发信地址和地域可以直接回车，凭证输入不会显示，也不会进入命令历史。脚本只更新 `.env` 中的邮件设置，不改变数据库密码、应用加密密钥、HTTPS Cookie 或公网地址。

```bash
sudo docker compose config --quiet
sudo docker compose build api-blue && sudo docker compose up -d api-blue worker
sudo docker compose ps
```

构建失败时不要继续重启。升级后仍然使用原有数据库卷，**不要执行 `down -v`**。

## 验证真实投递

在 `https://metertrail.com` 注册或重新申请验证码（申请间隔至少 60 秒），检查实际收件箱和垃圾箱，再完成验证。验证码在申请后 5 分钟失效。旧的 Mailpit 邮件不会迁移到真实邮箱；请申请新验证码。

```bash
sudo docker compose logs --since 5m --tail 80 worker
```

SES 失败日志只输出错误码和 RequestId，不输出收件人、验证码、正文或密钥。检查地域、模板审核状态、发信域名、子用户权限和控制台额度。API 接受成功并不等于已送达；实际投递状态还应在 SES 控制台查看。

保留加密发件队列及现有失败重试。腾讯 API 返回失败或缺少消息 ID 时，不会标记已发送，也不会自动回退到 Mailpit。网络响应丢失可能导致重复投递；验证码本身仍按原有一次性验证规则处理。队列最多尝试 10 次，耗尽后需定位原因再申请新验证码。

本地开发默认 `MAIL_PROVIDER=smtp`，保持 Mailpit。服务器使用 `MAIL_PROVIDER=tencent-ses` 时，必须配置凭证和两个大于零的模板 ID，缺少配置会拒绝启动，避免误把生产邮件发到本地邮箱。

## 参考资料

- [腾讯云 SMTP 权限说明](https://cloud.tencent.com/document/product/1288/65749)
- [SendEmail API 与模板要求](https://cloud.tencent.com/document/api/1288/51034)
- [发信模板](https://cloud.tencent.com/document/product/1288/55193)
- [SES 的 CAM 权限](https://cloud.tencent.com/document/product/598/70009)

自动化验证使用 SDK 模拟响应，不调用真实付费接口。真实账号配置和收件箱验收需在服务器配置后完成。
