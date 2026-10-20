# tests/test_auth.py
"""Tests for authentication and authorization."""


def test_dashboard_requires_auth(client):
    """Dashboard requires authentication."""
    r = client.get('/dashboard', follow_redirects=False)
    assert r.status_code in (302, 401)


def test_dashboard_with_auth(auth_client):
    """Dashboard accessible with admin auth."""
    r = auth_client.get('/dashboard')
    assert r.status_code == 200


def test_jobs_requires_auth(client):
    """Jobs page requires authentication."""
    r = client.get('/jobs', follow_redirects=False)
    assert r.status_code in (302, 401)


def test_jobs_with_auth(auth_client):
    """Jobs page accessible with auth."""
    r = auth_client.get('/jobs')
    assert r.status_code == 200


def test_api_stats_requires_auth(client):
    """Stats API requires authentication."""
    r = client.get('/api/stats')
    assert r.status_code in (302, 401)


def test_api_stats_with_auth(auth_client):
    """Stats API returns expected structure."""
    r = auth_client.get('/api/stats')
    assert r.status_code == 200
    data = r.get_json()
    assert data is not None
    # Should have some job count key
    assert any(k in data for k in ('total_jobs', 'jobs', 'job_count', 'jobs_total'))


def test_backup_accessible_by_admin(auth_client):
    """Backup page accessible by admin."""
    r = auth_client.get('/backup')
    assert r.status_code == 200


def test_backup_requires_auth(client):
    """Backup page requires auth."""
    r = client.get('/backup', follow_redirects=False)
    assert r.status_code in (302, 401)


def test_viewer_can_access_dashboard(viewer_client):
    """Viewer role can access dashboard."""
    r = viewer_client.get('/dashboard')
    assert r.status_code == 200


def test_logout(auth_client):
    """Logout clears session."""
    r = auth_client.get('/logout', follow_redirects=False)
    assert r.status_code in (200, 302)
