<script setup lang="ts">
import { ref } from "vue";
import { examples } from "./examples";
import { t } from "./lib";
const selected = ref("cURL"),
  copied = ref(false);
async function copy() {
  await navigator.clipboard.writeText(examples[selected.value] || "");
  copied.value = true;
  setTimeout(() => (copied.value = false), 1500);
}
</script>
<template>
  <div class="tabs" style="flex-wrap: wrap; gap: 18px">
    <button
      v-for="(_, name) in examples"
      :key="name"
      :class="{ active: selected === name }"
      @click="selected = String(name)"
    >
      {{ name }}
    </button>
  </div>
  <p>
    {{
      t(
        "Run from the repository root. First run node scripts/generate-example.mjs to create a current examples/batch.json. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.",
        "在仓库根目录执行，先运行 node scripts/generate-example.mjs 生成当前 examples/batch.json，再设置 METERTRAIL_ENDPOINT 与 METERTRAIL_API_KEY。",
      )
    }}
  </p>
  <p>{{ t('All examples send one request and one linked model call (2 units), with user, customer, feature, provider, model and Token usage. Use the field mapping and demonstration pricing instructions in REST integration above; do not replace unknown prices with zero.', '所有示例均包含一条请求及一条关联模型调用（2 units），带用户、客户、功能、供应商、模型和 Token 用量。字段映射与演示价格配置见上方 REST 接入说明；未知费用不会按零处理。') }}</p>
  <div class="code-box">
    <div>
      REST · {{ selected
      }}<button @click="copy">
        {{ copied ? t("Copied", "已复制") : t("Copy", "复制") }}
      </button>
    </div>
    <pre>{{ examples[selected] }}</pre>
  </div>
</template>
