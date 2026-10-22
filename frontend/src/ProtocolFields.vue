<script setup lang="ts">
import { t } from "./lib";
import { sections } from "./protocol-reference";
const ingestionFields = sections.filter(
  (section) =>
    section.en === "Request headers" || section.en.startsWith("Body:"),
);
</script>
<template>
  <div class="protocol-reference">
    <p>
      {{
        t(
          "The tables below describe API parameters. data is a nested JSON object inside each event. Choose fields for the event type you send; you do not need every field. String lengths are UTF-8 bytes unless stated otherwise.",
          "以下是接口参数说明。data 是每条事件内的 JSON 对象，按事件类型选择需要的字段即可，不需要全部填写。字符串长度默认按 UTF-8 字节计算。",
        )
      }}
    </p>
    <section v-for="section in ingestionFields" :key="section.en">
      <h3>{{ t(section.en, section.zh) }}</h3>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{{ t("Field name", "字段名称") }}</th>
              <th>{{ t("Description", "字段描述") }}</th>
              <th>{{ t("Type", "字段类型") }}</th>
              <th>{{ t("Required", "必填条件") }}</th>
              <th>{{ t("Length / constraints", "长度 / 取值范围") }}</th>
              <th>{{ t("Example", "示例") }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in section.rows" :key="row[0]">
              <td>
                <code>{{ row[0] }}</code>
              </td>
              <td>{{ t(row[1], row[2]) }}</td>
              <td>
                <code>{{ row[3] }}</code>
              </td>
              <td>{{ t(row[4], row[5]) }}</td>
              <td>{{ t(row[6], row[7]) }}</td>
              <td>
                <code>{{ row[8] }}</code>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
