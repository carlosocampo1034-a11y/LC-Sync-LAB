"""Genera la CA de laboratorio y el certificado del servidor (solo para la prueba con dos teléfonos).

Uso: python3 tools/generar_tls.py <IP Wi-Fi del teléfono servidor>
- Nunca sobrescribe: si ya existen, se detiene. Claves privadas con permisos 600 en instance/tls.
- Validez 30 días. Extensiones SKI/AKI (exigidas por verificadores estrictos: Python 3.13, Android).
- La CA lleva RESTRICCIÓN DE NOMBRES (crítica): solo puede emitir para la IP del laboratorio y 127.0.0.1. Si se instala
  en un iPhone para la prueba, ni robando su clave serviría para suplantar otros sitios.
"""
import datetime
import ipaddress
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

D = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "instance", "tls")


def guardar(nombre, datos, modo):
    fd = os.open(os.path.join(D, nombre), os.O_WRONLY | os.O_CREAT | os.O_EXCL, modo)
    os.write(fd, datos)
    os.close(fd)


def main(ip_lan):
    ipaddress.ip_address(ip_lan)
    os.makedirs(D, mode=0o700, exist_ok=True)
    if any(os.path.exists(os.path.join(D, n)) for n in ("lab_ca.key", "servidor.key")):
        sys.exit("ya existen certificados de laboratorio; no se sobrescriben")
    ahora = datetime.datetime.now(datetime.timezone.utc)
    inicio, fin = ahora - datetime.timedelta(minutes=5), ahora + datetime.timedelta(days=30)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_nom = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "L&C LAB CA (solo laboratorio)"),
                        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "L&C laboratorio")])
    ca_ski = x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key())
    ca = (x509.CertificateBuilder().subject_name(ca_nom).issuer_name(ca_nom).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(inicio).not_valid_after(fin)
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
          # IP: solo la del laboratorio y 127.0.0.1. DNS: solo el dominio reservado .invalid (RFC 6761), que no existe en
          # internet; sin esta entrada los nombres DNS quedarían SIN restricción (RFC 5280 restringe por tipo de nombre).
          .add_extension(x509.NameConstraints(permitted_subtrees=[
              x509.IPAddress(ipaddress.ip_network(ip_lan + "/32")), x509.IPAddress(ipaddress.ip_network("127.0.0.1/32")),
              x509.DNSName("lab.invalid")], excluded_subtrees=None), critical=True)
          .add_extension(ca_ski, critical=False)
          .add_extension(x509.AuthorityKeyIdentifier.from_issuer_subject_key_identifier(ca_ski), critical=False)
          .sign(ca_key, hashes.SHA256()))
    srv_key = ec.generate_private_key(ec.SECP256R1())
    srv = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "lc-lab-servidor")]))
           .issuer_name(ca_nom).public_key(srv_key.public_key()).serial_number(x509.random_serial_number())
           .not_valid_before(inicio).not_valid_after(fin)
           .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(ip_lan)),
                                                       x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
           .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
           .add_extension(x509.KeyUsage(True, False, False, False, True, False, False, False, False), critical=True)
           .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
           .add_extension(x509.SubjectKeyIdentifier.from_public_key(srv_key.public_key()), critical=False)
           .add_extension(x509.AuthorityKeyIdentifier.from_issuer_subject_key_identifier(ca_ski), critical=False)
           .sign(ca_key, hashes.SHA256()))
    pem = serialization.Encoding.PEM
    priv = lambda k: k.private_bytes(pem, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    guardar("lab_ca.key", priv(ca_key), 0o600)
    guardar("lab_ca.pem", ca.public_bytes(pem), 0o644)
    guardar("servidor.key", priv(srv_key), 0o600)
    guardar("servidor.pem", srv.public_bytes(pem), 0o644)
    print("CA vence", ca.not_valid_after_utc.date(), "· huella sha256", ca.fingerprint(hashes.SHA256()).hex()[:32] + "…")
    print("servidor SAN:", ip_lan, "127.0.0.1")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else sys.exit("uso: generar_tls.py <IP Wi-Fi del teléfono servidor>"))
