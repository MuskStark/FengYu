---
layout: page
title: Infinia
---

<script setup>
// Redirect to the English home on first load. Uses a path relative to the
// site base so it works under any deployment sub-path (e.g. /FengYu/).
if (typeof window !== 'undefined') {
  const path = window.location.pathname.replace(/\/+$/, '')
  if (!path.endsWith('/en') && !path.endsWith('/zh')) {
    window.location.replace('./en/')
  }
}
</script>

<meta http-equiv="refresh" content="0; url=en/" />
