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

### 专用 HTML 周报（2026-10-25）

周报另建第三个 HTML 模板，名称建议 `MeterTrail 运营周报`，上传 `backend/src/main/resources/mail/weekly-report.html`，在原发信地域等待审核通过。不要修改验证码或告警模板。模板为固定表格与内联样式，变量只接收经过 HTML 转义的文本，不能把整段 HTML 放进变量。

审核通过后在服务器 `.env` 设置 `TENCENT_SES_WEEKLY_TEMPLATE=模板ID`，重建并重建 API/Worker 容器后生效。未配置时为兼容升级仍沿用旧纯文本通知，因此**代码更新本身不代表 HTML 周报已上线**。必须核对模板审核、环境配置及真实收件后才能报告完成。

SMTP 本地验证使用同一份 HTML 模板，并提供纯文本替代内容；腾讯云使用独立模板 ID 与同一组字段。两者均显示费用、请求、失败、P95、模型调用、价格覆盖计数及费用变化，测试邮件明确标记模拟数据。周报只说明订阅来源和关闭入口，不使用额度/告警说明。

模板 227254 首次审核拒绝：链接域名使用变量 `console_url`，且 `notice` 独占一行，取值范围过宽。修订模板固定使用实际域名 `https://metertrail.com`，仅项目 UUID 保留变量；来源和模拟数据说明固定在模板中，`report_type` 仅传“测试邮件 / Test email”或“正式周报 / Scheduled report”。SES 不再接收 `console_url`、`notice` 参数。重新上传此版本审核，审核通过后再启用 ID，不能将拒绝状态当作可发送。

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
