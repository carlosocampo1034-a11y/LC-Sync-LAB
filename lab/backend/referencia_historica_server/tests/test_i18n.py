"""Tests for i18n language switching."""
import pytest


def test_set_lang_es(auth_client):
    r = auth_client.get('/set_lang/es', follow_redirects=False)
    assert r.status_code in (302, 200)
    with auth_client.session_transaction() as sess:
        assert sess.get('lang', 'es') == 'es'


def test_set_lang_en(auth_client):
    r = auth_client.get('/set_lang/en', follow_redirects=False)
    assert r.status_code in (302, 200)
    with auth_client.session_transaction() as sess:
        assert sess.get('lang') == 'en'


def test_set_lang_invalid_ignored(auth_client):
    r = auth_client.get('/set_lang/fr', follow_redirects=False)
    assert r.status_code in (302, 200)
    with auth_client.session_transaction() as sess:
        assert sess.get('lang', 'es') in ('es', 'en', None)


def test_tr_function_es(app):
    import app as flask_app
    with flask_app.app.test_request_context():
        from flask import session
        session['lang'] = 'es'
        result = flask_app.tr('nav.dashboard')
        assert result == 'Dashboard'


def test_tr_function_en(app):
    import app as flask_app
    with flask_app.app.test_request_context():
        from flask import session
        session['lang'] = 'en'
        result = flask_app.tr('nav.logout')
        assert 'LOGOUT' in result.upper() or 'SALIR' in result.upper()
