"""Página de ARRANQUE temporal para el iPhone: sirve SOLO el perfil público de la CA y sus instrucciones (sin backend,
sin datos, sin cookies) por HTTP en la IP Wi-Fi, durante 15 minutos como máximo. Registra la IP de quien la visita
(así se conoce la IP del iPhone para la lista blanca del modo red). Exige LC_LAB_LAN_AUTORIZADO=si-propietario.
Uso: arranque_perfil.py <IP Wi-Fi del servidor> <perfil.mobileconfig> [minutos]"""
import html
import http.server
import ipaddress
import os
import sys
import threading
import time

if os.environ.get("LC_LAB_LAN_AUTORIZADO") != "si-propietario":
    sys.exit("arranque NO autorizado: falta LC_LAB_LAN_AUTORIZADO=si-propietario")
HOST, PERFIL = sys.argv[1], sys.argv[2]
MIN = max(1, min(int(sys.argv[3]) if len(sys.argv) > 3 else 15, 30))
ip = ipaddress.ip_address(HOST)
if not ip.is_private or ip.is_loopback or ip.is_unspecified:
    sys.exit("solo una IP privada de la Wi-Fi")
DATOS = open(PERFIL, "rb").read()
HUELLA = os.environ.get("LC_HUELLA", "")
PAGINA = f"""<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>L&amp;C laboratorio</title></head>
<body style="font:17px -apple-system,sans-serif;margin:20px;line-height:1.5;color:#191919">
<h1 style="color:#EB1700">L&amp;C · prueba de laboratorio</h1>
<p>Este certificado permite que Safari confíe en el servidor de prueba <b>solo en 10.0.0.121</b> (la Wi-Fi de casa).
No sirve para ningún otro sitio y se borra solo en 7 días.</p>
<p><a href="/lab-ca.mobileconfig" style="display:block;background:#EB1700;color:#fff;text-align:center;padding:14px;
border-radius:12px;text-decoration:none;font-weight:700">Descargar el perfil</a></p>
<ol><li>Toca «Permitir» y luego «Cerrar».</li>
<li>Ajustes &gt; «Perfil descargado» &gt; Instalar (pide el código del iPhone).</li>
<li>Comprueba que la <b>huella SHA-256</b> en «Más detalles» sea:<br><code style="font-size:13px">{html.escape(HUELLA)}</code></li>
<li>Ajustes &gt; General &gt; Información &gt; Ajustes de confianza de certificados &gt; activa «L&amp;C LAB CA».</li></ol>
<p style="color:#6B6B6B;font-size:14px">Para quitarlo antes: Ajustes &gt; General &gt; VPN y gestión de dispositivos &gt; L&amp;C &gt; Eliminar.</p>
</body></html>""".encode()


class Arranque(http.server.BaseHTTPRequestHandler):
    server_version = "LC-arranque"
    sys_version = ""

    def _enviar(self, codigo, tipo, cuerpo, extra=None):
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(cuerpo)

    def do_GET(self):
        ua = (self.headers.get("User-Agent") or "")[:80]
        print(f"{time.strftime('%H:%M:%S')} visita desde {self.client_address[0]} · {self.path[:40]} · {ua}", flush=True)
        if self.path == "/":
            self._enviar(200, "text/html; charset=utf-8", PAGINA)
        elif self.path == "/lab-ca.mobileconfig":
            self._enviar(200, "application/x-apple-aspen-config", DATOS,
                         {"Content-Disposition": 'attachment; filename="LC-laboratorio.mobileconfig"'})
        else:
            self._enviar(404, "text/plain; charset=utf-8", b"no")

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer((HOST, 5080), Arranque)
threading.Thread(target=lambda: (time.sleep(MIN * 60), print("cierre automático", flush=True), os._exit(0)), daemon=True).start()
print(f"arranque en http://{HOST}:5080/ · se cierra solo en {MIN} min", flush=True)
srv.serve_forever()
