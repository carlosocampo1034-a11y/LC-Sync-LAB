# tests/test_audit.py
"""Tests for audit log endpoints."""


def test_audit_log_api(auth_client):
    """Audit log API returns structured response."""
    r = auth_client.get('/api/audit_log')
    assert r.status_code == 200
    data = r.get_json()
    assert data is not None
    # Should have some structure with entries
    assert any(k in data for k in ('entries', 'data', 'total', 'logs', 'items'))


def test_audit_log_page(auth_client):
    """Audit log page loads."""
    r = auth_client.get('/audit_log')
    assert r.status_code == 200


def test_audit_log_stats(auth_client):
    """Audit stats endpoint."""
    r = auth_client.get('/api/audit_log/stats')
    if r.status_code == 404:
        import pytest
        pytest.skip('audit stats endpoint not available')
    assert r.status_code == 200
    data = r.get_json()
    assert 'total' in data or 'by_action' in data or 'actions' in data


def test_audit_export_csv(auth_client):
    """Audit CSV export."""
    r = auth_client.get('/api/audit_log/export')
    if r.status_code == 404:
        import pytest
        pytest.skip('audit export endpoint not available')
    assert r.status_code == 200


def test_conflicts_api(auth_client):
    """Conflicts API returns list."""
    r = auth_client.get('/api/conflicts')
    assert r.status_code == 200
    assert isinstance(r.get_json(), list)


def test_conflicts_page(auth_client):
    """Conflicts page loads."""
    r = auth_client.get('/conflicts')
    assert r.status_code == 200
