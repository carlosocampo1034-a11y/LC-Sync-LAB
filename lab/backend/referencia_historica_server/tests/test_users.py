"""Tests for /api/users CRUD endpoints."""
import json
import pytest


def test_get_users_list(auth_client):
    r = auth_client.get('/api/users')
    assert r.status_code == 200
    data = r.get_json()
    assert isinstance(data, list)
    assert len(data) >= 1
    # No passwords in response
    for u in data:
        assert 'password' not in u
        assert 'password_hash' not in u


def test_get_users_requires_auth(client):
    r = client.get('/api/users')
    assert r.status_code in (401, 302, 403)


def test_create_user(auth_client):
    payload = {'username': 'testuser57', 'password': 'Test1234!', 'role': 'viewer', 'name': 'Test User 57'}
    r = auth_client.post('/api/users', json=payload)
    assert r.status_code in (200, 201)
    # Cleanup
    auth_client.delete('/api/users/testuser57')


def test_create_user_invalid_role(auth_client):
    payload = {'username': 'baduser57', 'password': 'Test1234!', 'role': 'superadmin', 'name': 'Bad'}
    r = auth_client.post('/api/users', json=payload)
    assert r.status_code in (400, 422)


def test_cannot_delete_admin(auth_client):
    r = auth_client.delete('/api/users/admin')
    assert r.status_code in (400, 403)


def test_update_user(auth_client):
    # Create then update
    auth_client.post('/api/users', json={'username': 'upd57', 'password': 'Test1!', 'role': 'viewer', 'name': 'Upd'})
    r = auth_client.put('/api/users/upd57', json={'name': 'Updated Name', 'role': 'dispatcher'})
    assert r.status_code == 200
    auth_client.delete('/api/users/upd57')
