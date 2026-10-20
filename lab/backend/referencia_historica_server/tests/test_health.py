# tests/test_health.py
"""Tests for health and basic endpoints."""


def test_health_public(client):
    """Health endpoint should be publicly accessible."""
    r = client.get('/api/health')
    assert r.status_code == 200
    data = r.get_json()
    assert data is not None
    assert data.get('status') == 'ok'


def test_health_has_version(client):
    """Health response should include version or db status."""
    r = client.get('/api/health')
    data = r.get_json()
    assert 'version' in data or 'db' in data


def test_login_page_accessible(client):
    """Login page should load without auth."""
    r = client.get('/login')
    assert r.status_code == 200


def test_dashboard_redirects_unauthenticated(client):
    """Dashboard should redirect to login if not authenticated."""
    r = client.get('/dashboard', follow_redirects=False)
    assert r.status_code in (302, 401)


def test_root_redirects(client):
    """Root should redirect somewhere."""
    r = client.get('/', follow_redirects=False)
    assert r.status_code in (200, 302)
