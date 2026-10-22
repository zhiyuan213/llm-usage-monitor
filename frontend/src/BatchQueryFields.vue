<script setup lang="ts">
import { t } from "./lib";
import { queryFields, queryHeaderFields } from "./batch-query-reference";
</script>
<template>
  <div class="protocol-reference">
    <h3>Header</h3>
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
          <tr v-for="row in queryHeaderFields" :key="row[0]">
            <td>
              <code>{{ row[0] }}</code>
            </td>
            <td>{{ t(row[1], row[2]) }}</td>
            <td>{{ row[3] }}</td>
            <td>{{ t(row[4], row[5]) }}</td>
            <td>{{ t(row[6], row[7]) }}</td>
            <td>
              <code>{{ row[8] }}</code>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p>
      {{
        t(
          "Use the same project API key as the upload. GET has no request Body.",
          "使用与上报相同项目的 API 密钥。GET 查询没有请求 Body。",
        )
      }}
    </p>
    <h3>{{ t("Path parameter and response fields", "路径参数与返回字段") }}</h3>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ t("Field name", "字段名称") }}</th>
            <th>{{ t("Description", "字段描述") }}</th>
            <th>{{ t("Type", "字段类型") }}</th>
            <th>{{ t("Length / constraints", "长度 / 取值范围") }}</th>
            <th>{{ t("Example", "示例") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in queryFields" :key="row[0]">
            <td>
              <code>{{ row[0] }}</code>
            </td>
            <td>{{ t(row[1], row[2]) }}</td>
            <td>{{ row[3] }}</td>
            <td>{{ t(row[4], row[5]) }}</td>
            <td>
              <code>{{ row[6] }}</code>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
