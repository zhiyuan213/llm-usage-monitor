<script setup lang="ts">
import {t} from './lib';
defineProps<{status:any}>();
const emit=defineEmits<{change:[enabled:boolean]}>();
</script>
<template>
  <div class="model-price-policy">
    <p>{{t('Public model prices are refreshed every 6 hours. Project overrides take priority for the same provider and model. Private models without a configured price stay unknown. Prices are matched to the request start time; updates do not recalculate historical costs.','公共模型价格每6小时同步。相同供应商和模型优先使用项目自定义价格；私有模型未配置价格时费用保持未知。按请求开始时间匹配价格，更新不会重算历史费用。')}}</p>
    <p class="muted">{{t('Public catalogue','公共价格目录')}}：{{status.status==='ok'?t('Available','可用'):status.status==='failed'?t('Last refresh failed; previous prices retained','最近同步失败，保留上次价格'):t('Waiting for first sync','等待首次同步')}} · {{t('Models','模型数')}} {{status.model_count||0}} · {{t('Last successful sync (UTC)','最近成功同步（UTC）')}} {{status.succeeded_at?new Date(status.succeeded_at).toISOString().replace('T',' ').replace('.000Z','Z'):'—'}}</p>
    <label class="check"><input type="checkbox" :checked="status.public_pricing!==false" @change="emit('change',($event.target as HTMLInputElement).checked)" />{{t('Automatically match public model prices (turn off for private deployments using public model names)','自动匹配公共模型价格（私有部署使用同名模型时请关闭）')}}</label>
  </div>
</template>
