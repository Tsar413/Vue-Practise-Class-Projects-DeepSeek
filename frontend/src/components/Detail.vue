<script setup>
import { labels, displayCell } from '../composables/ui'

// 递归展示对象，数组与嵌套对象都不会被隐藏。
// 字段值统一走带上下文的展示层：姓名/班级/校区/院系/地点与自由文本都做中性化，
// 但**不修改**传入对象，提交与接口调用仍使用原值。
defineProps({ value: Object, kind: String })
</script>

<template>
  <dl class="detail-list">
    <template v-for="(v, k) in value" :key="k">
      <dt>{{ labels[k] || k }}</dt>
      <dd>
        <Detail v-if="v && typeof v === 'object' && !Array.isArray(v)" :value="v" :kind="kind" />
        <span v-else>{{ displayCell(value, k, kind) }}</span>
      </dd>
    </template>
  </dl>
</template>
