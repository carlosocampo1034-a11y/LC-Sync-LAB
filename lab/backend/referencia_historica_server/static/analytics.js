// LC Technician — analytics.js

let _charts = {};

function destroyChart(id) {
  if (_charts[id]) { _charts[id].destroy(); delete _charts[id]; }
}

async function loadAnalytics() {
  try {
    const [overview, top] = await Promise.all([
      fetch('/api/analytics/overview').then(r => r.json()),
      fetch('/api/analytics/top?limit=5').then(r => r.json())
    ]);

    // KPIs
    document.getElementById('kpi-jobs').textContent = overview.jobs.total;
    document.getElementById('kpi-revenue').textContent = '$' + overview.revenue.total.toFixed(2);
    document.getElementById('kpi-ticket').textContent = '$' + overview.revenue.avg_ticket.toFixed(2);
    document.getElementById('kpi-techs').textContent = overview.technicians.total;
    document.getElementById('analytics-updated').textContent = 'Actualizado: ' + new Date().toLocaleTimeString();

    // Chart: WI by type
    destroyChart('chartWiTypes');
    const wiTypes = overview.work_items.by_type;
    _charts['chartWiTypes'] = new Chart(document.getElementById('chartWiTypes'), {
      type: 'doughnut',
      data: {
        labels: Object.keys(wiTypes),
        datasets: [{
          data: Object.values(wiTypes),
          backgroundColor: ['#1E88E5','#43A047','#FB8C00','#E53935','#8E24AA','#00ACC1']
        }]
      },
      options: { responsive: true, plugins: { legend: { position: 'bottom', labels: { color: '#e8eaf0' } } } }
    });

    // Chart: Jobs by status
    destroyChart('chartJobStatus');
    const jobStatus = overview.jobs.by_status;
    _charts['chartJobStatus'] = new Chart(document.getElementById('chartJobStatus'), {
      type: 'doughnut',
      data: {
        labels: Object.keys(jobStatus),
        datasets: [{
          data: Object.values(jobStatus),
          backgroundColor: ['#43A047','#1E88E5','#FB8C00','#E53935','#8E24AA']
        }]
      },
      options: { responsive: true, plugins: { legend: { position: 'bottom', labels: { color: '#e8eaf0' } } } }
    });

    // Chart: Top customers
    destroyChart('chartTopCustomers');
    const tc = top.top_customers;
    _charts['chartTopCustomers'] = new Chart(document.getElementById('chartTopCustomers'), {
      type: 'bar',
      data: {
        labels: tc.map(x => x.name ? x.name.split(',')[0] : '—'),
        datasets: [{
          label: 'Revenue $',
          data: tc.map(x => x.revenue),
          backgroundColor: '#1E88E5'
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        plugins: { legend: { display: false } },
        scales: { x: { ticks: { color: '#8892a4' } }, y: { ticks: { color: '#e8eaf0' } } }
      }
    });

    // Chart: Top technicians
    destroyChart('chartTopTechs');
    const tt = top.top_technicians;
    _charts['chartTopTechs'] = new Chart(document.getElementById('chartTopTechs'), {
      type: 'bar',
      data: {
        labels: tt.map(x => x.name || '—'),
        datasets: [{
          label: 'Jobs',
          data: tt.map(x => x.jobs),
          backgroundColor: '#43A047'
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        plugins: { legend: { display: false } },
        scales: { x: { ticks: { color: '#8892a4' } }, y: { ticks: { color: '#e8eaf0' } } }
      }
    });

    // Top WI types table
    const tbody = document.querySelector('#wi-types-table tbody');
    tbody.innerHTML = '';
    top.top_work_item_types.forEach(row => {
      tbody.innerHTML += `<tr>
        <td>${row.type}</td>
        <td class="amount">${row.count}</td>
        <td class="amount">$${row.revenue.toFixed(2)}</td>
      </tr>`;
    });

  } catch(e) {
    console.error('Analytics load error:', e);
  }
}

loadAnalytics();
setInterval(loadAnalytics, 60000);
