---
layout: page
title: Infinia
---

<script setup>
// Redirect to the English home. Runs client-side on first load of '/'.
if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/en') && !window.location.pathname.startsWith('/zh')) {
  window.location.replace('/en/')
}
</script>

<meta http-equiv="refresh" content="0; url=/en/" />
