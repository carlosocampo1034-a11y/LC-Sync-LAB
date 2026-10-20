import os
import sys
import tempfile
import pytest

# Add parent dir to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))


@pytest.fixture(scope='session')
def app():
    """Create test Flask app with temp database."""
    import app as lc_app

    # Create temporary DB
    db_fd, db_path = tempfile.mkstemp(suffix='.db')

    # Store original DB path and override
    original_db = lc_app.DB_PATH
    lc_app.DB_PATH = db_path
    lc_app.app.config['TESTING'] = True
    lc_app.app.config['WTF_CSRF_ENABLED'] = False

    # Initialize schema in temp DB
    lc_app.init_db()

    yield lc_app.app

    # Cleanup
    lc_app.DB_PATH = original_db
    os.close(db_fd)
    try:
        os.unlink(db_path)
    except Exception:
        pass


@pytest.fixture
def client(app):
    """Unauthenticated test client."""
    return app.test_client()


@pytest.fixture
def auth_client(app):
    """Authenticated test client (admin)."""
    c = app.test_client(use_cookies=True)
    # Disable exception propagation so 500s return HTTP response
    c.application.config['PROPAGATE_EXCEPTIONS'] = False
    with c.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['role'] = 'admin'
        sess['name'] = 'Administrador'
    return c


@pytest.fixture
def dispatcher_client(app):
    """Authenticated test client (dispatcher)."""
    c = app.test_client()
    with c.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'dispatcher'
        sess['role'] = 'dispatcher'
        sess['name'] = 'Dispatcher'
    return c


@pytest.fixture
def viewer_client(app):
    """Authenticated test client (viewer)."""
    c = app.test_client()
    with c.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'viewer'
        sess['role'] = 'viewer'
        sess['name'] = 'Viewer'
    return c
