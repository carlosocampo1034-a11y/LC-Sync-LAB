// LC Technician Web Dashboard — dashboard.js

// Keep footer live dot blinking
(function() {
  const dot = document.getElementById('liveDot');
  if (dot) {
    setInterval(() => {
      dot.style.opacity = dot.style.opacity === '0.2' ? '1' : '0.2';
    }, 1000);
  }
})();

// Generic fetch helper with error handling
async function apiFetch(url) {
  try {
    const r = await fetch(url);
    if (!r.ok) throw new Error(r.status);
    return r.json();
  } catch (e) {
    console.warn('apiFetch error', url, e);
    return null;
  }
}

// Format currency
function fmt(n) {
  return '$' + Number(n || 0).toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}
