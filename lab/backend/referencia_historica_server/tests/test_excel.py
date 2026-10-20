"""Tests for Excel export endpoints."""
import pytest


def test_jobs_xlsx_content_type(auth_client):
    r = auth_client.get('/api/export/jobs.xlsx')
    assert r.status_code == 200
    ct = r.content_type.lower()
    assert 'spreadsheet' in ct or 'xlsx' in ct or 'octet' in ct


def test_jobs_xlsx_not_empty(auth_client):
    r = auth_client.get('/api/export/jobs.xlsx')
    assert r.status_code == 200
    assert len(r.data) > 100


def test_work_items_xlsx_content_type(auth_client):
    r = auth_client.get('/api/export/work_items.xlsx')
    assert r.status_code == 200
    ct = r.content_type.lower()
    assert 'spreadsheet' in ct or 'xlsx' in ct or 'octet' in ct


def test_work_items_xlsx_not_empty(auth_client):
    r = auth_client.get('/api/export/work_items.xlsx')
    assert r.status_code == 200
    assert len(r.data) > 100


def test_xlsx_requires_auth(client):
    r = client.get('/api/export/jobs.xlsx')
    assert r.status_code in (401, 302)
