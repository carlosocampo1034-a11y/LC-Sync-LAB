# tests/test_sync.py
"""Tests for sync push/pull endpoints."""


EMPTY_PUSH = {
    'deviceId': 'pytest-device-001',
    'deviceName': 'pytest runner',
    'jobs': [],
    'work_items': [],
    'technicians': [],
    'memberships': [],
    'audit_logs': [],
    'membership_check_ins': [],
    'photos': []
}


def test_sync_push_empty(auth_client):
    """Sync push with empty payload should succeed."""
    r = auth_client.post('/api/sync/push', json=EMPTY_PUSH)
    assert r.status_code == 200
    data = r.get_json()
    assert data is not None


def test_sync_push_registers_device(auth_client):
    """Sync push should register device."""
    auth_client.post('/api/sync/push', json=EMPTY_PUSH)
    r = auth_client.get('/api/devices')
    assert r.status_code == 200
    devices = r.get_json()
    assert isinstance(devices, list)
    device_ids = [d.get('deviceId') for d in devices]
    assert 'pytest-device-001' in device_ids


def test_sync_pull_returns_data(auth_client):
    """Sync pull should return current data."""
    r = auth_client.get('/api/sync/pull?since=0')
    assert r.status_code == 200
    data = r.get_json()
    assert data is not None
    assert any(k in data for k in ('jobs', 'sync_jobs', 'data', 'technicians'))


def test_devices_endpoint_requires_auth(client):
    """Devices endpoint requires auth."""
    r = client.get('/api/devices', follow_redirects=False)
    assert r.status_code in (302, 401)


def test_devices_endpoint_with_auth(auth_client):
    """Devices endpoint returns list."""
    r = auth_client.get('/api/devices')
    assert r.status_code == 200
    assert isinstance(r.get_json(), list)


def test_notifications_endpoint(auth_client):
    """Notifications endpoint returns expected structure."""
    r = auth_client.get('/api/sync/notifications')
    assert r.status_code == 200
    data = r.get_json()
    assert 'jobs' in data
    assert 'renewals' in data
    assert 'now' in data
